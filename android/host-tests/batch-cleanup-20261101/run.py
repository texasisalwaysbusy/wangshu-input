#!/usr/bin/env python3
"""Compile actual split adapter methods against a synthetic, connection-scoped host.
No Android SDK, device, IPC, installation, network, or real user text is used.
Usage: run.py PROJECT OUTPUT [IME_SOURCE_OVERRIDE]
"""
from pathlib import Path
import hashlib, json, subprocess, sys

project = Path(sys.argv[1]).resolve()
out = Path(sys.argv[2]).resolve()
src = project / 'android/app/src/main/java/org/wangshu/ime'
ime = Path(sys.argv[3]).resolve() if len(sys.argv) > 3 else src / 'WangshuIme.java'
text = ime.read_text()

def extract(prefix):
    assert text.count(prefix) == 1, (prefix, text.count(prefix))
    start = text.index(prefix)
    brace = text.index('{', start)
    depth, end = 1, brace + 1
    while depth:
        depth += (text[end] == '{') - (text[end] == '}')
        end += 1
    return text[start:end]

methods = '\n'.join(extract(p) for p in [
    'private static final class SplitOwner ', 'public Object splitOwner()',
    'public boolean splitCurrent(', 'public SplitSelection.Snapshot snapshotSplit(',
    'public boolean replaceSplit('])
template = Path(__file__).with_name('BatchCleanupChecks.java.in').read_text()
assert template.count('/* ACTUAL_OWNER_METHODS */') == 1
out.mkdir(parents=True, exist_ok=True)
java = out / 'BatchCleanupChecks.java'
java.write_text(template.replace('/* ACTUAL_OWNER_METHODS */', methods))
classes = out / 'classes'
classes.mkdir(exist_ok=True)
sources = [src / 'SplitSelection.java', ime, Path(__file__), Path(__file__).with_name('BatchCleanupChecks.java.in')]
receipt = {'scope': 'Extracted production adapter and actual SplitSelection, synthetic host only; Android NOT_RUN',
           'sha256': {str(p): hashlib.sha256(p.read_bytes()).hexdigest() for p in sources}}
(out / 'source-receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
compile_cmd = ['java', '-XX:-UsePerfData', 'com.sun.tools.javac.Main', '-proc:none', '-d', str(classes), str(src / 'SplitSelection.java'), str(java)]
compiled = subprocess.run(compile_cmd)
if compiled.returncode:
    sys.exit(compiled.returncode)
sys.exit(subprocess.run(['java', '-XX:-UsePerfData', '-cp', str(classes), 'org.wangshu.ime.BatchCleanupChecks']).returncode)
