"""Create compact, normalized frequency dictionaries for the on-device predictor."""
from __future__ import annotations

import argparse
import re
from collections import defaultdict
from pathlib import Path


ALLOWED_SINGLE_RU = {"а", "в", "и", "к", "о", "с", "у", "я"}


def prepare(source: Path, destination: Path, limit: int, language: str) -> int:
    pattern = re.compile(r"^[а-яё]{2,}$") if language == "ru" else re.compile(r"^[a-z]{2,}$")
    words: dict[str, int] = defaultdict(int)
    for line in source.read_text(encoding="utf-8").splitlines():
        parts = line.rsplit(" ", 1)
        if len(parts) != 2:
            continue
        word, raw_frequency = parts[0].strip().lower(), parts[1]
        if not (pattern.fullmatch(word) or (language == "ru" and word in ALLOWED_SINGLE_RU)):
            continue
        try:
            words[word] += int(raw_frequency)
        except ValueError:
            continue
    ranked = sorted(words.items(), key=lambda item: (-item[1], item[0]))[:limit]
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text("".join(f"{word}\t{frequency}\n" for word, frequency in ranked), encoding="utf-8")
    return len(ranked)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    parser.add_argument("destination", type=Path)
    parser.add_argument("--limit", type=int, required=True)
    parser.add_argument("--language", choices=("ru", "en"), required=True)
    args = parser.parse_args()
    print(prepare(args.source, args.destination, args.limit, args.language))
