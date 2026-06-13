from __future__ import annotations

import os
from pathlib import Path
from typing import Iterable, Optional


REPO_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_EMOTE_PACK = Path(r"C:\Users\Callum\Downloads\skillcape_emote_pack_b238")
EMOTE_PACK_ENV = "RS254_SKILLCAPE_EMOTE_PACK_DIR"


def _is_emote_pack_dir(path: Path) -> bool:
    return (path / "data" / "skillcape_emotes.json").is_file()


def _candidate_roots() -> Iterable[Path]:
    seen: set[Path] = set()

    env_path = os.environ.get(EMOTE_PACK_ENV)
    if env_path:
        candidate = Path(env_path).expanduser()
        if candidate not in seen:
            seen.add(candidate)
            yield candidate

    for candidate in (
        DEFAULT_EMOTE_PACK,
        REPO_ROOT / "cache" / "skillcape_emote_pack_b238",
        REPO_ROOT / "skillcape_emote_pack_b238",
        REPO_ROOT.parent / "skillcape_emote_pack_b238",
        REPO_ROOT.parent.parent / "skillcape_emote_pack_b238",
    ):
        if candidate not in seen:
            seen.add(candidate)
            yield candidate


def resolve_emote_pack(required: bool = True) -> Optional[Path]:
    searched = list(_candidate_roots())
    for candidate in searched:
        if _is_emote_pack_dir(candidate):
            return candidate

    if not required:
        return None

    locations = "\n".join(f"  - {path}" for path in searched)
    raise FileNotFoundError(
        "Could not locate the skillcape emote pack. Set "
        f"{EMOTE_PACK_ENV} to a directory containing data/skillcape_emotes.json.\n"
        f"Searched:\n{locations}"
    )
