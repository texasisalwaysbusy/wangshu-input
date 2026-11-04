#!/usr/bin/env python3
"""Build affected TSF DLLs, native managers and fixtures with installed MinGW; run PE/mock COM, no registration."""
from pathlib import Path
import argparse, datetime, hashlib, json, shutil, subprocess
BASE=Path(__file__).resolve().parent;PROJECT=BASE.parent;ROOT=PROJECT.parents[1]
p=argparse.ArgumentParser(description=__doc__);p.add_argument('--out',type=Path,required=True);p.add_argument('--build',type=Path,required=True);a=p.parse_args()
out=a.out.resolve();build=a.build.resolve()
if not out.is_relative_to(BASE/'evidence') or out.exists() or not build.is_relative_to(BASE/'build-personal') or build.exists():raise SystemExit('New Windows-owned evidence/build paths required')
out.mkdir(parents=True);build.mkdir(parents=True)
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
def inputs():
    files={Path(__file__).resolve(),PROJECT/'tests/tsf_edit_test.cpp',PROJECT/'tests/windows_personal_tsf_test.cpp',PROJECT/'tests/windows_personal_store_test.cpp',PROJECT/'tests/tsf_stubs.h',PROJECT/'evidence/android-personal-format-roundtrip-cycle11/android-synthetic-store.json',PROJECT/'tools/run_wine.sh'}
    for directory in [BASE,PROJECT/'core',PROJECT/'staging/personal-native',PROJECT/'third_party/nlohmann']:
        files.update(x for x in directory.iterdir() if x.is_file() and x.suffix in {'.cpp','.h','.hpp','.MIT'})
    files.update(x for x in (PROJECT/'data').rglob('*') if x.is_file())
    return {str(x.relative_to(ROOT)):sha(x) for x in sorted(files)}
before=inputs();record={'started_utc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'scope':'Affected production TSF DLLs and actual PE/Wine mock TSF/COM tests only. Real Windows desktop/registration/x86 runtime/microphone NOT_RUN. Native managers rebuilt for new learning controls; unchanged voice helper not rebuilt.','inputs_before':before,'commands':[]}
shutil.copytree(PROJECT/'data',build/'data')
libs=['-lole32','-loleaut32','-luuid','-lsapi','-luser32','-lgdi32','-ladvapi32','-lnormaliz','-lwinpthread']
native=[str(BASE/'personal_store.cpp'),str(PROJECT/'staging/personal-native/personal_data.cpp'),str(PROJECT/'core/engine.cpp')]
cxx=ROOT/'.tools/mingw/usr/bin/x86_64-w64-mingw32-g++-posix';record['compiler_sha256']={}
commands=[]
for arch,triple in [('x64','x86_64-w64-mingw32'),('x86','i686-w64-mingw32')]:
    compiler=ROOT/('.tools/mingw/usr/bin/'+triple+'-g++-posix');record['compiler_sha256'][str(compiler.relative_to(ROOT))]=sha(compiler)
    commands.append(('build-'+arch,[str(compiler),'-std=c++17','-O2','-Wall','-Wextra','-static-libgcc','-static-libstdc++','-shared','-Wl,--no-undefined','-Wl,--kill-at',str(BASE/'text_service.cpp')]+native+['-o',str(build/('wangshu-ime-'+arch+'.dll'))]+libs+['-static'],360))
    commands.append(('build-manager-'+arch,[str(compiler),'-std=c++17','-O2','-Wall','-Wextra','-static','-municode','-mwindows',str(BASE/'personal_settings.cpp')]+native+['-o',str(build/('wangshu-personal-settings-'+arch+'.exe'))]+libs+['-lcomdlg32','-lshell32'],360))
commands += [('build-personal-tsf',[str(cxx),'-std=c++17','-O1','-Wall','-Wextra','-static',str(PROJECT/'tests/windows_personal_tsf_test.cpp')]+native+['-o',str(build/'personal-tsf-test.exe')]+libs,240),('build-personal-store',[str(cxx),'-std=c++17','-O1','-Wall','-Wextra','-static','-municode',str(PROJECT/'tests/windows_personal_store_test.cpp')]+native+['-o',str(build/'personal-store-test.exe')]+libs,240),('build-dll-smoke',[str(cxx),'-std=c++17','-O1','-Wall','-Wextra','-static','-municode',str(BASE/'dll_smoke.cpp'),'-o',str(build/'dll-smoke.exe')]+libs,90),('personal-tsf-runtime',['bash',str(PROJECT/'tools/run_wine.sh'),str(build/'personal-tsf-test.exe')],120),('personal-store-runtime',['bash',str(PROJECT/'tools/run_wine.sh'),str(build/'personal-store-test.exe'),'Z:'+str(out/'store-fixtures').replace('/','\\'),'Z:'+str(PROJECT/'evidence/android-personal-format-roundtrip-cycle11/android-synthetic-store.json').replace('/','\\')],120),('dll-runtime',['bash',str(PROJECT/'tools/run_wine.sh'),str(build/'dll-smoke.exe'),'Z:'+str(build/'wangshu-ime-x64.dll').replace('/','\\')],90)]
code=0
for label,command,bound in commands:
    print(label,flush=True);started=datetime.datetime.now(datetime.timezone.utc).isoformat()
    try:
        r=subprocess.run(command,cwd=ROOT,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=bound);code=r.returncode;stdout=r.stdout;stderr=r.stderr
    except subprocess.TimeoutExpired as error:
        code=124;stdout=error.stdout or b'';stderr=(error.stderr or b'')+b'\nHOST_RUNNER_TIMEOUT\n'
    (out/(label+'.stdout.txt')).write_bytes(stdout);(out/(label+'.stderr.txt')).write_bytes(stderr);record['commands'].append({'stage':label,'command':command,'started_utc':started,'exit_code':code})
    if code:break
record.update(finished_utc=datetime.datetime.now(datetime.timezone.utc).isoformat(),inputs_after=inputs(),exit_code=code)
record['inputs_stable']=record['inputs_after']==before;record['artifacts']={str(x.relative_to(ROOT)):sha(x) for x in build.iterdir() if x.suffix in {'.exe','.dll'}}
(out/'verification.json').write_text(json.dumps(record,indent=2)+'\n');(build/'source-attestation.json').write_text(json.dumps(record,indent=2)+'\n')
print(json.dumps({'exit_code':code,'inputs_stable':record['inputs_stable'],'out':str(out),'artifacts':record['artifacts']},indent=2));raise SystemExit(code if record['inputs_stable'] else 65)
