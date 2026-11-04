#!/usr/bin/env python3
"""Production settings codec fixed-seed mutations with unused load stubs."""
from pathlib import Path
import argparse,hashlib,json,subprocess,sys
p=argparse.ArgumentParser(description=__doc__);p.add_argument('project',type=Path);p.add_argument('output',type=Path);a=p.parse_args();root=a.project.resolve();out=a.output.resolve();out.mkdir(parents=True,exist_ok=False);here=Path(__file__).resolve().parent;src=root/'android/app/src/main/java/org/wangshu/ime'
files=[]
for name,value in {'android/content/Context.java':'package android.content;public class Context {}','org/wangshu/ime/AppPreferences.java':'package org.wangshu.ime;final class AppPreferences {static AppPreferences open(android.content.Context c,String n){throw new AssertionError("load not used by codec test");}String getString(String n,String d){throw new AssertionError("load not used by codec test");}}'}.items():
 f=out/'stubs'/name;f.parent.mkdir(parents=True,exist_ok=True);f.write_text(value,encoding='utf-8');files.append(f)
files += [src/'SettingsBackup.java',src/'MathSidebar.java',here/'SettingsBoundaryChecks.java']
receipt={'scope':'Production settings codec and symbol parser. Fake Android Context/preferences load boundary is not called. No preference mutation, recovery, Android storage or native acceptance.','Android':'NOT_RUN','sources':{str(f):hashlib.sha256(f.read_bytes()).hexdigest() for f in files},'commands':[],'success':False}
def run(label,cmd):
 r=subprocess.run(list(map(str,cmd)),capture_output=True,text=True,encoding='utf-8',errors='replace');(out/(label+'.txt')).write_text(r.stdout+r.stderr,encoding='utf-8');print(r.stdout+r.stderr,end='');receipt['commands'].append({'name':label,'command':list(map(str,cmd)),'returncode':r.returncode});return r.returncode==0
try:
 if run('compile',['java','-m','jdk.compiler/com.sun.tools.javac.Main','-source','17','-target','17','-encoding','UTF-8','-d',out/'classes',*files]):receipt['success']=run('run',['java','-cp',out/'classes','org.wangshu.ime.SettingsBoundaryChecks'])
finally:(out/'RESULT.json').write_text(json.dumps(receipt,indent=2)+'\n',encoding='utf-8')
sys.exit(0 if receipt['success'] else 1)
