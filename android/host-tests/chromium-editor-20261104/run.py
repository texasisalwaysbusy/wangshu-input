#!/usr/bin/env python3
"""Actual editing classes + extracted service lifecycle, synthetic Android only."""
import ast, hashlib, json, subprocess, sys
from pathlib import Path
root,out=map(lambda x:Path(x).resolve(),sys.argv[1:]);out.mkdir(parents=True,exist_ok=False)
here=Path(__file__).resolve().parent;src=root/'android/app/src/main/java/org/wangshu/ime'
helper=(here.parent/'mixed-integration-20261103/run_render_checks.py').read_text();ns={};exec(helper[helper.index('def take('):helper.index('fragments=')],ns);take=ns['take']
service=(src/'WangshuIme.java').read_text()
sigs=['int editorSession()', 'private void createBackspace()', 'private void cancelBackspace()', 'private BackspaceGesture.DeleteResult backspaceStep(', 'private void updateComposition()', 'private void updateEntryComposition(', 'private static final class TextWrite', 'private boolean textWriteFields(', 'private boolean textWriteCurrent(', '@Override public void onStartInput(', '@Override public void onFinishInput()', '@Override public void onWindowHidden()', '@Override public void onUpdateSelection(', 'private SplitSelection.Snapshot snapshotFrom(']
sigs += ['private void key(String key)', 'private void space()', 'private void punctuation(char c)', 'private void enter(boolean forceNewline)', 'private boolean candidatesReady()', 'private boolean chooseEntry(', 'private boolean finishEntry(', 'private void commitEntry(', 'private boolean prepareTextWrite(', 'private PendingTextCommit.Result writeBound(', 'private boolean commitText(', 'private boolean commit(', 'private boolean commitPending(', 'private boolean finishRaw()', 'private boolean commitCandidate(', 'private boolean choose(int index)', 'private boolean candidateContinuationCurrent(', 'static boolean verifiedCandidateInsertion(', 'private static final class CandidateClick', 'private boolean candidateClickFields(', 'private boolean candidateClickCurrent(', 'private boolean candidateAfterCommitCurrent(']
if 'private void startLocalCommand()' in service:sigs += ['private void startLocalCommand()']
if 'private void reconcileLocalPreeditSelection()' in service:sigs += ['private void reconcileLocalPreeditSelection()']
fragments={sig:take(service,sig) for sig in sigs}
if 'private void startLocalCommand()' not in service:
    command=take(service,"()->{math=false;buffer.setLength(0);buffer.append(")
    fragments['baseline command button lambda (signature only adapted)']=command.replace('()->','private void startLocalCommand()',1)

# Reuse explicit Android stubs, never production replacements for the editing classes.
tree=ast.parse((here.parent/'editor-boundary-20261103/run.py').read_text());stubs=ast.literal_eval(next(n.value for n in tree.body if isinstance(n,ast.Assign) and any(isinstance(t,ast.Name) and t.id=='stubs' for t in n.targets)))
stubs['android/view/inputmethod/InputConnection.java']=stubs['android/view/inputmethod/InputConnection.java'].replace('int GET_TEXT_WITH_STYLES=1;', 'int GET_TEXT_WITH_STYLES=1;default boolean setComposingText(CharSequence s,int p){return false;}default boolean performEditorAction(int a){return false;}')
stubs['android/view/inputmethod/EditorInfo.java']='package android.view.inputmethod;public class EditorInfo {public int inputType=1,imeOptions;public static final int IME_MASK_ACTION=255,IME_ACTION_GO=2,IME_ACTION_SEARCH=3,IME_ACTION_SEND=4,IME_ACTION_NEXT=5,IME_ACTION_DONE=6,IME_ACTION_PREVIOUS=7,IME_FLAG_NO_ENTER_ACTION=1073741824;}'
stubs['android/text/InputType.java']='package android.text;public class InputType {public static final int TYPE_TEXT_FLAG_MULTI_LINE=131072,TYPE_MASK_CLASS=15,TYPE_MASK_VARIATION=4080,TYPE_CLASS_TEXT=1,TYPE_CLASS_NUMBER=2,TYPE_CLASS_PHONE=3,TYPE_TEXT_VARIATION_PASSWORD=128,TYPE_TEXT_VARIATION_VISIBLE_PASSWORD=144,TYPE_TEXT_VARIATION_WEB_PASSWORD=224,TYPE_NUMBER_VARIATION_PASSWORD=16;}'
stubs['android/view/ViewConfiguration.java']='package android.view;public class ViewConfiguration {public static int getLongPressTimeout(){return 500;}public static ViewConfiguration get(Object o){return new ViewConfiguration();}public int getScaledTouchSlop(){return 8;}}'
stubs['org/wangshu/ime/WangshuIme.java']=(here/'Service.java.in').read_text().replace('PRODUCTION_METHODS','\n'.join(fragments.values()))
files=[]
for name,text in stubs.items():
 p=out/'stubs'/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text);files.append(p)
files += [src/name for name in ['EditorActions.java','BeforeCaretEditor.java','BackspaceGesture.java','MixedComposition.java','SplitSelection.java','PendingTextCommit.java','EnterPolicy.java','FuzzyPinyin.java']]
files += [here/'ChromeChecks.java']
receipt={'scope':'Production classes and verbatim extracted service methods, simulated callbacks and snapshots. Java regex \\X replaces Android ICU. Android APK/UI/Binder/Chrome/device NOT_RUN. Device failure is not independently reproduced.','source_sha256':{str(p.relative_to(src)):hashlib.sha256(p.read_bytes()).hexdigest() for p in files if p.is_relative_to(src)},'service_sha256':hashlib.sha256(service.encode()).hexdigest(),'method_sha256':{k:hashlib.sha256(v.encode()).hexdigest() for k,v in fragments.items()},'test_sha256':{p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in here.iterdir() if p.is_file()},'commands':[],'success':False}
for label,cmd in [('compile',['java','-m','jdk.compiler/com.sun.tools.javac.Main','-encoding','UTF-8','-d',out/'classes',*files]),('run',['java','-cp',out/'classes','org.wangshu.ime.ChromeChecks'])]:
 result=subprocess.run(list(map(str,cmd)),capture_output=True,text=True);(out/(label+'.txt')).write_text(result.stdout+result.stderr);print(result.stdout+result.stderr,end='');receipt['commands'].append({'label':label,'command':list(map(str,cmd)),'returncode':result.returncode})
 if result.returncode:break
receipt['success']=len(receipt['commands'])==2 and all(x['returncode']==0 for x in receipt['commands']);(out/'RESULT.json').write_text(json.dumps(receipt,indent=2)+'\n');sys.exit(0 if receipt['success'] else 1)
