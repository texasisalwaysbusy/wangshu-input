#!/usr/bin/env python3
"""Compile Windows targets with an existing explicit/discovered MinGW toolchain; never run them."""
import argparse,datetime,hashlib,json,os,shutil,subprocess,sys
from pathlib import Path
PROJECT=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser(description=__doc__)
p.add_argument('--tests',action='store_true')
p.add_argument('--voice-helper-only',action='store_true')
p.add_argument('--describe-inputs',action='store_true',help='Read-only project-relative source hashes')
p.add_argument('--describe-commands',action='store_true',help='Read-only build plan; no compiler execution or output creation')
p.add_argument('--output',type=Path,help='Required new directory for a build (or WANGSHU_WINDOWS_OUTPUT)')
p.add_argument('--cxx-x64',type=Path,help='Existing x64 MinGW C++ executable (or WANGSHU_CXX_X64)')
p.add_argument('--cxx-x86',type=Path,help='Existing x86 MinGW C++ executable (or WANGSHU_CXX_X86)')
p.add_argument('--architectures',nargs='+',choices=['x64','x86'],default=['x64','x86'],help='Production architectures; tests/helper are x64 only')
options=p.parse_args()
if options.tests and options.voice_helper_only:raise SystemExit('--tests and --voice-helper-only are separate build scopes')
def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()
def inputs():
    selected=set()
    for folder in ['windows','core','staging/personal-native','third_party/nlohmann']:
        selected.update(path for path in (PROJECT/folder).glob('*') if path.is_file() and path.suffix in {'.h','.hpp','.cpp','.inc','.MIT','.json'})
    selected.update(path for path in (PROJECT/'data').rglob('*') if path.is_file())
    selected.update([Path(__file__).resolve(),PROJECT/'tools/build_windows.sh',PROJECT/'third_party/sherpa/c-api.h'])
    if options.tests:selected.update(path for path in (PROJECT/'tests').glob('*') if path.is_file() and path.suffix in {'.cpp','.h','.inc'})
    return {path.relative_to(PROJECT).as_posix():sha(path) for path in sorted(selected)}
if options.describe_inputs:
    print(json.dumps(inputs(),sort_keys=True));raise SystemExit(0)
raw_output=options.output or os.environ.get('WANGSHU_WINDOWS_OUTPUT')
if not raw_output:raise SystemExit('Choose a NEW --output directory; existing build outputs are never overwritten')
output=Path(raw_output).absolute()
# Reject existing paths, including dangling symlinks, before resolving ancestors.
if os.path.lexists(output):raise SystemExit('Preserve previous outputs: --output must not exist')
output=output.resolve()
def compiler(arch):
    supplied=getattr(options,'cxx_'+arch) or os.environ.get('WANGSHU_CXX_'+arch.upper())
    triple='x86_64-w64-mingw32' if arch=='x64' else 'i686-w64-mingw32'
    found=supplied or shutil.which(triple+'-g++-posix') or shutil.which(triple+'-g++')
    if not found:raise SystemExit('No existing '+arch+' MinGW compiler; provide --cxx-'+arch+' (no download/install attempted)')
    path=Path(found).expanduser().resolve()
    if not path.is_file() or not os.access(path,os.X_OK):raise SystemExit('Compiler is not an executable file: '+str(path))
    return path
native=[PROJECT/'windows/personal_store.cpp',PROJECT/'staging/personal-native/personal_data.cpp',PROJECT/'core/engine.cpp']
libs=['-lole32','-loleaut32','-luuid','-lsapi','-luser32','-lgdi32','-ladvapi32','-lnormaliz','-lwinpthread']
commands=[];names=[];toolchains={}
def add(arch,name,flags,sources,extra):
    if arch not in toolchains:toolchains[arch]=compiler(arch)
    for source in sources:
        if not source.is_file():raise SystemExit('Required source missing: '+str(source))
    commands.append([str(toolchains[arch]),'-std=c++17','-O2','-Wall','-Wextra']+flags+list(map(str,sources))+['-o',str(output/name)]+extra);names.append(name)
if options.tests:
    for source,name,unicode in [
        ('windows/dll_smoke.cpp','dll-smoke.exe',True),
        ('tests/tsf_edit_test.cpp','tsf-edit-test.exe',False),
        ('tests/windows_personal_store_test.cpp','windows-personal-store-test.exe',True),
        ('tests/windows_personal_tsf_test.cpp','windows-personal-tsf-test.exe',False),
        ('windows/phrase_association_tsf_test.cpp','phrase-association-tsf-test.exe',False)]:
        add('x64',name,['-static']+(['-municode'] if unicode else []),[PROJECT/source]+([] if name=='dll-smoke.exe' else native),libs)
else:
    if not options.voice_helper_only:
        for arch in dict.fromkeys(options.architectures):
            add(arch,'wangshu-ime-'+arch+'.dll',['-static-libgcc','-static-libstdc++','-shared','-Wl,--no-undefined','-Wl,--kill-at'],[PROJECT/'windows/text_service.cpp']+native,libs+['-static'])
            add(arch,'wangshu-personal-settings-'+arch+'.exe',['-municode','-mwindows','-static'],[PROJECT/'windows/personal_settings.cpp']+native,['-luser32','-lgdi32','-lcomdlg32','-lshell32','-lole32','-lnormaliz','-lwinpthread'])
    add('x64','wangshu-voice-helper.exe',['-municode','-static'],[PROJECT/'windows/voice_helper.cpp'],['-lwinmm','-lbcrypt'])
before=inputs();compiler_before={arch:{'path':str(path),'sha256':sha(path)} for arch,path in toolchains.items()}
if options.describe_commands:
    print(json.dumps({'schema_version':2,'project_root':str(PROJECT),'output_root':str(output),'commands':commands,'compilers':compiler_before,'execution':'NOT_RUN'},indent=2));raise SystemExit(0)
# Exclusive directory allocation is the overwrite/concurrent-build boundary.
output.mkdir(parents=True,exist_ok=False)
record={'schema_version':2,'project_root':str(PROJECT),'output_root':str(output),'started_utc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'scope':'compile only; Windows execution, registration, GUI and microphone NOT_RUN','tests_build':options.tests,'build_scope':'tests' if options.tests else 'voice-helper-only' if options.voice_helper_only else 'production','inputs_sha256':before,'compilers':compiler_before,'commands':[]}
code=0
for command,name in zip(commands,names):
    print('BUILD '+name,flush=True);entry={'output':name,'command':command,'started_utc':datetime.datetime.now(datetime.timezone.utc).isoformat()}
    try:
        if os.path.lexists(output/name):raise FileExistsError('Unexpected existing output: '+name)
        result=subprocess.run(command,cwd=PROJECT);code=result.returncode
        if not code and not (output/name).is_file():code=66;entry['error']='Compiler reported success without the requested output'
    except OSError as error:code=67;entry['error']=str(error)
    entry['exit_code']=code;record['commands'].append(entry)
    if code:break
try:
    after=inputs();changed=sorted(key for key in before.keys()|after.keys() if before.get(key)!=after.get(key))
except OSError as error:changed=['<input discovery failed>'];record['input_error']=str(error)
compiler_changed=[]
for arch,path in toolchains.items():
    try:
        if sha(path)!=compiler_before[arch]['sha256']:compiler_changed.append(arch)
    except OSError:compiler_changed.append(arch)
if changed or compiler_changed:code=code or 65
record.update(finished_utc=datetime.datetime.now(datetime.timezone.utc).isoformat(),source_stable=not changed,changed_during_build=changed,compilers_stable=not compiler_changed,compilers_changed=compiler_changed,exit_code=code,outputs={name:sha(output/name) for name in names if (output/name).is_file()})
filename='last-tests-build.json' if options.tests else 'last-voice-helper-build.json' if options.voice_helper_only else 'last-production-build.json'
(output/filename).write_text(json.dumps(record,indent=2)+'\n')
if not code:
    (output/('tests-source-attestation.json' if options.tests else 'voice-helper-source-attestation.json' if options.voice_helper_only else 'source-attestation.json')).write_text(json.dumps(record,indent=2)+'\n')
if changed or compiler_changed:print('Source/compiler changed during build; outputs not eligible for packaging',file=sys.stderr)
raise SystemExit(code)
