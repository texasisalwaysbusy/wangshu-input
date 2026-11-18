#!/usr/bin/env python3
"""Build bounded prediction tables from fixed Tatoeba exports without network access."""
import argparse
import bz2
from collections import Counter
import hashlib
import json
from pathlib import Path
import re
import tarfile
import unicodedata


ENGLISH = re.compile(r"[a-z]+(?:'[a-z]+)?(?: +[a-z]+(?:'[a-z]+)?)*")
HAN = re.compile(r"[\u3400-\u4dbf\u4e00-\u9fff]+")
ENGLISH_SHA = "7e4251d06edd949db9e47cc360b472873da6c054af5dc4b9d2c9e06d024cd710"
ENGLISH_COMPLETION_SHA = "e96bad67af5c70c2a2af922fea8fd62c70534c5309cd7a06dec3ab417c2161f5"
ENGLISH_FITTED_SHA = "15e91b08b532fb15adccec9014123aa0cfa679b3e95ecaf616f2689819a9a56a"
MANDARIN_SHA = "262a48cacb7693f6e47a2c4c42f216a5c45dd28b9b62014b159628cc908e8fcc"
DICTIONARY_SHA = "ba80abb85e43b47f7d16c7e2a39f3de56e3dadc986fb67e4167bbd075e64d77b"


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def sentences(stream, language):
    seen = set()
    for line in stream:
        fields = line.decode("utf-8").rstrip("\r\n").split("\t")
        if len(fields) >= 3 and fields[1] == language:
            value = unicodedata.normalize("NFC", fields[2]).strip()
            if value and len(value) <= 256 and value not in seen:
                seen.add(value)
                yield value


def english_model(archive_path):
    words = Counter()
    contexts = Counter()
    sentence_count = 0
    with tarfile.open(archive_path, "r:bz2") as archive:
        with archive.extractfile("sentences_CC0.csv") as stream:
            for sentence in sentences(stream, "eng"):
                sentence_count += 1
                sentence = sentence.lower().replace("’", "'")
                for match in ENGLISH.finditer(sentence):
                    tokens = match[0].split()
                    for index, word in enumerate(tokens):
                        if not 1 <= len(word) <= 24:
                            continue
                        words[word] += 1
                        for length in (1, 2):
                            prior = tokens[max(index - length, 0):index]
                            if len(prior) == length and max(map(len, prior)) <= 24:
                                contexts[" ".join(prior), word, False] += 1
    return words, contexts, sentence_count


def han_dictionary(path):
    words = {}
    characters = set()
    with path.open(encoding="utf-8") as stream:
        for line in stream:
            if line.startswith("#"):
                continue
            _, word, score = line.rstrip("\r\n").split("\t")
            if HAN.fullmatch(word):
                characters.update(word)
                if 2 <= len(word) <= 6:
                    words[word] = max(words.get(word, 0), int(score))
    return words, characters


def segment_han(text, words):
    """Prefer dictionary coverage, then fewer pieces, then dictionary frequency."""
    size = len(text)
    best = [(0, 0, 0)] * (size + 1)
    ends = [size] * size
    for start in range(size - 1, -1, -1):
        end = start + 1
        tail = best[end]
        best[start] = (tail[0], tail[1] - 1, tail[2])
        ends[start] = end
        for end in range(start + 2, min(start + 6, size) + 1):
            score = words.get(text[start:end])
            if score is not None:
                tail = best[end]
                rank = (tail[0] + end - start, tail[1] - 1, tail[2] + score)
                if rank > best[start]:
                    best[start] = rank
                    ends[start] = end
    start = 0
    while start < size:
        end = ends[start]
        yield start, end
        start = end


def mandarin_model(export_path, dictionary, characters):
    contexts = Counter()
    sentence_count = 0
    used_runs = 0
    with bz2.open(export_path, "rb") as stream:
        for sentence in sentences(stream, "cmn"):
            sentence_count += 1
            for match in HAN.finditer(sentence):
                text = match[0]
                # The shipped pinyin lexicon uses simplified headwords. Avoid
                # training on runs containing Han outside that inventory.
                if not all(character in characters for character in text):
                    continue
                used_runs += 1
                for start, end in segment_han(text, dictionary):
                    for cursor in range(start, end):
                        for length in range(2, min(cursor, 6) + 1):
                            contexts[text[cursor-length:cursor], text[cursor:end], cursor != start] += 1
    return contexts, sentence_count, used_runs


def bounded_rows(counts, key_limit):
    groups = {}
    for (key, value, partial), count in counts.items():
        if count >= 2:
            groups.setdefault(key, []).append((value, count, partial))
    keys = sorted(groups, key=lambda key: (-sum(row[1] for row in groups[key]), key))
    for key in sorted(keys[:key_limit]):
        rows = sorted(groups[key], key=lambda row: (-row[1], row[0], row[2]))
        for value, count, partial in rows[:12]:
            yield key, value, count, partial


def emit(output, words, english, mandarin):
    return emit_rows(output, words, list(bounded_rows(english, 5500)), list(bounded_rows(mandarin, 9000)))


def emit_integer_table(stream, typename, name, items, suffix=""):
    stream.write(f"inline constexpr {typename} {name}[] = {{\n")
    for start in range(0, len(items), 8):
        stream.write("    " + ", ".join(f"{item}{suffix}" for item in items[start:start + 8]) + ",\n")
    stream.write("};\n")


def emit_compact_english(stream, words, rows):
    ids = {word: index for index, (word, _) in enumerate(words, 1)}
    if not ids or len(ids) > 65535:
        raise ValueError("English vocabulary cannot be indexed by 16-bit IDs")
    grouped = {}
    for history, word, count, partial in rows:
        history_words = history.split(" ")
        if not 1 <= len(history_words) <= 3 or partial or count < 2:
            raise ValueError("Invalid English transition")
        key = 0
        for token in history_words:
            key = (key << 16) | ids[token]
        grouped.setdefault(key, []).append((min(count, 65535) << 16) | ids[word])

    keys = sorted(grouped)
    offsets = [0]
    packed_rows = []
    for key in keys:
        followers = grouped[key]
        if len(followers) > 8 or len({row & 65535 for row in followers}) != len(followers):
            raise ValueError("Invalid English candidate group")
        packed_rows.extend(followers)
        offsets.append(len(packed_rows))

    # Keys are one to three nonzero word IDs. The final offset is the row sentinel.
    emit_integer_table(stream, "uint64_t", "englishContextKeys", keys, "ULL")
    emit_integer_table(stream, "uint32_t", "englishContextOffsets", offsets)
    emit_integer_table(stream, "uint32_t", "englishContextRows", packed_rows, "u")
    return {"english_contexts": len(keys), "english_context_bytes": len(keys) * 8 +
            (len(offsets) + len(packed_rows)) * 4}


def emit_rows(output, words, english, mandarin, compact_english=False):
    """Pack lexicographically grouped, pre-ranked context rows without reranking."""
    words = sorted((word, count) for word, count in words.items() if count >= 2)
    # Pack references into integers to avoid per-entry relocations in each native binary.
    chunk_size = 8192
    chunks = [""]
    refs = {}
    strings = {word for word, _ in words}
    text_tables = (("chineseContexts", mandarin),)
    if not compact_english:
        text_tables = (("englishContexts", english),) + text_tables
    strings.update(key for _, rows in text_tables for key, _, _, _ in rows)
    strings.update(value for _, rows in text_tables for _, value, _, _ in rows)
    for value in sorted(strings):
        assert 0 < len(value) < 64
        if len(chunks[-1]) + len(value) > chunk_size:
            chunks.append("")
        offset = (len(chunks) - 1) * chunk_size + len(chunks[-1])
        refs[value] = (offset << 6) | len(value)
        chunks[-1] += value
    with output.open("x", encoding="utf-8", newline="\n") as stream:
        stream.write("// Generated by tools/generate_context_predictions.py. See data/notices/TATOEBA-NOTICE.txt.\n")
        stream.write(f"inline constexpr size_t textChunkSize = {chunk_size};\n")
        stream.write("inline constexpr const char16_t* textChunks[] = {\n")
        for chunk in chunks:
            for index in range(0, len(chunk), 96):
                stream.write(f'    u"{chunk[index:index + 96]}"\n')
            stream.write("    ,\n")
        stream.write("};\n")
        stream.write("inline constexpr Word englishWords[] = {\n")
        for word, count in words:
            stream.write(f"    {{{refs[word]}, {min(count, 65535)}}},\n")
        stream.write("};\n")
        for name, rows in text_tables:
            stream.write(f"inline constexpr Transition {name}[] = {{\n")
            for key, value, count, partial in rows:
                mode = "true" if partial else "false"
                stream.write(f"    {{{refs[key]}, {refs[value]}, {min(count, 65535)}, {mode}}},\n")
            stream.write("};\n")
        compact = emit_compact_english(stream, words, english) if compact_english else {}
    return dict(compact, english_words=len(words), english_transitions=len(english),
                mandarin_transitions=len(mandarin))


def read_fitted_english(path):
    groups = {}
    with path.open(encoding="ascii") as stream:
        for line in stream:
            key, word, count, rank = line.rstrip("\r\n").split("\t")
            followers = groups.setdefault(key, [])
            if int(rank) != len(followers) + 1:
                raise ValueError("Noncanonical English candidate rank")
            followers.append((key, word, int(count), False))
    return [row for key in sorted(groups) for row in groups[key]]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("english", "mandarin", "dictionary", "output"):
        parser.add_argument(name, type=Path)
    parser.add_argument("--english-completion-vocabulary", type=Path,
                        help="Verified English word counts from the disjoint expanded corpus")
    parser.add_argument("--english-fitted-transitions", type=Path,
                        help="Verified contextual ranks from the same expanded corpus")
    args = parser.parse_args()
    for path, expected in ((args.english, ENGLISH_SHA), (args.mandarin, MANDARIN_SHA),
                           (args.dictionary, DICTIONARY_SHA)):
        if sha(path) != expected:
            parser.error(f"Unexpected source identity: {path}")
    words, english, english_count = english_model(args.english)
    completion_source = args.english_completion_vocabulary
    if completion_source is not None:
        if sha(completion_source) != ENGLISH_COMPLETION_SHA:
            parser.error(f"Unexpected English completion vocabulary identity: {completion_source}")
        with completion_source.open(encoding="ascii") as stream:
            words = {}
            for line in stream:
                word, count = line.rstrip("\r\n").split("\t")
                words[word] = int(count)
    mandarin, mandarin_count, mandarin_runs = mandarin_model(args.mandarin, *han_dictionary(args.dictionary))
    fitted = args.english_fitted_transitions
    if fitted is not None:
        if completion_source is None or sha(fitted) != ENGLISH_FITTED_SHA:
            parser.error("Fitted transitions require the verified expanded vocabulary and transition file")
        stats = emit_rows(args.output, words, read_fitted_english(fitted),
                          list(bounded_rows(mandarin, 9000)), compact_english=True)
        stats["english_fitted_sha256"] = ENGLISH_FITTED_SHA
    else:
        stats = emit(args.output, words, english, mandarin)
    if completion_source is not None:
        stats["english_completion_sha256"] = ENGLISH_COMPLETION_SHA
    print(json.dumps(dict(stats, english_sentences=english_count, mandarin_sentences=mandarin_count,
                          mandarin_han_runs=mandarin_runs,
                          output_sha256=sha(args.output)), indent=2))


if __name__ == "__main__":
    main()
