#!/usr/bin/env python3
"""Existing-MinGW build and actual Wine/Win32 module-return lifetime check, no registration."""
from pathlib import Path
import argparse,datetime,hashlib,json,subprocess
BASE=Path(__file__).resolve().parent;PROJECT=BASE.parent;ROOT=PROJECT.parents[1]
p=argparse.ArgumentParser(description=__doc__);p.add_argument('--out',type=Path,required=True);p.add_argument('--name',required=True);a=p.parse_args();out=a.out.resolve()
if not out.is_relative_to(BASE/'evidence') or out.exists() or not a.name.replace('-','').isalnum():raise SystemExit('New owned evidence and simple build name required')
build=BASE/'build-personal'/a.name
if build.exists():raise SystemExit('Build already exists')
out.mkdir(parents=True);build.mkdir(parents=True);sha=lambda x:hashlib.sha256(x.read_bytes()).hexdigest()
def inputs():
 files={Path(__file__).resolve(),PROJECT/'tools/run_wine.sh'}
 for folder in [BASE,PROJECT/'core',PROJECT/'staging/personal-native',PROJECT/'third_party/nlohmann']:
  files.update(x for x in folder.iterdir() if x.is_file() and x.suffix in {'.cpp','.h','.hpp','.MIT'})
 return {str(x.relative_to(ROOT)):sha(x) for x in sorted(files)}
before=inputs();record={'utc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'scope':'Actual generated test-only DLL consuming the same production queue header and native persistence; real Windows desktop/TSF registration NOT_RUN','inputs_before':before,'commands':[]}
cxx=ROOT/'.tools/mingw/usr/bin/x86_64-w64-mingw32-g++-posix';dll=build/'owned-learning-fixture.dll';exe=build/'learning-module-test.exe';libs=['-lole32','-loleaut32','-luuid','-lsapi','-luser32','-lgdi32','-ladvapi32','-lnormaliz','-lwinpthread']
commands=[('build-fixture',[str(cxx),'-std=c++17','-O1','-Wall','-Wextra','-shared','-static',str(BASE/'learning_module_fixture.cpp'),str(BASE/'personal_store.cpp'),str(PROJECT/'staging/personal-native/personal_data.cpp'),str(PROJECT/'core/engine.cpp'),'-o',str(dll)]+libs,240),('build-loader',[str(cxx),'-std=c++17','-O1','-Wall','-Wextra','-static','-municode',str(BASE/'learning_module_test.cpp'),'-o',str(exe)],90),('runtime',['bash',str(PROJECT/'tools/run_wine.sh'),str(exe),'Z:'+str(dll).replace('/','\\'),'Z:'+str(out/'fixtures').replace('/','\\')],90)]
code=0
for label,command,bound in commands:
 print(label,flush=True);r=subprocess.run(command,cwd=ROOT,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=bound);(out/(label+'.stdout.txt')).write_bytes(r.stdout);(out/(label+'.stderr.txt')).write_bytes(r.stderr);record['commands'].append({'stage':label,'command':command,'exit_code':r.returncode});code=r.returncode
 if code:break
record.update(inputs_after=inputs(),inputs_stable=before==inputs(),exit_code=code,artifacts={str(x.relative_to(ROOT)):sha(x) for x in build.iterdir() if x.suffix in {'.exe','.dll'}})
(out/'verification.json').write_text(json.dumps(record,indent=2)+'\n');print(json.dumps({'out':str(out),'exit_code':code,'inputs_stable':record['inputs_stable']},indent=2));raise SystemExit(code if record['inputs_stable'] else 65)
