#!/usr/bin/env bash
set -euo pipefail
IME_PROJECT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
IME_REPO="$(cd "$IME_PROJECT/../.." && pwd)"
IME_TEST="$IME_PROJECT/build/voice-model-installer-test"
mkdir -p "$IME_TEST/classes"
python3 - "$IME_TEST/duplicate-notice.zip" <<'PY'
import sys,zipfile,warnings
with warnings.catch_warnings():
 warnings.simplefilter('ignore',UserWarning)
 with zipfile.ZipFile(sys.argv[1],'w') as z:
  z.writestr('NOTICE.txt','first');z.writestr('NOTICE.txt','second')
PY
"$IME_REPO/.tools/jdk21/bin/javac" -d "$IME_TEST/classes" "$IME_PROJECT/tests/jvm/android/content/Context.java" "$IME_PROJECT/android/app/src/main/java/org/wangshu/ime/VoiceModels.java" "$IME_PROJECT/tests/jvm/VoiceModelInstallerTest.java"
"$IME_REPO/.tools/jdk21/bin/java" -Xmx128m -cp "$IME_TEST/classes" org.wangshu.ime.VoiceModelInstallerTest "$IME_TEST" "$IME_REPO/../scanner-app-recovery/wangshu-voice-zh-en-v1.zip" "$IME_TEST/duplicate-notice.zip"
