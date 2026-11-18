"""Keep the Windows symbol catalogue in sync with the authored Android entries."""
import argparse
import json
from pathlib import Path
import re


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "android/app/src/main/java/org/wangshu/ime/EmojiCatalog.java"
OUTPUT = ROOT / "windows/emoji_catalog.inc"
STRING = r'"(?:[^"\\]|\\["\\])*"'
GROUP = re.compile(r'add\(Kind\.(EMOJI|KAOMOJI),(' + STRING + r'),new String\[\]\[\]\{(.*?)\}\);', re.S)
ENTRY = re.compile(r'\{\s*(' + STRING + r')\s*,\s*(' + STRING + r')\s*\}')


def render():
    text = SOURCE.read_text(encoding="utf-8")
    groups = GROUP.findall(text)
    if len(groups) != text.count("add(Kind."):
        raise ValueError("Unrecognized Android catalogue group")
    rows = ["// Generated from the project-authored EmojiCatalog.java.",
            "// Refresh with tools/generate_windows_emoji_catalog.py."]
    for _, category, body in groups:
        if ENTRY.sub("", body).strip(" \t\r\n,"):
            raise ValueError(f"Unrecognized entry in {category}")
        entries = ENTRY.findall(body)
        if not entries:
            raise ValueError(f"Empty group {category}")
        for symbol, description in entries:
            literals = [json.dumps(json.loads(value), ensure_ascii=False)
                        for value in (category, symbol, description)]
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
