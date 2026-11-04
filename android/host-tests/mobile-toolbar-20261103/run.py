#!/usr/bin/env python3
"""Extract actual presentation code and run JVM fakes, not Android UI tests.

Usage: python3 run.py PROJECT NEW_OUTPUT_DIRECTORY [BASELINE_PROJECT]
No dependency downloads. Never overwrites an evidence directory.
"""
from pathlib import Path
import hashlib
import json
import subprocess
import sys

here = Path(__file__).resolve().parent
project, out = (Path(x).resolve() for x in sys.argv[1:3])
out.mkdir(parents=True, exist_ok=False)
java = project / 'android/app/src/main/java/org/wangshu/ime'
source = (java / 'WangshuIme.java').read_text()


def balanced(text, start):
    """Return through matching closing brace, respecting Java strings/comments."""
    opening = text.index('{', start)
    depth = 0
    state = 'code'
    i = opening
    while i < len(text):
        c = text[i]
        nxt = text[i:i + 2]
        if state in ('string', 'char'):
            if c == '\\':
                i += 2
                continue
            if c == ('"' if state == 'string' else "'"):
                state = 'code'
        elif state == 'line':
            if c == '\n':
                state = 'code'
        elif state == 'comment':
            if nxt == '*/':
                state = 'code'
                i += 2
                continue
        elif nxt == '//':
            state = 'line'
            i += 2
            continue
        elif nxt == '/*':
            state = 'comment'
            i += 2
            continue
        elif c == '"':
            state = 'string'
        elif c == "'":
            state = 'char'
        elif c == '{':
            depth += 1
        elif c == '}':
            depth -= 1
            if depth == 0:
                return text[start:i + 1]
        i += 1
    raise AssertionError('Unterminated Java block')


def method(text, signature):
    assert text.count(signature) == 1, signature
    return balanced(text, text.index(signature))


strip_signature = 'for(int i=0;i<current.length;i+=2)'
assert source.count(strip_signature) == 1
if 'private LinearLayout buildToolbar()' in source:
    toolbar_method = method(source, 'private LinearLayout buildToolbar()')
    toolbar_body = toolbar_method[toolbar_method.index('{')+1:toolbar_method.rindex('}')]
    toolbar_body = toolbar_body.replace('return toolbar;', 'root.addView(toolbar);')
else:
    toolbar_start = source.index('toolbar=row();', source.index('private void rebuild()'))
    toolbar_end = source.index('root.addView(toolbar);', toolbar_start) + len('root.addView(toolbar);')
    toolbar_body = source[toolbar_start:toolbar_end]
parts = {
    'STRIP': balanced(source, source.index(strip_signature)),
    'EXPANDED': method(source, 'private void expandedKeys()'),
    'BOTTOM': method(source, 'private void bottom()'),
    'ENTER': method(source, 'private void addEnterKey('),
    'LANGUAGE': method(source, 'private void addLanguageKey('),
    'BUTTON': method(source, 'private Button button('),
    'FUNCTION': method(source, 'private boolean functionLabel('),
    'CANDIDATE_BUTTON': method(source, 'private Button candidateButton(') if 'private Button candidateButton(' in source else 'private Button candidateButton(String text,int index){return button(text,displayedCandidate(index));}',
    'TOOLBAR': toolbar_body,
    'ICON_ENUM': method((java / 'ToolbarIcon.java').read_text(), 'enum Kind'),
}
template = (here / 'MobilePresentationChecks.java.in').read_text()
for key, value in parts.items():
    marker = '/*EXTRACT_' + key + '*/'
    assert template.count(marker) == 1, marker
    template = template.replace(marker, value)
generated = out / 'MobilePresentationChecks.java'
generated.write_text(template)
(out / 'extraction-receipt.json').write_text(json.dumps({
    'project': str(project),
    'kind': 'actual extracted Java methods/fragments with fake Android views',
    'sources': {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in
                [java / 'WangshuIme.java', java / 'ToolbarIcon.java', java / 'LanguageKeyPosition.java']},
    'fragments': {k: hashlib.sha256(v.encode()).hexdigest() for k, v in parts.items()},
}, indent=2) + '\n')

result = {'android_build': 'NOT_RUN', 'android_runtime': 'NOT_RUN', 'phone_ui': 'NOT_RUN'}


def run(name, cmd):
    completed = subprocess.run([str(x) for x in cmd], capture_output=True, text=True)
    text = completed.stdout + completed.stderr
    (out / (name + '.txt')).write_text(text)
    print(text, end='')
    result[name] = completed.returncode
    return completed.returncode


classes = out / 'classes'
classes.mkdir()
compiled = run('host-compile', ['java', '-m', 'jdk.compiler/com.sun.tools.javac.Main',
                              '-source', '17', '-target', '17', '-d', classes,
                              generated, java / 'LanguageKeyPosition.java', java / 'MixedComposition.java'])
if compiled == 0:
    run('host', ['java', '-cp', classes, 'org.wangshu.ime.MobilePresentationChecks'])

parser = out / 'parser'
parser.mkdir()
if run('syntax-compile', ['java', '-m', 'jdk.compiler/com.sun.tools.javac.Main',
                         '-d', parser, here / 'ParseSource.java']) == 0:
    run('syntax', ['java', '-cp', parser, 'ParseSource',
                   *sorted(project.glob('android/app/src/**/*.java'))])

if len(sys.argv) == 4:
    baseline = Path(sys.argv[3]).resolve()
    old = (baseline / 'android/app/src/main/java/org/wangshu/ime/WangshuIme.java').read_text()
    preserved = [
        'private void bottom()', 'private void addLanguageKey(',
        'private void addEnterKey(', 'private void bindCandidateOptions(',
        'private Runnable displayedCandidate(', 'public boolean onKeyDown(',
        'public boolean onKeyUp(',
        'private Button button(', 'private boolean functionLabel(',
    ]
    checks = []
    for signature in preserved:
        assert method(old, signature) == method(source, signature), signature
        checks.append('UNCHANGED ' + signature)
    for path in ['android/app/src/main/java/org/wangshu/ime/LanguageKeyPosition.java',
                 'android/app/build.gradle']:
        assert (baseline / path).read_bytes() == (project / path).read_bytes(), path
        checks.append('UNCHANGED ' + path)
    settings = (java / 'SettingsActivity.java').read_text()
    assert 'String[] choices={"空格右侧（默认）","空格左侧"}' in settings
    assert '.setTitle("底部中英文切换键位置 · 下次重建生效")' in settings
    language_dialog = settings[settings.index('add(panel,"底部中英文切换键位置"'):settings.index('add(panel,"候选文字大小"')]
    assert '.setMessage(' not in language_dialog
    assert 'ToolbarIcon.Kind.LANGUAGE' not in source
    for folder in ['windows', 'core', 'data']:
        paths = sorted(p.relative_to(baseline) for p in (baseline / folder).rglob('*') if p.is_file())
        assert paths == sorted(p.relative_to(project) for p in (project / folder).rglob('*') if p.is_file())
        assert all((baseline / p).read_bytes() == (project / p).read_bytes() for p in paths)
        checks.append(f'UNCHANGED {folder}/ all {len(paths)} files')
    (out / 'scope-and-wiring.txt').write_text('\n'.join(checks) + '\n')
    result['scope_and_wiring'] = 0
    print('\n'.join(checks))

(out / 'RESULT.json').write_text(json.dumps(result, indent=2) + '\n')
raise SystemExit(1 if any(isinstance(v, int) and v != 0 for v in result.values()) else 0)
