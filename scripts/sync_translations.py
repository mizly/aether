#!/usr/bin/env python3
"""Synchronize language pack keys and README coverage data."""

from __future__ import annotations

import json
import shutil
from collections import OrderedDict
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SOURCE_LOCALE = ROOT / "src/main/resources/assets/aether/lang/en_us.json"
TRANSLATIONS_DIR = ROOT / "translations"
README = ROOT / "README.md"
START_MARKER = "<!-- translation-coverage:start -->"
END_MARKER = "<!-- translation-coverage:end -->"


def load_json(path: Path) -> OrderedDict[str, str]:
    with path.open("r", encoding="utf-8-sig") as file:
        data = json.load(file, object_pairs_hook=OrderedDict)

    if not isinstance(data, dict):
        raise ValueError(f"{path} must contain a JSON object.")

    return data


def write_json(path: Path, data: OrderedDict[str, str]) -> None:
    path.write_text(
        json.dumps(data, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )


def sync_locale(locale_data: OrderedDict[str, str], english_data: OrderedDict[str, str]) -> OrderedDict[str, str]:
    """Keep translations, add missing source keys, and remove stale keys."""
    synced = OrderedDict(
        (key, value) for key, value in locale_data.items() if key in english_data
    )
    synced.update(
        (key, value)
        for key, value in english_data.items()
        if key not in synced
    )
    return synced


def coverage_for(
    locale_data: dict[str, str], english_data: dict[str, str]
) -> tuple[int, int, float]:
    total = len(english_data)
    covered = sum(
        1
        for key, english_value in english_data.items()
        if key in locale_data and locale_data[key] != english_value
    )
    percentage = (covered / total * 100) if total else 100.0
    return covered, total, percentage


def build_coverage_section(rows: list[tuple[str, int, int, float]]) -> str:
    lines = [
        START_MARKER,
        "## Translation Coverage",
        "",
    ]

    if not rows:
        lines.extend(
            [
                "No translation files have been added yet.",
                END_MARKER,
            ]
        )
        return "\n".join(lines)

    lines.extend(
        [
            "| Locale | Covered | Percentage |",
            "| --- | ---: | ---: |",
        ]
    )

    for locale, covered, total, percentage in rows:
        lines.append(f"| `{locale}` | {covered}/{total} | {percentage:.1f}% |")

    lines.append(END_MARKER)
    return "\n".join(lines)


def update_readme(section: str) -> None:
    current = README.read_text(encoding="utf-8") if README.exists() else ""

    if START_MARKER in current and END_MARKER in current:
        before = current.split(START_MARKER, 1)[0].rstrip()
        after = current.split(END_MARKER, 1)[1].lstrip()
        updated = f"{before}\n\n{section}\n"
        if after:
            updated += f"\n{after}"
    else:
        updated = f"{current.rstrip()}\n\n{section}\n"

    README.write_text(updated, encoding="utf-8")


def main() -> None:
    TRANSLATIONS_DIR.mkdir(parents=True, exist_ok=True)

    english_data = load_json(SOURCE_LOCALE)
    translations_english_path = TRANSLATIONS_DIR / SOURCE_LOCALE.name
    if not translations_english_path.exists() or SOURCE_LOCALE.read_bytes() != translations_english_path.read_bytes():
        shutil.copyfile(SOURCE_LOCALE, translations_english_path)

    coverage_rows: list[tuple[str, int, int, float]] = []

    for path in sorted(TRANSLATIONS_DIR.glob("*.json")):
        if path.name == SOURCE_LOCALE.name:
            continue

        locale_data = load_json(path)
        synced_data = sync_locale(locale_data, english_data)
        if synced_data != locale_data:
            write_json(path, synced_data)

        covered, total, percentage = coverage_for(synced_data, english_data)
        coverage_rows.append((path.stem, covered, total, percentage))

    update_readme(build_coverage_section(coverage_rows))


if __name__ == "__main__":
    main()
