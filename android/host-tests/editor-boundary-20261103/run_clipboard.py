#!/usr/bin/env python3
"""Extract exact clipboard admission/capture guards; synthetic OS/storage boundaries."""
from pathlib import Path
import argparse,hashlib,json,subprocess,sys
p=argparse.ArgumentParser(description=__doc__);p.add_argument('project',type=Path);p.add_argument('output',type=Path);a=p.parse_args();root=a.project.resolve();out=a.output.resolve();out.mkdir(parents=True,exist_ok=False);src=root/'android/app/src/main/java/org/wangshu/ime'
def method(text,signature):
 if text.count(signature)!=1:raise RuntimeError('ambiguous source signature '+signature)
 start=text.index(signature);opening=text.index('{',start);depth=0;state='code';i=opening
 while i<len(text):
  c=text[i];n=text[i:i+2]
  if state in ('string','char'):
   if c=='\\':i+=2;continue
   if c==('"' if state=='string' else "'"):state='code'
  elif state=='line':
   if c=='\n':state='code'
  elif state=='comment':
   if n=='*/':state='code';i+=2;continue
  elif n=='//':state='line';i+=2;continue
  elif n=='/*':state='comment';i+=2;continue
  elif c=='"':state='string'
  elif c=="'":state='char'
  elif c=='{':depth+=1
  elif c=='}':
   depth-=1
   if depth==0:return text[start:i+1]
  i+=1
 raise RuntimeError('unclosed source method')
ime=(src/'WangshuIme.java').read_text();store=(src/'ClipboardStore.java').read_text();personal=(src/'PersonalStore.java').read_text()
fragments={k:method(t,sig) for k,t,sig in [('allowed',store,'static String allowedText('),('text',personal,'static String text('),('current',ime,'private boolean clipboardCaptureCurrent('),('capture',ime,'private void captureClipboard(')]}
files={
 'android/os/PersistableBundle.java':'''package android.os;public final class PersistableBundle {boolean sensitive;public boolean getBoolean(String k,boolean d){return sensitive;}public void putBoolean(String k,boolean v){sensitive=v;}}''',
 'android/content/ClipDescription.java':'''package android.content;import android.os.PersistableBundle;public final class ClipDescription {public static final String MIMETYPE_TEXT_PLAIN="text/plain";public PersistableBundle extras;public boolean plain=true;public PersistableBundle getExtras(){return extras;}public boolean hasMimeType(String s){return plain;}}''',
 'android/content/ClipData.java':'''package android.content;public final class ClipData {public int count=1;public final ClipDescription description=new ClipDescription();public final Item item=new Item();public int getItemCount(){return count;}public ClipDescription getDescription(){return description;}public Item getItemAt(int n){return item;}public static final class Item {public Object uri,intent;public CharSequence text="Synthetic 👩🏽‍💻\\nText";public Object getUri(){return uri;}public Object getIntent(){return intent;}public CharSequence getText(){return text;}}}''',
 'org/wangshu/ime/ClipboardPolicyChecks.java':(Path(__file__).parent/'ClipboardPolicyChecks.java.in').read_text().replace('/*ALLOWED*/',fragments['allowed']).replace('/*TEXT*/',fragments['text']),
 'org/wangshu/ime/CaptureBoundaryChecks.java':(Path(__file__).parent/'CaptureBoundaryChecks.java.in').read_text().replace('/*CURRENT*/',fragments['current']).replace('/*CAPTURE*/',fragments['capture'].replace('android.content.ClipData clip','String clip'))
}
paths=[]
for name,value in files.items():
 target=out/'src'/name;target.parent.mkdir(parents=True,exist_ok=True);target.write_text(value,encoding='utf-8');paths.append(target)
receipt={'scope':'Exact extracted production admission and capture methods; fake clipboard types, permissions/preferences/listener and storage. No real clipboard, AtomicFile, Android lifecycle, disk durability or phone acceptance.','Android':'NOT_RUN','source_sha256':{str(src/n):hashlib.sha256((src/n).read_bytes()).hexdigest() for n in ('WangshuIme.java','ClipboardStore.java','PersonalStore.java')},'fragment_sha256':{k:hashlib.sha256(v.encode()).hexdigest() for k,v in fragments.items()},'commands':[],'success':False}
def run(label,cmd):
 x=subprocess.run([str(v) for v in cmd],capture_output=True,text=True,encoding='utf-8',errors='replace');(out/(label+'.txt')).write_text(x.stdout+x.stderr,encoding='utf-8');print(x.stdout+x.stderr,end='');receipt['commands'].append({'name':label,'command':list(map(str,cmd)),'returncode':x.returncode});return x.returncode==0
try:
 if run('compile',['java','-m','jdk.compiler/com.sun.tools.javac.Main','-encoding','UTF-8','-source','17','-target','17','-d',out/'classes',*paths]):receipt['success']=all([run(n,['java','-cp',out/'classes','org.wangshu.ime.'+n]) for n in ('ClipboardPolicyChecks','CaptureBoundaryChecks')])
finally:(out/'RESULT.json').write_text(json.dumps(receipt,indent=2)+'\n',encoding='utf-8')
sys.exit(0 if receipt['success'] else 1)
