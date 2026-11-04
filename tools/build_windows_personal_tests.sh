#!/usr/bin/env bash
set -euo pipefail
IME_PROJECT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
exec python3 "$IME_PROJECT/tools/build_windows_personal.py" --tests "$@"
