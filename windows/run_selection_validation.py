#!/usr/bin/env python3
"""Serial production/test builds plus affected TSF and DLL regression, without registration."""
from pathlib import Path
import argparse,datetime,hashlib,json,shutil,subprocess,sys

BASE=Path(__file__).resolve().parent;PROJECT=BASE.parent;ROOT=PROJECT.parents[1]
parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--out',required=True,type=Path);parser.add_argument('--build',required=True,type=Path)
args=parser.parse_args();out=args.out.resolve();build=args.build.resolve()
if not out.is_relative_to(BASE/'evidence') or out.exists() or not build.is_relative_to(BASE/'build-personal') or build.exists():raise SystemExit('Require NEW owned Windows evidence and ignored build directories')
out.mkdir(parents=True);build.mkdir(parents=True);sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
shutil.copytree(PROJECT/'data',build/'data')
data={str(p.relative_to(PROJECT/'data')):sha(p) for p in (PROJECT/'data').rglob('*') if p.is_file()}
if any(sha(build/'data'/name)!=digest for name,digest in data.items()):raise SystemExit('Copied generated-runtime data did not match existing project assets')
record={'started_utc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'scope':'Existing-tool builds and actual PE/Wine mock-TSF + COM/DLL regression. No installation, registration, publication, real Windows GUI or microphone.','runtime_data_sha256':data,'commands':[]}
builder=PROJECT/'tools/build_windows_personal.py'
commands=[('production-build',[sys.executable,str(builder),'--output',str(build)],600),('tests-build',[sys.executable,str(builder),'--tests','--output',str(build)],600),('tsf-runtime',['bash',str(PROJECT/'tools/run_wine.sh'),str(build/'windows-personal-tsf-test.exe')],120),('dll-runtime',['bash',str(PROJECT/'tools/run_wine.sh'),str(build/'dll-smoke.exe'),'Z:'+str(build/'wangshu-ime-x64.dll').replace('/','\\')],90)]
code=0
for stage,command,bound in commands:
    print(stage,flush=True);result=subprocess.run(command,cwd=ROOT,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=bound)
    (out/(stage+'.stdout.txt')).write_bytes(result.stdout);(out/(stage+'.stderr.txt')).write_bytes(result.stderr)
    record['commands'].append({'stage':stage,'command':command,'exit_code':result.returncode});code=result.returncode
    if code:break
record['finished_utc']=datetime.datetime.now(datetime.timezone.utc).isoformat()
record['receipts']={name:{'path':str((build/name).relative_to(ROOT)),'sha256':sha(build/name)} for name in ['source-attestation.json','tests-source-attestation.json','last-production-build.json','last-tests-build.json'] if (build/name).is_file()}
record['binaries']={str(p.relative_to(ROOT)):sha(p) for p in build.glob('*') if p.is_file() and p.suffix in {'.exe','.dll'}}
record['not_run']=['real Windows GUI / TSF registration','real microphone','x86 runtime','unchanged store runtime (compiled only)','duplicate tsf-edit-test runtime (included by personal TSF runner)']
(out/'verification.json').write_text(json.dumps(record,indent=2)+'\n');print(json.dumps({'out':str(out),'build':str(build),'exit_code':code,'stages':record['commands']},indent=2));raise SystemExit(code)
