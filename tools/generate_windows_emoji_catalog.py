"""Keep the Windows symbol catalogue in sync with the authored Android entries."""
import argparse
from functools import lru_cache
import json
from pathlib import Path
import re


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "android/app/src/main/java/org/wangshu/ime/EmojiCatalog.java"
OUTPUT = ROOT / "windows/emoji_catalog.inc"
PINYIN = ROOT / "data/pinyin.tsv"
READINGS = {"女": "nv", "乘": "cheng"}
STRING = r'"(?:[^"\\]|\\["\\])*"'
GROUP = re.compile(r'add\(Kind\.(EMOJI|KAOMOJI),(' + STRING + r'),new String\[\]\[\]\{(.*?)\}\);', re.S)
ENTRY = re.compile(r'\{\s*(' + STRING + r')\s*,\s*(' + STRING + r')\s*\}')


def pinyin_aliases(descriptions):
    pieces = {description[start:end]
              for description in descriptions
              for start in range(len(description))
              for end in range(start + 1, len(description) + 1)}
    readings = {}
    for line in PINYIN.read_text(encoding="utf-8").splitlines():
        if not line or line.startswith("#"):
            continue
        pinyin, text, score = line.split("\t")
        if text in pieces and re.fullmatch(r"[a-z]+", pinyin):
            rank = (int(score), pinyin)
            if text not in readings or rank > readings[text]:
                readings[text] = rank

    @lru_cache(maxsize=None)
    def spell(text):
        if not text:
            return ""
        if text[0] == " ":
            return spell(text[1:])
        for end in range(len(text), 0, -1):
            token = text[:end]
            pinyin = READINGS.get(token) or readings.get(token, (0, ""))[1]
            if pinyin:
                rest = spell(text[end:])
                if rest is not None:
                    return pinyin + rest
        return None

    aliases = {description: spell(description) for description in descriptions}
    missing = [description for description, alias in aliases.items() if not alias]
    if missing:
        raise ValueError(f"Missing pinyin search aliases for {missing}")
    return aliases


def render():
    text = SOURCE.read_text(encoding="utf-8")
    groups = GROUP.findall(text)
    if len(groups) != text.count("add(Kind."):
        raise ValueError("Unrecognized Android catalogue group")
    catalogue = []
    for _, category, body in groups:
        if ENTRY.sub("", body).strip(" \t\r\n,"):
            raise ValueError(f"Unrecognized entry in {category}")
        entries = ENTRY.findall(body)
        if not entries:
            raise ValueError(f"Empty group {category}")
        for symbol, description in entries:
            catalogue.append(tuple(json.loads(value) for value in (category, symbol, description)))
    aliases = pinyin_aliases({description for _, _, description in catalogue})
    rows = ["// Generated from the project-authored EmojiCatalog.java.",
            "// Pinyin search aliases derive from data/pinyin.tsv, CC BY-SA 4.0.",
            "// See data/CC-CEDICT-NOTICE.txt. Regenerate with tools/generate_windows_emoji_catalog.py."]
    for category, symbol, description in catalogue:
        fields = (category, symbol, description, aliases[description])
        literals = [json.dumps(value, ensure_ascii=False) for value in fields]
        rows.append("    {" + ", ".join("u8" + value for value in literals) + "},")
    return ("\n".join(rows) + "\n").encode("utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    expected = render()
    if args.check:
        if not OUTPUT.is_file() or OUTPUT.read_bytes() != expected:
            raise SystemExit("Windows emoji catalogue differs from Android source")
    else:
        OUTPUT.write_bytes(expected)


if __name__ == "__main__":
    main()
