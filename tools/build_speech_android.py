#!/usr/bin/env python3
"""Compile pinned, already-staged ASR-only sources using an existing Android NDK. No downloads."""
from pathlib import Path
import argparse,hashlib,json,os,subprocess,zipfile
PROJECT=Path(__file__).resolve().parents[1]
ABIS=['x86','x86_64','arm64-v8a','armeabi-v7a']
def digest(path):
 h=hashlib.sha256()
 with path.open('rb') as stream:
  while block:=stream.read(1048576):h.update(block)
 return h.hexdigest()
def main():
 parser=argparse.ArgumentParser(description=__doc__)
 parser.add_argument('--speech-work',required=True,type=Path);parser.add_argument('--cache',required=True,type=Path)
 parser.add_argument('--sdk',required=True,type=Path);parser.add_argument('--build-root',required=True,type=Path)
 parser.add_argument('--output',required=True,type=Path);parser.add_argument('--jobs',type=int,default=3)
 args=parser.parse_args();work=args.speech_work.resolve();cache=args.cache.resolve();sdk=args.sdk.resolve();build_root=args.build_root.resolve();output=args.output.resolve()
 if args.jobs<1 or args.jobs>8:parser.error('--jobs must be 1..8')
 if build_root.exists() or output.exists() or output.with_name(output.name+'.receipt.json').exists():parser.error('Build/output already exists; choose new paths to preserve earlier results')
 meta=json.loads((PROJECT/'docs/speech-dependencies.json').read_text());receipt=json.loads((work/'SPEECH-SOURCE-RECEIPT.json').read_text())
 if receipt['source_lock_sha256']!=digest(PROJECT/'docs/speech-dependencies.json'):raise RuntimeError('Source lock changed since staging')
 if not receipt.get('staged_source_files'):raise RuntimeError('Missing verified staged source manifest')
 actual={}
 for group in ['source','deps','ort-android']:
  for path in sorted((work/group).rglob('*')):
   if path.is_symlink():raise RuntimeError('Staged source symlink refused: '+str(path))
   if path.is_file():
    resolved=path.resolve()
    if work not in resolved.parents:raise RuntimeError('Staged source resolves outside workspace')
    actual[str(path.relative_to(work))]=digest(path)
 if actual!=receipt['staged_source_files']:
  changed=sorted(key for key in actual.keys()|receipt['staged_source_files'].keys() if actual.get(key)!=receipt['staged_source_files'].get(key))
  raise RuntimeError('Staged source membership/hash changed: '+', '.join(changed[:12]))
 bindings=cache/meta['sherpa-java-bindings']['archive']
 if not bindings.is_file() or digest(bindings)!=meta['sherpa-java-bindings']['sha256']:raise RuntimeError('Pinned Java bindings archive missing or changed')
 suffix='.exe' if os.name=='nt' else '';cmake=sdk/'cmake/3.22.1/bin'/('cmake'+suffix);ninja=cmake.parent/('ninja'+suffix);ndk=sdk/'ndk/27.2.12479018';host='windows-x86_64' if os.name=='nt' else 'linux-x86_64'
 strip=ndk/'toolchains/llvm/prebuilt'/host/'bin'/('llvm-strip'+suffix);readelf=strip.with_name('llvm-readelf'+suffix)
 for tool in [cmake,ninja,strip,readelf]:
  if not tool.is_file():raise RuntimeError('Existing tool missing: '+str(tool))
 build_root.mkdir(parents=True,exist_ok=False);output.parent.mkdir(parents=True,exist_ok=True)
 def run(label,cmd,env=None):
  with (build_root/(label+'.txt')).open('x',encoding='utf-8') as log:
   completed=subprocess.run([str(item) for item in cmd],stdout=log,stderr=subprocess.STDOUT,env=env,text=True)
  if completed.returncode:raise RuntimeError('Build step failed; preserved log '+label)
 def aligned(path):
  result=subprocess.run([str(readelf),'-lW',str(path)],capture_output=True,text=True,check=True)
  loads=[line.split()[-1] for line in result.stdout.splitlines() if line.strip().startswith('LOAD ')]
  if not loads or any(int(value,16)<16384 for value in loads):raise RuntimeError('Native library lacks 16 KiB LOAD alignment: '+str(path))
  return {'path':str(path),'bytes':path.stat().st_size,'sha256':digest(path),'load_alignment':loads}
 artifacts=[]
 for abi in ABIS:
  build=build_root/abi;env={**os.environ,'SHERPA_ONNXRUNTIME_INCLUDE_DIR':str(work/'ort-android/headers'),'SHERPA_ONNXRUNTIME_LIB_DIR':str(work/'ort-android/jni'/abi)}
  flags={'BUILD_SHARED_LIBS':'OFF','SHERPA_ONNX_ENABLE_TTS':'OFF','SHERPA_ONNX_ENABLE_SPEAKER_DIARIZATION':'OFF','SHERPA_ONNX_ENABLE_WEBSOCKET':'OFF','SHERPA_ONNX_ENABLE_PORTAUDIO':'OFF','SHERPA_ONNX_ENABLE_BINARY':'OFF','SHERPA_ONNX_ENABLE_TESTS':'OFF','SHERPA_ONNX_ENABLE_PYTHON':'OFF','SHERPA_ONNX_ENABLE_JNI':'ON','SHERPA_ONNX_ENABLE_C_API':'OFF','SHERPA_ONNX_LINK_LIBSTDCPP_STATICALLY':'OFF','CMAKE_BUILD_TYPE':'Release','CMAKE_TOOLCHAIN_FILE':str(ndk/'build/cmake/android.toolchain.cmake'),'ANDROID_ABI':abi,'ANDROID_PLATFORM':'android-26','ANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES':'ON','CMAKE_CXX_FLAGS':'-DEIGEN_MPL2_ONLY','CMAKE_SHARED_LINKER_FLAGS':'-Wl,-z,max-page-size=16384','FETCHCONTENT_FULLY_DISCONNECTED':'ON'}
  for name,directory in {'kaldi_native_fbank':'kaldi-native-fbank-1.22.3','kaldi_decoder':'kaldi-decoder-0.3.0','simple-sentencepiece':'simple-sentencepiece-0.7','json':'json-3.12.0','kaldifst':'kaldifst-1.8.0','eigen':'eigen-3.4.0','openfst':'openfst-1.8.5-2026-07-09','kissfft':'kissfft-febd4caeed32e33ad8b2e0bb5ea77542c40f18ec'}.items():
   path=work/'deps'/directory
   if not path.is_dir():raise RuntimeError('Missing pinned dependency '+str(path))
   flags['FETCHCONTENT_SOURCE_DIR_'+name.upper()]=str(path)
  print('Configuring and building '+abi,flush=True)
  run(abi+'-configure',[cmake,'-S',work/'source/sherpa-onnx-1.13.6','-B',build,'-G','Ninja','-DCMAKE_MAKE_PROGRAM='+str(ninja),*['-D'+key+'='+value for key,value in flags.items()]],env)
  run(abi+'-build',[cmake,'--build',build,'--target','sherpa-onnx-jni','-j',args.jobs],env)
  libraries=list(build.rglob('libsherpa-onnx-jni.so'))
  if len(libraries)!=1:raise RuntimeError('JNI library count unexpected for '+abi)
  library=libraries[0];run(abi+'-strip',[strip,'--strip-unneeded',library]);ort=work/'ort-android/jni'/abi/'libonnxruntime.so'
  artifacts.append({'abi':abi,'jni':aligned(library),'onnxruntime':aligned(ort),'flags':flags})
 with zipfile.ZipFile(bindings) as original,zipfile.ZipFile(output,'x',zipfile.ZIP_DEFLATED,compresslevel=4) as target:
  def put(name,data):
   info=zipfile.ZipInfo(name,(2023,2,20,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED;info.external_attr=0o100644<<16;target.writestr(info,data)
  binding_hashes={name:hashlib.sha256(original.read(name)).hexdigest() for name in ['AndroidManifest.xml','classes.jar','R.txt']}
  for name in binding_hashes:put(name,original.read(name))
  for entry in artifacts:
   for key in ['jni','onnxruntime']:
    path=Path(entry[key]['path']);put('jni/'+entry['abi']+'/'+path.name,path.read_bytes())
 result={'kind':'wangshu-asr-only-pinned-source-build','asr_only':True,'abis':ABIS,'aar':str(output),'bytes':output.stat().st_size,'sha256':digest(output),'historical_aar_sha256':'274286de363563513036c0afd4efc04590b9bf8ac0a74968fed7d40811fc214c','historical_hash_match':digest(output)=='274286de363563513036c0afd4efc04590b9bf8ac0a74968fed7d40811fc214c','source_lock_sha256':digest(PROJECT/'docs/speech-dependencies.json'),'java_bindings_archive_sha256':meta['sherpa-java-bindings']['sha256'],'source_receipt_sha256':digest(work/'SPEECH-SOURCE-RECEIPT.json'),'bindings_members_sha256':binding_hashes,'artifacts':artifacts,'runtime_acceptance':'NOT_RUN'}
 output.with_name(output.name+'.receipt.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result,indent=2));return 0
if __name__=='__main__':raise SystemExit(main())
