#include "../core/engine.h"
#include <iostream>
#include <chrono>
#include <stdexcept>
#include <set>
#include <fstream>
#include <sstream>
static int count=0;
static void check(bool value,const char* name){if(!value)throw std::runtime_error(name);count++;std::cout<<"PASS "<<name<<'\n';}
int main(int argc,char**argv){
    try{
        auto loadStart=std::chrono::steady_clock::now();wangshu::Engine e;check(argc>1&&e.load(argv[1]),"load real shared dictionary and symbols");std::cout<<"METRIC dictionary load ms="<<std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now()-loadStart).count()<<"; Linux cloud only\n";check(e.entries()>100000,"full Chinese dictionary, not a tiny demo table");
        check(e.lookup("nihao").front().text=="你好","nihao chooses 你好");check(e.lookup("zhongwen").front().text=="中文","zhongwen chooses 中文");check(e.lookup("shi").front().text=="是","common single-syllable ranking");check(e.lookup("ZhongWen").front().text=="中文","case normalization shared");
        auto phrase=e.lookup("nihaowoshishurufa");bool segmented=false;for(const auto& c:phrase)if(c.text=="你好我是输入法")segmented=true;check(segmented,"phrase segmentation without exact dictionary entry");
        check(e.lookup("\\alpha").front().text=="α","math alias alpha");check(e.lookup("\\Gamma").front().text=="Γ","math case preserved");check(e.lookup("\\X").front().text=="𝕏","non-BMP mathematical symbol");
        check(e.voiceMath("大于等于，小于等于，根号，乘以")=="≥，≤，√，×","longest mathematical voice phrases first");
        check(e.voiceMath("二分之一等于四分之一乘以二")=="½=¼×二","spoken fraction and operator conversion in explicit math mode");
        std::set<std::string> aliases;bool validSymbols=true;for(const auto& s:e.symbols()){validSymbols&=aliases.insert(s.alias).second;validSymbols&=wangshu::utf8(wangshu::utf16(s.text))==s.text;}check(validSymbols&&aliases.size()>=200,"200+ unique mathematical aliases with valid Unicode roundtrip");
        check(e.lookup("\\psi").front().text=="ψ"&&e.lookup("\\Upsilon").front().text=="Υ"&&e.lookup("\\sub9").front().text=="₉","complete Greek and digit subscript access");
        check(e.punctuation(',',true)=="，"&&e.punctuation(',',false)==",","shared Chinese/English punctuation");
        auto u=wangshu::utf16("中文𝕏α");check(u.size()==5,"UTF16 surrogate pair length");check(wangshu::utf8(u)=="中文𝕏α","UTF8/UTF16 roundtrip across both platform bindings");
        check(!wangshu::utf16(std::string("\xff")).empty(),"invalid UTF8 handled without crashing");check(e.lookup(std::string(129,'a')).empty(),"bounded composition input");check(e.lookup("bad\tinput").empty(),"control input rejected");
        auto page1=e.lookup("shi",9,0),page2=e.lookup("shi",9,1);std::set<std::string> first;for(const auto& c:page1)first.insert(c.text);bool overlap=false;for(const auto& c:page2)overlap|=first.count(c.text)>0;check(!overlap&&!page2.empty(),"candidate pagination without duplicates");
        if(argc>2){std::ifstream corpus(argv[2]);check(corpus.good(),"independent synthetic typing corpus loaded");std::string line;int total=0,correct=0;while(std::getline(corpus,line)){if(line.empty()||line[0]=='#')continue;auto split=line.find('\t');if(split==std::string::npos)continue;auto key=line.substr(0,split),expected=line.substr(split+1);auto candidates=e.lookup(key);auto actual=candidates.empty()?"":candidates.front().text;total++;if(actual==expected)correct++;else std::cout<<"MISMATCH "<<key<<" expected="<<expected<<" actual="<<actual<<'\n';}std::cout<<"CORPUS first-candidate accuracy "<<correct<<" / "<<total<<"; synthetic common words only, not general Chinese accuracy\n";check(total>=30&&correct*100>=total*90,"at least 90 percent first-candidate common-word corpus");}
        auto t=std::chrono::steady_clock::now();for(int i=0;i<1000;i++)e.lookup(i%2?"zhongwenshurufa":"nihao");auto ms=std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now()-t).count();std::cout<<"METRIC 1000 native lookups ms="<<ms<<"; Linux cloud, not device latency\n";
        std::cout<<"TOTAL "<<count<<" PASS; production portable engine only, OS integration separate\n";return 0;
    }catch(const std::exception& ex){std::cerr<<"FAIL "<<ex.what()<<'\n';return 1;}
}
