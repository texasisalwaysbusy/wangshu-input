#!/usr/bin/env python3
"""Serial existing-tool Windows builds with complete input-membership attestation."""
import argparse,datetime,hashlib,json,os,subprocess,sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[3]
PROJECT=ROOT/'projects/input-method'
p=argparse.ArgumentParser(description=__doc__);p.add_argument('--tests',action='store_true');p.add_argument('--voice-helper-only',action='store_true',help='Compile only the helper and write a dedicated helper attestation');p.add_argument('--describe-inputs',action='store_true');p.add_argument('--output',type=Path);options=p.parse_args()
if options.tests and options.voice_helper_only:raise SystemExit('--tests and --voice-helper-only are separate build scopes')
output=(options.output or Path(os.environ.get('WANGSHU_WINDOWS_OUTPUT',str(PROJECT/'windows/build')))).resolve()
if not output.is_relative_to(ROOT):raise SystemExit('Build output must stay in this cloud repository')
output.mkdir(parents=True,exist_ok=True)

def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()
def inputs():
    selected=set()
    for folder in ['windows','core','staging/personal-native','third_party/nlohmann']:
        for path in (PROJECT/folder).glob('*'):
            if path.is_file() and path.suffix in {'.h','.hpp','.cpp','.MIT','.json'}:selected.add(path)
    for path in (PROJECT/'data').rglob('*'):
        if path.is_file():selected.add(path)
    selected.update([Path(__file__).resolve(),PROJECT/'tools/build_windows.sh'])
    # voice_helper.cpp directly includes this repository-owned ABI header.
    # Keep it explicit: a missing required file must fail discovery, not silently
    # disappear from a glob and leave an apparently complete build receipt.
    selected.add(PROJECT/'third_party/sherpa/c-api.h')
    if options.tests:
        selected.update(path for path in (PROJECT/'tests').glob('*') if path.is_file() and path.suffix in {'.cpp','.h'})
    return {str(path.relative_to(ROOT)):sha(path) for path in sorted(selected)}
if options.describe_inputs:
    print(json.dumps(inputs(),sort_keys=True));raise SystemExit(0)
before=inputs();record={'started_utc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'scope':'existing MinGW cross compile only; Windows registration and actual Windows GUI NOT_RUN','tests_build':options.tests,'build_scope':'tests' if options.tests else 'voice-helper-only' if options.voice_helper_only else 'production','inputs_sha256':before,'commands':[]};code=0
native=[PROJECT/'windows/personal_store.cpp',PROJECT/'staging/personal-native/personal_data.cpp',PROJECT/'core/engine.cpp']
libs=['-lole32','-loleaut32','-luuid','-lsapi','-luser32','-lgdi32','-ladvapi32','-lnormaliz','-lwinpthread']
commands=[];names=[]
if options.tests:
    cxx=ROOT/'.tools/mingw/usr/bin/x86_64-w64-mingw32-g++-posix'
    for source,name,unicode in [('windows/dll_smoke.cpp','dll-smoke.exe',True),('tests/tsf_edit_test.cpp','tsf-edit-test.exe',False),('tests/windows_personal_store_test.cpp','windows-personal-store-test.exe',True),('tests/windows_personal_tsf_test.cpp','windows-personal-tsf-test.exe',False)]:
        sources=[PROJECT/source]+([] if name=='dll-smoke.exe' else native)
        commands.append([str(cxx),'-std=c++17','-O2','-Wall','-Wextra','-static']+(['-municode'] if unicode else [])+list(map(str,sources))+['-o',str(output/name)]+libs);names.append(name)
else:
    for architecture,triple in [('x64','x86_64-w64-mingw32'),('x86','i686-w64-mingw32')]:
        cxx=ROOT/('.tools/mingw/usr/bin/'+triple+'-g++-posix')
        name='wangshu-ime-'+architecture+'.dll';commands.append([str(cxx),'-std=c++17','-O2','-Wall','-Wextra','-static-libgcc','-static-libstdc++','-shared','-Wl,--no-undefined','-Wl,--kill-at',str(PROJECT/'windows/text_service.cpp')]+list(map(str,native))+['-o',str(output/name)]+libs+['-static']);names.append(name)
        name='wangshu-personal-settings-'+architecture+'.exe';commands.append([str(cxx),'-std=c++17','-O2','-Wall','-Wextra','-municode','-mwindows','-static',str(PROJECT/'windows/personal_settings.cpp')]+list(map(str,native))+['-o',str(output/name),'-luser32','-lgdi32','-lcomdlg32','-lshell32','-lole32','-lnormaliz','-lwinpthread']);names.append(name)
    name='wangshu-voice-helper.exe';commands.append([str(ROOT/'.tools/mingw/usr/bin/x86_64-w64-mingw32-g++-posix'),'-std=c++17','-O2','-Wall','-Wextra','-municode','-static',str(PROJECT/'windows/voice_helper.cpp'),'-o',str(output/name),'-lwinmm','-lbcrypt']);names.append(name)
if options.voice_helper_only:
    commands=[command for command,name in zip(commands,names) if name=='wangshu-voice-helper.exe'];names=['wangshu-voice-helper.exe']
for command,name in zip(commands,names):
    print('BUILD '+name,flush=True);started=datetime.datetime.now(datetime.timezone.utc).isoformat();result=subprocess.run(command,cwd=ROOT);record['commands'].append({'output':name,'started_utc':started,'exit_code':result.returncode})
    if result.returncode:code=result.returncode;break
after=inputs();changed=sorted(key for key in before.keys()|after.keys() if before.get(key)!=after.get(key));record.update(finished_utc=datetime.datetime.now(datetime.timezone.utc).isoformat(),source_stable=not changed,changed_during_build=changed,exit_code=code,outputs={str((output/name).relative_to(ROOT)):sha(output/name) for name in names if (output/name).is_file()})
filename='last-tests-build.json' if options.tests else 'last-voice-helper-build.json' if options.voice_helper_only else 'last-production-build.json';(output/filename).write_text(json.dumps(record,indent=2)+'\n')
if not code and not changed:(output/('tests-source-attestation.json' if options.tests else 'voice-helper-source-attestation.json' if options.voice_helper_only else 'source-attestation.json')).write_text(json.dumps(record,indent=2)+'\n')
if changed:print('Inputs changed during build; outputs not eligible for packaging: '+', '.join(changed),file=sys.stderr);code=code or 65
raise SystemExit(code)
