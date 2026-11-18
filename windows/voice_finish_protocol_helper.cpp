// Synthetic child for VoiceProcess protocol testing. No microphone or private data.
#include <windows.h>
#include <iostream>
#include <string>

static int previewFixture(){
 std::cout<<"PREVIEW before-ready\nREADY\nPREVIEW "<<std::string(2049,'x')
          <<"\nPREVIEW \xff\nPREVIEW first\nPREVIEW "<<std::flush;
 Sleep(150);
 std::cout<<u8"你好"<<"\n"<<std::flush;
 char command=0;
 DWORD read=0;
 if(!ReadFile(GetStdHandle(STD_INPUT_HANDLE),&command,1,&read,nullptr)||read!=1||command!='F')return 2;
 std::cout<<"PREVIEW too-late\n"<<std::flush;
 Sleep(150);
 std::cout<<"RESULT finished\n"<<std::flush;
 return 0;
}

int main(int argc,char** argv){
 if(argc==2&&std::string(argv[1])=="--preview")return previewFixture();
 std::cout<<"READY\n"<<std::flush;
 Sleep(1200);
 HANDLE in=GetStdHandle(STD_INPUT_HANDLE);DWORD available=0,total=0,n=0;char bytes[512];
 while(PeekNamedPipe(in,nullptr,0,nullptr,&available,nullptr)&&available){
  DWORD take=available<sizeof(bytes)?available:sizeof(bytes);
  if(!ReadFile(in,bytes,take,&n,nullptr)||!n)return 2;
  for(DWORD i=0;i<n;++i){if(bytes[i]!='F')return 3;++total;}
 }
 std::cout<<"RESULT "<<total<<"\n"<<std::flush;
 return 0;
}
