// Production microphone methods with in-process WinMM doubles. No device calls.
#include <windows.h>
#include <mmsystem.h>
#include <vector>
#include <sstream>
#include <iostream>
#include <stdexcept>
#include <array>
#include <algorithm>

enum class Scenario { Normal, StopError, ResetError, UnprepareError, CloseError, PrepareError, Cancel };
static Scenario scenario;
static std::vector<LPWAVEHDR> queued;
static std::vector<int> decoded;
static int prepares=0,stops=0,resets=0,unprepares=0,closes=0;
static HWAVEIN syntheticHandle=reinterpret_cast<HWAVEIN>(1);
static MMRESULT WINAPI syntheticOpen(LPHWAVEIN handle,UINT,LPCWAVEFORMATEX,DWORD_PTR,DWORD_PTR,DWORD){
    *handle=syntheticHandle;return MMSYSERR_NOERROR;
}
static MMRESULT WINAPI syntheticPrepare(HWAVEIN,LPWAVEHDR header,UINT){
    ++prepares;
    if(scenario==Scenario::PrepareError&&prepares==2)return MMSYSERR_ERROR;
    header->dwFlags|=WHDR_PREPARED;return MMSYSERR_NOERROR;
}
static MMRESULT WINAPI syntheticAdd(HWAVEIN,LPWAVEHDR header,UINT){
    header->dwFlags=(header->dwFlags&~WHDR_DONE)|WHDR_INQUEUE;
    queued.push_back(header);return MMSYSERR_NOERROR;
}
static MMRESULT WINAPI syntheticStart(HWAVEIN){return MMSYSERR_NOERROR;}
static MMRESULT WINAPI syntheticStop(HWAVEIN){
    ++stops;return scenario==Scenario::StopError?MMSYSERR_ERROR:MMSYSERR_NOERROR;
}
static MMRESULT WINAPI syntheticReset(HWAVEIN){
    ++resets;
    if(scenario==Scenario::ResetError)return MMSYSERR_ERROR;
    int value=0;
    for(auto header:queued){
        auto samples=reinterpret_cast<short*>(header->lpData);
        samples[0]=static_cast<short>(++value);
        header->dwBytesRecorded=2;
        header->dwFlags=(header->dwFlags&~WHDR_INQUEUE)|WHDR_DONE;
    }
    queued.clear();return MMSYSERR_NOERROR;
}
static MMRESULT WINAPI syntheticUnprepare(HWAVEIN,LPWAVEHDR header,UINT){
    ++unprepares;
    if((header->dwFlags&WHDR_INQUEUE)||(scenario==Scenario::UnprepareError&&unprepares==1))return WAVERR_STILLPLAYING;
    header->dwFlags&=~WHDR_PREPARED;return MMSYSERR_NOERROR;
}
static MMRESULT WINAPI syntheticClose(HWAVEIN){
    ++closes;
    return scenario==Scenario::CloseError||!queued.empty()?WAVERR_STILLPLAYING:MMSYSERR_NOERROR;
}
#define waveInOpen syntheticOpen
#define waveInPrepareHeader syntheticPrepare
#define waveInAddBuffer syntheticAdd
#define waveInStart syntheticStart
#define waveInStop syntheticStop
#define waveInReset syntheticReset
#define waveInUnprepareHeader syntheticUnprepare
#define waveInClose syntheticClose
#define wmain unused_voice_helper_entry
#include "voice_helper.cpp"
#undef wmain
#undef waveInOpen
#undef waveInPrepareHeader
#undef waveInAddBuffer
#undef waveInStart
#undef waveInStop
#undef waveInReset
#undef waveInUnprepareHeader
#undef waveInClose

static void acceptSynthetic(const SherpaOnnxOnlineStream*,int32_t rate,const float* samples,int32_t n){
    if(rate!=16000||n!=1)throw std::runtime_error("unexpected fixture format");
    decoded.push_back(static_cast<int>(samples[0]*32768.f));
}
static int32_t notReady(const SherpaOnnxOnlineRecognizer*,const SherpaOnnxOnlineStream*){return 0;}
static void reset(Scenario value){
    scenario=value;queued.clear();decoded.clear();prepares=stops=resets=unprepares=closes=0;
    finishRecording=false;cancelled=false;
}
static bool successfulCapture(){
    try{
        Asr decoder;
        decoder.pSherpaOnnxOnlineStreamAcceptWaveform=acceptSynthetic;
        decoder.pSherpaOnnxIsOnlineStreamReady=notReady;
        std::ostringstream output;PreviewReporter preview(output);
        {
            Microphone mic;
            mic.start();
            // Synthetic finish before the first poll; reset returns queued PCM.
            finishRecording=true;
            if(scenario==Scenario::Cancel)cancelled=true;
            mic.record(decoder,preview);
            mic.closeConfirmed();
        }
        return true;
    }catch(const std::runtime_error&){return false;}
}
int main(){
    int passed=0,failed=0;
    auto check=[&](bool ok,const char* label){
        std::cout<<(ok?"PASS ":"FAIL ")<<label<<" stop="<<stops<<" reset="<<resets
                 <<" unprepare="<<unprepares<<" close="<<closes<<" decoded="<<decoded.size()<<"\n";
        (ok?passed:failed)++;
    };
    reset(Scenario::Normal);
    check(successfulCapture()&&closes==1&&unprepares==4&&decoded==std::vector<int>({1,2,3,4}),
          "normal ordered tail drained and all four buffers closed");
    reset(Scenario::StopError);
    check(!successfulCapture()&&resets>=1&&closes==1,"stop failure cannot report successful capture");
    reset(Scenario::ResetError);
    check(!successfulCapture()&&closes==1&&decoded.empty()&&unprepares==0,
          "reset failure suppresses final data and does not unprepare still-owned buffers");
    reset(Scenario::UnprepareError);
    check(!successfulCapture()&&closes==1,"unprepare failure cannot report successful capture");
    reset(Scenario::CloseError);
    check(!successfulCapture()&&closes==1,"device close failure cannot report successful capture");
    reset(Scenario::PrepareError);
    check(!successfulCapture()&&closes==1&&unprepares==1,"partial startup error releases prepared buffer");
    reset(Scenario::Cancel);
    check(successfulCapture()&&decoded.empty()&&closes==1,"cancel drains no speech and closes capture");
    reset(Scenario::CloseError);
    {
        Microphone mic;mic.start();
        bool first=false,second=false;
        try{mic.closeConfirmed();}catch(const std::runtime_error&){first=true;}
        try{mic.closeConfirmed();}catch(const std::runtime_error&){second=true;}
        check(first&&second&&closes==1,"unconfirmed close cannot become success on repeated confirmation");
    }
    std::cout<<"TOTAL "<<passed<<" PASS "<<failed<<" FAIL; synthetic WinMM; microphone NOT_RUN\n";
    return failed?1:0;
}
