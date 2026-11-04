#!/usr/bin/env python3
"""Compile production editing classes with explicit synthetic Android boundaries."""
from pathlib import Path
import argparse, hashlib, json, subprocess, sys
p=argparse.ArgumentParser(description=__doc__);p.add_argument('project',type=Path);p.add_argument('output',type=Path);p.add_argument('--editor-source',type=Path);args=p.parse_args()
root=args.project.resolve();out=args.output.resolve();out.mkdir(parents=True,exist_ok=False)
here=Path(__file__).resolve().parent;src=root/'android/app/src/main/java/org/wangshu/ime'
ime_source=(src/'WangshuIme.java').read_text();accessor='int editorSession(){return inputSession;}'
if ime_source.count(accessor)!=1:raise RuntimeError('missing or ambiguous production editor-session accessor')
for lifecycle in ('onStartInput','onFinishInput','onWindowHidden','onDestroy'):
 line=next(line for line in ime_source.splitlines() if 'public void '+lifecycle+'(' in line)
 if 'editorActions.reset();' not in line:raise RuntimeError('missing lifecycle selection reset: '+lifecycle)
editor_source=args.editor_source.resolve() if args.editor_source else src/'EditorActions.java'
editor_copy=out/'production/EditorActions.java';editor_copy.parent.mkdir();editor_copy.write_bytes(editor_source.read_bytes())
stubs={
'android/icu/text/BreakIterator.java':r'''package android.icu.text;import java.util.*;import java.util.regex.*;public final class BreakIterator {public static final int DONE=-1;private final ArrayList<Integer> points=new ArrayList<>();public static BreakIterator getCharacterInstance(Locale l){return new BreakIterator();}public void setText(String s){points.clear();points.add(0);Matcher m=Pattern.compile("\\X").matcher(s);while(m.find())points.add(m.end());}public boolean isBoundary(int p){return points.contains(p);}public int preceding(int p){int result=DONE;for(int x:points){if(x>=p)break;result=x;}return result;}public int following(int p){for(int x:points)if(x>p)return x;return DONE;}}''',
'android/os/Looper.java':'''package android.os;public final class Looper {private static final Looper MAIN=new Looper();private static final Thread OWNER=Thread.currentThread();public static Looper getMainLooper(){return MAIN;}public static Looper myLooper(){return Thread.currentThread()==OWNER?MAIN:null;}}''',
'android/os/SystemClock.java':'''package android.os;public final class SystemClock {public static long uptimeMillis(){return 1;}}''',
'android/text/Spanned.java':'''package android.text;public interface Spanned extends CharSequence {int SPAN_COMPOSING=256;Object[] getSpans(int s,int e,Class<?> c);int getSpanFlags(Object o);}''',
'android/view/KeyEvent.java':'''package android.view;public final class KeyEvent {public static final int META_SHIFT_ON=1,ACTION_DOWN=0,ACTION_UP=1;public final int action;public KeyEvent(long d,long e,int a,int c,int r,int m){action=a;}}''',
'android/view/inputmethod/ExtractedText.java':'''package android.view.inputmethod;public final class ExtractedText {public CharSequence text;public int startOffset,partialStartOffset=-1,partialEndOffset=-1,selectionStart,selectionEnd;}''',
'android/view/inputmethod/ExtractedTextRequest.java':'''package android.view.inputmethod;public final class ExtractedTextRequest {public int hintMaxChars,hintMaxLines,flags;}''',
'android/view/inputmethod/InputConnection.java':'''package android.view.inputmethod;import android.view.KeyEvent;public interface InputConnection {int GET_TEXT_WITH_STYLES=1;default ExtractedText getExtractedText(ExtractedTextRequest r,int f){return null;}default CharSequence getSelectedText(int f){return null;}default CharSequence getTextBeforeCursor(int n,int f){return null;}default CharSequence getTextAfterCursor(int n,int f){return null;}default boolean setSelection(int s,int e){return false;}default boolean commitText(CharSequence v,int p){return false;}default boolean deleteSurroundingText(int b,int a){return false;}default boolean sendKeyEvent(KeyEvent e){return false;}default boolean performContextMenuAction(int id){return false;}default boolean finishComposingText(){return false;}default boolean beginBatchEdit(){return false;}default boolean endBatchEdit(){return false;}}''',
'android/R.java':'''package android;public final class R {public static final class id {public static final int selectAll=1,copy=2,cut=3,paste=4;}}''',
'org/wangshu/ime/WangshuIme.java':'''package org.wangshu.ime;import android.view.inputmethod.InputConnection;final class WangshuIme {InputConnection connection;int inputSession=1;Runnable onConnection;ACCESSORInputConnection getCurrentInputConnection(){InputConnection old=connection;if(onConnection!=null){Runnable r=onConnection;onConnection=null;r.run();}return old;}}'''
}
files=[]
stubs['org/wangshu/ime/WangshuIme.java']=stubs['org/wangshu/ime/WangshuIme.java'].replace('ACCESSOR',accessor)
for name,value in stubs.items():
 target=out/'stubs'/name;target.parent.mkdir(parents=True,exist_ok=True);target.write_text(value,encoding='utf-8');files.append(target)
files += [editor_copy,*[src/name for name in ('BeforeCaretEditor.java','EmojiSelection.java','EmojiCatalog.java')]]
files += [here/'EditorBoundaryChecks.java',here/'BeforeCaretBoundaryChecks.java',here/'SymbolBoundaryChecks.java']
receipt={'scope':'Production classes with synthetic InputConnection, service/session, Looper and Java regex \\X boundary adapter. NOT Android ICU/editor, APK, phone or Windows acceptance. No user data.','Android':'NOT_RUN','editor_source':str(editor_source),'editor_source_sha256':hashlib.sha256(editor_source.read_bytes()).hexdigest(),'ime_source_sha256':hashlib.sha256((src/'WangshuIme.java').read_bytes()).hexdigest(),'extracted_accessor':accessor,'boundary_adapter':'Java regex \\X; not Android ICU','sources':{str(x):hashlib.sha256(x.read_bytes()).hexdigest() for x in files},'commands':[],'success':False}
def run(name,cmd):
 r=subprocess.run([str(x) for x in cmd],capture_output=True,text=True,encoding='utf-8',errors='replace');(out/(name+'.txt')).write_text(r.stdout+r.stderr,encoding='utf-8');print(r.stdout+r.stderr,end='');receipt['commands'].append({'name':name,'command':list(map(str,cmd)),'returncode':r.returncode});return r.returncode==0
try:
 if run('compile',['java','-m','jdk.compiler/com.sun.tools.javac.Main','-encoding','UTF-8','-source','17','-target','17','-d',out/'classes',*files]):
  passed=[run(name,['java','-cp',out/'classes','org.wangshu.ime.'+name]) for name in ('EditorBoundaryChecks','BeforeCaretBoundaryChecks','SymbolBoundaryChecks')];receipt['success']=all(passed)
finally:(out/'RESULT.json').write_text(json.dumps(receipt,indent=2)+'\n',encoding='utf-8')
sys.exit(0 if receipt['success'] else 1)
