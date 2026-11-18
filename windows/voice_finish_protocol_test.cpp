// Real VoiceProcess with an owned synthetic child. No microphone or TSF registration.
#ifndef UNICODE
#define UNICODE
#endif
#define _UNICODE
#include <windows.h>
#include <filesystem>
#include <iostream>
#include <stdexcept>
#include "voice_process.h"
static unsigned checks=0;
static void check(bool ok,const char* name){if(!ok)throw std::runtime_error(name);std::cout<<"PASS "<<name<<"\n";++checks;}
static void waitReady(VoiceProcess& p){ULONGLONG deadline=GetTickCount64()+10000;while(p.state==VoiceProcess::Starting&&GetTickCount64()<deadline){p.poll();Sleep(10);}}
static void waitDone(VoiceProcess& p){ULONGLONG deadline=GetTickCount64()+10000;while(p.active()&&GetTickCount64()<deadline){p.poll();Sleep(10);}}
static void run(const std::filesystem::path& folder,bool beforeReady){
 VoiceProcess p;check(p.start(folder),beforeReady?"start before-ready scenario":"start recording scenario");
 if(!beforeReady){waitReady(p);check(p.state==VoiceProcess::Recording,"helper reaches recording state");}
 for(int i=0;i<64;++i)p.finish();
 check(p.state==VoiceProcess::Finishing,"repeated finish remains in finishing state");
 waitDone(p);check(p.state==VoiceProcess::Complete,"synthetic child completes");
 if(p.text!="1")std::cerr<<"Observed finish commands: "<<p.text<<"\n";
 check(p.text=="1",beforeReady?"starting finish sends exactly once":"recording finish sends exactly once");
 p.finish();check(p.state==VoiceProcess::Complete,"finish after completion is ignored");p.cancel();check(p.state==VoiceProcess::Idle&&p.text.empty(),"cancel clears result");
}
static void waitPreview(VoiceProcess& process){
 const auto deadline=GetTickCount64()+10000;
 while(process.active()&&process.preview()!=L"你好"&&GetTickCount64()<deadline){process.poll();Sleep(10);}
}
static void preview(const std::filesystem::path& folder){
 VoiceProcess process;
 check(process.start(folder,L"--preview"),"start synthetic live-preview child");
 waitPreview(process);
 check(process.state==VoiceProcess::Recording&&process.preview()==L"你好"&&process.text.empty(),
       "bounded UTF-8 preview replaces earlier text without publishing the final result");
 process.finish();
 check(process.state==VoiceProcess::Finishing&&process.preview().empty(),"finish hides preview immediately");
 while(process.state==VoiceProcess::Finishing){
  process.poll();
  if(!process.preview().empty())throw std::runtime_error("late preview became visible");
  Sleep(10);
 }
 check(process.state==VoiceProcess::Complete&&process.preview().empty()&&process.text=="finished",
       "late preview is discarded and final result is separate");
 check(process.start(folder,L"--preview")&&process.preview().empty()&&process.text.empty(),"replacement session starts empty");
 waitPreview(process);
 check(!process.preview().empty(),"replacement gets its own preview");
 process.cancel();
 process.poll();
 check(!process.active()&&process.preview().empty()&&process.text.empty(),"cancel clears preview and cannot replay child output");
}
int wmain(int argc,wchar_t** argv){try{check(argc==2,"synthetic helper folder supplied");const std::filesystem::path folder(argv[1]);run(folder,false);run(folder,true);preview(folder);std::cout<<"TOTAL "<<checks<<" PASS; synthetic child, real Windows microphone NOT RUN\n";return 0;}catch(const std::exception& e){std::cerr<<"FAIL "<<e.what()<<"\n";return 1;}}
