#!/usr/bin/env python3
"""Score frozen Wangshu phase-one observations. Never captures audio or accesses a device."""
import argparse
from collections import Counter, defaultdict
import hashlib
import json
import math
from pathlib import Path
import re
import unicodedata


ROOT = Path(__file__).resolve().parents[1]
SAMPLES = ROOT / "tests/phase-one-evaluation"


def read_json(path):
    return json.loads(path.read_text(encoding="utf-8"))


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def normalize_speech(text):
    text = unicodedata.normalize("NFKC", text).lower()
    return "".join(" " if unicodedata.category(char).startswith("P") else char for char in text)


def speech_words(text, language):
    if language == "en":
        return text.split()
    return re.findall(r"[\u3400-\u4dbf\u4e00-\u9fff\U00020000-\U000323af]|[^\s\u3400-\u4dbf\u4e00-\u9fff\U00020000-\U000323af]+", text)


def distance(reference, observed):
    previous = list(range(len(observed) + 1))
    for index, target in enumerate(reference, 1):
        current = [index]
        for offset, value in enumerate(observed, 1):
            current.append(min(current[-1] + 1, previous[offset] + 1,
                               previous[offset - 1] + (target != value)))
        previous = current
    return previous[-1]


def speech_row(reference, raw, language):
    expected = normalize_speech(reference)
    observed = normalize_speech(raw)
    expected_chars = "".join(expected.split())
    observed_chars = "".join(observed.split())
    expected_words = speech_words(expected, language)
    observed_words = speech_words(observed, language)
    char_edits = distance(expected_chars, observed_chars)
    word_edits = distance(expected_words, observed_words)
    target_en = Counter(re.findall(r"[a-z]+", expected))
    actual_en = Counter(re.findall(r"[a-z]+", observed))
    return dict(reference_normalized=expected, raw_normalized=observed,
                reference_chars=len(expected_chars), character_edits=char_edits,
                reference_words=len(expected_words), word_edits=word_edits,
                cer=char_edits / len(expected_chars), wer=word_edits / len(expected_words),
                target_english_tokens=target_en.total(),
                matched_english_tokens=(target_en & actual_en).total())


def historical(directory):
    rows = []
    for label, language in (("zh", "zh"), ("en", "en"), ("mixed", "mixed"), ("numbers", "zh")):
        path = directory / f"{label}-001.json"
        source = read_json(path)
        saved = source["metrics"]
        row = speech_row(saved["reference"], saved["raw_hypothesis"], language)
        compare = ["reference_chars", "character_edits", "cer"]
        if language == "en":
            compare += ["reference_words", "word_edits", "wer"]
        if any(row[key] != saved[key] for key in compare):
            raise ValueError(f"Historical score disagrees with original receipt: {path}")
        rows.append(dict(id=source["case"], layer=source["layer"], source_sha256=sha256(path),
                         reference=saved["reference"], raw=saved["raw_hypothesis"],
                         historical_metrics=saved, recalculated=row))
    return dict(status="PASS", scope="Historical metric reproduction only", rows=rows)


def percentile95(values):
    return sorted(values)[math.ceil(len(values) * 0.95) - 1]


def voice_scores(samples, observations, metadata):
    groups = defaultdict(list)
    rows = []
    for sample in samples:
        item = observations.get(sample["id"])
        label = "format" if "formatted" in sample else sample["language"]
        row = dict(sample=sample, observation=item)
        if item is not None:
            row.update(speech_row(sample["reference"], item["raw"], sample["language"]))
            if label == "format":
                row["exact_format"] = item.get("formatted") == sample["formatted"]
        rows.append(row)
        groups[label].append(row)
    summary = {}
    for label, group in groups.items():
        scored = [row for row in group if row["observation"] is not None]
        result = dict(required=len(group), observed=len(scored), status="NOT_RUN")
        if scored and label == "format":
            result["exact_format_count"] = sum(row["exact_format"] for row in scored)
            passed = result["exact_format_count"] == len(group)
        elif scored:
            result["cer"] = sum(row["character_edits"] for row in scored) / sum(row["reference_chars"] for row in scored)
            result["wer"] = sum(row["word_edits"] for row in scored) / sum(row["reference_words"] for row in scored)
            if label == "en":
                passed = result["wer"] <= 0.25 and all(row["wer"] <= 0.60 for row in scored)
            elif label == "mixed":
                passed = result["cer"] <= 0.25 and all(
                    2 * row["matched_english_tokens"] >= row["target_english_tokens"] for row in scored)
            else:
                passed = result["cer"] <= 0.15
        if len(scored) == len(group):
            result["status"] = "PASS" if passed else "FAIL"
        summary[label] = result
    if metadata["layer"] not in ("public-wav", "real-microphone"):
        for result in summary.values():
            result["status"] = "NOT_RUN"
    return rows, summary


def prediction_scores(samples, observations):
    groups = defaultdict(list)
    rows = []
    for sample in samples:
        item = observations.get(sample["id"])
        row = dict(sample=sample, observation=item)
        if item is not None:
            candidates = item["candidates"]
            if len(candidates) > 5 or any(not value for value in candidates):
                raise ValueError(f"Expected up to five nonempty suffixes for {sample['id']}")
            latency = item["latency_ms"]
            if not isinstance(latency, (int, float)) or not math.isfinite(latency) or latency < 0:
                raise ValueError(f"Invalid query latency for {sample['id']}")
            expected = {unicodedata.normalize("NFC", value) for value in sample["acceptable"]}
            row["rank"] = next((index for index, value in enumerate(candidates, 1)
                                if unicodedata.normalize("NFC", value) in expected), None)
        groups[(sample["language"], sample["kind"])].append(row)
        rows.append(row)
    summary = {}
    for (language, kind), group in groups.items():
        scored = [row for row in group if row["observation"] is not None]
        result = dict(required=len(group), observed=len(scored), status="NOT_RUN")
        summary[f"{language}/{kind}"] = result
        if not scored:
            continue
        covered = [row for row in scored if row["observation"]["candidates"]]
        result["coverage"] = len(covered) / len(scored)
        for cutoff in (1, 3, 5):
            result[f"top{cutoff}"] = sum(row["rank"] is not None and row["rank"] <= cutoff
                                         for row in scored) / len(scored)
        result["p95_ms"] = percentile95([row["observation"]["latency_ms"] for row in scored])
        if len(scored) != len(group):
            continue
        passed = result["p95_ms"] <= 80
        if kind == "sentence":
            judgments = [row["observation"].get("blind_first_acceptable") for row in covered]
            if result["coverage"] < 0.75 or not passed:
                result["status"] = "FAIL"
                continue
            if any(type(value) is not bool for value in judgments):
                continue
            result["blind_first_acceptance"] = sum(judgments) / len(covered)
            passed = result["blind_first_acceptance"] >= 0.80
        else:
            passed = passed and result["coverage"] >= 0.80 and result["top3"] >= 0.60
        result["status"] = "PASS" if passed else "FAIL"
    return rows, summary


def score(kind, source):
    path = SAMPLES / f"{kind}.jsonl"
    frozen = read_json(SAMPLES / "FROZEN.json")
    if sha256(ROOT / "docs/PHASE-ONE-CLOSEOUT.md") != frozen["protocol_sha256"]:
        raise ValueError("Frozen scoring protocol checksum mismatch")
    if sha256(path) != frozen["files"][path.name]:
        raise ValueError(f"Frozen sample checksum mismatch: {path}")
    samples = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines()]
    metadata = source["run"]
    for key in ("build", "layer", "platform", "configuration"):
        if not metadata.get(key):
            raise ValueError(f"Run identity requires {key}")
    allowed = {sample["id"] for sample in samples}
    observations = {}
    for item in source["observations"]:
        identity = item["id"]
        if identity not in allowed or identity in observations:
            raise ValueError(f"Unknown or repeated observation: {identity}")
        observations[identity] = item
    if kind == "voice":
        rows, summary = voice_scores(samples, observations, metadata)
    else:
        rows, summary = prediction_scores(samples, observations)
    statuses = {result["status"] for result in summary.values()}
    status = "FAIL" if "FAIL" in statuses else "PASS" if statuses == {"PASS"} else "NOT_RUN"
    return dict(status=status, protocol=frozen["protocol"], sample_sha256=sha256(path),
                run=metadata, summary=summary, rows=rows)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("kind", choices=("historical", "voice", "prediction"))
    parser.add_argument("input", type=Path, help="Historic voices directory or JSON run observations")
    parser.add_argument("output", type=Path, help="New result JSON, never overwritten")
    args = parser.parse_args()
    result = historical(args.input) if args.kind == "historical" else score(args.kind, read_json(args.input))
    with args.output.open("x", encoding="utf-8") as target:
        json.dump(result, target, ensure_ascii=False, indent=2)
        target.write("\n")
    print(result["status"])


if __name__ == "__main__":
    main()
