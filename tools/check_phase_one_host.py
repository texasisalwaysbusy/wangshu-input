#!/usr/bin/env python3
"""Run source-bound host regressions with existing tools and retain all evidence.

No installation, download, deletion, device operation, real Windows execution,
or Android acceptance. Each invocation requires a new evidence directory.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys


def source_files(project):
    excluded={'build','evidence','__pycache__','build-personal','build-portability-fixtures'}
    return {str(p.relative_to(project)):hashlib.sha256(p.read_bytes()).hexdigest()
            for p in sorted(project.rglob('*'))
            if p.is_file() and not any(part in excluded for part in p.relative_to(project).parts)}


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--project',type=Path,default=Path(__file__).resolve().parents[1])
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--sanitizers',action='store_true',help='Also run fixed-seed ASan/UBSan probes; no LeakSanitizer claim')
    args=parser.parse_args()
    if not sys.platform.startswith('linux'):parser.error('this aggregate currently requires Linux host tools; use the native Windows receipt runner for PE tests')
    project=args.project.resolve();output=args.output.absolute()
    if output.exists() or output.is_symlink():parser.error('output must be a new directory')
    output.mkdir(parents=True,exist_ok=False)
    before=source_files(project)
    result={'scope':'Host-only. Linux portable core, extracted methods or synthetic platform boundaries. No Android APK/editor/UI/device/microphone nor Windows PE/TSF runtime acceptance. Finite editorial seed cases are not general-language quality.','inputs_sha256':before,'checks':[],'success':False}
    def run(label,command,timeout=180,env=None):
        command=[str(x) for x in command]
        try:
            finished=subprocess.run(command,capture_output=True,text=True,timeout=timeout,env=env)
            code=finished.returncode;stdout=finished.stdout;stderr=finished.stderr
        except (OSError,subprocess.TimeoutExpired) as failure:
            code=None;stdout='';stderr=str(failure)
        (output/(label+'.stdout.txt')).write_text(stdout,encoding='utf-8')
        (output/(label+'.stderr.txt')).write_text(stderr,encoding='utf-8')
        check={'name':label,'command':command,'returncode':code}
        if env and env.get('ASAN_OPTIONS')=='detect_leaks=0':
            check['environment_overrides']={'ASAN_OPTIONS':'detect_leaks=0'}
            check['limitation']='LeakSanitizer is disabled on this ptrace-constrained host.'
        result['checks'].append(check)
        print(label+': '+('PASS' if code==0 else 'FAIL '+str(code)),flush=True)
        return code==0
    def cpp(label,relative,arguments=(),sanitizers=False):
        binary=output/label
        command=['g++','-std=c++17','-O1' if sanitizers else '-O2','-Wall','-Wextra']
        if sanitizers:command+=['-g','-fsanitize=address,undefined','-fno-omit-frame-pointer']
        command += [project/'core/engine.cpp',project/relative,'-o',binary]
        if run(label+'-compile',command):
            env={**os.environ,'ASAN_OPTIONS':'detect_leaks=0'} if sanitizers else None
            run(label,[binary,*arguments],env=env)
    try:
        cpp('core','tests/core_test.cpp',[project/'data',project/'tests/typing-corpus.tsv'])
        cpp('local-predictions','tests/local_prediction_test.cpp',[project/'data'])
        cpp('prediction-structure','tests/local_prediction_focused_test.cpp',[project/'data'])
        cpp('prediction-han-boundary','tests/local_prediction_han_test.cpp',[project/'data'])
        if args.sanitizers:cpp('prediction-sanitizers','tests/local_prediction_stress_test.cpp',[project/'data'],True)
        run('jni-context-guard',['python3',project/'tests/jni_context_guard_test.py','--project',project,'--output',output/'jni-context-guard'])
        run('android-alignment-fixtures',['python3',project/'tests/android_alignment_test.py','--evidence',output/'android-alignment-fixtures'])
        run('settings',['python3',project/'android/host-tests/settings-backup-20261103/run.py',project,output/'settings'])
        run('editor-boundaries',['python3',project/'android/host-tests/editor-boundary-20261103/run.py',project,output/'editor-boundaries'])
        run('chromium-editor-preedit',['python3',project/'android/host-tests/chromium-editor-20261104/run.py',project,output/'chromium-editor-preedit'])
        run('clipboard-boundaries',['python3',project/'android/host-tests/editor-boundary-20261103/run_clipboard.py',project,output/'clipboard-boundaries'])
        run('settings-boundaries',['python3',project/'android/host-tests/editor-boundary-20261103/run_settings.py',project,output/'settings-boundaries'])
        run('associations',['python3',project/'android/host-tests/association-cancel-20261101/run.py',project,output/'associations'])
        classes=output/'associations/classes'
        extra=[project/'tests/jvm/LocalControllerChecks.java',project/'android/app/src/main/java/org/wangshu/ime/MixedComposition.java']
        if run('local-controller-compile',['java','-m','jdk.compiler/com.sun.tools.javac.Main','-source','17','-target','17','-encoding','UTF-8','-cp',classes,'-d',classes,*extra]):
            run('local-controller',['java','-cp',classes,'org.wangshu.ime.LocalControllerChecks'])
        run('mobile-presentation',['python3',project/'android/host-tests/mobile-toolbar-20261103/run.py',project,output/'mobile-presentation'])
        run('voice-ui',['python3',project/'android/host-tests/voice-ui-20261104/run.py',project,output/'voice-ui'])
        run('voice-safety',['python3',project/'android/host-tests/voice-safety-20261104/run.py',project,output/'voice-safety'])
        run('presentation-policies',['python3',project/'android/host-tests/presentation-policy-20261104/run.py',project,output/'presentation-policies'])
        run('mixed-integration',['python3',project/'android/host-tests/mixed-integration-20261103/run.py',project,output/'mixed-integration'])
        for name in ['buffer_order','completion_reentry','poll_reentry','range_progress','ready_deadline','sapi_reentry']:
            run('windows-voice-'+name,['python3',project/('windows/test_voice_'+name+'_host.py')])
        run('windows-input-guard',['python3',project/'tests/windows_personal_build_guard_test.py'])
        run('windows-build-portability',['python3',project/'tests/windows_build_portability_test.py'])
        run('windows-receipt-contracts',['python3',project/'tests/windows_receipt_tools_test.py','--fixtures',output/'windows-receipt-fixtures'])
        after=source_files(project)
        result['source_unchanged']=before==after
        result['success']=result['source_unchanged'] and all(c['returncode']==0 for c in result['checks'])
    finally:
        (output/'RESULT.json').write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8')
    return 0 if result['success'] else 1


if __name__=='__main__':
    raise SystemExit(main())
