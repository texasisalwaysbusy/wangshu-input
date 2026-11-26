"""Pure host checks: no downloads, model execution or user data."""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "tools"))
from model_vocab import vocabulary_from_bytes


class VocabularyIdentityTest(unittest.TestCase):
    def test_unicode_separators_remain_token_bytes(self):
        tokens = ["[PAD]", "\u2028", "中", "##\u2028", "伞", "\u2029", "冬"]
        raw = ("\n".join(tokens) + "\n").encode()
        mapping = vocabulary_from_bytes(raw, len(tokens), {"[PAD]": 0})
        self.assertEqual(list(mapping.items()), list(zip(tokens, range(len(tokens)))))
        self.assertNotEqual(raw.decode().splitlines(), tokens)

    def test_duplicate_hidden_by_dictionary_size_is_rejected(self):
        raw = b"first\nsecond\nsecond\n"
        with self.assertRaises(ValueError):
            vocabulary_from_bytes(raw, 2)
        with self.assertRaises(ValueError):
            vocabulary_from_bytes(raw, 3)

    def test_crlf_and_no_final_delimiter(self):
        self.assertEqual(vocabulary_from_bytes(b"a\r\nb\r\n", 2), {"a": 0, "b": 1})
        self.assertEqual(vocabulary_from_bytes(b"a\nb", 2), {"a": 0, "b": 1})

    def test_whitespace_is_not_stripped(self):
        self.assertEqual(vocabulary_from_bytes(b"a\n a\na \n\n", 4),
                         {"a": 0, " a": 1, "a ": 2, "": 3})

    def test_shape_and_special_ids(self):
        for size in (0, -1, True, 1, 3):
            with self.assertRaises(ValueError):
                vocabulary_from_bytes(b"[PAD]\n[UNK]\n", size)
        for special in ({"[PAD]": 1}, {"[MASK]": 2}):
            with self.assertRaises(ValueError):
                vocabulary_from_bytes(b"[PAD]\n[UNK]\n", 2, special)

    def test_invalid_input_encoding(self):
        with self.assertRaises(UnicodeDecodeError):
            vocabulary_from_bytes(b"\xff\n", 1)
        with self.assertRaises(ValueError):
            vocabulary_from_bytes(b"a\rb", 2)


if __name__ == "__main__":
    unittest.main(verbosity=2)
