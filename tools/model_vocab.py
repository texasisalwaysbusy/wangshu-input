"""Identity checks for model vocabularies whose UTF-8 rows are implicit token IDs.

Do not use str.splitlines(): Unicode separators can themselves be vocabulary
entries. This loader is for LF / CRLF token-per-row files (such as BERT's
vocab.txt), not for explicit-ID tokens.txt or JSON tokenizer tables.
"""
from argparse import ArgumentParser
from hashlib import sha256
import json
from pathlib import Path


def vocabulary_from_bytes(raw, expected_size, special_ids=None):
    """Return an exact dense ID mapping, or reject a mismatched vocabulary."""
    if not isinstance(expected_size, int) or isinstance(expected_size, bool) or expected_size <= 0:
        raise ValueError("expected vocabulary size must be a positive integer")
    text = raw.decode("utf-8", errors="strict")
    # LF separates rows, and CR is only allowed as part of a CRLF delimiter.
    # Never strip whitespace or use Unicode's broad line-breaking rules.
    text = text.replace("\r\n", "\n")
    if "\r" in text:
        raise ValueError("unexpected bare CR in LF/CRLF vocabulary")
    rows = text.split("\n")
    if rows[-1] == "":
        rows.pop()
    if len(rows) != expected_size:
        raise ValueError(f"physical vocabulary row count {len(rows)} != {expected_size}")
    mapping = {token: index for index, token in enumerate(rows)}
    if len(mapping) != len(rows):
        raise ValueError("duplicate vocabulary token would change implicit IDs")
    for token, expected in (special_ids or {}).items():
        if mapping.get(token) != expected:
            raise ValueError(f"incorrect or missing special token ID: {token}")
    return mapping


def load_vocabulary(path, expected_size, special_ids=None):
    return vocabulary_from_bytes(Path(path).read_bytes(), expected_size, special_ids)


def main():
    parser = ArgumentParser(description=__doc__)
    parser.add_argument("vocabulary", type=Path)
    parser.add_argument("--vocab-size", required=True, type=int)
    parser.add_argument("--bert-special-ids", action="store_true",
                        help="Require the canonical original BERT special IDs")
    args = parser.parse_args()
    raw = args.vocabulary.read_bytes()
    special = {"[PAD]": 0, "[UNK]": 100, "[CLS]": 101, "[SEP]": 102, "[MASK]": 103} if args.bert_special_ids else {}
    mapping = vocabulary_from_bytes(raw, args.vocab_size, special)
    print(json.dumps({"status": "PASS", "size": len(mapping),
                      "sha256": sha256(raw).hexdigest(), "special_ids": special}))


if __name__ == "__main__":
    main()
