#!/usr/bin/env python3
"""Reject unresolved conflict markers and bare branch labels in tracked code.

Git can accept files where a human accidentally drops conflict delimiters but
leaves the names of both branches as standalone lines. These compile into a
broken production APK. Run before Android SDK / Gradle provisioning in CI.
"""
from __future__ import annotations

import pathlib
import re
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
SOURCE_SUFFIXES = {
    ".kt", ".kts", ".java", ".cpp", ".cc", ".c", ".h", ".hpp",
    ".xml", ".gradle", ".py", ".sh", ".yml", ".yaml",
}
MERGE_MARKER = re.compile(r"^(?:<{7}(?:\s|$)|>{7}(?:\s|$)|={7}$|\|{7}(?:\s|$))")
BRANCH_LABEL = re.compile(
    r"^(?:main|master|develop|"
    r"(?:fix|feat|feature|release|qa|hotfix|chore)/[A-Za-z0-9._/-]+)$"
)


def reason(line: str) -> str | None:
    bare = line.strip()
    if MERGE_MARKER.match(bare):
        return "unresolved Git conflict marker"
    if BRANCH_LABEL.fullmatch(bare):
        return "bare Git branch label in source"
    return None


def tracked_sources() -> list[pathlib.Path]:
    paths = subprocess.check_output(
        ["git", "-C", str(ROOT), "ls-files", "-z", "--",
         "app/src", "inference/src", "scripts", ".github/workflows",
         "app/build.gradle.kts", "inference/build.gradle.kts",
         "build.gradle.kts", "settings.gradle.kts"],
    )
    return [
        ROOT / pathlib.Path(p.decode("utf-8", errors="replace"))
        for p in paths.split(b"\0") if p and pathlib.Path(p.decode("utf-8", errors="replace")).suffix in SOURCE_SUFFIXES
    ]


def main() -> int:
    errors: list[str] = []
    for file in tracked_sources():
        if not file.exists():
            continue
        try:
            lines = file.read_text(encoding="utf-8").splitlines()
        except UnicodeDecodeError:
            continue
        for number, line in enumerate(lines, 1):
            why = reason(line)
            if why:
                errors.append(f"{file.relative_to(ROOT)}:{number}: {why}")
    if errors:
        print("\n".join(errors), file=sys.stderr)
        print("Merge artifact guard: FAIL", file=sys.stderr)
        return 1
    print("Merge artifact guard: PASS (tracked source files)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
