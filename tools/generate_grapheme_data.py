"""Generate C++ Unicode 17.0 grapheme property ranges from verified UCD files."""
import argparse
import hashlib
from pathlib import Path


SOURCES = {
    "GraphemeBreakProperty.txt": "d6b51d1d2ae5c33b451b7ed994b48f1f4dc62b2272a5831e7fd418514a6bae89",
    "emoji-data.txt": "2cb2bb9455cda83e8481541ecf5b6dfda66a3bb89efa3fa7c5297eccf607b72b",
    "DerivedCoreProperties.txt": "24c7fed1195c482faaefd5c1e7eb821c5ee1fb6de07ecdbaa64b56a99da22c08",
}


def properties(source, select):
    records = []
    for line in source.read_text(encoding="utf-8").splitlines():
        fields = [field.strip() for field in line.partition("#")[0].split(";")]
        if len(fields) < 2:
            continue
        value = select(fields[1:])
        if value is not None:
            first, _, last = fields[0].partition("..")
            records.append((int(first, 16), int(last or first, 16), value))
    merged = []
    for first, last, value in sorted(records):
        if merged and first <= merged[-1][1]:
            raise ValueError(f"Overlapping property ranges in {source.name}")
        if merged and first == merged[-1][1] + 1 and value == merged[-1][2]:
            merged[-1] = (merged[-1][0], last, value)
        else:
            merged.append((first, last, value))
    return merged


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("ucd", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    for name, expected in SOURCES.items():
        if hashlib.sha256((args.ucd / name).read_bytes()).hexdigest() != expected:
            parser.error(f"Unexpected Unicode property file: {name}")
    tables = (
        ("Break", "breakRanges", "GraphemeBreakProperty.txt", lambda values: values[0]),
        ("Indic", "indicRanges", "DerivedCoreProperties.txt",
         lambda values: values[1] if values[0] == "InCB" and len(values) == 2 else None),
        ("bool", "pictographRanges", "emoji-data.txt",
         lambda values: "true" if values[0] == "Extended_Pictographic" else None),
    )
    with args.output.open("x", encoding="ascii", newline="\n") as stream:
        stream.write("// Generated Unicode 17.0.0 data. See data/notices/UNICODE-LICENSE.txt.\n")
        for kind, name, source, select in tables:
            stream.write(f"inline constexpr PropertyRange<{kind}> {name}[] = {{\n")
            for first, last, value in properties(args.ucd / source, select):
                token = value if kind == "bool" else f"{kind}::{value}"
                stream.write(f"    {{0x{first:X}, 0x{last:X}, {token}}},\n")
            stream.write("};\n")
    print(hashlib.sha256(args.output.read_bytes()).hexdigest())


if __name__ == "__main__":
    main()
