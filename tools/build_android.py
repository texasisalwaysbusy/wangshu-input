#!/usr/bin/env python3
"""Build only with an existing Android toolchain. Never downloads or installs dependencies."""
from pathlib import Path
import argparse, hashlib, json, os, shutil, subprocess, sys, zipfile

ROOT=Path(__file__).resolve().parents[1]
ASR_SHA='274286de363563513036c0afd4efc04590b9bf8ac0a74968fed7d40811fc214c'

def verify_asr(aar):
    if not aar.is_file():return False,'missing ASR-only AAR'
    sha=hashlib.sha256(aar.read_bytes()).hexdigest()
    if aar.stat().st_size==38778359 and sha==ASR_SHA:return True,'historical pinned ASR-only artifact'
    receipt_path=aar.with_name(aar.name+'.receipt.json')
    if not receipt_path.is_file():return False,'rebuilt AAR requires its pinned-source build receipt; not the historical hash'
    try:
        receipt=json.loads(receipt_path.read_text())
        meta=json.loads((ROOT/'docs/speech-dependencies.json').read_text())
        abis={'x86','x86_64','arm64-v8a','armeabi-v7a'}
        if receipt['kind']!='wangshu-asr-only-pinned-source-build' or receipt['asr_only'] is not True or receipt['sha256']!=sha or receipt['bytes']!=aar.stat().st_size or set(receipt['abis'])!=abis:raise ValueError('identity')
        if receipt['source_lock_sha256']!=hashlib.sha256((ROOT/'docs/speech-dependencies.json').read_bytes()).hexdigest() or receipt['java_bindings_archive_sha256']!=meta['sherpa-java-bindings']['sha256']:raise ValueError('source lock')
        entries=dict(receipt['bindings_members_sha256'])
        if set(entries)!={'AndroidManifest.xml','classes.jar','R.txt'}:raise ValueError('bindings')
        if len(receipt['artifacts'])!=4 or {entry['abi'] for entry in receipt['artifacts']}!=abis:raise ValueError('ABIs')
        for entry in receipt['artifacts']:
            if entry['flags']['SHERPA_ONNX_ENABLE_TTS']!='OFF' or entry['flags']['FETCHCONTENT_FULLY_DISCONNECTED']!='ON':raise ValueError('build scope')
            for key,name in [('jni','libsherpa-onnx-jni.so'),('onnxruntime','libonnxruntime.so')]:
                if not entry[key]['load_alignment'] or any(int(value,16)<16384 for value in entry[key]['load_alignment']):raise ValueError('alignment receipt')
                entries['jni/'+entry['abi']+'/'+name]=entry[key]['sha256']
        with zipfile.ZipFile(aar) as archive:
            if len(archive.namelist())!=len(entries) or set(archive.namelist())!=set(entries):raise ValueError('extra/missing archive members')
            if any(hashlib.sha256(archive.read(name)).hexdigest()!=expected for name,expected in entries.items()):raise ValueError('member hash')
        return True,'new ASR-only build with verified output/member hashes and pinned-source receipt; not claimed byte-identical to historical AAR'
    except (OSError,KeyError,TypeError,ValueError,zipfile.BadZipFile):return False,'ASR-only rebuild receipt/content validation failed'

def inspect(flavor='full'):
    sdk=next((Path(os.environ[key]).expanduser().resolve() for key in ('ANDROID_SDK_ROOT','ANDROID_HOME') if os.environ.get(key)),None)
    java_home=Path(os.environ['JAVA_HOME']).expanduser().resolve() if os.environ.get('JAVA_HOME') else None
    java=str(java_home/'bin/java') if java_home else shutil.which('java')
    if java_home and not Path(java).is_file() and (java_home/'bin/java.exe').is_file():java=str(java_home/'bin/java.exe')
    gradle=os.environ.get('WANGSHU_GRADLE') or shutil.which('gradle')
    checks=[]
    def check(name,ok,detail):checks.append({'name':name,'ok':bool(ok),'detail':str(detail)})
    check('java',java and Path(java).is_file(),java or 'Set JAVA_HOME to an existing JDK 17 or newer')
    compiler=False
    if java and Path(java).is_file():
        try:compiler=subprocess.run([java,'--list-modules'],capture_output=True,text=True,timeout=15).stdout.find('jdk.compiler@')>=0
        except (OSError,subprocess.TimeoutExpired):pass
    check('java_compiler_module',compiler,'jdk.compiler must be present; a java executable alone is insufficient')
    check('gradle',gradle and Path(gradle).is_file(),gradle or 'Set WANGSHU_GRADLE to an existing Gradle 8.13 executable')
    check('android_sdk',sdk and sdk.is_dir(),sdk or 'Set ANDROID_SDK_ROOT to an existing SDK')
    for label,relative in [('platform_36','platforms/android-36/android.jar'),('build_tools_35','build-tools/35.0.0/source.properties'),('ndk_27','ndk/27.2.12479018/source.properties'),('cmake_3_22','cmake/3.22.1/source.properties')]:
        check(label,sdk and (sdk/relative).is_file(),sdk/relative if sdk else relative)
    if flavor=='full':
        aar=ROOT/'android/app/libs/sherpa-onnx-asr-only-1.13.6.aar'
        valid,reason=verify_asr(aar)
        check('asr_only_aar',valid,f'{aar}; {reason}; historical SHA256 {ASR_SHA}; universal TTS AAR is not a substitute')
    return {'flavor':flavor,'ready':all(item['ok'] for item in checks),'checks':checks,'dependency_resolution':'offline only','runtime_acceptance':'NOT_RUN','voice_model':'external optional package, not included in APK','offline_cache':'preflight does not certify dependency cache; Gradle offline build may report missing artifacts'},java,gradle,sdk

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--flavor',choices=('full','typingPreview'),default='full')
    parser.add_argument('--preflight',action='store_true')
    parser.add_argument('--receipt',type=Path)
    parser.add_argument('tasks',nargs='*')
    args=parser.parse_args();report,java,gradle,sdk=inspect(args.flavor)
    if args.receipt:
        args.receipt.parent.mkdir(parents=True,exist_ok=True)
        if args.receipt.exists():parser.error('Receipt already exists; choose a new file to preserve earlier results')
        args.receipt.write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report,indent=2))
    if args.preflight:return 0 if report['ready'] else 2
    if not report['ready']:
        print('Build not started. Supply the missing existing toolchain/dependencies; nothing was downloaded or installed.',file=sys.stderr);return 2
    cap=args.flavor[0].upper()+args.flavor[1:]
    tasks=args.tasks or ['assemble'+cap+'Debug','assemble'+cap+'DebugAndroidTest','lint'+cap+'Debug']
    # Prevent this wrapper from becoming a package installer or a device-control shortcut.
    allowed={'assemble'+cap+'Debug','assemble'+cap+'DebugAndroidTest','lint'+cap+'Debug','test'+cap+'DebugUnitTest'}
    if any(task not in allowed for task in tasks):parser.error('Use explicit assemble/lint/unit-test tasks for the selected flavor; install/clean/other tasks are not supported')
    env=os.environ.copy();env['ANDROID_HOME']=env['ANDROID_SDK_ROOT']=str(sdk)
    env.setdefault('ANDROID_USER_HOME',str(ROOT/'.android-user'))
    # Reuse the operator-selected existing cache; never silently select an empty one.
    if not env.get('GRADLE_USER_HOME'):
        env['GRADLE_USER_HOME']=str(Path.home()/'.gradle')
    return subprocess.run([gradle,'--offline','--no-daemon','--console=plain','-Pandroid.builder.sdkDownload=false',*tasks],cwd=ROOT/'android',env=env).returncode

if __name__=='__main__':raise SystemExit(main())
