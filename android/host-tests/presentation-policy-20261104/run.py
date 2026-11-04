#!/usr/bin/env python3
"""Run unchanged production pure classes and source-bound panel callbacks on an existing JDK.
No installs, device access, network, audio, preferences, or user data. New output directory only.
"""
from pathlib import Path
import hashlib,json,subprocess,sys,xml.etree.ElementTree as ET
project,out=map(lambda x:Path(x).resolve(),sys.argv[1:3]);out.mkdir(parents=True,exist_ok=False)
here=Path(__file__).resolve().parent;java=project/'android/app/src/main/java/org/wangshu/ime'
sources=[java/'SymbolPanelCatalog.java',java/'VoiceResultPolicy.java',here/'PresentationPolicyChecks.java']
result={'scope':'Direct production pure classes, extracted real callbacks with fake views, XML/source wiring. No Android build/layout/TalkBack/phone or ASR acceptance.','checks':[]}
def run(name,cmd):
    r=subprocess.run([str(x) for x in cmd],text=True,capture_output=True);(out/(name+'.txt')).write_text(r.stdout+r.stderr);print(r.stdout+r.stderr,end='');result['checks'].append({'name':name,'returncode':r.returncode});return r.returncode==0
classes=out/'classes';classes.mkdir()
if run('compile',['java','-m','jdk.compiler/com.sun.tools.javac.Main','-proc:none','-d',classes,*sources]):run('policies',['java','-cp',classes,'org.wangshu.ime.PresentationPolicyChecks'])
ime=(java/'WangshuIme.java').read_text()
def extract(signature):
    assert ime.count(signature)==1, signature
    start=ime.index(signature);opening=ime.index('{',start);depth=1;end=opening+1
    while depth:
        depth+=(ime[end]=='{')-(ime[end]=='}');end+=1
    return ime[start:end]
template=(here/'PanelCallbackChecks.java.in').read_text()
for marker,signature in [('/* REVEAL */','private void revealSymbolTab('),('/* CHOOSE */','private void chooseSymbol('),('/* SYMBOL_BUTTON */','private Button symbolButton('),('/* CAPTURE */','private VoiceResultPolicy captureVoiceResultPolicy('),('/* FORMAT */','private String formatVoiceResult(')]:
    assert template.count(marker)==1;template=template.replace(marker,extract(signature))
generated=out/'PanelCallbackChecks.java';generated.write_text(template)
if run('callback-compile',['java','-m','jdk.compiler/com.sun.tools.javac.Main','-proc:none','-cp',classes,'-d',classes,generated]):run('callbacks',['java','-cp',classes,'org.wangshu.ime.PanelCallbackChecks'])
checks=[]
def check(ok,label):
    checks.append({'name':label,'passed':bool(ok)});print(('PASS ' if ok else 'FAIL ')+label)
check(ime.count('captureVoiceResultPolicy();')==3,'all three recognition starts capture immutable result policy')
check(ime.count('formatVoiceResult(resultPolicy,')==3,'all three result paths share the transformer')
check(ime.count('NativeBridge.voiceMath(')==1,'one production native math conversion callsite')
check('mathVoice' not in ime and 'mathematical=math' not in ime,'no mutable shared mathVoice flag')
check('return math?VoiceResultPolicy.MATH_SYMBOLS:VoiceResultPolicy.RAW;' in ime,'single explicit legacy-panel adapter retained, not full behavioral decoupling')
check('text->NativeBridge.voiceMath(text)' in ime,'native loading/conversion remains lazy')
check('symbolCategory=SymbolPanelCatalog.DEFAULT_CATEGORY' in ime,'default symbol panel uses common catalog')
check('for(String category:SymbolPanelCatalog.categories())' in ime,'actual tab rendering consumes catalog')
check('builtins!=null?builtins:DataFiles.ready?NativeBridge.symbols(symbolCategory):new String[0]' in ime,'builtins do not need dictionary readiness')
check('b.setSelected(selected)' in ime and 'KeyboardSkin.function(this,b,selected)' in ime,'selected symbol tab has native selected state and visual skin')
check('Button b=symbolButton(symbol,alias,builtins!=null,viewSession,viewGeneration,symbolEditor)' in ime,'actual symbol grid uses pre-side-effect guarded button')
check('if(!emojiPanelActive())revealSymbolTab(' in ime,'normal symbols restore selected tab without overriding emoji scroll memory')
check('int remainder=(values.length/2)%6' in ime and 'for(int empty=remainder;empty<6;empty++)' in ime,'last symbol row pads to six columns')
check('categories.addView(b,new LinearLayout.LayoutParams(dp(72),dp(compact()?28:36)))' in ime,'category row height not increased in portrait or landscape')
ns='{http://schemas.android.com/apk/res/android}';manifest=ET.parse(project/'android/app/src/main/AndroidManifest.xml').getroot();app=manifest.find('application');service=app.find('service')
preview_manifest=ET.parse(project/'android/app/src/typingPreview/AndroidManifest.xml').getroot();preview_app=preview_manifest.find('application');preview_service=preview_app.find('service')
check(preview_app.get(ns+'label')=='@string/app_name' and preview_service.get(ns+'label')=='@string/ime_name','preview manifest overrides also use flavor resources')
check(app.get(ns+'label')=='@string/app_name' and service.get(ns+'label')=='@string/ime_name','application and IME use flavor-specific name resources')
main={x.get('name'):x.text for x in ET.parse(project/'android/app/src/main/res/values/strings.xml').getroot()};preview={x.get('name'):x.text for x in ET.parse(project/'android/app/src/typingPreview/res/values/strings.xml').getroot()}
check(all('完整候选' in main[key] for key in ['app_name','ime_name','subtype_name']),'full labels explicitly say candidate, not finished release')
check(all('预览' in preview[key] or 'Preview' in preview[key] for key in ['app_name','ime_name','subtype_name']),'preview labels distinguish application/service/subtype')
result['wiring']=checks
result['sha256']={str(p.relative_to(project)) if p.is_relative_to(project) else p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in [*sources,java/'WangshuIme.java',Path(__file__),here/'PanelCallbackChecks.java.in']}
result['success']=all(c['returncode']==0 for c in result['checks']) and all(c['passed'] for c in checks)
(out/'RESULT.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
raise SystemExit(0 if result['success'] else 1)
