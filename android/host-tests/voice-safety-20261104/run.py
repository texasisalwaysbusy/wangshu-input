#!/usr/bin/env python3
"""Bind synthetic voice/toolbar checks to production method bodies. No device, microphone, user data, installation or network."""
from pathlib import Path
import hashlib,json,subprocess,sys
project,out=map(lambda x:Path(x).resolve(),sys.argv[1:3]); out.mkdir(parents=True,exist_ok=False)
here=Path(__file__).resolve().parent; src=project/'android/app/src/main/java/org/wangshu/ime'
s=(src/'WangshuIme.java').read_text(); skin=(src/'KeyboardSkin.java').read_text()
def take(text,prefix):
 assert text.count(prefix)==1,(prefix,text.count(prefix))
 start=text.index(prefix); brace=text.index('{',start); depth=1;end=brace+1
 while depth:
  depth+=(text[end]=='{')-(text[end]=='}');end+=1
 return text[start:end]
parts={sig:take(s,sig) for sig in ['private final class HeldVoice','private HeldVoice ownedVoice(', 'private void createVoiceHold()', 'private boolean hasVoiceWork()', 'private void cancelHoldForOtherInput()', 'private SplitSelection.Snapshot voiceSnapshot(', 'static SplitSelection.Snapshot connectionSnapshot(', 'static boolean sameVoiceSnapshot(', 'static boolean voiceCommitVisible(', 'private boolean voiceScope(', 'private void showVoiceReview(', 'private void attemptVoiceCommit(', 'private void insertVoiceReview()', 'private void cancelVoice()', 'private void voiceToolState(']}
for optional in ['private boolean voiceWriteCurrent(']:
 if optional in s:parts[optional]=take(s,optional)
template=(here/'VoiceSafetyChecks.java.in').read_text();template=template.replace('/* PRODUCTION */','\n'.join(parts.values())).replace('/* SKIN */',take(skin,'static void tool(').replace('Context c','VoiceContext c'))
java=out/'WangshuIme.java'; java.write_text(template)
stub=out/'android/icu/text/BreakIterator.java';stub.parent.mkdir(parents=True);stub.write_text(r'''package android.icu.text;import java.util.*;import java.util.regex.*;public final class BreakIterator {private final ArrayList<Integer> points=new ArrayList<>();public static BreakIterator getCharacterInstance(Locale l){return new BreakIterator();}public void setText(String s){points.clear();points.add(0);Matcher m=Pattern.compile("\\X").matcher(s);while(m.find())points.add(m.end());}public boolean isBoundary(int p){return points.contains(p);}}''')
color=out/'android/graphics/Color.java';color.parent.mkdir(parents=True);color.write_text('package android.graphics;public final class Color {public static final int TRANSPARENT=0;}')
sources=[java,stub,color,src/'VoiceHoldController.java',src/'SplitSelection.java']
# A shared complete-snapshot helper, when present, is compiled unmodified.
for name in ['ExtractedTextSnapshot.java','EditorSnapshot.java','FullTextSnapshot.java']:
 if (src/name).exists():sources.append(src/name)
receipt={'scope':'Extracted production voice adapter, snapshot/commit/cancel/toolbar methods, actual VoiceHoldController and KeyboardSkin.tool body; synthetic InputConnection/recognizer/drawables. No Android, Chrome, microphone, native renderer, device or real data. Java regex \\X models boundaries, not Android ICU.','source_sha256':{str(p):hashlib.sha256(p.read_bytes()).hexdigest() for p in [src/'WangshuIme.java',src/'KeyboardSkin.java',src/'VoiceHoldController.java',here/'run.py',here/'VoiceSafetyChecks.java.in']},'fragments_sha256':{n:hashlib.sha256(v.encode()).hexdigest() for n,v in parts.items()},'success':False,'commands':[]}
for name,cmd in [('compile',['java','-XX:-UsePerfData','-m','jdk.compiler/com.sun.tools.javac.Main','-encoding','UTF-8','-d',str(out/'classes'),*map(str,sources)]),('run',['java','-XX:-UsePerfData','-cp',str(out/'classes'),'org.wangshu.ime.WangshuIme'])]:
 r=subprocess.run(cmd,capture_output=True,text=True);(out/(name+'.txt')).write_text(r.stdout+r.stderr);print(r.stdout+r.stderr,end='');receipt['commands'].append({'name':name,'returncode':r.returncode})
 if r.returncode:break
receipt['success']=r.returncode==0
(out/'RESULT.json').write_text(json.dumps(receipt,indent=2)+'\n');sys.exit(r.returncode)
