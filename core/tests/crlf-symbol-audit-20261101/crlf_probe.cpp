#include "../../engine.h"
#include <filesystem>
#include <fstream>
#include <iostream>
#include <string>
static bool verify(std::filesystem::path dir,const std::string& newline){
 std::filesystem::create_directories(dir);std::ofstream dict(dir/"pinyin.tsv"),signs(dir/"symbols.tsv");
 for(int i=0;i<10;i++)dict<<"a\tword"<<i<<"\t"<<(100-i)<<newline;
 signs<<newline<<"   "<<newline<<"alpha\tα\tGreek\t阿尔法"<<newline<<"beta\tβ\tGreek"<<newline<<"plus\t+\tmath\t加号|加"<<newline;
 dict.close();signs.close();wangshu::Engine engine;if(!engine.load(dir.string()))return false;
 std::string actual=engine.voiceMath("阿尔法和加号和加");auto greek=engine.symbols("Greek");
 if(actual!="α和+和+"||greek.size()!=2){std::cerr<<"FAIL line endings change spoken matching or symbol category: voice="<<actual<<", Greek-count="<<greek.size()<<"\n";return false;}
 if(engine.lookup("\\alpha").empty()||engine.lookup("\\alpha")[0].text!="α")return false;
 return true;
}
int main(int argc,char** argv){
 if(argc!=2)return 2;std::filesystem::path dir=argv[1];
 if(!verify(dir/"crlf","\r\n")||!verify(dir/"lf","\n"))return 1;
 std::cout<<"PASS LF and CRLF symbol category, blank lines, aliases and every spoken alternative\n";return 0;
}
