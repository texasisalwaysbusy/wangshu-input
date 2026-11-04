// Real VoiceProcess with an owned synthetic child. Wine is not a Windows-device test.
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
int wmain(int argc,wchar_t** argv){try{check(argc==2,"synthetic helper folder supplied");run(std::filesystem::path(argv[1]),false);run(std::filesystem::path(argv[1]),true);std::cout<<"TOTAL "<<checks<<" PASS; Wine synthetic child, real Windows microphone NOT RUN\n";return 0;}catch(const std::exception& e){std::cerr<<"FAIL "<<e.what()<<"\n";return 1;}}
