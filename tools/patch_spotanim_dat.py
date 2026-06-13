#!/usr/bin/env python3
"""
Patches spotanim.dat inside the config JagFile (idx0 file 2) to fix all
skillcape spotanim entries (IDs 812-835) with correct model/seq/ambient
from the emote pack JSONs.

Also reports the state of the spotanim seqs (4938-4982) in seq.dat.

Usage:
    python tools/patch_spotanim_dat.py [--dry-run]
"""

import bz2, json, struct, shutil, sys
from pathlib import Path

from skillcape_paths import resolve_emote_pack

CLIENT_CACHE = Path(__file__).resolve().parents[1] / "cache"
EMOTE_PACK   = resolve_emote_pack()

DAT  = CLIENT_CACHE / "main_file_cache.dat"
IDX0 = CLIENT_CACHE / "main_file_cache.idx0"

SECTOR = 520
DSIZE  = 512

# spotanim pack: ID -> name (from server spotanim.pack, skillcapes only)
SPOTANIM_NAMES = {
    812: "skillcape_gfx_fletching",
    813: "skillcape_gfx_magic",
    814: "skillcape_gfx_mining",
    815: "skillcape_gfx_smithing",
    817: "skillcape_gfx_runecraft",
    818: "skillcape_gfx_crafting",
    819: "skillcape_gfx_fishing",
    821: "skillcape_gfx_cooking",
    822: "skillcape_gfx_woodcutting",
    823: "skillcape_gfx_attack",
    824: "skillcape_gfx_defence",
    826: "skillcape_gfx_thieving",
    828: "skillcape_gfx_strength",
    829: "skillcape_gfx_prayer",
    830: "skillcape_gfx_agility",
    831: "skillcape_gfx_firemaking",
    832: "skillcape_gfx_ranged",
    833: "skillcape_gfx_hitpoints_a",
    834: "skillcape_gfx_hitpoints_b",
    835: "skillcape_gfx_herblore",
}

# ── cache I/O ──────────────────────────────────────────────────────────────

def read_sectors(dat: bytes, idx: bytes, file_id: int) -> bytes:
    o = file_id * 6
    sz  = (idx[o]<<16)|(idx[o+1]<<8)|idx[o+2]
    sec = (idx[o+3]<<16)|(idx[o+4]<<8)|idx[o+5]
    out = bytearray()
    chunk = 0
    while sec != 0 and len(out) < sz:
        soff = sec * SECTOR
        hdr  = dat[soff:soff+8]
        nxt  = (hdr[4]<<16)|(hdr[5]<<8)|hdr[6]
        pl   = min(DSIZE, sz - len(out))
        out += dat[soff+8:soff+8+pl]
        sec = nxt; chunk += 1
    return bytes(out)

def write_sectors(dat: bytearray, idx: bytearray, file_id: int, archive_id: int, data: bytes):
    first_sec = len(dat) // SECTOR
    total = len(data)
    remaining = total; chunk = 0; offset = 0
    needed = (file_id + 1) * 6
    if len(idx) < needed:
        idx += b'\x00' * (needed - len(idx))
    cur_sec = first_sec
    while remaining > 0:
        pl = min(DSIZE, remaining)
        next_sec = (cur_sec + 1) if remaining > DSIZE else 0
        hdr = bytes([
            (file_id >> 8) & 0xFF, file_id & 0xFF,
            (chunk >> 8) & 0xFF, chunk & 0xFF,
            (next_sec >> 16) & 0xFF, (next_sec >> 8) & 0xFF, next_sec & 0xFF,
            archive_id & 0xFF,
        ])
        sector = hdr + data[offset:offset+pl]
        sector += b'\x00' * (SECTOR - len(sector))
        dat += sector
        offset += pl; remaining -= pl; chunk += 1; cur_sec += 1
    entry = bytes([
        (total >> 16) & 0xFF, (total >> 8) & 0xFF, total & 0xFF,
        (first_sec >> 16) & 0xFF, (first_sec >> 8) & 0xFF, first_sec & 0xFF,
    ])
    idx[file_id*6:file_id*6+6] = entry

# ── JagFile ───────────────────────────────────────────────────────────────

def jag_name_hash(name: str) -> int:
    h = 0
    for c in name.upper():
        h = h * 61 + ord(c) - 32
    return h & 0xFFFFFFFF

def jag_decompress(raw: bytes) -> tuple[bytes, bool]:
    """Returns (inner_data, was_bzip2).  inner_data starts at fileCount."""
    uncmp = (raw[0]<<16)|(raw[1]<<8)|raw[2]
    cmp_  = (raw[3]<<16)|(raw[4]<<8)|raw[5]
    if uncmp == cmp_:
        return raw[6:], False  # not bzip2 at JagFile level
    data = bz2.decompress(b'BZh9' + raw[6:6+cmp_])
    assert len(data) == uncmp, f"BZip2 size mismatch: {len(data)} vs {uncmp}"
    return data, True

def parse_jag_files(inner: bytes) -> list[dict]:
    p = 0
    fc = (inner[p]<<8)|inner[p+1]; p += 2
    entries = []
    header_end = p + fc * 10
    cur = header_end
    for _ in range(fc):
        h  = struct.unpack('>I', inner[p:p+4])[0]
        us = (inner[p+4]<<16)|(inner[p+5]<<8)|inner[p+6]
        ps = (inner[p+7]<<16)|(inner[p+8]<<8)|inner[p+9]
        p += 10
        entries.append({'hash': h, 'us': us, 'ps': ps, 'off': cur})
        cur += ps
    return entries, fc

def extract_file_from_jag(inner: bytes, entries: list[dict], name: str, was_bzip2: bool) -> bytes | None:
    target = jag_name_hash(name)
    for e in entries:
        if e['hash'] == target:
            raw = inner[e['off']:e['off']+e['ps']]
            if not was_bzip2:
                # Individual files are bzip2-compressed
                return bz2.decompress(b'BZh9' + raw)
            return raw
    return None

def rebuild_jag(inner_orig: bytes, entries: list[dict], fc: int, was_bzip2: bool,
                name: str, new_file_data: bytes) -> bytes:
    target = jag_name_hash(name)
    # Build new inner content
    new_inner = bytearray()
    new_inner += bytes([fc >> 8, fc & 0xFF])  # fileCount (g2)
    # File entries header (we'll fill sizes after)
    file_data_list = []
    for e in entries:
        if e['hash'] == target:
            fd = new_file_data
        else:
            fd = inner_orig[e['off']:e['off']+e['ps']]
        file_data_list.append((e['hash'], e['us'], fd))

    for h, orig_us, fd in file_data_list:
        us = len(fd) if h == target else orig_us
        ps = len(fd)
        new_inner += struct.pack('>I', h)
        new_inner += bytes([(us>>16)&0xFF, (us>>8)&0xFF, us&0xFF])
        new_inner += bytes([(ps>>16)&0xFF, (ps>>8)&0xFF, ps&0xFF])
    for _, _, fd in file_data_list:
        new_inner += fd

    inner_bytes = bytes(new_inner)
    total = len(inner_bytes)

    if was_bzip2:
        compressed = bz2.compress(inner_bytes)
        # Strip "BZh9" prefix (4 bytes) — rev-254 BZip2 omits the stream header
        compressed = compressed[4:]
        outer = bytes([
            (total >> 16)&0xFF, (total>>8)&0xFF, total&0xFF,
            (len(compressed)>>16)&0xFF, (len(compressed)>>8)&0xFF, len(compressed)&0xFF,
        ])
        return outer + compressed
    else:
        # Not bzip2 at JagFile level — store individual files raw
        outer = bytes([
            (total>>16)&0xFF, (total>>8)&0xFF, total&0xFF,
            (total>>16)&0xFF, (total>>8)&0xFF, total&0xFF,
        ])
        return outer + inner_bytes

# ── spotanim.dat parsing ──────────────────────────────────────────────────

def parse_spotanim_dat(data: bytes) -> list[dict]:
    p = 0
    count = (data[p]<<8)|data[p+1]; p += 2
    entries = []
    for _ in range(count):
        e = {'model': -1, 'anim': -1, 'resizeh': 128, 'resizev': 128,
             'angle': 0, 'ambient': 0, 'contrast': 0, 'recol_s': [], 'recol_d': []}
        while True:
            op = data[p]; p += 1
            if op == 0:
                break
            elif op == 1:
                e['model'] = (data[p]<<8)|data[p+1]; p += 2
            elif op == 2:
                e['anim'] = (data[p]<<8)|data[p+1]; p += 2
            elif op == 4:
                e['resizeh'] = (data[p]<<8)|data[p+1]; p += 2
            elif op == 5:
                e['resizev'] = (data[p]<<8)|data[p+1]; p += 2
            elif op == 6:
                e['angle'] = (data[p]<<8)|data[p+1]; p += 2
            elif op == 7:
                e['ambient'] = data[p]; p += 1
            elif op == 8:
                e['contrast'] = data[p]; p += 1
            elif 40 <= op < 50:
                e['recol_s'].append(((data[p]<<8)|data[p+1], op-40)); p += 2
            elif 50 <= op < 60:
                e['recol_d'].append(((data[p]<<8)|data[p+1], op-50)); p += 2
            else:
                print(f"  WARN: unknown spotanim opcode {op} at pos {p-1}")
        entries.append(e)
    return entries

def encode_spotanim_entry(e: dict) -> bytes:
    b = bytearray()
    if e['model'] != -1:
        b += bytes([1, (e['model']>>8)&0xFF, e['model']&0xFF])
    if e['anim'] != -1:
        b += bytes([2, (e['anim']>>8)&0xFF, e['anim']&0xFF])
    if e['resizeh'] != 128:
        b += bytes([4, (e['resizeh']>>8)&0xFF, e['resizeh']&0xFF])
    if e['resizev'] != 128:
        b += bytes([5, (e['resizev']>>8)&0xFF, e['resizev']&0xFF])
    if e['angle'] != 0:
        b += bytes([6, (e['angle']>>8)&0xFF, e['angle']&0xFF])
    if e['ambient'] != 0:
        b += bytes([7, e['ambient'] & 0xFF])
    if e['contrast'] != 0:
        b += bytes([8, e['contrast'] & 0xFF])
    for val, idx in e.get('recol_s', []):
        b += bytes([40+idx, (val>>8)&0xFF, val&0xFF])
    for val, idx in e.get('recol_d', []):
        b += bytes([50+idx, (val>>8)&0xFF, val&0xFF])
    b += bytes([0])  # end
    return bytes(b)

def encode_spotanim_dat(entries: list[dict]) -> bytes:
    count = len(entries)
    out = bytearray()
    out += bytes([count>>8, count&0xFF])
    for e in entries:
        out += encode_spotanim_entry(e)
    return bytes(out)

# ── seq.dat frame-count check ─────────────────────────────────────────────

def count_seq_frames(seq_dat: bytes, seq_id: int) -> int:
    p = 0
    total = (seq_dat[p]<<8)|seq_dat[p+1]; p += 2
    if seq_id >= total:
        return -1
    # Fast path: count frames by scanning seq seq_id
    # We need to skip seq_id entries
    for _ in range(seq_id):
        while True:
            op = seq_dat[p]; p += 1
            if op == 0: break
            elif op == 1:
                fc = (seq_dat[p]<<8)|seq_dat[p+1]; p += 2
                p += fc * 2  # frame IDs
                p += fc * 2  # delays
            elif op == 2: p += 2
            elif op == 3:
                lc = (seq_dat[p]<<8)|seq_dat[p+1]; p += 2
                p += lc * 4  # labels
            elif op == 4: pass
            elif op == 5: p += 2
            elif op == 6: p += 2
            elif op == 7: p += 2
            elif op == 8: p += 2
            elif op == 9: p += 2
            elif op == 10: p += 2
            elif op == 11: p += 2
            elif op == 12: p += 1
            else: break  # unknown, bail

    # Now parse seq_id
    while True:
        op = seq_dat[p]; p += 1
        if op == 0: return 0
        elif op == 1:
            fc = (seq_dat[p]<<8)|seq_dat[p+1]
            return fc
        elif op == 2: p += 2
        elif op == 3:
            lc = (seq_dat[p]<<8)|seq_dat[p+1]; p += 2
            p += lc * 4
        elif op == 4: pass
        elif op == 5: p += 2
        elif op == 6: p += 2
        elif op == 7: p += 2
        elif op == 8: p += 2
        elif op == 9: p += 2
        elif op == 10: p += 2
        elif op == 11: p += 2
        elif op == 12: p += 1
        else: return -99  # parse error

# ── main ──────────────────────────────────────────────────────────────────

def main():
    dry_run = '--dry-run' in sys.argv
    print("=== SpotAnim.dat Patcher ===")
    if dry_run:
        print("DRY RUN\n")

    # Load emote pack GFX info: spotanim_id -> {model, seq, ambient}
    gfx_correct = {}
    for f in sorted((EMOTE_PACK / "decoded").glob("gfx_*.json")):
        gid = int(f.stem[4:])
        if gid not in SPOTANIM_NAMES:
            continue
        d = json.loads(f.read_text())
        gfx_correct[gid] = {
            'model':   d.get('model_id', -1),
            'anim':    d.get('sequence_id', -1),
            'ambient': d.get('ambient', 0),
        }
    print(f"Loaded {len(gfx_correct)} GFX overrides from emote pack\n")

    # Read config JagFile
    dat = bytearray(DAT.read_bytes())
    idx0 = bytearray(IDX0.read_bytes())
    jag_raw = read_sectors(bytes(dat), bytes(idx0), 2)
    inner, was_bzip2 = jag_decompress(jag_raw)
    entries, fc = parse_jag_files(inner)
    print(f"JagFile: was_bzip2={was_bzip2}, {fc} files\n")

    # Extract and parse spotanim.dat
    spotanim_bytes = extract_file_from_jag(inner, entries, "spotanim.dat", was_bzip2)
    if spotanim_bytes is None:
        print("ERROR: spotanim.dat not found in JagFile!")
        return
    print(f"spotanim.dat size: {len(spotanim_bytes)} bytes")
    spa_entries = parse_spotanim_dat(spotanim_bytes)
    print(f"  -> {len(spa_entries)} spotanim entries\n")

    # Also extract seq.dat for frame count check
    seq_bytes = extract_file_from_jag(inner, entries, "seq.dat", was_bzip2)
    if seq_bytes:
        print(f"seq.dat size: {len(seq_bytes)} bytes")

    # Report and fix skillcape spotanims
    changed = 0
    print(f"{'ID':<6} {'Name':<30} {'cur_model':>10} {'cur_seq':>8} {'amb':>4}  {'new_model':>10} {'new_seq':>8} {'amb':>4}  {'status'}")
    print("-" * 100)
    for sid, name in sorted(SPOTANIM_NAMES.items()):
        if sid >= len(spa_entries):
            print(f"{sid:<6} {name:<30} {'OOB':>10}")
            continue
        cur = spa_entries[sid]
        want = gfx_correct.get(sid)
        if not want:
            print(f"{sid:<6} {name:<30} {'no override':>10}")
            continue

        status = ""
        model_ok = (cur['model'] == want['model'])
        anim_ok  = (cur['anim']  == want['anim'])
        amb_ok   = (cur['ambient'] == want['ambient'])
        if model_ok and anim_ok and amb_ok:
            status = "OK"
        else:
            reasons = []
            if not model_ok: reasons.append("model")
            if not anim_ok:  reasons.append("seq")
            if not amb_ok:   reasons.append("ambient")
            status = f"FIX ({','.join(reasons)})"
            # Apply fix
            spa_entries[sid]['model']   = want['model']
            spa_entries[sid]['anim']    = want['anim']
            spa_entries[sid]['ambient'] = want['ambient']
            changed += 1

        # Check spotanim seq frames if seq.dat available
        seq_frames = ""
        if seq_bytes and want['anim'] >= 0:
            try:
                fc_val = count_seq_frames(seq_bytes, want['anim'])
                seq_frames = f"(frames={fc_val})"
            except:
                seq_frames = "(err)"

        print(f"{sid:<6} {name:<30} {cur['model']:>10} {cur['anim']:>8} {cur['ambient']:>4}  "
              f"{want['model']:>10} {want['anim']:>8} {want['ambient']:>4}  {status} {seq_frames}")

    print(f"\n{changed} entries need fixing")

    if changed == 0:
        print("Nothing to do.")
        return

    if dry_run:
        print("\nDRY RUN — no changes written.")
        return

    # Backup
    for f, bname in [(DAT, "main_file_cache.dat.bak_spa"), (IDX0, "main_file_cache.idx0.bak_spa")]:
        bak = CLIENT_CACHE / bname
        if not bak.exists():
            shutil.copy2(f, bak)
            print(f"Backed up {f.name}")

    # Encode new spotanim.dat
    new_spotanim = encode_spotanim_dat(spa_entries)
    print(f"New spotanim.dat size: {len(new_spotanim)} bytes (was {len(spotanim_bytes)})")

    # Rebuild JagFile
    new_jag = rebuild_jag(inner, entries, fc, was_bzip2, "spotanim.dat", new_spotanim)
    print(f"New JagFile size: {len(new_jag)} bytes (was {len(jag_raw)})")

    # Write new JagFile to cache (as NEW sectors at end of dat)
    write_sectors(dat, idx0, 2, 0, new_jag)
    print(f"Written: dat={len(dat):,} bytes, idx0={len(idx0):,} bytes")

    DAT.write_bytes(dat)
    IDX0.write_bytes(idx0)
    print("Done.")

if __name__ == '__main__':
    main()
