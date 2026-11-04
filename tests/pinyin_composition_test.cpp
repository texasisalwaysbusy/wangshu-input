#include "engine.h"
#include <algorithm>
#include <chrono>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <random>
#include <set>
#include <stdexcept>
#include <string>
#include <vector>

static int checks=0;
static void check(bool value,const std::string& label) {
    if(!value)throw std::runtime_error(label);
    ++checks;std::cout<<"PASS "<<label<<'\n';
}
static std::vector<std::string> collect(const wangshu::Engine& e,const std::string& key,size_t limit=9) {
    std::vector<std::string> result;
    for(size_t page=0;page<=100;++page) {
        auto values=e.lookup(key,limit,page);
        for(const auto& value:values)result.push_back(value.text);
        if(values.size()<limit)break;
    }
    return result;
}
static size_t rank(const std::vector<std::string>& values,const std::string& expected) {
    auto at=std::find(values.begin(),values.end(),expected);
    return at==values.end()?0:static_cast<size_t>(at-values.begin())+1;
}
int main(int argc,char**argv) {
 try {
    wangshu::Engine e;check(argc==3&&e.load(argv[1]),"load frozen real dictionary");
    const auto originalCount=e.entries();
    auto cesh=collect(e,"cesh"),shortened=collect(e,"ceshy"),full=collect(e,"ceshiye");
    check(!cesh.empty()&&cesh.front()=="测试","existing cesh prefix stays usable");
    check(!shortened.empty(),"appending y no longer empties candidates");
    check(rank(shortened,"测试页")>0,"abbreviated phrase includes 测试页 without adding a dictionary entry");
    check(rank(full,"测试页")>0&&rank(full,"测试页")<=9,"full spelling offers 测试页 on first native page");
    std::cout<<"METRIC ceshy 测试页 rank="<<rank(shortened,"测试页")<<"; ceshiye rank="<<rank(full,"测试页")<<"; not a general language-quality score\n";
    check(collect(e,"cesh")==cesh,"backspace to cesh restores identical stateless candidates");
    check(collect(e,"CESHIYE")==full&&collect(e,"ce'shi'ye")==full,"case and established separator normalization");
    check(collect(e,"ceshy",50)==shortened&&collect(e,"ceshiye",50)==full,"page-size-independent composed ranking");
    check(std::set<std::string>(shortened.begin(),shortened.end()).size()==shortened.size(),"no duplicate candidates across abbreviation pages");
    check(collect(e,"ceshy")==shortened,"repeat query deterministic");
    for(const auto& item:std::vector<std::pair<std::string,std::string>>{{"nh","你好"},{"shrf","输入法"},{"zhongwensrf","中文输入法"},{"nihaowoshishrf","你好我是输入法"},{"bjdx","北京大学"}}) {
        auto values=e.lookup(item.first);check(!values.empty()&&values.front().text==item.second,"independent mixed/initial spelling "+item.first);
    }
    check(e.lookup("nihao").front().text=="你好"&&e.lookup("shi").front().text=="是","exact common-word priorities unchanged");
    auto mixed=e.lookup("ceshiye`test`shrf");
    check(!mixed.empty()&&mixed.front().text.find("test")!=std::string::npos,"explicit mixed literal segment remains exact");
    check(e.lookup("\\alpha").front().text=="α"&&e.lookup("\\X").front().text=="𝕏","symbol and non-BMP aliases unchanged");
    check(e.lookup("").empty()&&e.lookup("''").empty()&&e.lookup("ce shi").empty()&&e.lookup("ceshi1").empty()&&e.lookup("中").empty(),"invalid and empty input still rejected");
    check(e.lookup(std::string(129,'a')).empty()&&e.lookup("nihao",0).empty()&&e.lookup("nihao",51).empty()&&e.lookup("nihao",9,101).empty(),"public input/page bounds unchanged");
    check(e.lookup(std::string(33,'q')).empty(),"overlength abbreviation does not enter bounded fallback");
    check(!e.load(std::string(argv[2])+"/absent")&&e.entries()==originalCount&&collect(e,"ceshy")==shortened,"failed reload preserves all existing index state");
    std::mt19937 random(0x61ce5);
    auto start=std::chrono::steady_clock::now();
    for(int i=0;i<96;++i) {
        std::string key;const int size=1+static_cast<int>(random()%32);
        for(int k=0;k<size;++k)key+=static_cast<char>('a'+random()%26);
        auto values=e.lookup(key,50);
        std::set<std::string> seen;
        for(const auto& value:values) {
            if(value.text.size()>512||!seen.insert(value.text).second||wangshu::utf8(wangshu::utf16(value.text))!=value.text)
                throw std::runtime_error("bounded deterministic random probe failed");
        }
    }
    check(true,"96 fixed-seed random queries obey output bounds, Unicode and uniqueness");
    std::cout<<"METRIC random96_ms="<<std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-start).count()<<"; Linux host only\n";
    const auto fixture=std::filesystem::u8path(argv[2]);
    check(!std::filesystem::exists(fixture),"synthetic fixture destination is new");
    std::filesystem::create_directories(fixture);
    std::ofstream dictionary(fixture/"pinyin.tsv");
    dictionary<<"ce\t测\t1000000\nshi\t试\t1100000\nye\t页\t900000\nceshi\t测试\t2000000\n"
              <<"ni\t你\t1200000\nhao\t好\t1200000\nnihao\t你好\t2000000\n"
              <<"shu\t输\t1200000\nru\t入\t1200000\nfa\t法\t1200000\nshurufa\t输入法\t2000000\n";
    dictionary.close();std::ofstream symbols(fixture/"symbols.tsv");symbols<<"alpha\tα\tgreek\t阿尔法\n";symbols.close();
    wangshu::Engine simple;check(simple.load(fixture.string()),"independent synthetic dictionary loads");
    check(simple.lookup("ceshy").front().text=="测试页"&&simple.lookup("ceshiye").front().text=="测试页","same generic parser works with independent fixture frequencies");
    check(simple.lookup("shrf").front().text=="输入法","synthetic fixture exercises unrelated mixed shorthand");
    const auto longFixture=fixture/"long-candidates";
    std::filesystem::create_directories(longFixture);
    std::filesystem::copy_file(fixture/"pinyin.tsv",longFixture/"pinyin.tsv");
    std::filesystem::copy_file(fixture/"symbols.tsv",longFixture/"symbols.tsv");
    std::string longPrefix,longSuffix;
    for(int i=0;i<100;++i){longPrefix+="甲";longSuffix+="乙";}
    std::ofstream longWords(longFixture/"pinyin.tsv",std::ios::app);
    longWords<<"nihao\t"<<longPrefix<<"\t2200000\nhao\t"<<longSuffix<<"\t2100000\n";
    longWords.close();wangshu::Engine longEngine;
    check(longEngine.load(longFixture.string()),"long-candidate fixture loads independently");
    check(rank(collect(longEngine,"nihh"),longPrefix+"好")>0,"oversized best suffix does not hide a later short valid combination");
    check(rank(collect(longEngine,"nihaohao"),longPrefix+"好")>0,"full-spelling composition enforces length without dropping shorter alternatives");
    check(e.load(argv[1])&&collect(e,"ceshy")==shortened,"successful dictionary reload rebuilds consistent index");
    std::cout<<"TOTAL "<<checks<<" PASS; portable C++ only; no Android/device/Windows acceptance\n";return 0;
 }catch(const std::exception& failure){std::cerr<<"FAIL "<<failure.what()<<'\n';return 1;}
}
