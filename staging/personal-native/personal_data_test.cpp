#include "personal_data.h"
#include <iostream>
#include <functional>
#include <fstream>
#include <sstream>
using namespace wangshu;
static int checks=0;
static void check(bool condition,const char* text){if(!condition)throw std::runtime_error(text);++checks;std::cout<<"PASS "<<text<<'\n';}
static void refused(const std::function<void()>& action,const char* label){bool failure=false;try{action();}catch(const std::exception&){failure=true;}check(failure,label);}
int main(int argc,char** argv){try{
    if(argc!=2&&argc!=3)throw std::runtime_error("Dictionary data directory required");
    Engine engine;check(engine.load(argv[1]),"existing full native dictionary loads unchanged");
    auto data=PersonalData::parse(R"({"kind":"wangshu-personal","version":1,"words":[{"pinyin":"WANG'SHU","text":"望舒"},{"pinyin":"wangshu","text":"望舒"},{"pinyin":"wangshu","text":"同音词"}],"phrases":[{"shortcut":"THANKS","text":"谢谢\nThank you 👩‍🔬"}]})");
    check(data.words.size()==2&&data.words[0].pinyin=="wangshu","Android v1 normalization and stable duplicate rules");
    check(data.phrases[0].shortcut=="thanks"&&data.phrases[0].text=="谢谢\nThank you 👩‍🔬","multiline Unicode phrase and case-normalized shorthand retained");
    check(PersonalData::parse(data.serialize()).serialize()==data.serialize(),"canonical native serialization roundtrips");
    check(data.merged(data).words.size()==2&&data.merged(data).phrases.size()==1,"additive merge idempotently retains existing records");
    auto custom=data.lookup(engine,"wangshu");check(custom.size()>=2&&custom[0].text=="望舒"&&custom[1].text=="同音词","personal exact entries precede native candidates in source order");
    check(data.lookup(engine,"thanks")[0].text=="谢谢\nThank you 👩‍🔬","phrase shorthand reaches actual native candidate overlay");
    auto plain=engine.lookup("nihao"),overlay=data.lookup(engine,"nihao");check(plain[0].text==overlay[0].text,"unrelated native Chinese candidate behavior retained");
    check(data.lookup(engine,"\\X")[0].text==engine.lookup("\\X")[0].text,"mathematical backslash aliases retain case-sensitive baseline");
    refused([]{PersonalData::parse("{} trailing");},"trailing JSON payload refused");
    refused([]{PersonalData::parse(R"({"kind":"wangshu-personal","version":1,"words":[],"phrases":[],"extra":true})");},"unknown root fields refused without silent loss");
    refused([]{PersonalData::parse(R"({"kind":"wangshu-personal","version":1,"words":[{"pinyin":"nihao","text":123}],"phrases":[]})");},"non-string text refused");
    refused([]{PersonalData::key("ni3hao3");},"invalid pinyin does not silently change into another key");
    refused([]{PersonalData::validateText(std::string("a\0b",3),200,false);},"unsupported controls refused");
    refused([]{PersonalData::validateText(std::string("\xed\xa0\x80",3),200,false);},"UTF8 surrogate encoding refused");
    refused([]{PersonalData::validateText(std::string("\xc0\x80",2),200,false);},"overlong UTF8 sequence refused");
    refused([]{PersonalData::parse(std::string(1024*1024+1,' '));},"oversized file refused before parsing");
    refused([]{PersonalData::parse(std::string(40,'[')+"0"+std::string(40,']'));},"deep unexpected JSON refused at bounded nesting");
    PersonalData large;for(int i=0;i<1001;i++)large.words.push_back({"same","word"+std::to_string(i)});
    check(large.merged(large).words.size()==1001,"reimport above half capacity deduplicates before final capacity check");
    PersonalData aliases;for(int i=0;i<20;i++)aliases.words.push_back({"shi","私词"+std::to_string(i)});
    auto page0=aliases.lookup(engine,"shi",0),page1=aliases.lookup(engine,"shi",1),page2=aliases.lookup(engine,"shi",2);check(page0.size()==9&&page1.size()==9&&page2.size()==9&&page0[0].text=="私词0"&&page1[0].text=="私词9"&&page2[0].text=="私词18","custom candidates paginate into unchanged built-in candidates without duplication");
    if(argc==3){std::ifstream file(argv[2],std::ios::binary);if(!file)throw std::runtime_error("Android synthetic store fixture unavailable");std::ostringstream bytes;bytes<<file.rdbuf();auto android=PersonalData::parse(bytes.str());check(android.lookup(engine,"wangshu")[0].text=="望舒测试","portable parser consumes actual Android AtomicFile fixture and candidate");check(android.lookup(engine,"hello")[0].text=="First line\n数学 α + 👩‍🔬","actual Android multiline Unicode phrase roundtrips into native candidates");}
    std::cout<<"PASS "<<checks<<" staged portable personal-data checks; Windows/Android integration NOT_YET_IMPLEMENTED\n";return 0;
}catch(const std::exception& e){std::cerr<<"FAIL "<<e.what()<<'\n';return 1;}}
