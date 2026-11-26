// Existing production corpus and dictionary; no editor or model installation.
#include "../core/engine.h"
#include "../core/context_predictions.h"
#include <algorithm>
#include <iostream>
#include <set>
#include <stdexcept>
#include <string>
#include <vector>

using wangshu::Candidate;
static unsigned checks=0;
static void require(bool ok,const char* message){
    if(!ok)throw std::runtime_error(message);
    ++checks;
}
static bool equal(const Candidate& a,const Candidate& b){
    return a.text==b.text&&a.annotation==b.annotation&&a.score==b.score;
}
static bool same(const std::vector<Candidate>& a,const std::vector<Candidate>& b){
    return a.size()==b.size()&&std::equal(a.begin(),a.end(),b.begin(),equal);
}
static bool completion(const Candidate& item){
    return item.annotation=="语料补全"||item.annotation=="词典补全";
}
static bool next(const Candidate& item){return item.annotation=="语料下一词";}
static bool both(const std::vector<Candidate>& value){
    return std::any_of(value.begin(),value.end(),completion)&&std::any_of(value.begin(),value.end(),next);
}
static void validate(const std::vector<Candidate>& baseline,const std::vector<Candidate>& visible){
    require(visible.size()<=8,"bounded display");
    require(baseline.empty()?visible.empty():!visible.empty()&&equal(baseline.front(),visible.front()),
            "original primary candidate retained");
    std::set<std::string> keys;
    for(const auto& item:visible)require(keys.insert(item.text).second,"unique insertion text");
    for(const auto& item:baseline)require(std::any_of(visible.begin(),visible.end(),[&](const auto& candidate){
        return equal(item,candidate);
    }),"original choice retained unchanged");
    require(std::count_if(visible.begin(),visible.end(),completion)<=4&&
            std::count_if(visible.begin(),visible.end(),next)<=4,"each lane independently bounded");
    if(both(visible)){
        require(visible.size()>=2&&completion(visible[0])!=completion(visible[1]),"alternate lane immediately reachable");
    }else require(same(baseline,visible),"unavailable alternate lane leaves existing output unchanged");
}
int main(int argc,char** argv){
    try{
        if(argc!=2)return 2;
        wangshu::Engine engine;
        require(engine.load(argv[1]),"production dictionary");
        // These cases are the first distinct examples from the baseline table
        // scan, not reference answers used to fit model quality.
        for(const std::string text:{"一下你","一下我","一个小","一个新","一个月","一个美","一个陌生","一些事"}){
            const auto baseline=engine.predict(text,"",false,4);
            const auto visible=engine.predictForDisplay(text,"");
            require(!both(baseline)&&both(visible),"previously monopolized lane now accessible");
            validate(baseline,visible);
            std::cout<<"PASS "<<text<<": ";
            for(const auto& item:visible)std::cout<<item.annotation<<" "<<item.text<<" | ";
            std::cout<<"\n";
        }
        std::set<std::u16string> keys;
        size_t alternates=0,scanned=0;
        for(const auto& item:wangshu::context_prediction::chineseContexts){
            auto key=std::u16string(wangshu::context_prediction::text(item.key));
            if(!keys.insert(key).second)continue;
            auto text=wangshu::utf8(key);
            auto baseline=engine.predict(text,"",false,4);
            auto visible=engine.predictForDisplay(text,"");
            validate(baseline,visible);
            alternates+=both(visible)&&!both(baseline);
            if(++scanned==256)break;
        }
        require(scanned==256&&alternates>0,"structural corpus sweep");
        std::cout<<"Corpus contexts "<<scanned<<", newly dual-lane "<<alternates<<"\n";
        for(const auto& text:std::vector<std::string>{"今天","I would ","https://host/Thank you ","文件/太阳能电","今",
                                                      std::string("\xff",1)}){
            const bool english=text=="I would "||text.find("https:")==0;
            auto baseline=engine.predict(text,"",english,4);
            require(same(baseline,engine.predictForDisplay(text,"",english)),"existing example, English or unsafe route");
        }
        for(const auto& text:{"日期：2026","一个小"}){
            require(same(engine.predict(text,"",false,4,true),engine.predictForDisplay(text,"",false,true)),
                    "numeric panel route unchanged");
        }
        require(engine.predictForDisplay("一个小","时").empty(),"interior caret remains refused");
        for(const auto& item:engine.predictForDisplay("一个小","，时"))
            require(item.text!="时","existing adjacent completion not repeated");
        require(engine.predictForDisplay("一个小",std::string("\xff",1)).empty(),"malformed right context rejected");
        std::cout<<"TOTAL "<<checks<<" PASS; shared core, generated corpus/dictionary, device NOT_RUN\n";
        return 0;
    }catch(const std::exception& error){
        std::cerr<<"FAIL "<<error.what()<<"\n";return 1;
    }
}
