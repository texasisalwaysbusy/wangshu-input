#!/usr/bin/env python3
"""Exercise exact production prediction UTF16 guard with a synthetic JNI interface.

This does not validate the JNI ABI, real VM, Android, or full native bridge.
"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess


parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--project',type=Path,default=Path(__file__).resolve().parents[1])
parser.add_argument('--output',type=Path,required=True)
args=parser.parse_args()
project=args.project.resolve();output=args.output.absolute()
if output.exists() or output.is_symlink():parser.error('output must be new')
bridge=project/'android/app/src/main/cpp/bridge.cpp';source=bridge.read_text()
signature='static bool predictionText('
start=source.index(signature);opening=source.index('{',start);depth=1;end=opening+1
while depth:
    depth+=(source[end]=='{')-(source[end]=='}');end+=1
fragment=source[start:end]
output.mkdir(parents=True,exist_ok=False)
code=r'''#include "engine.h"
#include <iostream>
#include <stdexcept>
using jchar=char16_t;using jsize=int;using jstring=const std::u16string*;
struct JNIEnv {int gets=0,releases=0;bool fail=false;jsize GetStringLength(jstring s){return s->size();}const jchar* GetStringChars(jstring s,void*){gets++;return fail?nullptr:s->data();}void ReleaseStringChars(jstring,const jchar*){releases++;}};
'''+fragment+r'''
int main(){int count=0;auto check=[&](bool value,const char* label){if(!value)throw std::runtime_error(label);count++;std::cout<<"PASS "<<label<<"\n";};
JNIEnv e;std::string out="sentinel";check(predictionText(&e,nullptr,out)&&out.empty()&&e.gets==0,"null is empty without acquisition");
std::u16string plain=u"I would ";check(predictionText(&e,&plain,out)&&out=="I would "&&e.gets==1&&e.releases==1,"valid ASCII copied and released once");
std::u16string chinese=u"今天";check(predictionText(&e,&chinese,out)&&out=="今天","valid Han encoded without repair");
std::u16string pair=u"𝕏\n今天";check(predictionText(&e,&pair,out)&&out=="𝕏\n今天","valid supplementary pair accepted");
for(auto value:std::vector<std::u16string>{std::u16string(1,0xd800)+u"\n今天",std::u16string(1,0xdc00)+u"\n今天",u"今天\n"+std::u16string(1,0xd800),std::u16string{0xd800,u'A'},std::u16string{0xdc00,0xd800}}){int before=e.releases;check(!predictionText(&e,&value,out)&&e.releases==before+1,"malformed whole-input UTF16 rejected and released");}
std::u16string max(32768,u'A');check(predictionText(&e,&max,out)&&out.size()==32768,"maximum bounded input accepted");
std::u16string large(32769,u'A');int before=e.gets;check(!predictionText(&e,&large,out)&&e.gets==before,"overbound refused before JNI character acquisition");
std::u16string nul{u'a',0,u'b'};check(predictionText(&e,&nul,out)&&out==std::string("a\0b",3),"embedded NUL not truncated");
e.fail=true;before=e.releases;check(!predictionText(&e,&plain,out)&&e.releases==before,"failed JNI acquisition not dereferenced or released");
std::cout<<"TOTAL "<<count<<" PASS; exact extracted production guard with synthetic JNI only. Actual JNI ABI and Android NOT_RUN.\n";
}
'''
generated=output/'guard.cpp';generated.write_text(code);binary=output/'guard'
inputs=[bridge,project/'core/engine.cpp',project/'core/engine.h',generated]
receipt={'scope':'Exact extracted production helper; synthetic JNI interface, no real JNI ABI/VM/Android.','source_sha256':{str(p):hashlib.sha256(p.read_bytes()).hexdigest() for p in inputs},'fragment_sha256':hashlib.sha256(fragment.encode()).hexdigest(),'success':False,'runs':[]}
for name,command in [('compile',['g++','-std=c++17','-O2','-Wall','-Wextra','-Werror','-I'+str(project/'core'),str(generated),str(project/'core/engine.cpp'),'-o',str(binary)]),('run',[str(binary)])]:
    result=subprocess.run(command,capture_output=True,text=True,timeout=120)
    (output/(name+'.txt')).write_text(result.stdout+result.stderr)
    print(result.stdout+result.stderr,end='');receipt['runs'].append({'command':command,'returncode':result.returncode})
    if result.returncode:break
receipt['success']=result.returncode==0
(output/'RESULT.json').write_text(json.dumps(receipt,indent=2)+'\n')
raise SystemExit(result.returncode)
