#!/usr/bin/env python3
"""Static checks for the datapack examples shipped with MineTurn.

Run from the repository root:

    python tools/validate_examples.py

Exit code 0 means every check passed; 1 means at least one problem was reported.

Scope and non-goals
-------------------
Examples are *partial* datapacks. They intentionally reference actions, items and
functions that live in the base mod (`src/main/resources/data/mineturn/`) or in
another pack, so a dangling reference is **not** an error. This script only checks
things that are objectively wrong: unparseable JSON, a malformed `pack.mcmeta`, a
`data/` tree that is not `data/<namespace>/...`, syntactically invalid ids,
example directories listed in `build.gradle` that do not exist, and local file
links in the docs that point at missing files.

Only the Python standard library is used.
"""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
EXAMPLES = ROOT / "examples"
BASE_DATA = ROOT / "src" / "main" / "resources" / "data"

PACK_FORMAT = 48
ID_RE = re.compile(r"^[a-z0-9_.-]+:[a-z0-9_./-]+$")

problems: list[str] = []
notes: list[str] = []
checked_files = 0


def fail(message: str) -> None:
    problems.append(message)


def load_json(path: Path):
    """Returns (value, None) or (None, error message)."""
    global checked_files
    checked_files += 1
    try:
        return json.loads(path.read_text(encoding="utf-8")), None
    except UnicodeDecodeError as error:
        return None, f"not valid UTF-8: {error}"
    except json.JSONDecodeError as error:
        return None, f"invalid JSON: {error.msg} (line {error.lineno}, column {error.colno})"


def rel(path: Path) -> str:
    return path.relative_to(ROOT).as_posix()


# --------------------------------------------------------------------------- #
# 1. every example pack: pack.mcmeta + data tree
# --------------------------------------------------------------------------- #
def check_pack(pack: Path) -> None:
    meta = pack / "pack.mcmeta"
    if not meta.is_file():
        fail(f"{rel(pack)}: missing pack.mcmeta")
    else:
        value, error = load_json(meta)
        if error:
            fail(f"{rel(meta)}: {error}")
        else:
            blob = value.get("pack") if isinstance(value, dict) else None
            if not isinstance(blob, dict):
                fail(f"{rel(meta)}: missing top-level 'pack' object")
            else:
                fmt = blob.get("pack_format")
                if fmt != PACK_FORMAT:
                    fail(f"{rel(meta)}: pack_format is {fmt!r}, expected {PACK_FORMAT}")
                if not blob.get("description"):
                    fail(f"{rel(meta)}: 'pack.description' is missing or empty")

    data = pack / "data"
    if not data.is_dir():
        fail(f"{rel(pack)}: missing data/ directory")
        return
    namespaces = [entry for entry in data.iterdir() if entry.is_dir()]
    if not namespaces:
        fail(f"{rel(data)}: contains no namespace directory")
    for namespace in namespaces:
        if not re.match(r"^[a-z0-9_.-]+$", namespace.name):
            fail(f"{rel(namespace)}: namespace '{namespace.name}' has invalid characters")


# --------------------------------------------------------------------------- #
# 2. every JSON file parses, and definition ids are well formed
# --------------------------------------------------------------------------- #
def check_json_tree(directory: Path) -> None:
    for path in sorted(directory.rglob("*.json")):
        value, error = load_json(path)
        if error:
            fail(f"{rel(path)}: {error}")
            continue
        if path.parent.name in {"actions", "grants"} and isinstance(value, dict):
            name = path.stem
            namespace = path.parent.parent.parent.name  # data/<ns>/mineturn/actions
            full = f"{namespace}:{name}"
            if not ID_RE.match(full):
                fail(f"{rel(path)}: derived id '{full}' is not a valid resource location")


# --------------------------------------------------------------------------- #
# 3. example directories referenced by build.gradle must exist
# --------------------------------------------------------------------------- #
def check_build_references() -> None:
    build = ROOT / "build.gradle"
    if not build.is_file():
        fail("build.gradle is missing")
        return
    text = build.read_text(encoding="utf-8")
    referenced = sorted({match for match in re.findall(r"examples/([A-Za-z0-9_.-]+)", text)})
    if not referenced:
        notes.append("build.gradle references no example directories")
    for name in referenced:
        if not (EXAMPLES / name).is_dir():
            fail(f"build.gradle references examples/{name}, which does not exist")

    # The reverse direction is informational: an example that nothing packages is fine (it may be a
    # manual drop-in), but it is worth surfacing.
    present = sorted(entry.name for entry in EXAMPLES.iterdir() if entry.is_dir())
    for name in present:
        if name not in referenced:
            notes.append(f"examples/{name} is not referenced by build.gradle")


# --------------------------------------------------------------------------- #
# 4. local file links in the docs resolve
# --------------------------------------------------------------------------- #
DOC_DIRS = [ROOT, ROOT / "docs", ROOT / "examples"]
LINK_RE = re.compile(r"\[[^\]]*\]\(([^)]+)\)")

# Directories that hold build output, downloaded dependencies or editor state. They are never part of
# the shipped project, so scanning them only produces noise (node_modules alone has hundreds of
# third-party READMEs with links that are broken on purpose).
EXCLUDED_DIRS = {
    ".git", ".gradle", ".idea", ".github", "build", "run", "run-gametest", "outputs",
    "node_modules", "repo", "__pycache__",
}


def iter_project_files(directory: Path, suffix: str):
    for path in sorted(directory.rglob(f"*{suffix}")):
        if any(part in EXCLUDED_DIRS for part in path.relative_to(ROOT).parts[:-1]):
            continue
        yield path


def check_doc_links() -> None:
    for directory in DOC_DIRS:
        for path in iter_project_files(directory, ".md"):
            text = path.read_text(encoding="utf-8")
            for target in LINK_RE.findall(text):
                target = target.strip()
                if target.startswith(("http://", "https://", "mailto:", "#")):
                    continue
                if "://" in target:
                    continue
                bare = target.split("#", 1)[0].split("?", 1)[0]
                if not bare:
                    continue
                if not (path.parent / bare).exists() and not (ROOT / bare).exists():
                    fail(f"{rel(path)}: link target '{target}' does not exist")


# --------------------------------------------------------------------------- #
# 5. example READMEs are only required when the pack is packaged as an archive
# --------------------------------------------------------------------------- #
def check_example_readmes() -> None:
    for pack in sorted(entry for entry in EXAMPLES.iterdir() if entry.is_dir()):
        data = pack / "data"
        if not data.is_dir():
            continue
        if not (pack / "README.md").is_file():
            notes.append(f"{rel(pack)}: no README.md (acceptable, but a short note helps users)")


def main() -> int:
    if not EXAMPLES.is_dir():
        print(f"FAIL: {rel(EXAMPLES)} not found; run this from the repository root")
        return 1
    if not BASE_DATA.is_dir():
        fail(f"{rel(BASE_DATA)} not found; the base datapack is required for reference checks")

    packs = sorted(entry for entry in EXAMPLES.iterdir() if entry.is_dir())
    for pack in packs:
        check_pack(pack)
        check_json_tree(pack)
    check_build_references()
    check_doc_links()
    check_example_readmes()

    print(f"example packs checked : {len(packs)}")
    print(f"json files parsed     : {checked_files}")
    if notes:
        print(f"\nnotes ({len(notes)}):")
        for note in notes:
            print(f"  - {note}")
    if problems:
        print(f"\nFAILED with {len(problems)} problem(s):")
        for problem in problems:
            print(f"  - {problem}")
        return 1
    print("\nOK: all example checks passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
