"""Portable build CLI contract; owned fake compilers only. No MinGW/Wine/install/deletion."""
from pathlib import Path
import hashlib,json,os,subprocess,sys,tempfile
PROJECT=Path(__file__).resolve().parents[1]
base=PROJECT/'windows/build-portability-fixtures';base.mkdir(parents=True,exist_ok=True)
root=Path(tempfile.mkdtemp(prefix='retained-',dir=base));project=root/'arbitrary-project-name';project.mkdir()
for folder in ['tools','windows','core','staging/personal-native','third_party/nlohmann','third_party/sherpa','tests','data']:(project/folder).mkdir(parents=True)
(project/'tools/build_windows_personal.py').write_bytes((PROJECT/'tools/build_windows_personal.py').read_bytes())
for name in ['tools/build_windows.sh','windows/dll_smoke.cpp','tests/tsf_edit_test.cpp','tests/windows_personal_store_test.cpp','tests/windows_personal_tsf_test.cpp','windows/phrase_association_tsf_test.cpp','windows/personal_store.cpp','staging/personal-native/personal_data.cpp','core/engine.cpp','windows/text_service.cpp','windows/personal_settings.cpp','windows/voice_helper.cpp','third_party/sherpa/c-api.h','third_party/nlohmann/json.hpp','data/synthetic.tsv']:(project/name).write_text('// synthetic owned fixture\n')
compiler=root/'fake-compiler.py';compiler.write_text('''#!/usr/bin/env python3
import os,sys
from pathlib import Path
args=sys.argv[1:];log=Path(os.environ['MOCK_LOG'])
with log.open('a') as f:f.write(repr(args)+'\\n')
action=os.environ.get('MOCK_ACTION','')
if action=='fail':raise SystemExit(9)
if action!='missing':Path(args[args.index('-o')+1]).write_text('OWNED MOCK ARTIFACT, NOT A PE BINARY\\n')
if action=='mutate-source':
 p=Path(os.environ['MOCK_SOURCE']);p.write_text(p.read_text()+'// changed during mock compile\\n')
if action=='mutate-compiler':
 p=Path(__file__);p.write_text(p.read_text()+'# changed during mock compile\\n')
''');compiler.chmod(0o700)
log=root/'mock-command-log.txt';environment=dict(os.environ,MOCK_LOG=str(log));checks=[]
def check(label,value):checks.append({'label':label,'state':'PASS' if value else 'FAIL'})
def run(*args,action=''):
 env=dict(environment,MOCK_ACTION=action,MOCK_SOURCE=str(project/'core/engine.cpp'))
 return subprocess.run([sys.executable,str(project/'tools/build_windows_personal.py'),*map(str,args)],env=env,capture_output=True,text=True)
def opts(out):return ['--tests','--cxx-x64',compiler,'--output',root/out]
def invocations():return len(log.read_text().splitlines()) if log.exists() else 0
r=run('--describe-inputs');d=json.loads(r.stdout)
check('arbitrary project directory resolves from script',r.returncode==0 and 'windows/phrase_association_tsf_test.cpp' in d)
check('describe inputs writes no output and executes no compiler',not (project/'windows/build').exists() and invocations()==0)
r=run(*opts('plan'),'--describe-commands');plan=json.loads(r.stdout)
check('plan has all five Windows targets including association TSF',r.returncode==0 and len(plan['commands'])==5 and any('phrase-association-tsf-test.exe' in c[c.index('-o')+1] for c in plan['commands']))
assoc=next(c for c in plan['commands'] if 'phrase-association-tsf-test.exe' in c[c.index('-o')+1])
check('association target links real TSF dependency sources',all(str(project/p) in assoc for p in ['windows/phrase_association_tsf_test.cpp','windows/personal_store.cpp','staging/personal-native/personal_data.cpp','core/engine.cpp']))
check('plan executes nothing and creates no output directory',invocations()==0 and not (root/'plan').exists())
r=run(*opts('success'));receipt=json.loads((root/'success/tests-source-attestation.json').read_text())
check('mock tests build produces all five distinct outputs',r.returncode==0 and len(receipt['outputs'])==5 and invocations()==5)
check('receipt records portable roots, source and compiler hashes',receipt['schema_version']==2 and receipt['project_root']==str(project) and receipt['source_stable'] and receipt['compilers_stable'] and receipt['compilers']['x64']['sha256']==hashlib.sha256(compiler.read_bytes()).hexdigest())
count=invocations();before=(root/'success/tests-source-attestation.json').read_bytes();r=run(*opts('success'))
check('existing output rejected before compiler invocation',r.returncode!=0 and invocations()==count and (root/'success/tests-source-attestation.json').read_bytes()==before)
r=run('--tests','--cxx-x64',root/'absent-compiler','--output',root/'absent-tool')
check('absent explicit compiler fails without output creation',r.returncode!=0 and not (root/'absent-tool').exists() and invocations()==count)
r=run('--tests','--cxx-x64',compiler)
check('output is explicit and required',r.returncode!=0 and invocations()==count)
r=run('--tests','--voice-helper-only','--cxx-x64',compiler,'--output',root/'conflict')
check('conflicting scopes fail before output creation',r.returncode!=0 and not (root/'conflict').exists())
r=run('--voice-helper-only','--cxx-x64',compiler,'--output',root/'helper')
check('helper-only needs x64 only and compiles exactly once',r.returncode==0 and invocations()==count+1 and (root/'helper/voice-helper-source-attestation.json').is_file())
r=run('--architectures','x86','--cxx-x64',compiler,'--cxx-x86',compiler,'--output',root/'x86-plan','--describe-commands');plan=json.loads(r.stdout)
check('selected x86 production still explicitly plans x64 voice helper',r.returncode==0 and len(plan['commands'])==3 and set(plan['compilers'])=={'x64','x86'} and not (root/'x86-plan').exists())
r=run(*opts('failed'),action='fail');receipt=json.loads((root/'failed/last-tests-build.json').read_text())
check('compiler failure preserves receipt without success attestation',r.returncode==9 and len(receipt['commands'])==1 and not (root/'failed/tests-source-attestation.json').exists())
r=run(*opts('missing'),action='missing');receipt=json.loads((root/'missing/last-tests-build.json').read_text())
check('successful compiler with no artifact fails closed',r.returncode==66 and receipt['exit_code']==66 and not (root/'missing/tests-source-attestation.json').exists())
r=run(*opts('changed-source'),action='mutate-source');receipt=json.loads((root/'changed-source/last-tests-build.json').read_text())
check('source mutation fails attestation',r.returncode==65 and not receipt['source_stable'] and 'core/engine.cpp' in receipt['changed_during_build'] and not (root/'changed-source/tests-source-attestation.json').exists())
r=run(*opts('changed-tool'),action='mutate-compiler');receipt=json.loads((root/'changed-tool/last-tests-build.json').read_text())
check('compiler mutation fails attestation',r.returncode==65 and not receipt['compilers_stable'] and receipt['compilers_changed']==['x64'] and not (root/'changed-tool/tests-source-attestation.json').exists())
required=project/'windows/phrase_association_tsf_test.cpp';parked=required.with_suffix('.parked');required.rename(parked);count=invocations();r=run(*opts('missing-source'))
check('missing association source fails before output creation',r.returncode!=0 and invocations()==count and not (root/'missing-source').exists());parked.rename(required)
link=root/'dangling-output';link.symlink_to(root/'never-created');r=run(*opts('dangling-output'))
check('dangling output symlink is rejected',r.returncode!=0 and not (root/'never-created').exists() and invocations()==count)
failed=sum(x['state']=='FAIL' for x in checks)
print(json.dumps({'state':'FAIL' if failed else 'PASS','passed':len(checks)-failed,'failed':failed,'checks':checks,'fixture_root':str(root),'fixtures_preserved':True,'scope':'Owned Python mock compiler only; Windows compilation/runtime/GUI NOT_RUN; no installs/deletion'},indent=2));raise SystemExit(bool(failed))
