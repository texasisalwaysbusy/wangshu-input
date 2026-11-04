#!/usr/bin/env python3
"""Extract production UI methods into a synthetic host, not Android/phone acceptance.
Only creates files below OUTPUT. No device, microphone, store, SDK, network, or installation.
Usage: run.py PROJECT OUTPUT
"""
from pathlib import Path
import hashlib,json,subprocess,sys
project,out=map(lambda x:Path(x).resolve(),sys.argv[1:3]); src=project/'android/app/src/main/java/org/wangshu/ime'
ime=(src/'WangshuIme.java').read_text(); settings=(src/'SettingsActivity.java').read_text()
def method(text,prefix):
 assert text.count(prefix)==1,(prefix,text.count(prefix))
 start=text.index(prefix); brace=text.index('{',start); depth=1;end=brace+1
 # Selected methods have no brace literals/comments. Fail explicitly if malformed.
 while depth:
  depth+=(text[end]=='{')-(text[end]=='}');end+=1
 return text[start:end]
if 'private void showVoiceRightActionSettings()' in settings:
 dialog=method(settings,'private void showVoiceRightActionSettings()')
 assert 'add(panel,"按住语音右侧动作",this::showVoiceRightActionSettings)' in settings
else:
 old=method(settings,'add(panel,"按住语音右侧动作",()->')
 dialog='private void showVoiceRightActionSettings()'+old[old.index('{'):]
cancel=method(ime,'private void cancelVoice()')
if 'private void refreshCompositionStatus()' in ime:
 status=method(ime,'private void refreshCompositionStatus()')
 assert 'refreshCompositionStatus();' in method(ime,'private void refresh()')
else:
 start=ime.index('composition.setText(offlineTask!=null?voiceState:');end=ime.index('candidates.removeAllViews();',start)
 status='private void refreshCompositionStatus(){if(composition==null)return;'+ime[start:end]+'}'
template=Path(__file__).with_name('VoiceUiChecks.java.in').read_text()
for token,body in [('/* STATUS */',status),('/* CANCEL */',cancel),('/* SETTINGS */',dialog)]:
 assert template.count(token)==1;template=template.replace(token,body)
out.mkdir(parents=True,exist_ok=True);java=out/'VoiceUiChecks.java';java.write_text(template)
view=out/'android/view/View.java';view.parent.mkdir(parents=True,exist_ok=True);view.write_text('package android.view; public class View { private static int next=1; public static int generateViewId(){return next++;} }\n')
classes=out/'classes';classes.mkdir(exist_ok=True)
sources=[src/'WangshuIme.java',src/'SettingsActivity.java',src/'VoiceHoldSettings.java',src/'VoiceHoldController.java',Path(__file__),Path(__file__).with_name('VoiceUiChecks.java.in')]
(out/'receipt.json').write_text(json.dumps({'scope':'Actual extracted production methods with synthetic View/store/capture objects; AlertDialog message/list precedence modeled from AOSP. Android rendering, filesystem persistence, microphone, native build NOT_RUN.','sha256':{str(p):hashlib.sha256(p.read_bytes()).hexdigest() for p in sources}},indent=2)+'\n')
cmd=['java','-XX:-UsePerfData','com.sun.tools.javac.Main','-proc:none','-d',str(classes),str(java),str(view),str(src/'VoiceHoldSettings.java'),str(src/'VoiceHoldController.java')]
r=subprocess.run(cmd);assert r.returncode==0,'host compilation failed'
sys.exit(subprocess.run(['java','-XX:-UsePerfData','-cp',str(classes),'org.wangshu.ime.VoiceUiChecks']).returncode)
