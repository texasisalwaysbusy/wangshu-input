// Synthetic child for VoiceProcess protocol testing. No microphone or private data.
#include <windows.h>
#include <iostream>
int main(){
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
