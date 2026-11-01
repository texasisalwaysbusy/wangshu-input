// Owned child process: local CPU ASR and microphone only, no file/audio history.
#ifndef UNICODE
#define UNICODE
#endif
#define _UNICODE
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <mmsystem.h>
#include <bcrypt.h>
#include <atomic>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <string>
#include <thread>
#include <vector>
#include <array>
#include <stdexcept>
#include <cstring>
#include "../third_party/sherpa/c-api.h"

static std::atomic<bool> finishRecording{false},cancelled{false};
static std::string utf8(const std::wstring& w){int n=WideCharToMultiByte(CP_UTF8,WC_ERR_INVALID_CHARS,w.data(),static_cast<int>(w.size()),nullptr,0,nullptr,nullptr);if(!n)throw std::runtime_error("path encoding");std::string s(n,0);WideCharToMultiByte(CP_UTF8,WC_ERR_INVALID_CHARS,w.data(),static_cast<int>(w.size()),s.data(),n,nullptr,nullptr);return s;}
static std::wstring executableDirectory(){std::vector<wchar_t> b(32768);DWORD n=GetModuleFileNameW(nullptr,b.data(),static_cast<DWORD>(b.size()));if(!n||n>=b.size())throw std::runtime_error("module path");return std::filesystem::path(std::wstring(b.data(),n)).parent_path().wstring();}
static bool verify(const std::filesystem::path& path,const char* expected){
    BCRYPT_ALG_HANDLE alg=nullptr;BCRYPT_HASH_HANDLE hash=nullptr;DWORD objectSize=0,n=0;std::array<unsigned char,32> digest{};
    if(BCryptOpenAlgorithmProvider(&alg,BCRYPT_SHA256_ALGORITHM,nullptr,0)<0)return false;
    bool ok=BCryptGetProperty(alg,BCRYPT_OBJECT_LENGTH,reinterpret_cast<PUCHAR>(&objectSize),sizeof(objectSize),&n,0)>=0;
    std::vector<unsigned char> object(objectSize),buffer(65536);if(ok)ok=BCryptCreateHash(alg,&hash,object.data(),objectSize,nullptr,0,0)>=0;
    std::ifstream in(path,std::ios::binary);if(!in)ok=false;
    while(ok&&in){in.read(reinterpret_cast<char*>(buffer.data()),buffer.size());auto count=in.gcount();if(count>0)ok=BCryptHashData(hash,buffer.data(),static_cast<ULONG>(count),0)>=0;}
    if(ok)ok=BCryptFinishHash(hash,digest.data(),digest.size(),0)>=0;
    if(hash)BCryptDestroyHash(hash);
    BCryptCloseAlgorithmProvider(alg,0);
    const char* hex="0123456789abcdef";std::string actual;for(auto c:digest){actual+=hex[c>>4];actual+=hex[c&15];}return ok&&actual==expected;
}
#define API(name) decltype(&name) p##name=nullptr
struct Asr {
 HMODULE module=nullptr;const SherpaOnnxOnlineRecognizer* recognizer=nullptr;const SherpaOnnxOnlineStream* stream=nullptr;
 API(SherpaOnnxCreateOnlineRecognizer);API(SherpaOnnxDestroyOnlineRecognizer);API(SherpaOnnxCreateOnlineStream);API(SherpaOnnxDestroyOnlineStream);API(SherpaOnnxOnlineStreamAcceptWaveform);API(SherpaOnnxIsOnlineStreamReady);API(SherpaOnnxDecodeOnlineStream);API(SherpaOnnxGetOnlineStreamResult);API(SherpaOnnxDestroyOnlineRecognizerResult);API(SherpaOnnxOnlineStreamInputFinished);API(SherpaOnnxReadWave);API(SherpaOnnxFreeWave);
 ~Asr(){if(stream)pSherpaOnnxDestroyOnlineStream(stream);if(recognizer)pSherpaOnnxDestroyOnlineRecognizer(recognizer);if(module)FreeLibrary(module);}
 void load(const std::filesystem::path& base,const std::filesystem::path& models){
  const char* names[]={"encoder.onnx","decoder.onnx","joiner.onnx","tokens.txt"};const char* hashes[]={"8fa764187a261844f859d7143ebaa563af5d10adfece4c18a8f414c88cba2a9b","2e3b5ec371f8899ee6acd829fd753ba45772df57a91bdf37cde3136354e7db7d","1ed689c5ed19dbaa725d9d191bb4822b5f4855a39e1ffd28cbc1f340d25b2ee0","a8e0e4ec53810e433789b54a5c0134a7eaa2ffca595a6334d54c00da858841d3"};
  std::string paths[4];for(int i=0;i<4;i++){auto path=models/names[i];if(!verify(path,hashes[i]))throw std::runtime_error("voice model SHA-256 mismatch or absent");paths[i]=utf8(path.wstring());}
  auto dll=base/L"sherpa-onnx-c-api.dll";module=LoadLibraryExW(dll.c_str(),nullptr,LOAD_LIBRARY_SEARCH_DLL_LOAD_DIR|LOAD_LIBRARY_SEARCH_DEFAULT_DIRS);if(!module)throw std::runtime_error("CPU runtime load failed");
#define GET(name) do{FARPROC symbol=GetProcAddress(module,#name);static_assert(sizeof(symbol)==sizeof(p##name));std::memcpy(&p##name,&symbol,sizeof(symbol));}while(false);if(!p##name)throw std::runtime_error("runtime API mismatch")
 GET(SherpaOnnxCreateOnlineRecognizer);GET(SherpaOnnxDestroyOnlineRecognizer);GET(SherpaOnnxCreateOnlineStream);GET(SherpaOnnxDestroyOnlineStream);GET(SherpaOnnxOnlineStreamAcceptWaveform);GET(SherpaOnnxIsOnlineStreamReady);GET(SherpaOnnxDecodeOnlineStream);GET(SherpaOnnxGetOnlineStreamResult);GET(SherpaOnnxDestroyOnlineRecognizerResult);GET(SherpaOnnxOnlineStreamInputFinished);GET(SherpaOnnxReadWave);GET(SherpaOnnxFreeWave);
#undef GET
  SherpaOnnxOnlineRecognizerConfig c{};c.feat_config.sample_rate=16000;c.feat_config.feature_dim=80;c.model_config.transducer.encoder=paths[0].c_str();c.model_config.transducer.decoder=paths[1].c_str();c.model_config.transducer.joiner=paths[2].c_str();c.model_config.tokens=paths[3].c_str();c.model_config.num_threads=2;c.model_config.provider="cpu";c.model_config.model_type="zipformer";c.decoding_method="greedy_search";
  recognizer=pSherpaOnnxCreateOnlineRecognizer(&c);if(!recognizer)throw std::runtime_error("recognizer creation failed");stream=pSherpaOnnxCreateOnlineStream(recognizer);if(!stream)throw std::runtime_error("stream creation failed");
 }
 void accept(const float* samples,int n,int rate=16000){pSherpaOnnxOnlineStreamAcceptWaveform(stream,rate,samples,n);while(!cancelled&&pSherpaOnnxIsOnlineStreamReady(recognizer,stream))pSherpaOnnxDecodeOnlineStream(recognizer,stream);}
 std::string result(){std::vector<float> silence(8000);accept(silence.data(),silence.size());pSherpaOnnxOnlineStreamInputFinished(stream);while(!cancelled&&pSherpaOnnxIsOnlineStreamReady(recognizer,stream))pSherpaOnnxDecodeOnlineStream(recognizer,stream);const auto* r=pSherpaOnnxGetOnlineStreamResult(recognizer,stream);std::string text=r&&r->text?r->text:"";if(r)pSherpaOnnxDestroyOnlineRecognizerResult(r);return text;}
};
struct Microphone {
 HWAVEIN handle=nullptr;std::array<WAVEHDR,4> headers{};std::array<std::array<short,1600>,4> data{};
 ~Microphone(){if(handle){waveInReset(handle);for(auto& h:headers)if(h.dwFlags&WHDR_PREPARED)waveInUnprepareHeader(handle,&h,sizeof(h));waveInClose(handle);}}
 void start(){WAVEFORMATEX f{};f.wFormatTag=WAVE_FORMAT_PCM;f.nChannels=1;f.nSamplesPerSec=16000;f.wBitsPerSample=16;f.nBlockAlign=2;f.nAvgBytesPerSec=32000;if(waveInOpen(&handle,WAVE_MAPPER,&f,0,0,CALLBACK_NULL)!=MMSYSERR_NOERROR)throw std::runtime_error("microphone unavailable; check Windows permission");for(size_t i=0;i<headers.size();i++){headers[i].lpData=reinterpret_cast<char*>(data[i].data());headers[i].dwBufferLength=data[i].size()*2;if(waveInPrepareHeader(handle,&headers[i],sizeof(WAVEHDR))!=MMSYSERR_NOERROR||waveInAddBuffer(handle,&headers[i],sizeof(WAVEHDR))!=MMSYSERR_NOERROR)throw std::runtime_error("microphone buffer failed");}if(waveInStart(handle)!=MMSYSERR_NOERROR)throw std::runtime_error("microphone start failed");}
 void record(Asr& asr){start();ULONGLONG deadline=GetTickCount64()+30000;while(!finishRecording&&!cancelled&&GetTickCount64()<deadline){bool available=false;for(auto& h:headers)if(h.dwFlags&WHDR_DONE){available=true;std::vector<float> samples(h.dwBytesRecorded/2);auto* pcm=reinterpret_cast<short*>(h.lpData);for(size_t i=0;i<samples.size();i++)samples[i]=pcm[i]/32768.f;asr.accept(samples.data(),samples.size());h.dwBytesRecorded=0;if(waveInAddBuffer(handle,&h,sizeof(h))!=MMSYSERR_NOERROR)throw std::runtime_error("microphone requeue failed");}if(!available)Sleep(10);}waveInStop(handle);waveInReset(handle);if(!cancelled)for(auto& h:headers)if((h.dwFlags&WHDR_DONE)&&h.dwBytesRecorded){std::vector<float> samples(h.dwBytesRecorded/2);auto* pcm=reinterpret_cast<short*>(h.lpData);for(size_t i=0;i<samples.size();i++)samples[i]=pcm[i]/32768.f;asr.accept(samples.data(),samples.size());h.dwBytesRecorded=0;}}
};
int wmain(int argc,wchar_t** argv){
 try{
  auto base=std::filesystem::path(executableDirectory());bool fixture=argc==5&&std::wstring(argv[1])==L"--wav";
  if(argc!=1&&!fixture){std::cerr<<"usage: voice-helper [--wav public.wav --models folder]\n";return 2;}
  if(fixture&&std::wstring(argv[3])!=L"--models")return 2;
  if(!fixture){std::thread([]{char c=0;DWORD n=0;HANDLE input=GetStdHandle(STD_INPUT_HANDLE);while(ReadFile(input,&c,1,&n,nullptr)&&n){if(c=='F'){finishRecording=true;return;}if(c=='C'){cancelled=true;return;}}cancelled=true;}).detach();}
  Asr asr;asr.load(base,fixture?std::filesystem::path(argv[4]):base/L"models");
  if(fixture){auto path=utf8(argv[2]);const auto* wave=asr.pSherpaOnnxReadWave(path.c_str());if(!wave)throw std::runtime_error("fixture WAV unreadable");asr.accept(wave->samples,wave->num_samples,wave->sample_rate);asr.pSherpaOnnxFreeWave(wave);}else if(!cancelled&&!finishRecording){std::cout<<"READY\n"<<std::flush;Microphone mic;mic.record(asr);}
  if(!cancelled){auto text=asr.result();if(text.size()>16384)throw std::runtime_error("result too large");for(char& c:text)if(c=='\r'||c=='\n')c=' ';std::cout<<"RESULT "<<text<<"\n"<<std::flush;}return cancelled?3:0;
 }catch(const std::exception& e){std::cout<<"ERROR "<<e.what()<<"\n"<<std::flush;return 1;}catch(...){std::cout<<"ERROR local recognition failed\n"<<std::flush;return 1;}
}
