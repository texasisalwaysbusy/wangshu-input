"""Check production displayed-action entry points against synthetic editor boundaries."""
from pathlib import Path
import hashlib
import json
import subprocess
import sys

src = Path(sys.argv[1]).resolve() / 'android/app/src/main/java/org/wangshu/ime'
out = Path(sys.argv[2]).resolve()
out.mkdir(parents=True)
ime = (src / 'WangshuIme.java').read_text(encoding='utf-8')


def take(prefix):
    start = ime.index(prefix)
    begin = ime.index('{', start)
    end = begin + 1
    depth = 1
    while depth:
        depth += (ime[end] == '{') - (ime[end] == '}')
        end += 1
    return ime[start:end]


fragments = {'symbol': take('private void chooseSymbol(')}
if 'private boolean writeDisplayedAction(' in ime:
    fragments['write'] = take('private boolean writeDisplayedAction(')
fixture = Path(__file__).with_name('DisplayedEditorActions.java.in')
generated = out / 'DisplayedEditorActions.java'
generated.write_text(fixture.read_text(encoding='utf-8')
                     .replace('/* PRODUCTION */', '\n'.join(fragments.values()))
                     .replace('PRODUCTION_HAS_BOUND_WRITER', str('write' in fragments).lower()),
                     encoding='utf-8')
classes = out / 'classes'
common = ['java', '-XX:-UsePerfData']
compile_result = subprocess.run(common + ['-m', 'jdk.compiler/com.sun.tools.javac.Main', '-encoding', 'UTF-8',
                                         '-d', str(classes), str(generated), str(src / 'SplitSelection.java')])
test_code = None
if compile_result.returncode == 0:
    test_code = subprocess.run(common + ['-cp', str(classes), 'org.wangshu.ime.DisplayedEditorActions']).returncode
(out / 'RESULT.json').write_text(json.dumps({
    'scope': 'actual service method bodies, synthetic callbacks and editor; Android NOT_RUN',
    'ime_sha256': hashlib.sha256(ime.encode('utf-8')).hexdigest(),
    'fragment_sha256': {name: hashlib.sha256(body.encode('utf-8')).hexdigest()
                        for name, body in fragments.items()},
    'fixture_sha256': hashlib.sha256(fixture.read_bytes()).hexdigest(),
    'compile_exit': compile_result.returncode,
    'test_exit': test_code,
}, indent=2) + '\n', encoding='utf-8')
sys.exit(compile_result.returncode or test_code)
