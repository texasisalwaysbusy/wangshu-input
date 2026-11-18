"""Exercise actual voice commit methods against an isolated, reentrant editor."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys


root, output = (Path(value).resolve() for value in sys.argv[1:3])
java_root = root / "android/app/src/main/java/org/wangshu/ime"
source = Path(sys.argv[3]).resolve() if len(sys.argv) == 4 else java_root / "WangshuIme.java"
template = Path(__file__).with_name("VoiceCommitChecks.java.in")
extractor = Path(__file__).parents[1] / "mixed-integration-20261103/run_render_checks.py"
script = extractor.read_text(encoding="utf-8")
namespace = {}
exec(script[script.index("def take("):script.index("fragments=")], namespace)
text = source.read_text(encoding="utf-8")
signatures = [
    "static boolean sameVoiceSnapshot(", "static boolean voiceCommitVisible(",
    "private boolean voiceScope(", "private void showVoiceReview(", "private void attemptVoiceCommit(",
    "private void insertVoiceReview(",
]
if "private boolean voiceWriteCurrent(" in text:
    signatures.append("private boolean voiceWriteCurrent(")
if "private boolean voiceResultCurrent(" in text:
    signatures.extend(["private boolean voiceResultCurrent(", "private void dispatchVoiceCommit("])
bodies = [namespace["take"](text, signature) for signature in signatures]
# The harness uses only simple Han/ASCII boundaries; Android ICU runs in the APK.
bodies = [body.replace("android.icu.text.BreakIterator", "java.text.BreakIterator") for body in bodies]
insert = "insertVoiceReview(generation);" if "insertVoiceReview(long " in text else "insertVoiceReview();"
output.mkdir()
test = output / "VoiceCommitChecks.java"
test.write_text(template.read_text(encoding="utf-8").replace("/* METHODS */", "\n".join(bodies))
                .replace("/* PRESS */", insert), encoding="utf-8")
java = Path(os.environ["JAVA_HOME"]) / "bin/java.exe"
assert java.resolve().drive == output.drive == "E:"
commands = [
    [java, "-m", "jdk.compiler/com.sun.tools.javac.Main", "-encoding", "UTF-8", "-d", output,
     test, java_root / "SplitSelection.java", java_root / "VoiceCommitUndo.java"],
    [java, "-cp", output, "org.wangshu.ime.VoiceCommitChecks"],
]
steps = []
for label, command in zip(("compile", "run"), commands, strict=True):
    process = subprocess.run(command, cwd=output, capture_output=True, timeout=90)
    (output / (label + ".log")).write_bytes(process.stdout + process.stderr)
    print((process.stdout + process.stderr).decode("utf-8"), end="")
    steps.append({"name": label, "exit_code": process.returncode})
    if process.returncode:
        break
inputs = [source, template, Path(__file__), extractor, java_root / "SplitSelection.java",
          java_root / "VoiceCommitUndo.java"]
(output / "RESULT.json").write_text(json.dumps({
    "steps": steps, "source_sha256": {str(path): hashlib.sha256(path.read_bytes()).hexdigest() for path in inputs},
    "scope": "Production voice commit methods with synthetic editors. No Android framework, device or microphone.",
}, indent=2), encoding="utf-8")
raise SystemExit(steps[-1]["exit_code"])
