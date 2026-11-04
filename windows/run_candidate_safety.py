#!/usr/bin/env python3
"""Serial installed-MinGW PE build and existing-Wine TSF mock-host checks; no registration/install."""
from pathlib import Path
import argparse,datetime,hashlib,json,subprocess

BASE=Path(__file__).resolve().parent;PROJECT=BASE.parent;ROOT=PROJECT.parents[1]
parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--out',required=True,type=Path);parser.add_argument('--name',required=True)
args=parser.parse_args();out=args.out.resolve()
if not out.is_relative_to(BASE/'evidence') or out.exists() or not args.name.replace('-','').isalnum():raise SystemExit('A NEW Windows evidence directory and simple artifact name required')
out.mkdir(parents=True);exe=BASE/'build-personal'/(args.name+'.exe')
if exe.exists():raise SystemExit('Refusing to overwrite an existing PE artifact')
inputs=[BASE/'candidate_safety_test.cpp',BASE/'text_service.cpp',BASE/'personal_store.cpp',BASE/'personal_store.h',BASE/'tsf_extended.h',PROJECT/'tests/tsf_edit_test.cpp',PROJECT/'tests/tsf_stubs.h',PROJECT/'staging/personal-native/personal_data.cpp',PROJECT/'staging/personal-native/personal_data.h',PROJECT/'core/engine.cpp',PROJECT/'core/engine.h',PROJECT/'third_party/nlohmann/json.hpp']
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
before={str(p.relative_to(ROOT)):sha(p) for p in inputs}
record={'started_utc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'scope':'Actual PE production TSF in existing Wine with generated personal files and mock COM/text store; no registration, OS Windows GUI, Android or real user data','inputs_before':before,'commands':[]}
compile=[str(ROOT/'.tools/mingw/usr/bin/x86_64-w64-mingw32-g++-posix'),'-std=c++17','-O1','-Wall','-Wextra','-Wno-unused-parameter','-static',str(BASE/'candidate_safety_test.cpp'),str(BASE/'personal_store.cpp'),str(PROJECT/'staging/personal-native/personal_data.cpp'),str(PROJECT/'core/engine.cpp'),'-o',str(exe),'-lole32','-loleaut32','-luuid','-lsapi','-luser32','-lgdi32','-ladvapi32','-lnormaliz','-lwinpthread']
code=0
for stage,command,bound in [('compile',compile,180),('runtime',['bash',str(PROJECT/'tools/run_wine.sh'),str(exe)],90)]:
    result=subprocess.run(command,cwd=ROOT,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=bound);(out/(stage+'.stdout.txt')).write_bytes(result.stdout);(out/(stage+'.stderr.txt')).write_bytes(result.stderr)
    record['commands'].append({'stage':stage,'command':command,'exit_code':result.returncode});code=result.returncode
    if stage=='compile' and code:break
record['inputs_after']={str(p.relative_to(ROOT)):sha(p) for p in inputs};record['inputs_stable']=record['inputs_before']==record['inputs_after'];record['finished_utc']=datetime.datetime.now(datetime.timezone.utc).isoformat();record['artifact']={'path':str(exe.relative_to(ROOT)),'sha256':sha(exe)} if exe.exists() else None
(out/'verification.json').write_text(json.dumps(record,indent=2)+'\n')
print(json.dumps({'out':str(out),'exit_code':code,'inputs_stable':record['inputs_stable'],'artifact':record['artifact']},indent=2));raise SystemExit(code if record['inputs_stable'] else 65)
