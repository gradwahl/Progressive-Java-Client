#!/usr/bin/env python3
"""
Adds the 27 skillcape spotanim models (IDs 19069-35330) to the rev-254 client
cache (main_file_cache.dat + main_file_cache.idx1).

Usage:
    python tools/patch_skillcape_models.py
    python tools/patch_skillcape_models.py --dry-run
"""
import gzip, io, struct, sys, shutil
from pathlib import Path

from skillcape_paths import resolve_emote_pack

CLIENT_CACHE = Path(__file__).resolve().parents[1] / "cache"
EMOTE_PACK   = resolve_emote_pack()

DAT  = CLIENT_CACHE / "main_file_cache.dat"
IDX1 = CLIENT_CACHE / "main_file_cache.idx1"

SECTOR   = 520
DSIZE    = 512
ARCHIVE  = 2  # idx1 archiveId in sector headers

def _fs_append(dat: bytearray, idx: bytearray, fid: int, arc: int, data: bytes):
    """Write data to dat as new sectors, update idx entry at fid."""
    first_sector = len(dat) // SECTOR
    total = len(data)
    remaining = total
    chunk = 0
    offset = 0

    # Extend idx to cover fid
    needed = (fid + 1) * 6
    if len(idx) < needed:
        idx += b'\x00' * (needed - len(idx))

    while remaining > 0:
        payload = min(DSIZE, remaining)
        next_sec = (first_sector + chunk + 1) if remaining > DSIZE else 0
        header = struct.pack('>HHBHB',
            fid & 0xFFFF,
            chunk & 0xFFFF,
            (next_sec >> 16) & 0xFF,
            next_sec & 0xFFFF,
            arc & 0xFF)
        # header is 7 bytes, but format is: fid(2) chunk(2) next(3) arc(1) = 8 bytes
        # rebuild properly
        header = bytes([
            (fid >> 8) & 0xFF, fid & 0xFF,
            (chunk >> 8) & 0xFF, chunk & 0xFF,
            (next_sec >> 16) & 0xFF, (next_sec >> 8) & 0xFF, next_sec & 0xFF,
            arc & 0xFF,
        ])
        sector_data = header + data[offset:offset+payload]
        sector_data += b'\x00' * (SECTOR - len(sector_data))
        dat += sector_data
        offset += payload
        remaining -= payload
        chunk += 1

    # Write idx entry: fileSize(u24) firstSector(u24)
    entry = bytes([
        (total >> 16) & 0xFF, (total >> 8) & 0xFF, total & 0xFF,
        (first_sector >> 16) & 0xFF, (first_sector >> 8) & 0xFF, first_sector & 0xFF,
    ])
    idx[fid*6 : fid*6+6] = entry

def main():
    dry_run = '--dry-run' in sys.argv
    print("=== Skillcape Model Patcher ===")
    if dry_run:
        print("DRY RUN\n")

    # Collect all spotanim model IDs from emote pack
    import json
    model_ids = {}
    for f in (EMOTE_PACK / "decoded").glob("gfx_*.json"):
        d = json.loads(f.read_text())
        if "model_id" in d:
            mid = d["model_id"]
            pack_file = EMOTE_PACK / f"raw/models/index7_group_{mid}/model_{mid}.dat"
            if pack_file.exists():
                model_ids[mid] = pack_file
            else:
                print(f"  WARN: model {mid} not in emote pack, skipping")

    print(f"Found {len(model_ids)} models in emote pack: {sorted(model_ids)}\n")

    dat = bytearray(DAT.read_bytes())
    idx = bytearray(IDX1.read_bytes())

    force = '--force' in sys.argv
    added = 0
    for mid in sorted(model_ids):
        # Check if already present
        o = mid * 6
        if o + 6 <= len(idx):
            b = idx[o:o+6]
            sz = (b[0]<<16)|(b[1]<<8)|b[2]
            if sz > 0 and not force:
                print(f"  model {mid}: already in cache, skipping (use --force to overwrite)")
                continue

        raw = model_ids[mid].read_bytes()
        buf = io.BytesIO()
        with gzip.GzipFile(fileobj=buf, mode='wb', mtime=0) as gz:
            gz.write(raw)
        model_data = buf.getvalue()
        print(f"  model {mid}: adding {len(raw)} bytes raw -> {len(model_data)} bytes gzipped")
        if not dry_run:
            _fs_append(dat, idx, mid, ARCHIVE, model_data)
        added += 1

    print(f"\n{added} models {'would be' if dry_run else ''} added")

    if not dry_run and added > 0:
        # Backup originals once
        for f, bname in [(DAT, "main_file_cache.dat.bak_models"),
                         (IDX1, "main_file_cache.idx1.bak_models")]:
            bak = CLIENT_CACHE / bname
            if not bak.exists():
                shutil.copy2(f, bak)
                print(f"Backed up {f.name}")

        DAT.write_bytes(dat)
        IDX1.write_bytes(idx)
        print(f"Written: dat={len(dat):,} bytes, idx1={len(idx):,} bytes")
        print("Done.")

if __name__ == '__main__':
    main()
