#!/usr/bin/env python3
"""Compile and run the production JNI bridge using existing Linux JDK/C++ tools.

This is actual JVM/JNI testing, not Android or APK acceptance. Output must be new.
No download, installation, cleanup, or external state changes are performed.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--project',type=Path,default=Path(__file__).resolve().parents[1])
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--java-home',type=Path)
    parser.add_argument('--cxx',default='g++')
    args=parser.parse_args()
    if not sys.platform.startswith('linux'):
        parser.error('this host JNI harness currently requires Linux; Android is not emulated')
    project=args.project.resolve()
    java=shutil.which('java')
    home=args.java_home
    if home is None and os.environ.get('JAVA_HOME'):
        home=Path(os.environ['JAVA_HOME'])
    if home is None:
        if java is None:parser.error('existing JDK required; java not found')
        home=Path(java).resolve().parents[1]
    home=home.resolve();java=home/'bin/java'
    cxx=shutil.which(args.cxx)
    if cxx is None:parser.error('existing C++ compiler not found')
    cxx=Path(cxx).resolve()
    files=[project/'core/engine.cpp',project/'core/engine.h',project/'core/local_predictions.h',project/'core/local_prediction_data.inc',project/'core/mixed_composition.h',project/'android/app/src/main/cpp/bridge.cpp',project/'android/app/src/main/java/org/wangshu/ime/NativeBridge.java',project/'tests/jvm/LocalPredictionJniTest.java',project/'data/pinyin.tsv',project/'data/symbols.tsv']
    for path in [*files,java,home/'include/jni.h',home/'include/linux/jni_md.h']:
        if not path.is_file():parser.error('missing existing input: '+str(path))
    output=args.output.absolute()
    if output.exists() or output.is_symlink():parser.error('output must not already exist')
    output.mkdir(parents=True,exist_ok=False)
    before={str(p.relative_to(project)):digest(p) for p in files}
    tools={str(p):digest(p) for p in [java.resolve(),cxx]}
    classes=output/'classes';classes.mkdir()
    lib=output/'libwangshuime.so'
    commands=[
        [str(cxx),'-std=c++17','-shared','-fPIC','-O2','-Wall','-Wextra','-Werror','-pthread','-I'+str(home/'include'),'-I'+str(home/'include/linux'),'-I'+str(project/'core'),str(project/'core/engine.cpp'),str(project/'android/app/src/main/cpp/bridge.cpp'),'-o',str(lib)],
        [str(java),'-m','jdk.compiler/com.sun.tools.javac.Main','-encoding','UTF-8','-source','17','-target','17','-d',str(classes),str(files[6]),str(files[7])],
        [str(java),'-Xcheck:jni','-Djava.library.path='+str(output),'-cp',str(classes),'org.wangshu.ime.LocalPredictionJniTest',str(project/'data')]
    ]
    receipt={'schema_version':1,'project':str(project),'scope':'Actual Linux JVM/production JNI. Android NOT_RUN; no editor/OS/UI/device acceptance. Finite examples are not language quality evidence.','inputs_sha256':before,'tools_sha256':tools,'runs':[],'success':False}
    try:
        for n,command in enumerate(commands):
            result=subprocess.run(command,capture_output=True,text=True,timeout=180)
            (output/f'{n+1}.stdout.txt').write_text(result.stdout)
            (output/f'{n+1}.stderr.txt').write_text(result.stderr)
            receipt['runs'].append({'command':command,'returncode':result.returncode})
            print(result.stdout,end='');print(result.stderr,end='',file=sys.stderr)
            if result.returncode:return result.returncode
        if before!={str(p.relative_to(project)):digest(p) for p in files}:raise RuntimeError('source changed during checks')
        if tools!={str(p):digest(p) for p in [java.resolve(),cxx]}:raise RuntimeError('tool changed during checks')
        receipt['outputs_sha256']={str(p.relative_to(output)):digest(p) for p in [lib,*sorted(classes.rglob('*.class'))]}
        receipt['success']=True
        return 0
    except Exception as failure:
        receipt['failure']=str(failure)
        raise
    finally:
        (output/'RESULT.json').write_text(json.dumps(receipt,indent=2)+'\n')


if __name__=='__main__':
    raise SystemExit(main())
