#!/usr/bin/env python3
"""Production-method JVM/C++ synthetic host regressions. No Android/Windows native claim.
Usage: python3 run.py PROJECT NEW_OUTPUT_DIRECTORY
Only writes under NEW_OUTPUT_DIRECTORY. Requires existing python3, java (JDK), and g++.
"""
from pathlib import Path
import subprocess,sys,json,hashlib
here=Path(__file__).resolve().parent
if len(sys.argv)!=3:raise SystemExit(__doc__)
project,out=map(lambda x:Path(x).resolve(),sys.argv[1:]);out.mkdir(parents=True,exist_ok=False)
checks=['entry','policy','privacy_reentry','render','windows_mixed','windows_async','bound_commit'];results={}
for name in checks:
 script=here/('run_'+name+'_checks.py');x=subprocess.run([sys.executable,str(script),str(project),str(out/name)],capture_output=True,text=True)
 (out/(name+'.txt')).write_text(x.stdout+x.stderr);print(x.stdout+x.stderr,end='');results[name]=x.returncode
paths=['android/app/src/main/java/org/wangshu/ime/WangshuIme.java','android/app/src/main/java/org/wangshu/ime/PersonalPhraseAssociation.java','android/app/src/main/java/org/wangshu/ime/PendingTextCommit.java','android/app/src/main/java/org/wangshu/ime/MixedComposition.java','windows/text_service.cpp','core/mixed_composition.h']
(out/'RESULT.json').write_text(json.dumps({'scope':'Actual production methods/controllers with synthetic Android and TSF boundaries. Native build/device/UI/IPC NOT_RUN.','results':results,'source_sha256':{name:hashlib.sha256((project/name).read_bytes()).hexdigest() for name in paths},'test_sha256':{str(p.relative_to(here)):hashlib.sha256(p.read_bytes()).hexdigest() for p in here.rglob('*') if p.is_file()}},indent=2)+'\n')
raise SystemExit(1 if any(results.values()) else 0)
