#!/usr/bin/env python3
r"""
Extract a minimal OSRS actor asset pack for skillcape emote body replacement.

Source cache:
  C:\Users\Callum\Downloads\Project\117hd port\osrs_cache_b238_gallery.zip

Output pack:
  <repo>/cache/osrs_skillcape_actor_pack

The pack contains:
  - a compact JSON manifest describing default male/female body kits
  - skillcape cape wear-model metadata keyed by emote sequence
  - the raw model .dat files needed to assemble those actors at runtime
"""

from __future__ import annotations

import gzip
import json
import shutil
import zipfile
from pathlib import Path
from typing import Dict, List, Optional

import patch_skillcape_emotes as patch
from skillcape_paths import resolve_emote_pack

REPO_ROOT = Path(__file__).resolve().parents[1]
SOURCE_ZIP = Path(r"C:\Users\Callum\Downloads\Project\117hd port\osrs_cache_b238_gallery.zip")
EMOTE_PACK = resolve_emote_pack()
OUTPUT_ROOT = REPO_ROOT / "cache" / "osrs_skillcape_actor_pack"
OUTPUT_MODELS = OUTPUT_ROOT / "models"
OUTPUT_DATA = OUTPUT_ROOT / "data"
SPOTANIM_MODEL_IDS = {
    19069, 19070, 19071, 19072, 19073, 19074, 19075, 19076, 19077,
    19078, 19079, 19080, 19081, 19082, 19083, 19084, 19085, 19086,
    19087, 19088, 19089, 19090, 19091, 19092, 19978, 29172, 35330,
}


def container_decompress(raw: bytes) -> bytes:
    compression = raw[0]
    compressed_length = int.from_bytes(raw[1:5], "big")
    if compression == 0:
        return raw[5:5 + compressed_length]

    payload = raw[5:5 + compressed_length + 4]
    decompressed_length = int.from_bytes(payload[:4], "big")
    if compression == 1:
        return patch._bz2_decompress(payload[4:], decompressed_length)
    if compression == 2:
        return gzip.decompress(payload[4:])
    raise ValueError(f"Unsupported container compression type {compression}")


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
    raise RuntimeError("Could not infer file count from archive payload")


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


def parse_identkit(data: bytes) -> Dict:
    pos = 0
    out = {
        "bodyPartId": -1,
        "models": [],
        "nonSelectable": False,
        "recolors": [],
    }
    while pos < len(data):
        opcode = data[pos]
        pos += 1
        if opcode == 0:
            break
        if opcode == 1:
            out["bodyPartId"] = data[pos]
            pos += 1
        elif opcode == 2:
            count = data[pos]
            pos += 1
            out["models"] = [int.from_bytes(data[pos + i * 2:pos + i * 2 + 2], "big") for i in range(count)]
            pos += count * 2
        elif opcode == 3:
            out["nonSelectable"] = True
        elif opcode == 5:
            count = data[pos]
            pos += 1
            out["models"] = [int.from_bytes(data[pos + i * 4:pos + i * 4 + 4], "big") for i in range(count)]
            pos += count * 4
        elif opcode == 40:
            count = data[pos]
            pos += 1
            for _ in range(count):
                src = int.from_bytes(data[pos:pos + 2], "big")
                dst = int.from_bytes(data[pos + 2:pos + 4], "big")
                pos += 4
                out["recolors"].append([src, dst])
        elif opcode == 41:
            count = data[pos]
            pos += 1 + count * 4
        elif 60 <= opcode < 70:
            pos += 2
        elif 70 <= opcode < 80:
            pos += 4
        else:
            raise RuntimeError(f"Unknown identkit opcode {opcode}")
    return out


def parse_item(data: bytes) -> Dict:
    pos = 0
    out = {
        "name": None,
        "maleModels": [-1, -1, -1],
        "femaleModels": [-1, -1, -1],
        "maleOffset": 0,
        "femaleOffset": 0,
        "recolors": [],
    }
    while pos < len(data):
        opcode = data[pos]
        pos += 1
        if opcode == 0:
            break
        if opcode in (2, 3, 9) or 30 <= opcode < 40:
            end = data.index(0, pos)
            text = data[pos:end].decode("latin-1")
            pos = end + 1
            if opcode == 2:
                out["name"] = text
        elif opcode in (1, 4, 5, 6, 7, 8, 10, 78, 79, 90, 91, 92, 93, 94, 95, 97, 98, 110, 111, 112, 139, 140, 148, 149):
            pos += 2
        elif opcode == 12:
            pos += 4
        elif opcode in (11, 15, 16, 65):
            pass
        elif opcode in (13, 14, 27, 42, 113, 114, 115):
            pos += 1
        elif opcode == 23:
            out["maleModels"][0] = int.from_bytes(data[pos:pos + 2], "big")
            pos += 2
            out["maleOffset"] = data[pos]
            pos += 1
        elif opcode == 24:
            out["maleModels"][1] = int.from_bytes(data[pos:pos + 2], "big")
            pos += 2
        elif opcode == 25:
            out["femaleModels"][0] = int.from_bytes(data[pos:pos + 2], "big")
            pos += 2
            out["femaleOffset"] = data[pos]
            pos += 1
        elif opcode == 26:
            out["femaleModels"][1] = int.from_bytes(data[pos:pos + 2], "big")
            pos += 2
        elif opcode == 40:
            count = data[pos]
            pos += 1
            for _ in range(count):
                src = int.from_bytes(data[pos:pos + 2], "big")
                dst = int.from_bytes(data[pos + 2:pos + 4], "big")
                pos += 4
                out["recolors"].append([src, dst])
        elif opcode == 41:
            count = data[pos]
            pos += 1 + count * 4
        elif opcode == 43:
            op_id = data[pos]
            pos += 1
            while True:
                subop = data[pos] - 1
                pos += 1
                if subop == -1:
                    break
                end = data.index(0, pos)
                pos = end + 1
        elif 44 <= opcode <= 54:
            value = int.from_bytes(data[pos:pos + 4], "big")
            pos += 4
            if opcode == 45:
                out["maleModels"][0] = value
                out["maleOffset"] = data[pos]
                pos += 1
            elif opcode == 46:
                out["maleModels"][1] = value
            elif opcode == 47:
                out["maleModels"][2] = value
            elif opcode == 48:
                out["femaleModels"][0] = value
                out["femaleOffset"] = data[pos]
                pos += 1
            elif opcode == 49:
                out["femaleModels"][1] = value
            elif opcode == 50:
                out["femaleModels"][2] = value
        elif 100 <= opcode < 110:
            pos += 4
        elif opcode == 75:
            pos += 2
        elif opcode == 249:
            count = data[pos]
            pos += 1
            for _ in range(count):
                is_string = data[pos]
                pos += 1
                pos += 3
                if is_string:
                    end = data.index(0, pos)
                    pos = end + 1
                else:
                    pos += 4
        else:
            raise RuntimeError(f"Unknown item opcode {opcode}")
    return out


def load_archive_files(dat: bytes, idx: bytes, file_id: int, arc_id: int, max_files: int) -> List[bytes]:
    raw = patch._fs_read(dat, idx, file_id, arc_id)
    if raw is None:
        raise RuntimeError(f"Could not read archive {file_id} from index arc={arc_id}")
    decoded = container_decompress(raw)
    file_count = infer_archive_file_count(decoded, max_files)
    return split_archive_files(decoded, file_count)


def choose_default_parts(identkits: List[Dict], start_part: int) -> List[Dict]:
    chosen = []
    for body_part in range(start_part, start_part + 7):
        match = None
        for kit_id, kit in enumerate(identkits):
            if kit["bodyPartId"] == body_part and not kit["nonSelectable"] and kit["models"]:
                match = {
                    "kitId": kit_id,
                    "bodyPartId": body_part,
                    "models": kit["models"],
                    "recolors": kit["recolors"],
                }
                break
        if match is None:
            raise RuntimeError(f"No default identkit found for bodyPartId={body_part}")
        chosen.append(match)
    return chosen


def main() -> None:
    if not SOURCE_ZIP.is_file():
        raise SystemExit(f"Missing OSRS cache zip: {SOURCE_ZIP}")

    if OUTPUT_ROOT.exists():
        shutil.rmtree(OUTPUT_ROOT)
    OUTPUT_MODELS.mkdir(parents=True, exist_ok=True)
    OUTPUT_DATA.mkdir(parents=True, exist_ok=True)

    manifest = json.loads((EMOTE_PACK / "data" / "skillcape_emotes.json").read_text(encoding="utf-8"))

    with zipfile.ZipFile(SOURCE_ZIP) as zf:
        dat = zf.read("osrs_cache_b238_gallery/main_file_cache.dat2")
        idx2 = zf.read("osrs_cache_b238_gallery/main_file_cache.idx2")
        idx7 = zf.read("osrs_cache_b238_gallery/main_file_cache.idx7")

    identkit_files = load_archive_files(dat, idx2, 3, 2, 2000)
    item_files = load_archive_files(dat, idx2, 10, 2, 40000)

    identkits = [parse_identkit(entry) for entry in identkit_files]
    male_parts = choose_default_parts(identkits, 0)
    female_parts = choose_default_parts(identkits, 7)

    capes: Dict[str, Dict] = {}
    required_model_ids = set()

    for part in male_parts + female_parts:
        required_model_ids.update(model_id for model_id in part["models"] if model_id >= 0)

    for emote in manifest["emotes"]:
        seq_id = int(emote["animation_id"])
        cape_item_id = int(emote["cape_item_ids"][0]) if emote.get("cape_item_ids") else -1
        if cape_item_id < 0 or cape_item_id >= len(item_files):
            continue
        item = parse_item(item_files[cape_item_id])
        capes[str(seq_id)] = {
            "itemId": cape_item_id,
            "name": item["name"],
            "maleModels": item["maleModels"],
            "femaleModels": item["femaleModels"],
            "maleOffset": item["maleOffset"],
            "femaleOffset": item["femaleOffset"],
            "recolors": item["recolors"],
        }
        required_model_ids.update(model_id for model_id in item["maleModels"] if model_id >= 0)
        required_model_ids.update(model_id for model_id in item["femaleModels"] if model_id >= 0)

    required_model_ids.update(SPOTANIM_MODEL_IDS)

    missing_models = []
    for model_id in sorted(required_model_ids):
        raw = patch._fs_read(dat, idx7, model_id, 7)
        if raw is None:
            missing_models.append(model_id)
            continue
        model_dir = OUTPUT_MODELS / f"index7_group_{model_id}"
        model_dir.mkdir(parents=True, exist_ok=True)
        (model_dir / f"model_{model_id}.dat").write_bytes(container_decompress(raw))

    pack = {
        "source": str(SOURCE_ZIP),
        "maleDefaultParts": male_parts,
        "femaleDefaultParts": female_parts,
        "capesBySeq": capes,
        "spotanimModels": sorted(SPOTANIM_MODEL_IDS),
        "missingModels": missing_models,
    }
    (OUTPUT_DATA / "osrs_skillcape_actor.json").write_text(json.dumps(pack, indent=2), encoding="utf-8")
    props = []
    props.append(f"source={SOURCE_ZIP}")
    props.append("male.parts=" + "|".join(",".join(str(m) for m in part["models"]) for part in male_parts))
    props.append("male.partRecolors=" + "|".join(",".join(f"{src}:{dst}" for src, dst in part["recolors"]) for part in male_parts))
    props.append("female.parts=" + "|".join(",".join(str(m) for m in part["models"]) for part in female_parts))
    props.append("female.partRecolors=" + "|".join(",".join(f"{src}:{dst}" for src, dst in part["recolors"]) for part in female_parts))
    for seq_id, cape in sorted(capes.items(), key=lambda entry: int(entry[0])):
        props.append(f"seq.{seq_id}.itemId={cape['itemId']}")
        props.append(f"seq.{seq_id}.name={cape['name']}")
        props.append(f"seq.{seq_id}.maleModels={','.join(str(m) for m in cape['maleModels'] if m >= 0)}")
        props.append(f"seq.{seq_id}.femaleModels={','.join(str(m) for m in cape['femaleModels'] if m >= 0)}")
        props.append(f"seq.{seq_id}.maleOffset={cape['maleOffset']}")
        props.append(f"seq.{seq_id}.femaleOffset={cape['femaleOffset']}")
        props.append(f"seq.{seq_id}.recolors={','.join(f'{src}:{dst}' for src, dst in cape['recolors'])}")
    (OUTPUT_DATA / "osrs_skillcape_actor.properties").write_text("\n".join(props) + "\n", encoding="utf-8")

    print(f"Wrote actor pack to {OUTPUT_ROOT}")
    print(f"Exported {len(required_model_ids) - len(missing_models)} model(s)")
    if missing_models:
        print("Missing model IDs:")
        print("  " + ", ".join(str(m) for m in missing_models))


if __name__ == "__main__":
    main()
