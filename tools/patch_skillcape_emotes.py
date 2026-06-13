#!/usr/bin/env python3
"""
Skillcape Emote Cache Patcher — rev-254 Progressive-Java-Client

Adds seq configs, spotanim configs, and stub animation frame archives so
all 27 skillcape GFX effects play without crashing.

Stubs give correct timing (delays from the seq JSON) but no skeleton
deformation (model renders in its default pose).  This is intentional;
full b238->rev254 frame conversion can be wired in later.

Usage:
    python tools/patch_skillcape_emotes.py            # patch in-place
    python tools/patch_skillcape_emotes.py --dry-run  # analyse only
    python tools/patch_skillcape_emotes.py --no-backup
    python tools/patch_skillcape_emotes.py --scan     # diagnose cache layout
"""

import bz2, gzip, io, json, os, shutil, struct, sys, zlib
from pathlib import Path
from typing import Dict, List, Optional, Tuple

from skillcape_paths import resolve_emote_pack

# ───────────────────────────── paths ──────────────────────────────────────
CLIENT_CACHE = Path(__file__).resolve().parents[1] / "cache"
EMOTE_PACK   = resolve_emote_pack()

DAT  = CLIENT_CACHE / "main_file_cache.dat"
IDXS = [CLIENT_CACHE / f"main_file_cache.idx{i}" for i in range(5)]
BACKUP = CLIENT_CACHE / "backup_pre_skillcape_patch"

# ─────────────────────────── filestream ───────────────────────────────────
# Sector layout: 8-byte header + 512 bytes data = 520 bytes/sector
# Header: fileId(u16) chunkId(u16) nextSector(u24) archiveId(u8)
# idx entry at fileId*6: fileSize(u24) firstSector(u24)
#
# On-demand archive IDs (archiveId in sector header = fileStreamIndex+1):
#   idx0 archiveId=1  JagFiles (config file 2, versionlist file 5)
#   idx1 archiveId=2  Models
#   idx2 archiveId=3  Animation frames  ← we write new files here
#   idx3 archiveId=4  MIDI
#   idx4 archiveId=5  Maps

SECTOR = 520
DSIZE  = 512

def _fs_read(dat: bytes, idx: bytes, fid: int, arc: int) -> Optional[bytes]:
    o = fid * 6
    if o + 6 > len(idx):
        return None
    b = idx[o:o+6]
    sz  = (b[0] << 16) | (b[1] << 8) | b[2]
    sec = (b[3] << 16) | (b[4] << 8) | b[5]
    if sz <= 0 or sec == 0:
        return None
    out, chunk = bytearray(), 0
    while len(out) < sz:
        so    = sec * SECTOR
        avail = len(dat) - so
        need  = min(DSIZE, sz - len(out))
        # The last sector of a file may be truncated in the dat (client writes
        # only the header + payload bytes, not a full padded sector).  Allow
        # reading it as long as header + needed payload bytes are present.
        if avail < SECTOR:
            if avail < 8 + need:
                return None
            s = dat[so:so+avail] + bytes(SECTOR - avail)  # zero-pad to full sector
        else:
            s = dat[so:so+SECTOR]
        if s[0]*256+s[1] != fid or s[2]*256+s[3] != chunk or s[7] != arc:
            return None
        out.extend(s[8:8+need])
        sec = (s[4] << 16) | (s[5] << 8) | s[6]
        chunk += 1
    return bytes(out)

def _fs_read_verbose(dat: bytes, idx: bytes, fid: int, arc: int) -> Optional[bytes]:
    """Same as _fs_read but prints why it fails."""
    o = fid * 6
    if o + 6 > len(idx):
        print(f"  fid={fid}: idx entry at {o} is past end of idx ({len(idx)} bytes)")
        return None
    b = idx[o:o+6]
    sz  = (b[0] << 16) | (b[1] << 8) | b[2]
    sec = (b[3] << 16) | (b[4] << 8) | b[5]
    print(f"  fid={fid}: idx entry sz={sz} firstSector={sec}")
    if sz <= 0 or sec == 0:
        print(f"  fid={fid}: empty (sz={sz} sec={sec})")
        return None
    out, chunk = bytearray(), 0
    while len(out) < sz:
        so    = sec * SECTOR
        avail = len(dat) - so
        need  = min(DSIZE, sz - len(out))
        if avail < SECTOR:
            if avail < 8 + need:
                print(f"  fid={fid} chunk={chunk}: sector {sec} truncated (avail={avail} need={8+need})")
                return None
            s = dat[so:so+avail] + bytes(SECTOR - avail)
        else:
            s = dat[so:so+SECTOR]
        hfid = s[0]*256+s[1]; hchk = s[2]*256+s[3]; harc = s[7]
        if hfid != fid or hchk != chunk or harc != arc:
            print(f"  fid={fid} chunk={chunk}: header mismatch hfid={hfid} hchk={hchk} harc={harc} (expected fid={fid} chunk={chunk} arc={arc})")
            return None
        out.extend(s[8:8+need])
        sec = (s[4] << 16) | (s[5] << 8) | s[6]
        chunk += 1
    return bytes(out)

def _scan_idx(dat: bytes, idx: bytes, arc: int, name: str):
    n = len(idx) // 6
    print(f"\n{name}: {len(idx):,} bytes = {n} entries (archiveId={arc})")
    found = 0
    for fid in range(n):
        b = idx[fid*6:fid*6+6]
        sz  = (b[0] << 16) | (b[1] << 8) | b[2]
        sec = (b[3] << 16) | (b[4] << 8) | b[5]
        if sz > 0 and sec > 0:
            so = sec * SECTOR
            if so + SECTOR <= len(dat):
                s   = dat[so:so+SECTOR]
                hfid = s[0]*256+s[1]; harc = s[7]
                ok  = hfid == fid and s[2] == 0 and s[3] == 0 and harc == arc
                print(f"  [{fid}] sz={sz:>8,}  sec={sec:>6}  sector-arc={harc}  {'OK' if ok else '*** HEADER MISMATCH ***'}")
            else:
                print(f"  [{fid}] sz={sz:>8,}  sec={sec:>6}  *** sector OOB ***")
            found += 1
    if found == 0:
        print("  (all entries empty)")

def _rebuild_versionlist_from_idx2(dat: bytes, idx2: bytes) -> dict:
    """
    If versionlist is not cached, reconstruct anim_version, anim_crc, anim_index
    by scanning every file already in idx2.  All other versionlist arrays are left
    empty (the client re-downloads the full versionlist on next startup anyway,
    but we need SOMETHING so we can extend it with our new archives before writing
    it back to the local cache).
    """
    n = len(idx2) // 6
    anim_version, anim_crc = [], []
    max_gid = -1
    for fid in range(n):
        data = _fs_read(dat, idx2, fid, 3)
        if data is None or len(data) < 2:
            anim_version.append(0)
            anim_crc.append(0)
            continue
        ver = struct.unpack('>H', data[-2:])[0]
        raw = zlib.crc32(data[:-2]) & 0xFFFFFFFF
        crc = raw - 0x100000000 if raw >= 0x80000000 else raw
        anim_version.append(ver)
        anim_crc.append(crc)
        # peek inside the gzip archive to find max global frame ID
        try:
            archive = gzip.decompress(data[:-2])
            if len(archive) >= 10:
                # trailer: last 8 bytes  sizeA sizeB sizeC sizeD (u16 each)
                sA = struct.unpack('>H', archive[-8:-6])[0]
                n_frames = sA // 3 if sA >= 3 else 0
                for i in range(n_frames):
                    gid = struct.unpack('>H', archive[2+i*3:4+i*3])[0]
                    if gid > max_gid:
                        max_gid = gid
        except Exception:
            pass

    anim_index = [0] * (max_gid + 1) if max_gid >= 0 else []
    print(f"  Reconstructed from idx2: {n} files, max globalId={max_gid}, animIndex.len={len(anim_index)}")
    return {
        "anim_version": anim_version,
        "anim_crc":     anim_crc,
        "anim_index":   anim_index,
    }

def _fs_append(dat: bytearray, idx: bytearray, fid: int, arc: int, data: bytes):
    """Append NEW sectors for fid; updates idx entry (old sectors stay as dead bytes)."""
    first = (len(dat) + SECTOR - 1) // SECTOR or 1
    pos, chunk, sec = 0, 0, first
    sz = len(data)
    while pos < sz:
        n = min(DSIZE, sz - pos)
        nxt = 0 if pos + n >= sz else sec + 1
        hdr = bytes([fid >> 8, fid & 0xFF,
                     chunk >> 8, chunk & 0xFF,
                     nxt >> 16, (nxt >> 8) & 0xFF, nxt & 0xFF,
                     arc])
        pay = data[pos:pos+n]
        blk = hdr + pay + bytes(DSIZE - len(pay))
        needed = (sec + 1) * SECTOR
        if len(dat) < needed:
            dat.extend(b'\x00' * (needed - len(dat)))
        dat[sec*SECTOR:(sec+1)*SECTOR] = blk
        pos += n; sec = nxt if nxt else sec + 1; chunk += 1
    # write idx entry
    need_idx = (fid + 1) * 6
    if len(idx) < need_idx:
        idx.extend(b'\x00' * (need_idx - len(idx)))
    idx[fid*6:fid*6+6] = bytes([sz >> 16, (sz >> 8) & 0xFF, sz & 0xFF,
                                 first >> 16, (first >> 8) & 0xFF, first & 0xFF])

# ─────────────────────────── jagfile ──────────────────────────────────────
# Two variants on disk:
#   outer-compressed   outerPacked != outerUnpacked  -> BZip2 whole body,
#                                                       individual files raw
#   per-file-compressed outerPacked == outerUnpacked -> body raw,
#                                                       individual files BZip2
#
# Both variants use Jagex raw BZip2 (NO "BZh9" magic header in the stream).
# Python's bz2.compress() adds "BZh9" -> we strip 4 bytes when writing.
# Python's bz2.decompress() needs "BZh9" -> we prepend it when reading.

def _jag_hash(name: str) -> int:
    h = 0
    for c in name.upper():
        h = (h * 61 + ord(c) - 32) & 0xFFFFFFFF
    return h - 0x100000000 if h >= 0x80000000 else h

def _bz2_decompress(raw: bytes, expected_size: int) -> bytes:
    for prefix in (b'BZh9', b'BZh1', b''):
        try:
            out = bz2.decompress(prefix + raw)
            if len(out) == expected_size:
                return out
        except Exception:
            pass
    raise ValueError(f"BZip2 decompress failed ({len(raw)}->{expected_size})")

def _bz2_compress(data: bytes) -> bytes:
    """Jagex raw BZip2: strip the 4-byte 'BZhN' header."""
    c = bz2.compress(data, compresslevel=9)
    assert c[:3] == b'BZh', "bz2 output missing magic"
    return c[4:]   # drop 'B','Z','h','N'

def _jag_read(raw: bytes) -> Dict[int, bytes]:
    """Parse JagFile -> {hash: decompressed_bytes}."""
    outer_unp = (raw[0] << 16) | (raw[1] << 8) | raw[2]
    outer_pk  = (raw[3] << 16) | (raw[4] << 8) | raw[5]

    if outer_unp != outer_pk:
        body = _bz2_decompress(raw[6:], outer_unp)
        per_file_bzip = False
    else:
        body = raw[6:]
        per_file_bzip = True

    pos = 0
    fc  = (body[pos] << 8) | body[pos+1]; pos += 2
    dir_end = pos + fc * 10
    meta = []
    for _ in range(fc):
        h   = struct.unpack('>i', body[pos:pos+4])[0]; pos += 4
        upk = (body[pos] << 16)|(body[pos+1] << 8)|body[pos+2]; pos += 3
        pk  = (body[pos] << 16)|(body[pos+1] << 8)|body[pos+2]; pos += 3
        meta.append((h, upk, pk))

    out, fpos = {}, dir_end
    for h, upk, pk in meta:
        chunk = body[fpos:fpos+pk]
        out[h] = _bz2_decompress(chunk, upk) if (per_file_bzip and pk != upk) else chunk
        fpos += pk
    return out

def _jag_pack(files: Dict[int, bytes]) -> bytes:
    """Pack {hash: bytes} -> whole-archive-BZip2 JagFile (Jagex format)."""
    entries = sorted(files.items())
    hdr = bytearray(struct.pack('>H', len(entries)))
    body = bytearray()
    for h, d in entries:
        sz = len(d)
        hdr += struct.pack('>i', h)
        hdr += bytes([(sz>>16)&0xFF,(sz>>8)&0xFF,sz&0xFF])  # unpackedSize
        hdr += bytes([(sz>>16)&0xFF,(sz>>8)&0xFF,sz&0xFF])  # packedSize (same -> raw)
        body += d
    plain = bytes(hdr) + bytes(body)
    comp  = _bz2_compress(plain)
    su, sp = len(plain), len(comp)
    return bytes([(su>>16)&0xFF,(su>>8)&0xFF,su&0xFF,
                  (sp>>16)&0xFF,(sp>>8)&0xFF,sp&0xFF]) + comp

# ─────────────────────────── seq.dat ──────────────────────────────────────

def _seq_parse(data: bytes) -> Tuple[int, List[bytes]]:
    count = struct.unpack('>H', data[:2])[0]
    pos, entries = 2, []
    for _ in range(count):
        s = pos
        while pos < len(data):
            op = data[pos]; pos += 1
            if   op == 0: break
            elif op == 1: n = data[pos]; pos += 1 + n * 6
            elif op == 2: pos += 2
            elif op == 3: pos += 1 + data[pos]
            elif op == 4: pass
            elif op in (5, 8, 9, 10, 11): pos += 1
            elif op in (6, 7): pos += 2
            else: break
        entries.append(data[s:pos])
    return count, entries

def _seq_entry(global_ids: List[int], delays: List[int]) -> bytes:
    n = len(global_ids)
    assert n <= 255
    b = bytearray([1, n])
    for i in range(n):
        b += struct.pack('>HHH', global_ids[i], 0xFFFF, delays[i])
    b.append(0)
    return bytes(b)

def _seq_pack(entries: List[bytes]) -> bytes:
    return struct.pack('>H', len(entries)) + b''.join(entries)

# ────────────────────────── spotanim.dat ──────────────────────────────────

def _spa_parse(data: bytes) -> Tuple[int, List[bytes]]:
    count = struct.unpack('>H', data[:2])[0]
    pos, entries = 2, []
    for _ in range(count):
        s = pos
        while pos < len(data):
            op = data[pos]; pos += 1
            if   op == 0: break
            elif op in (1, 2, 4, 5, 6): pos += 2
            elif op in (7, 8): pos += 1
            elif 40 <= op < 60: pos += 2
            else: break
        entries.append(data[s:pos])
    return count, entries

def _spa_entry(model_id: int, seq_id: int) -> bytes:
    return bytes([1]) + struct.pack('>H', model_id) + bytes([2]) + struct.pack('>H', seq_id) + bytes([0])

def _spa_pack(entries: List[bytes]) -> bytes:
    return struct.pack('>H', len(entries)) + b''.join(entries)

def _is_placeholder_entry(entry: bytes) -> bool:
    return entry == bytes([0])

# ─────────────────── animation frame archive ──────────────────────────────
# rev-254 AnimFrame.unpack() layout (single-archive file):
#
#   Section A:  frameCount(u16)  [ globalId(u16)  boneCount(u8) ] × N
#   Section B:  sizeB bytes of mask data          (sizeB = 0 for stubs)
#   Section C:  sizeC bytes of transform values   (sizeC = 0 for stubs)
#   Section D:  sizeD bytes of per-frame delay    (one u8 per frame)
#   AnimBase:   size(u8) types[size](u8) per-bone: labelCount(u8) labels[](u8)
#   Trailer:    sizeA(u16) sizeB(u16) sizeC(u16) sizeD(u16)
#
# For stubs: boneCount=0 -> no mask / value data -> model stays in default pose.
# AnimBase: 1 reference bone, 0 labels (minimum valid skeleton).

def _build_animframe_archive(global_ids: List[int], delays: List[int]) -> bytes:
    n = len(global_ids)
    sec_a = bytearray(struct.pack('>H', n))
    for gid in global_ids:
        sec_a += struct.pack('>H', gid)
        sec_a.append(0)                          # boneCount = 0
    sec_d     = bytes(min(255, max(1, d)) for d in delays)
    anim_base = bytes([1, 0, 0])                 # 1 bone, type=reference, 0 labels
    trailer   = struct.pack('>HHHH', n * 3, 0, 0, n)
    return bytes(sec_a) + sec_d + anim_base + trailer

def _make_versioned(archive: bytes, version: int = 1) -> bytes:
    """GZip-compress archive and append 2-byte version (matches OnDemand.validate())."""
    buf = io.BytesIO()
    with gzip.GzipFile(fileobj=buf, mode='wb', mtime=0) as gz:
        gz.write(archive)
    return buf.getvalue() + struct.pack('>H', version)

def _ver_crc(data: bytes) -> Tuple[int, int]:
    """Returns (version, signed_crc32) from versioned anim data."""
    ver = struct.unpack('>H', data[-2:])[0]
    crc = zlib.crc32(data[:-2]) & 0xFFFFFFFF
    return ver, (crc - 0x100000000 if crc >= 0x80000000 else crc)

# ──────────────────────────────── main ────────────────────────────────────

def main():
    dry_run   = '--dry-run'   in sys.argv
    no_backup = '--no-backup' in sys.argv
    scan_only = '--scan'      in sys.argv

    print("=== Skillcape Emote Cache Patcher ===")
    if dry_run:
        print("DRY RUN – no files will be written\n")

    # ── load cache ──────────────────────────────────────────────────────
    dat = bytearray(DAT.read_bytes())
    idx = [bytearray(f.read_bytes()) for f in IDXS]

    if scan_only:
        _scan_idx(bytes(dat), bytes(idx[0]), 1, "idx0 (JagFiles)")
        _scan_idx(bytes(dat), bytes(idx[1]), 2, "idx1 (Models)")
        _scan_idx(bytes(dat), bytes(idx[2]), 3, "idx2 (AnimFrames)")
        _scan_idx(bytes(dat), bytes(idx[3]), 4, "idx3 (MIDI)")
        _scan_idx(bytes(dat), bytes(idx[4]), 5, "idx4 (Maps)")
        return

    # ── backup ──────────────────────────────────────────────────────────
    if not dry_run and not no_backup:
        BACKUP.mkdir(parents=True, exist_ok=True)
        for f in [DAT] + IDXS:
            dst = BACKUP / f.name
            if not dst.exists():
                shutil.copy2(f, dst)
                print(f"Backed up {f.name}")
        print()

    # ── read config JagFile (idx0, file 2, archiveId=1) ─────────────────
    print("Reading cache files...")
    config_raw = _fs_read(bytes(dat), bytes(idx[0]), 2, 1)
    if not config_raw:
        print("ERROR: Cannot read config JagFile from idx0 file_id=2. Diagnosing:")
        _fs_read_verbose(bytes(dat), bytes(idx[0]), 2, 1)
        _scan_idx(bytes(dat), bytes(idx[0]), 1, "idx0")
        sys.exit(1)
    print(f"Config JagFile:      {len(config_raw):,} bytes")

    cfg = _jag_read(config_raw)
    seq_dat      = cfg[_jag_hash("seq.dat")]
    spotanim_dat = cfg[_jag_hash("spotanim.dat")]
    seq_count, seq_entries = _seq_parse(seq_dat)
    spa_count, spa_entries = _spa_parse(spotanim_dat)
    print(f"Existing seq count:      {seq_count}")
    print(f"Existing spotanim count: {spa_count}")

    # ── read versionlist JagFile (idx0, file 5, archiveId=1) ────────────
    # The client downloads versionlist from the server on first run and caches
    # it at file_id=5 in idx0.  If this is a fresh/cleared cache the file may
    # not exist yet — reconstruct it from idx2 in that case.
    vl_raw = _fs_read(bytes(dat), bytes(idx[0]), 5, 1)
    vl_from_scratch = False
    if not vl_raw:
        print("Versionlist not in local cache (file_id=5 absent) — reconstructing from idx2...")
        _fs_read_verbose(bytes(dat), bytes(idx[0]), 5, 1)
        vl_data = _rebuild_versionlist_from_idx2(bytes(dat), bytes(idx[2]))
        vl_from_scratch = True
    else:
        print(f"Versionlist JagFile: {len(vl_raw):,} bytes")
        vl = _jag_read(vl_raw)
        def _vl_u16(name):
            d = vl.get(_jag_hash(name), b'')
            return list(struct.unpack(f'>{len(d)//2}H', d)) if d else []
        def _vl_i32(name):
            d = vl.get(_jag_hash(name), b'')
            return list(struct.unpack(f'>{len(d)//4}i', d)) if d else []
        vl_data = {
            "anim_version": _vl_u16("anim_version"),
            "anim_crc":     _vl_i32("anim_crc"),
            "anim_index":   _vl_u16("anim_index"),
        }
        # carry all other versionlist entries unchanged
        vl_data["_raw_jag"] = vl   # used when repacking below

    anim_version = vl_data["anim_version"]
    anim_crc     = vl_data["anim_crc"]
    anim_index   = vl_data["anim_index"]

    cur_anim_files  = len(anim_version)   # getFileCount(1) / versions[1].length
    cur_anim_frames = len(anim_index)     # AnimFrame.list = new AnimFrame[len+1]
    print(f"Animation files in idx2:  {cur_anim_files}")
    print(f"animIndex.length:         {cur_anim_frames}\n")

    # ── emote pack ──────────────────────────────────────────────────────
    emotes = json.loads((EMOTE_PACK / "data" / "skillcape_emotes.json").read_text())["emotes"]

    needed_seqs: set = set()
    needed_gfxs: set = set()
    gfx_info_map: Dict[int, Dict[str, int]] = {}

    for em in emotes:
        needed_seqs.add(em["animation_id"])
        for g in em.get("gfx_ids", []):
            needed_gfxs.add(g)

    dec = EMOTE_PACK / "decoded"
    for gid in needed_gfxs:
        p = dec / f"gfx_{gid}.json"
        if p.exists():
            info = json.loads(p.read_text())
            sid  = info.get("sequence_id")
            mid  = info.get("model_id")
            if sid is not None:
                gfx_info_map.setdefault(gid, {})["sequence_id"] = sid
                needed_seqs.add(sid)
            if mid is not None:
                gfx_info_map.setdefault(gid, {})["model_id"] = mid

    print(f"Needed seqs:   {sorted(needed_seqs)}")
    print(f"Needed GFX:    {sorted(needed_gfxs)}")

    # ── collect archive groups ───────────────────────────────────────────
    # archive_groups: group_id -> max file_id needed
    archive_groups: Dict[int, int] = {}
    seq_info: Dict[int, dict] = {}          # seq_id -> {group, file_ids, delays}

    frames_dir = EMOTE_PACK / "raw" / "frames"

    for sid in sorted(needed_seqs):
        p = dec / f"seq_{sid}.json"
        if not p.exists():
            print(f"  WARN: seq_{sid}.json not in emote pack")
            continue
        fb  = json.loads(p.read_text()).get("frame_block", {})
        arc = fb.get("frame_archive_ids", [])
        fid = fb.get("frame_file_ids", [])
        dl  = fb.get("frame_lengths", [])
        if not arc:
            print(f"  WARN: seq {sid} has no archive IDs")
            continue
        grp = arc[0]
        archive_groups[grp] = max(archive_groups.get(grp, 0), max(fid) if fid else 0)
        seq_info[sid] = {"group": grp, "file_ids": fid, "delays": dl}

    print(f"\nFrame archive groups: {sorted(archive_groups)}")

    # ── assign global frame IDs ──────────────────────────────────────────
    next_gid = cur_anim_frames
    grp_map: Dict[int, Dict[int, int]] = {}   # group -> {file_id -> global_id}
    archives_todo: List[Tuple[int, List[int], List[int]]] = []

    for grp in sorted(archive_groups):
        gdir = frames_dir / f"index0_group_{grp}"
        if not gdir.exists():
            print(f"  WARN: missing frame dir {gdir}")
            continue
        avail = sorted(int(f.stem.split('_')[1]) for f in gdir.glob("frame_*.dat"))
        if not avail:
            print(f"  WARN: no frame files in {gdir}")
            continue
        max_needed = archive_groups[grp]
        fids = list(range(1, max(max(avail), max_needed) + 1))
        gids = list(range(next_gid, next_gid + len(fids)))
        grp_map[grp] = {f: g for f, g in zip(fids, gids)}
        archives_todo.append((grp, fids, gids))
        print(f"  Group {grp}: {len(fids)} frames -> global IDs {gids[0]}..{gids[-1]}")
        next_gid += len(fids)

    total_new = next_gid - cur_anim_frames
    print(f"\nTotal new frame IDs: {total_new}  ({cur_anim_frames}..{next_gid-1})")

    # ── build stub animation archives ────────────────────────────────────
    print("\nBuilding animation archives (stub/identity)...")
    new_anim: List[Tuple[int, int, bytes]] = []  # (version, crc, versioned_data)

    for grp, fids, gids in archives_todo:
        archive = _build_animframe_archive(gids, [1] * len(gids))
        versioned = _make_versioned(archive)
        ver, crc  = _ver_crc(versioned)
        new_anim.append((ver, crc, versioned))
        print(f"  Group {grp}: {len(gids)} frames -> {len(versioned):,} bytes gzipped")

    # ── build seq entries ────────────────────────────────────────────────
    print("\nBuilding seq.dat entries...")
    max_sid = max(max(needed_seqs, default=0), seq_count - 1)
    while len(seq_entries) <= max_sid:
        seq_entries.append(bytes([0]))

    for sid in sorted(seq_info):
        info  = seq_info[sid]
        grp   = info["group"]
        fids  = info["file_ids"]
        dls   = info["delays"]
        if grp not in grp_map:
            print(f"  WARN: seq {sid} references missing group {grp}")
            continue
        fm  = grp_map[grp]
        gids = [fm.get(f, 0) for f in fids]
        dlys = dls if dls else [1] * len(gids)
        if len(gids) > 255:
            print(f"  WARN: seq {sid} has {len(gids)} frames; truncating to 255")
            gids = gids[:255]; dlys = dlys[:255]
        seq_entries[sid] = _seq_entry(gids, dlys)
        print(f"  seq {sid}: {len(gids)} frames  group={grp}")

    # ── build spotanim entries ───────────────────────────────────────────
    print("\nBuilding spotanim.dat entries...")
    max_gid_spa = max(max(needed_gfxs, default=0), spa_count - 1)
    while len(spa_entries) <= max_gid_spa:
        spa_entries.append(bytes([0]))

    for gid in sorted(needed_gfxs):
        info = gfx_info_map.get(gid, {})
        sid = info.get("sequence_id")
        mid = info.get("model_id")
        if sid is None or mid is None:
            print(f"  WARN: GFX {gid} missing model/seq metadata; leaving empty")
            continue
        spa_entries[gid] = _spa_entry(mid, sid)
        print(f"  GFX {gid} -> model {mid}, seq {sid}")

    # ── rebuild binary blobs ─────────────────────────────────────────────
    new_seq_dat  = _seq_pack(seq_entries)
    new_spa_dat  = _spa_pack(spa_entries)
    print(f"\nNew seq count:     {len(seq_entries)}")
    print(f"New spotanim count: {len(spa_entries)}")

    # extend versionlist arrays
    for ver, crc, _ in new_anim:
        anim_version.append(ver)
        anim_crc.append(crc)
    while len(anim_index) < next_gid:
        anim_index.append(0)   # values unused; only length matters

    print(f"New anim_version length: {len(anim_version)}")
    print(f"New anim_index length:   {len(anim_index)}")

    missing_models = sorted({
        info["model_id"]
        for info in gfx_info_map.values()
        if "model_id" in info and info["model_id"] * 6 + 6 > len(idx[1])
    })
    if missing_models:
        print("\nNOTE: These OSRS spotanim models are not present in idx1 and still need")
        print("a b238->rev254 model conversion/import step before the visual GFX can render:")
        print("  " + ", ".join(str(m) for m in missing_models))

    # repack config JagFile
    cfg[_jag_hash("seq.dat")]      = new_seq_dat
    cfg[_jag_hash("spotanim.dat")] = new_spa_dat
    new_config_raw = _jag_pack(cfg)

    # repack versionlist JagFile — if we read it from cache, update the
    # existing entries in-place; if we built from scratch, build a minimal
    # JagFile with just the anim arrays (client replaces it fully on next run
    # once it downloads fresh from the server).
    if vl_from_scratch:
        vl_new: Dict[int, bytes] = {}
    else:
        vl_new = vl_data["_raw_jag"]
    vl_new[_jag_hash("anim_version")] = struct.pack(f'>{len(anim_version)}H', *anim_version)
    vl_new[_jag_hash("anim_crc")]     = struct.pack(f'>{len(anim_crc)}i',     *anim_crc)
    vl_new[_jag_hash("anim_index")]   = struct.pack(f'>{len(anim_index)}H',   *anim_index)
    new_vl_raw = _jag_pack(vl_new)

    print(f"\nNew config JagFile:      {len(new_config_raw):,} bytes")
    print(f"New versionlist JagFile: {len(new_vl_raw):,} bytes")

    if dry_run:
        print("\n[DRY RUN] Nothing written.")
        return

    # ── write animation archives to idx2 (archiveId=3) ──────────────────
    print(f"\nWriting {len(new_anim)} animation archive(s) to idx2 (starting at file {cur_anim_files})...")
    for i, (ver, crc, data) in enumerate(new_anim):
        fid = cur_anim_files + i
        _fs_append(dat, idx[2], fid, 3, data)
        print(f"  idx2 file {fid}  ver={ver}  crc={crc}  size={len(data):,}B")

    # ── overwrite config and versionlist in idx0 (archiveId=1) ──────────
    print("Writing config JagFile -> idx0 file 2...")
    _fs_append(dat, idx[0], 2, 1, new_config_raw)

    print("Writing versionlist JagFile -> idx0 file 5...")
    _fs_append(dat, idx[0], 5, 1, new_vl_raw)

    # ── flush to disk ────────────────────────────────────────────────────
    print("Writing cache files to disk...")
    DAT.write_bytes(bytes(dat))
    for i, f in enumerate(IDXS):
        f.write_bytes(bytes(idx[i]))

    print("\nDone. Patch applied successfully.")
    print("The client will recalculate checksums from local cache on next startup,")
    print("so no server-side changes are needed.")


if __name__ == "__main__":
    main()
