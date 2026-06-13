#!/usr/bin/env python3
from __future__ import annotations

import json
import gzip
import shutil
import zipfile
from pathlib import Path
from typing import Dict, Iterable, List, Optional, Tuple


REPO_ROOT = Path(__file__).resolve().parents[1]
OUTPUT_ROOT = REPO_ROOT / "osrs" / "skillcape_emote_pack_b238"
ACTOR_PACK_JSON = REPO_ROOT / "cache" / "osrs_skillcape_actor_pack" / "data" / "osrs_skillcape_actor.json"

# Canonical extracted-cache directory (preferred over zip)
_CACHE_DIR_NAME = "osrs_cache_b238"
_ZIP_NAMES = ("osrs_cache_b238_gallery.zip", "osrs_cache_b238.zip")


def _find_cache_source() -> Tuple[Optional[Path], Optional[Path]]:
    """Return (cache_dir, zip_path) — whichever is found first.  cache_dir wins."""
    project_root = REPO_ROOT.parent
    search_roots = [REPO_ROOT / "osrs", project_root, project_root.parent]
    if project_root.parent.exists():
        for sibling in project_root.parent.iterdir():
            if sibling.is_dir() and sibling != project_root:
                search_roots.append(sibling)

    # Prefer an extracted directory
    for root in search_roots:
        d = root / _CACHE_DIR_NAME
        if d.is_dir() and (d / "main_file_cache.dat2").is_file():
            return d, None

    # Fall back to any recognised zip
    for root in search_roots:
        for name in _ZIP_NAMES:
            z = root / name
            if z.is_file():
                return None, z

    return None, None


def _load_cache_files(*names: str) -> Dict[str, bytes]:
    """Load named cache files from an extracted directory or zip."""
    cache_dir, zip_path = _find_cache_source()
    if cache_dir is not None:
        result = {}
        for name in names:
            p = cache_dir / name
            if not p.is_file():
                raise FileNotFoundError(f"Missing cache file: {p}")
            result[name] = p.read_bytes()
        return result
    if zip_path is not None:
        with zipfile.ZipFile(zip_path) as zf:
            # files may be at root or inside a single top-level directory
            prefix = ""
            for info in zf.infolist():
                parts = info.filename.split("/")
                if len(parts) == 2 and parts[1] == "main_file_cache.dat2":
                    prefix = parts[0] + "/"
                    break
            return {name: zf.read(prefix + name) for name in names}
    raise SystemExit(
        f"OSRS cache not found. Place the extracted cache at:\n"
        f"  {REPO_ROOT / 'osrs' / _CACHE_DIR_NAME}\n"
        f"or provide a zip named {_ZIP_NAMES[0]} alongside the project."
    )


def _cache_source_label() -> str:
    cache_dir, zip_path = _find_cache_source()
    return str(cache_dir if cache_dir else zip_path)


BODY_EMOTES = [
    ("attack", 4959, [823]),
    ("strength", 4981, [828]),
    ("defence", 4961, [824]),
    ("ranged", 4973, [832]),
    ("prayer", 4979, [829]),
    ("magic", 4939, [813]),
    ("runecraft", 4947, [817]),
    ("hitpoints", 4971, [833, 834]),
    ("agility", 4977, [830]),
    ("herblore", 4969, [835]),
    ("thieving", 4965, [826]),
    ("crafting", 4949, [818]),
    ("fletching", 4937, [812]),
    ("slayer", 4967, [827]),
    ("construction", 4953, [820]),
    ("mining", 4941, [814]),
    ("smithing", 4943, [815]),
    ("fishing", 4951, [819]),
    ("cooking", 4955, [821]),
    ("firemaking", 4975, [831]),
    ("woodcutting", 4957, [822]),
    ("farming", 4963, [825]),
    ("quest_point", 4945, [816]),
    ("hunter", 5158, [907]),
    ("achievement_diary", 2709, [323]),
    ("music", 4751, [1537]),
    ("max", 7121, []),
]


SEQUENCE_SPECS = {
    4959: ("attack", 11517, 44),
    4981: ("strength", 11511, 110),
    4961: ("defence", 11525, 55),
    4973: ("ranged", 11534, 62),
    4979: ("prayer", 11509, 58),
    4939: ("magic", 11528, 36),
    4947: ("runecraft", 11526, 60),
    4971: ("hitpoints", 11519, 60),
    4977: ("agility", 11523, 48),
    4969: ("herblore", 11514, 138),
    4965: ("thieving", 11512, 26),
    4949: ("crafting", 11518, 118),
    4937: ("fletching", 11522, 96),
    4967: ("slayer", 11529, 30),
    4953: ("construction", 11524, 113),
    4941: ("mining", 11520, 43),
    4943: ("smithing", 11531, 137),
    4951: ("fishing", 11516, 89),
    4955: ("cooking", 11513, 181),
    4975: ("firemaking", 11530, 70),
    4957: ("woodcutting", 11515, 150),
    4963: ("farming", 11521, 59),
    4945: ("quest_point", 11510, 85),
    5158: ("hunter", 11532, 112),
    2709: ("achievement_diary", 12195, 187),
    4751: ("music", 12213, 41),
    7121: ("max", 11443, 44),
    4960: ("attack-gfx", 10074, 44),
    4982: ("strength-gfx", 10062, 98),
    4962: ("defence-gfx", 10063, 42),
    4974: ("ranged-gfx", 10056, 60),
    4980: ("prayer-gfx", 10065, 58),
    4940: ("magic-gfx", 10067, 36),
    4948: ("runecraft-gfx", 10061, 60),
    4972: ("hitpoints-gfx", 10053, 60),
    4978: ("agility-gfx", 10055, 48),
    4970: ("herblore-gfx", 10054, 136),
    4966: ("thieving-gfx", 10060, 26),
    4950: ("crafting-gfx", 10066, 112),
    4938: ("fletching-gfx", 10071, 96),
    4968: ("slayer-gfx", 10076, 30),
    4954: ("construction-gfx", 10072, 113),
    4942: ("mining-gfx", 10059, 43),
    4944: ("smithing-gfx", 10073, 137),
    4952: ("fishing-gfx", 10058, 89),
    4956: ("cooking-gfx", 10050, 181),
    4976: ("firemaking-gfx", 10069, 70),
    4958: ("woodcutting-gfx", 10068, 147),
    4964: ("farming-gfx", 10057, 59),
    4946: ("quest-gfx", 10070, 85),
    5159: ("hunter-gfx", 10075, 105),
    2419: ("diary-gfx", 8939, 157),
    8046: ("music-gfx", 12548, 38),
}


GFX_SPECS = {
    823: (19071, 4960),
    828: (19090, 4982),
    824: (19075, 4962),
    832: (19087, 4974),
    829: (19085, 4980),
    813: (19083, 4940),
    817: (19069, 4948),
    833: (19081, 4972),
    834: (19082, 4972),
    830: (19070, 4978),
    835: (19080, 4970),
    826: (19091, 4966),
    818: (19074, 4950),
    812: (19079, 4938),
    827: (19088, 4968),
    820: (19072, 4954),
    814: (19084, 4942),
    815: (19089, 4944),
    819: (19078, 4952),
    821: (19073, 4956),
    831: (19077, 4976),
    822: (19092, 4958),
    825: (19076, 4964),
    816: (19086, 4946),
    907: (19978, 5159),
    323: (29172, 2419),
    1537: (35330, 8046),
}


# The reconstructed pack originally assigned every imported sequence a flat
# delay of 3. That makes the shorter skillcape emotes noticeably too fast in
# the rev-254 client, so we give each sequence a small duration floor instead.
BASE_FRAME_DELAY = 3
MIN_SEQUENCE_DURATION = 160
MAX_FRAME_DELAY = 6


def fs_read(dat: bytes, idx: bytes, fid: int, archive: int) -> Optional[bytes]:
    sector_size = 520
    payload_size = 512
    offset = fid * 6
    if offset + 6 > len(idx):
        return None
    entry = idx[offset:offset + 6]
    size = (entry[0] << 16) | (entry[1] << 8) | entry[2]
    sector = (entry[3] << 16) | (entry[4] << 8) | entry[5]
    if size <= 0 or sector == 0:
        return None

    out = bytearray()
    chunk = 0
    while len(out) < size:
        sector_offset = sector * sector_size
        available = len(dat) - sector_offset
        needed = min(payload_size, size - len(out))
        if available < sector_size:
            if available < 8 + needed:
                return None
            block = dat[sector_offset:sector_offset + available] + bytes(sector_size - available)
        else:
            block = dat[sector_offset:sector_offset + sector_size]
        if ((block[0] << 8) | block[1]) != fid or ((block[2] << 8) | block[3]) != chunk or block[7] != archive:
            return None
        out.extend(block[8:8 + needed])
        sector = (block[4] << 16) | (block[5] << 8) | block[6]
        chunk += 1
    return bytes(out)


def container_decompress(raw: bytes) -> bytes:
    compression = raw[0]
    compressed_length = int.from_bytes(raw[1:5], "big")
    if compression == 0:
        return raw[5:5 + compressed_length]

    payload = raw[5:5 + compressed_length + 4]
    decompressed_length = int.from_bytes(payload[:4], "big")
    if compression == 1:
        import bz2
        for prefix in (b"BZh9", b"BZh1", b""):
            try:
                out = bz2.decompress(prefix + payload[4:])
                if len(out) == decompressed_length:
                    return out
            except Exception:
                pass
        raise RuntimeError("BZip2 decode failed")
    if compression == 2:
        return gzip.decompress(payload[4:])
    raise RuntimeError(f"Unsupported compression type {compression}")


def infer_archive_file_count(data: bytes, max_files: int) -> int:
    chunks = data[-1]
    for count in range(1, max_files + 1):
        table_len = 1 + chunks * count * 4
        if table_len >= len(data):
            break
        table_off = len(data) - table_len
        pos = table_off
        total = 0
        ok = True
        for _ in range(chunks):
            chunk_size = 0
            for _ in range(count):
                delta = int.from_bytes(data[pos:pos + 4], "big", signed=True)
                pos += 4
                chunk_size += delta
                if chunk_size < 0:
                    ok = False
                    break
                total += chunk_size
            if not ok:
                break
        if ok and total == table_off:
            return count
    raise RuntimeError("Could not infer archive file count")


def split_archive_files(data: bytes, file_count: int) -> List[bytes]:
    if file_count == 1:
        return [data]

    chunks = data[-1]
    table_off = len(data) - 1 - chunks * file_count * 4
    chunk_sizes = [[0] * chunks for _ in range(file_count)]
    totals = [0] * file_count
    pos = table_off
    for chunk in range(chunks):
        chunk_size = 0
        for file_id in range(file_count):
            delta = int.from_bytes(data[pos:pos + 4], "big", signed=True)
            pos += 4
            chunk_size += delta
            chunk_sizes[file_id][chunk] = chunk_size
            totals[file_id] += chunk_size

    files = [bytearray(size) for size in totals]
    offsets = [0] * file_count
    pos = 0
    for chunk in range(chunks):
        for file_id in range(file_count):
            chunk_size = chunk_sizes[file_id][chunk]
            files[file_id][offsets[file_id]:offsets[file_id] + chunk_size] = data[pos:pos + chunk_size]
            offsets[file_id] += chunk_size
            pos += chunk_size
    return [bytes(entry) for entry in files]


def write_json(path: Path, obj: Dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(obj, indent=2) + "\n", encoding="utf-8")


def build_frame_lengths(frame_count: int) -> List[int]:
    if frame_count <= 0:
        return []

    delay = max(BASE_FRAME_DELAY, min(MAX_FRAME_DELAY, (MIN_SEQUENCE_DURATION + frame_count - 1) // frame_count))
    return [delay] * frame_count


def load_actor_item_map() -> Dict[int, int]:
    if not ACTOR_PACK_JSON.is_file():
        return {}
    actor_pack = json.loads(ACTOR_PACK_JSON.read_text(encoding="utf-8"))
    out: Dict[int, int] = {}
    for seq_id, info in actor_pack.get("capesBySeq", {}).items():
        out[int(seq_id)] = int(info.get("itemId", -1))
    return out


def ensure_clean_dir(path: Path) -> None:
    if path.exists():
        shutil.rmtree(path)
    path.mkdir(parents=True, exist_ok=True)


def export_frames(dat: bytes, idx0: bytes, needed_groups: Iterable[int], output_root: Path) -> None:
    for group_id in sorted(set(needed_groups)):
        raw = fs_read(dat, idx0, group_id, 0)
        if raw is None:
            raise RuntimeError(f"Missing frame group {group_id} in idx0")
        decoded = container_decompress(raw)
        file_count = infer_archive_file_count(decoded, 2048)
        files = split_archive_files(decoded, file_count)
        group_dir = output_root / "raw" / "frames" / f"index0_group_{group_id}"
        group_dir.mkdir(parents=True, exist_ok=True)
        for i, frame in enumerate(files, start=1):
            (group_dir / f"frame_{i}.dat").write_bytes(frame)


def collect_skeleton_ids(output_root: Path) -> set:
    """Return unique skeleton group IDs by reading the first 2 bytes of each exported frame."""
    skeleton_ids: set = set()
    frames_dir = output_root / "raw" / "frames"
    if not frames_dir.exists():
        return skeleton_ids
    for group_dir in frames_dir.iterdir():
        if not group_dir.is_dir():
            continue
        for frame_file in sorted(group_dir.iterdir()):
            if frame_file.suffix == ".dat":
                data = frame_file.read_bytes()
                if len(data) >= 2:
                    skeleton_ids.add((data[0] << 8) | data[1])
                break  # one frame per group is enough — all share the same skeleton
    return skeleton_ids


def export_skeletons(dat: bytes, idx1: bytes, skeleton_ids: set, output_root: Path) -> None:
    """Extract AnimBase (skeleton) data from idx1 and write in the format loadOsrsBase expects.

    The b238 AnimBase archive format decompresses directly to the loadOsrsBase file format:
      [1-byte count][count bytes: types][count bytes: labelGroupCounts][N bytes: label values]
    No conversion needed — write the decompressed bytes straight to disk.
    """
    for skeleton_id in sorted(skeleton_ids):
        raw = fs_read(dat, idx1, skeleton_id, 1)
        if raw is None:
            print(f"  Warning: skeleton group {skeleton_id} not found in idx1, skipping")
            continue
        decoded = container_decompress(raw)

        if len(decoded) < 1:
            print(f"  Warning: skeleton {skeleton_id} data empty, skipping")
            continue
        count = decoded[0] & 0xFF
        expected_min = 1 + count * 2
        if count == 0 or expected_min > len(decoded):
            print(f"  Warning: skeleton {skeleton_id} count={count} invalid for {len(decoded)}-byte payload, skipping")
            continue

        skel_dir = output_root / "raw" / "skeletons" / f"index1_group_{skeleton_id}"
        skel_dir.mkdir(parents=True, exist_ok=True)
        (skel_dir / f"skeleton_{skeleton_id}.dat").write_bytes(decoded)
        print(f"  skeleton {skeleton_id}: {count} transforms")


def export_models(dat: bytes, idx7: bytes, output_root: Path, model_ids: Iterable[int]) -> None:
    for model_id in sorted(set(model_ids)):
        raw = fs_read(dat, idx7, model_id, 7)
        if raw is None:
            raise RuntimeError(f"Missing model {model_id} in idx7")
        decoded = container_decompress(raw)
        model_dir = output_root / "raw" / "models" / f"index7_group_{model_id}"
        model_dir.mkdir(parents=True, exist_ok=True)
        (model_dir / f"model_{model_id}.dat").write_bytes(decoded)


def main() -> None:
    # cache source is validated inside _load_cache_files / _find_cache_source

    ensure_clean_dir(OUTPUT_ROOT)

    actor_item_ids = load_actor_item_map()

    emotes = []
    needed_seq_ids = set()
    needed_model_ids = set()

    for name, seq_id, gfx_ids in BODY_EMOTES:
        needed_seq_ids.add(seq_id)
        cape_item_id = actor_item_ids.get(seq_id, -1)
        emotes.append({
            "name": name,
            "animation_id": seq_id,
            "cape_item_ids": [cape_item_id] if cape_item_id >= 0 else [],
            "gfx_ids": gfx_ids,
        })
        for gfx_id in gfx_ids:
            model_id, gfx_seq_id = GFX_SPECS[gfx_id]
            needed_seq_ids.add(gfx_seq_id)
            needed_model_ids.add(model_id)

    cache = _load_cache_files(
        "main_file_cache.dat2",
        "main_file_cache.idx0",
        "main_file_cache.idx1",
        "main_file_cache.idx7",
    )
    dat  = cache["main_file_cache.dat2"]
    idx0 = cache["main_file_cache.idx0"]
    idx1 = cache["main_file_cache.idx1"]
    idx7 = cache["main_file_cache.idx7"]

    needed_groups = []
    for seq_id in sorted(needed_seq_ids):
        if seq_id not in SEQUENCE_SPECS:
            raise RuntimeError(f"Missing sequence spec for seq {seq_id}")
        _, group_id, frame_count = SEQUENCE_SPECS[seq_id]
        needed_groups.append(group_id)
        write_json(
            OUTPUT_ROOT / "decoded" / f"seq_{seq_id}.json",
            {
                "id": seq_id,
                "frame_block": {
                    "frame_archive_ids": [group_id] * frame_count,
                    "frame_file_ids": list(range(1, frame_count + 1)),
                    "frame_lengths": build_frame_lengths(frame_count),
                },
            },
        )

    for gfx_id, (model_id, seq_id) in GFX_SPECS.items():
        write_json(
            OUTPUT_ROOT / "decoded" / f"gfx_{gfx_id}.json",
            {
                "id": gfx_id,
                "model_id": model_id,
                "sequence_id": seq_id,
                "ambient": 0,
            },
        )

    write_json(
        OUTPUT_ROOT / "data" / "skillcape_emotes.json",
        {
            "source": _cache_source_label(),
            "emotes": emotes,
        },
    )

    export_frames(dat, idx0, needed_groups, OUTPUT_ROOT)
    export_models(dat, idx7, OUTPUT_ROOT, needed_model_ids)

    print("Extracting skeletons from idx1...")
    skeleton_ids = collect_skeleton_ids(OUTPUT_ROOT)
    export_skeletons(dat, idx1, skeleton_ids, OUTPUT_ROOT)

    print(f"Wrote reconstructed skillcape emote pack to {OUTPUT_ROOT}")
    print(f"Exported {len(set(needed_groups))} frame group(s)")
    print(f"Exported {len(needed_model_ids)} model(s)")
    print(f"Exported {len(skeleton_ids)} skeleton(s)")


if __name__ == "__main__":
    main()
