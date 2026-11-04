#!/usr/bin/env bash
set -euo pipefail
IME_PROJECT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
IME_REPO="$(cd "$IME_PROJECT/../.." && pwd)"
export WINEPREFIX="$IME_REPO/.tools/wine-test-prefix-v3"
export XDG_CACHE_HOME="$IME_REPO/.tools/wine-cache"
IME_WINE64="$IME_REPO/.tools/wine/usr/lib/x86_64-linux-gnu/wine"
export WINEDLLPATH="$IME_WINE64:$IME_REPO/.tools/wine/usr/lib/i386-linux-gnu/wine"
export LD_LIBRARY_PATH="$IME_WINE64/x86_64-unix:$IME_REPO/.tools/wine/usr/lib/x86_64-linux-gnu${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
export WINEDLLOVERRIDES='mscoree,mshtml='
export WINEDEBUG='-all'
exec "$IME_REPO/.tools/wine/usr/lib/wine/wine64" "$@"
