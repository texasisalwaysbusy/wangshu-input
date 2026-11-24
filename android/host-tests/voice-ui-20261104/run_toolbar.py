"""Replay actual toolbar speech and review methods. All audio, editors and clipboard are synthetic."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys

project, output = (Path(arg).resolve() for arg in sys.argv[1:3])
root = project / "android/app/src/main/java/org/wangshu/ime"
source = Path(sys.argv[3]).resolve() if len(sys.argv) == 4 else root / "WangshuIme.java"
extractor = Path(__file__).parents[1] / "mixed-integration-20261103/run_render_checks.py"
entry = extractor.read_text(encoding="utf-8")
scope = {}
exec(entry[entry.index("def take("):entry.index("fragments=")], scope)
take = scope["take"]
original = source.read_text(encoding="utf-8")
methods = [
    "private boolean voiceScope(", "private boolean voiceWriteCurrent(",
    "private boolean voiceResultCurrent(", "private void showVoiceReview(",
    "private void startVoice(", "private void cancelVoice()", "private void copyVoiceReview(",
]
methods += [sig for sig in (
    "private static final class ToolbarVoice", "private boolean toolbarVoiceCurrent(",
    "private boolean acceptToolbarVoice(", "private boolean releaseDeviceRecognizer(",
    "private void failToolbarVoice(", "private void completeToolbarVoice(",
    "private void startBundledVoice(", "private void startDeviceVoice(",
) if sig in original]
template = Path(__file__).with_name("ToolbarVoiceChecks.java.in")
text = template.read_text(encoding="utf-8")
copy = "if(revision==voiceReviewRevision)copyVoiceReview();"
if "private void copyVoiceReview(long " in original:
    copy = "copyVoiceReview(revision);"
text = text.replace("/* COPY */", copy).replace("/* METHODS */", "\n".join(take(original, sig) for sig in methods))
if "private ToolbarVoice toolbarVoice;" in original:
    text = text.replace("/* FIELDS */", "private ToolbarVoice toolbarVoice;")
output.mkdir()
temporary = output / "tmp"
temporary.mkdir()
java = Path(os.environ["JAVA_HOME"]) / "bin/java.exe"
assert java.resolve().drive == output.resolve().drive == source.resolve().drive == "E:"
env = dict(os.environ, TEMP=str(temporary), TMP=str(temporary), JAVA_TOOL_OPTIONS=
           f'-Duser.home="{temporary}" -Djava.io.tmpdir="{temporary}" -Dfile.encoding=UTF-8 -XX:-UsePerfData')
fixture = output / "ToolbarVoiceChecks.java"
fixture.write_text(text, encoding="utf-8")
commands = {
    "compile": [java, "-m", "jdk.compiler/com.sun.tools.javac.Main", "-proc:none", "-encoding", "UTF-8",
                "-d", output, fixture],
    "run": [java, "-cp", output, "org.wangshu.ime.ToolbarVoiceChecks"],
}
steps = {}
for label, args in commands.items():
    command = list(map(str, args))
    result = subprocess.run(command, env=env, cwd=output, capture_output=True, timeout=90)
    logs = result.stdout + result.stderr
    (output / (label + ".log")).write_bytes(logs)
    print(logs.decode("utf-8", errors="replace"), end="")
    steps[label] = {"command":command,"exit":result.returncode, "log_sha256":hashlib.sha256(logs).hexdigest()}
    if result.returncode:
        break
inputs = {str(path): hashlib.sha256(path.read_bytes()).hexdigest()
          for path in (source, template, Path(__file__), extractor)}
with (output/"RESULT.json").open("x", encoding="utf-8") as receipt:
    json.dump({"scope":"Actual selected methods with synthetic recognition, clipboard and editors",
               "inputs_sha256":inputs,"steps":steps}, receipt, indent=2)
    receipt.write("\n")
raise SystemExit(result.returncode)
