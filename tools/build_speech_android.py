"""Build CPU ASR-only JNI from the pinned local official sources; never downloads."""
from pathlib import Path
import os,subprocess,shutil,json,hashlib,zipfile
repo=Path(__file__).resolve().parents[3]
project=repo/'projects/input-method'
source=repo/'.tools/sherpa/source/sherpa-onnx-1.13.6'
deps=repo/'.tools/sherpa/deps'
cmake=repo/'.tools/android-sdk/cmake/3.22.1/bin/cmake'
ndk=repo/'.tools/android-sdk/ndk/27.2.12479018'
artifacts=[]
for abi in ['x86','x86_64','arm64-v8a','armeabi-v7a']:
 build=repo/'.tools/sherpa/build-asr'/abi;build.mkdir(parents=True,exist_ok=True)
 env={**os.environ,'SHERPA_ONNXRUNTIME_INCLUDE_DIR':str(repo/'.tools/sherpa/ort-android/headers'),'SHERPA_ONNXRUNTIME_LIB_DIR':str(repo/'.tools/sherpa/ort-android/jni'/abi)}
 flags={'BUILD_SHARED_LIBS':'OFF','SHERPA_ONNX_ENABLE_TTS':'OFF','SHERPA_ONNX_ENABLE_SPEAKER_DIARIZATION':'OFF','SHERPA_ONNX_ENABLE_WEBSOCKET':'OFF','SHERPA_ONNX_ENABLE_PORTAUDIO':'OFF','SHERPA_ONNX_ENABLE_BINARY':'OFF','SHERPA_ONNX_ENABLE_TESTS':'OFF','SHERPA_ONNX_ENABLE_PYTHON':'OFF','SHERPA_ONNX_ENABLE_JNI':'ON','SHERPA_ONNX_ENABLE_C_API':'OFF','SHERPA_ONNX_LINK_LIBSTDCPP_STATICALLY':'OFF','CMAKE_BUILD_TYPE':'Release','CMAKE_TOOLCHAIN_FILE':str(ndk/'build/cmake/android.toolchain.cmake'),'ANDROID_ABI':abi,'ANDROID_PLATFORM':'android-26','ANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES':'ON','CMAKE_CXX_FLAGS':'-DEIGEN_MPL2_ONLY','CMAKE_SHARED_LINKER_FLAGS':'-Wl,-z,max-page-size=16384','FETCHCONTENT_FULLY_DISCONNECTED':'ON'}
 for name,directory in {'kaldi_native_fbank':'kaldi-native-fbank-1.22.3','kaldi_decoder':'kaldi-decoder-0.3.0','simple-sentencepiece':'simple-sentencepiece-0.7','json':'json-3.12.0','kaldifst':'kaldifst-1.8.0','eigen':'eigen-3.4.0','openfst':'openfst-1.8.5-2026-07-09','kissfft':'kissfft-febd4caeed32e33ad8b2e0bb5ea77542c40f18ec'}.items():
  path=deps/directory
  if not path.is_dir():raise RuntimeError('Missing preverified source '+str(path))
  flags['FETCHCONTENT_SOURCE_DIR_'+name.upper()]=str(path)
 print('Configuring '+abi,flush=True)
 subprocess.run([str(cmake),'-S',str(source),'-B',str(build),'-G','Ninja','-DCMAKE_MAKE_PROGRAM='+str(cmake.parent/'ninja'),*['-D'+k+'='+v for k,v in flags.items()]],env=env,check=True)
 subprocess.run([str(cmake),'--build',str(build),'--target','sherpa-onnx-jni','-j','3'],env=env,check=True)
 lib=build/'lib/libsherpa-onnx-jni.so'
 if not lib.exists():lib=next(build.rglob('libsherpa-onnx-jni.so'))
 subprocess.run([str(ndk/'toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip'),'--strip-unneeded',str(lib)],check=True)
 artifacts.append((abi,lib,repo/'.tools/sherpa/ort-android/jni'/abi/'libonnxruntime.so'))
 print('Built '+abi,flush=True)
# Retain only upstream Java bindings plus our ASR-only native builds.
original=repo/'.tools/downloads/sherpa-onnx-static-link-onnxruntime-1.13.6.aar'
dest=project/'android/app/libs/sherpa-onnx-asr-only-1.13.6.aar'
with zipfile.ZipFile(original) as src,zipfile.ZipFile(dest,'w',zipfile.ZIP_DEFLATED) as dst:
 for name in ['AndroidManifest.xml','classes.jar','R.txt']:dst.writestr(name,src.read(name))
 for abi,lib,ort in artifacts:
  dst.write(lib,'jni/'+abi+'/'+lib.name);dst.write(ort,'jni/'+abi+'/'+ort.name)
print('ASR-only AAR',dest.stat().st_size,hashlib.sha256(dest.read_bytes()).hexdigest())
