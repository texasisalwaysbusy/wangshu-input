#!/usr/bin/env python3
"""Build/execute composition-mutation TSF PE tests with existing tools; no install or registration."""
from pathlib import Path
import argparse,datetime,hashlib,json,subprocess

BASE=Path(__file__).resolve().parent;PROJECT=BASE.parent;ROOT=PROJECT.parents[1]
parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--out',required=True,type=Path);parser.add_argument('--name',required=True)
args=parser.parse_args();out=args.out.resolve()
if not out.is_relative_to(BASE/'evidence') or out.exists() or not args.name.replace('-','').isalnum():raise SystemExit('Require NEW Windows evidence and simple PE name')
exe=BASE/'build-personal'/(args.name+'.exe')
if exe.exists():raise SystemExit('Existing artifact will not be overwritten')
out.mkdir(parents=True)
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
def inputs():
    selected={Path(__file__).resolve(),PROJECT/'tests/tsf_edit_test.cpp',PROJECT/'tests/tsf_stubs.h',PROJECT/'tools/run_wine.sh'}
    for directory in [BASE,PROJECT/'core',PROJECT/'staging/personal-native',PROJECT/'third_party/nlohmann']:
        selected.update(p for p in directory.iterdir() if p.is_file() and p.suffix in {'.cpp','.h','.hpp','.MIT','.json'})
    selected.update(p for p in (PROJECT/'data').rglob('*') if p.is_file())
    return {str(p.relative_to(ROOT)):sha(p) for p in sorted(selected)}
before=inputs();cxx=ROOT/'.tools/mingw/usr/bin/x86_64-w64-mingw32-g++-posix'
record={'started_utc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'scope':'Actual PE production TSF with selection-aware mock host under existing Wine; real Windows GUI/registration/microphone NOT_RUN','inputs_before':before,'compiler_sha256':sha(cxx),'commands':[]}
sources=[BASE/'composition_mutation_test.cpp',BASE/'personal_store.cpp',PROJECT/'staging/personal-native/personal_data.cpp',PROJECT/'core/engine.cpp']
command=[str(cxx),'-std=c++17','-O1','-Wall','-Wextra','-Wno-unused-parameter','-static',*map(str,sources),'-o',str(exe),'-lole32','-loleaut32','-luuid','-lsapi','-luser32','-lgdi32','-ladvapi32','-lnormaliz','-lwinpthread']
code=0
for stage,args,bound in [('compile',command,180),('runtime',['bash',str(PROJECT/'tools/run_wine.sh'),str(exe)],90)]:
    result=subprocess.run(args,cwd=ROOT,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=bound);(out/(stage+'.stdout.txt')).write_bytes(result.stdout);(out/(stage+'.stderr.txt')).write_bytes(result.stderr)
    record['commands'].append({'stage':stage,'command':args,'exit_code':result.returncode});code=result.returncode
    if stage=='compile' and code:break
record['inputs_after']=inputs();record['inputs_stable']=record['inputs_before']==record['inputs_after'];record['finished_utc']=datetime.datetime.now(datetime.timezone.utc).isoformat()
if exe.exists():
    artifact=out/'composition-mutation-test.exe';artifact.write_bytes(exe.read_bytes());record['artifact']={'path':str(artifact.relative_to(ROOT)),'sha256':sha(artifact),'matches_built_pe':sha(artifact)==sha(exe)}
(out/'verification.json').write_text(json.dumps(record,indent=2)+'\n')
print(json.dumps({'out':str(out),'exit_code':code,'inputs_stable':record['inputs_stable'],'artifact':record.get('artifact')},indent=2));raise SystemExit(code if record['inputs_stable'] else 65)
