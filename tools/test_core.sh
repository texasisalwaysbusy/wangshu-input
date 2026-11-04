#!/usr/bin/env bash
set -euo pipefail
IME_PROJECT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
mkdir -p "$IME_PROJECT/build" "$IME_PROJECT/evidence"
g++ -std=c++17 -O2 -Wall -Wextra "$IME_PROJECT/core/engine.cpp" "$IME_PROJECT/tests/core_test.cpp" -o "$IME_PROJECT/build/core_test"
"$IME_PROJECT/build/core_test" "$IME_PROJECT/data" "$IME_PROJECT/tests/typing-corpus.tsv"
