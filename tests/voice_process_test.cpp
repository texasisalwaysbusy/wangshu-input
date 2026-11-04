#ifndef UNICODE
#define UNICODE
#endif
#define _UNICODE
#include <windows.h>
#include <iostream>
#include <filesystem>
#include "../windows/voice_process.h"
static int checks=0;
static void check(bool ok,const char* text){if(!ok)throw std::runtime_error(text);std::cout<<"PASS "<<text<<"\n";checks++;}
int wmain(int argc,wchar_t** argv){try{
 check(argc==4,"explicit test helper folder, public WAV and pinned models supplied");
 auto folder=std::filesystem::absolute(argv[1]);std::wstring args=L"--wav \""+std::wstring(argv[2])+L"\" --models \""+argv[3]+L"\"";
 VoiceProcess process;check(process.start(folder,args),"production process wrapper starts owned child without shell");
 ULONGLONG deadline=GetTickCount64()+60000;while(process.active()&&GetTickCount64()<deadline){process.poll();Sleep(50);}
 if(process.state!=VoiceProcess::Complete)std::cerr<<"child error="<<process.error<<"\n";
 check(process.state==VoiceProcess::Complete,"bounded anonymous pipe receives actual completed recognition");
 check(process.text.find("这是第一种")!=std::string::npos&&process.text.find("什么意思")!=std::string::npos,"actual PE CPU inference returns expected Chinese fixture phrases");
 process.cancel();check(process.state==VoiceProcess::Idle&&process.text.empty(),"cancel clears transcript from parent session");
 check(process.start(folder,args),"second independent recognition starts");DWORD pid=process.processId();HANDLE owned=OpenProcess(SYNCHRONIZE,FALSE,pid);check(owned!=nullptr,"owned child can be observed for lifecycle test");process.cancel();
 check(WaitForSingleObject(owned,10000)==WAIT_OBJECT_0,"cancel terminates owned child/job rather than leaving microphone worker");CloseHandle(owned);
 check(!process.start(folder/L"missing")&&process.state==VoiceProcess::Failed,"missing optional helper fails explicitly without shell fallback");
 std::cout<<"PASS "<<checks<<" production child/inference/lifetime checks; real Windows microphone NOT RUN\n";return 0;
 }catch(const std::exception& e){std::cerr<<"FAIL "<<e.what()<<"\n";return 1;}}
