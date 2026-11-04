"""Actual discovery CLI on an isolated retained repository; no live-source edits or deletion."""
from pathlib import Path
import hashlib,json,subprocess,sys,tempfile

PROJECT=Path(__file__).resolve().parents[1]
base=PROJECT/'windows/build-personal/guard-fixtures';base.mkdir(parents=True,exist_ok=True)
root=Path(tempfile.mkdtemp(prefix='dependency-check-',dir=base));project=root/'projects/input-method'
(project/'tools').mkdir(parents=True)
for folder in ['windows','core','staging/personal-native','third_party/nlohmann','third_party/sherpa','data','tests']:(project/folder).mkdir(parents=True)
for name,text in [('tools/build_windows_personal.py',(PROJECT/'tools/build_windows_personal.py').read_text()),('tools/build_windows.sh','existing shell wrapper'),('windows/text_service.cpp','// fake owned fixture'),('windows/voice_process.h','// fake voice child process header'),('core/engine.h','// fake core fixture'),('third_party/nlohmann/json.hpp','// fake vendor header'),('third_party/sherpa/c-api.h','// fake sherpa header A'),('data/words.tsv','nihao\t你好'),('tests/tsf_fixture.cpp','// fake Windows-only test')]:
    (project/name).write_text(text)
checks=[]
def check(label,value):checks.append({'label':label,'state':'PASS' if value else 'FAIL'})
def command(*extra):return [sys.executable,str(project/'tools/build_windows_personal.py'),'--describe-inputs','--output',str(project/'windows/build'),*extra]
def discover(*extra):
    result=subprocess.run(command(*extra),capture_output=True,text=True,check=True);return json.loads(result.stdout)
first=discover();header='third_party/nlohmann/json.hpp';sherpa='third_party/sherpa/c-api.h'
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
check('actual .hpp vendor dependency is enumerated',header in first)
check('vendor fingerprint equals actual fixture bytes',first.get(header)==sha(project/'third_party/nlohmann/json.hpp'))
(project/'third_party/nlohmann/json.hpp').write_text('// modified fake vendor header');changed=discover()
check('changed isolated vendor bytes alter the guarded hash',changed.get(header)!=first.get(header))
added=project/'windows/added.hpp';added.write_text('// additional owned source header');expanded=discover()
check('new source membership is detected','windows/added.hpp' in expanded and set(expanded)!=set(changed))
retained=added.with_suffix('.parked');added.rename(retained)
check('removed membership is detected without deleting fixture bytes',set(discover())==set(changed) and retained.read_text()=='// additional owned source header')
check('required Sherpa C API dependency is enumerated',sherpa in first)
check('Sherpa fingerprint equals actual fixture bytes',first.get(sherpa)==sha(project/'third_party/sherpa/c-api.h'))
(project/'third_party/sherpa/c-api.h').write_text('// fake sherpa header B');modified=discover()
check('same-size Sherpa header content change alters attestation',modified.get(sherpa)!=first.get(sherpa))
check('existing voice process header remains covered','windows/voice_process.h' in modified)
check('Windows tests mode includes its C++ fixture','tests/tsf_fixture.cpp' in discover('--tests'))
required=project/'third_party/sherpa/c-api.h';parked=required.with_suffix('.h.parked');required.rename(parked)
absent=subprocess.run(command(),capture_output=True,text=True)
check('missing required header fails discovery instead of silently dropping dependency',absent.returncode!=0)
parked.rename(required);restored=discover()
check('restoring retained header restores exact input set and fingerprint',restored==modified)
check('unchanged discovery is deterministic',discover()==restored)
(project/'windows/build').mkdir(parents=True,exist_ok=True)
(project/'windows/build/generated.hpp').write_text('// generated build output is not a production input')
check('generated build artifacts do not enter source membership',discover()==restored)
failed=sum(c['state']=='FAIL' for c in checks)
print(json.dumps({'state':'FAIL' if failed else 'PASS','passed':len(checks)-failed,'failed':failed,'checks':checks,'fixture_root':str(root),'fixtures_preserved':True,'scope':'actual discovery CLI on retained synthetic repository; no compilation/install/live-source edits/deletion'},indent=2))
raise SystemExit(1 if failed else 0)
