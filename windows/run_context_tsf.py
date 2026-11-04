#!/usr/bin/env python3
"""One small PE in a NEW evidence directory; existing Wine, no installation or registration."""
from pathlib import Path
import argparse, datetime, hashlib, json, os, subprocess
BASE=Path(__file__).resolve().parent; PROJECT=BASE.parent; ROOT=PROJECT.parents[1]
p=argparse.ArgumentParser();p.add_argument('--out',type=Path,required=True);args=p.parse_args();out=args.out.resolve()
if not out.is_relative_to(BASE/'evidence/context-tsf-20261028') or out.exists():raise SystemExit('Require NEW scoped evidence directory')
out.mkdir(parents=True)
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
def inputs():
    selected={Path(__file__).resolve(),PROJECT/'tests/tsf_edit_test.cpp',PROJECT/'tests/tsf_stubs.h',PROJECT/'tools/run_wine.sh'}
    for d in [BASE,PROJECT/'core',PROJECT/'staging/personal-native',PROJECT/'third_party/nlohmann']:
        selected.update(x for x in d.iterdir() if x.is_file() and x.suffix in {'.cpp','.h','.hpp','.MIT','.json'})
    selected.update(x for x in (PROJECT/'data').rglob('*') if x.is_file())
    return {str(x.relative_to(ROOT)):sha(x) for x in sorted(selected)}
before=inputs();record={'started_utc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'scope':'Actual production PE plus mock TSF host via existing Wine. Generated text only. Real Windows GUI/registration/microphone NOT_RUN.','inputs_before':before,'commands':[]}
for name in ['text_service.cpp','context_candidates.h','numeric_candidates.h','numeric_tsf_test.cpp','context_tsf_test.cpp']:(out/name).write_bytes((BASE/name).read_bytes())
(out/'tsf_edit_test.cpp').write_bytes((PROJECT/'tests/tsf_edit_test.cpp').read_bytes())
target=os.path.relpath(PROJECT/'data',out);(out/'data').symlink_to(target,target_is_directory=True);record['data_symlink']=target
cxx=ROOT/'.tools/mingw/usr/bin/x86_64-w64-mingw32-g++-posix';exe=out/'context-tsf-checks.exe';record['compiler_sha256']=sha(cxx)
command=[str(cxx),'-std=c++17','-Os','-s','-Wall','-Wextra','-Wno-unused-parameter','-static',str(BASE/'context_tsf_test.cpp'),str(BASE/'personal_store.cpp'),str(PROJECT/'staging/personal-native/personal_data.cpp'),str(PROJECT/'core/engine.cpp'),'-o',str(exe),'-lole32','-loleaut32','-luuid','-lsapi','-luser32','-lgdi32','-ladvapi32','-lnormaliz','-lwinpthread']
code=0
try:
    for stage,cmd,bound in [('compile',command,180),('runtime',['bash',str(PROJECT/'tools/run_wine.sh'),str(exe)],150)]:
        result=subprocess.run(cmd,cwd=ROOT,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=bound)
        (out/(stage+'.stdout.txt')).write_bytes(result.stdout);(out/(stage+'.stderr.txt')).write_bytes(result.stderr)
        record['commands'].append({'stage':stage,'command':cmd,'exit_code':result.returncode});code=result.returncode
        if stage=='compile' and code:break
except subprocess.TimeoutExpired as e:
    code=124;record['timeout']={'command':e.cmd,'seconds':e.timeout};(out/'timeout.stdout.txt').write_bytes(e.stdout or b'');(out/'timeout.stderr.txt').write_bytes(e.stderr or b'')
record['inputs_after']=inputs();record['inputs_stable']=before==record['inputs_after'];record['finished_utc']=datetime.datetime.now(datetime.timezone.utc).isoformat()
if exe.exists():record['artifact']={'path':str(exe.relative_to(ROOT)),'size':exe.stat().st_size,'sha256':sha(exe)}
record['exit_code']=code;(out/'receipt.json').write_text(json.dumps(record,ensure_ascii=False,indent=2)+'\n')
print(json.dumps({'out':str(out),'code':code,'inputs_stable':record['inputs_stable'],'artifact':record.get('artifact')},ensure_ascii=False))
raise SystemExit(code if record['inputs_stable'] else 3)
