#include "../core/engine.h"
#include "../core/mixed_composition.h"
#include "../core/local_predictions.h"
#include <algorithm>
#include <iostream>
#include <set>
#include <stdexcept>

static size_t assertions=0, contexts=0;
static void check(bool v,const std::string& name){++assertions;if(!v)throw std::runtime_error(name);}
static void print(const wangshu::Engine& engine,const std::string& raw){
 std::cout<<"PROBE mixed="<<raw<<"\n";
 for(auto& c:engine.lookup(raw))std::cout<<"  "<<c.text<<" score="<<c.score<<"\n";
}
int main(int argc,char** argv){try{
 wangshu::Engine engine;check(argc==2&&engine.load(argv[1]),"load dictionary");
 namespace lp=wangshu::local_prediction;
 for(const auto& example:lp::examples()){
  std::u16string sentence;for(const auto& unit:example.units)sentence+=unit;
  check(lp::valid(sentence),"valid seed UTF16");
  for(size_t i=0;i<=sentence.size();++i)for(bool english:{false,true}){
   auto before=sentence.substr(0,i);auto values=lp::suggest(before,u"",english,8);++contexts;
   check(values.size()<=8,"result count bound");std::set<std::u16string> seen;
   size_t matched=values.empty()?0:values.front().matched;
   for(const auto& value:values){
    check(!value.suffix.empty()&&lp::valid(value.suffix),"nonempty valid result");
    check(value.row>0&&value.row<=lp::examples().size(),"row provenance bound");
    auto language=lp::examples()[value.row-1].language;
    check(language==2||language==(english?1:0),"language gate");
    check(value.matched>=2&&value.matched==matched&&value.matched<=before.size(),"longest context contract");
    check(value.suffix.size()<=(value.sentence?128:64),"suffix size bound");
    std::u16string folded=value.suffix;for(auto& c:folded)c=lp::lower(c);
    check(seen.insert(folded).second,"casefold deduplication");
    std::u16string origin;for(auto& unit:lp::examples()[value.row-1].units)origin+=unit;
    for(auto& c:origin)c=lp::lower(c);
    check(origin.find(folded)!=std::u16string::npos,"all output from declared editorial row");
   }
   for(const auto& after:std::vector<std::u16string>{u"letter",u"中文",std::u16string(1,char16_t(0xd800))}){
    check(lp::suggest(before,after,english,8).empty(),"word-internal or malformed following context suppressed");
   }
  }
 }
 for(auto raw:{"", "`", "``", "```", "nihao`\n`", "nihao`\x7f`", "nihao`\x01`", "nihao`\x80`", "nihao`中文`", "ni`literal`!", "\\alpha`x`"})
  check(engine.lookup(raw).empty(),"invalid mixed input rejected");
 for(size_t limit:{size_t(0),size_t(51),size_t(-1)})check(engine.lookup("nihao`x`",limit).empty(),"mixed lookup limit validation");
 for(size_t page:{size_t(101),size_t(-1)})check(engine.lookup("nihao`x`",9,page).empty(),"mixed lookup page validation");
 std::set<std::string> pages;size_t count=0;
 for(size_t page=0;page<12;++page)for(auto& c:engine.lookup("shi`x`shi`y`shi",4,page)){
  check(pages.insert(c.text).second,"mixed pagination unique");check(c.text.size()<=512,"mixed byte bound");++count;
 }
 check(count<=36,"mixed beam cap");
 for(auto bytes:{std::string("\xc0\xaf"),std::string("\xe0\x80\x80"),std::string("\xf0\x80\x80\x80"),std::string("\xed\xa0\x80"),std::string("\xf4\x90\x80\x80"),std::string("\xff"),std::string("\xe4\xb8"),std::string("\x80")}){
  check(engine.predict(bytes+"\n今天","").empty(),"malformed UTF8 before safe last line rejected");
  check(engine.predict("今天",bytes).empty(),"malformed UTF8 after rejected");
 }
 for(auto surrogate:{char16_t(0xd800),char16_t(0xdbff),char16_t(0xdc00),char16_t(0xdfff)}){
  auto invalid=std::u16string(1,surrogate)+u"\n今天";
  check(lp::suggest(invalid,u"",false).empty(),"malformed UTF16 before safe last line rejected");
 }
 auto repaired=std::u16string(1,char16_t(0xfffd))+u"\n今天";
 check(!lp::suggest(repaired,u"",false).empty(),"literal replacement character on previous line valid input");
 for(size_t limit:{size_t(0),size_t(9),size_t(-1)})check(lp::suggest(u"今天",u"",false,limit).empty(),"prediction limit validation");
 check(lp::suggest(std::u16string(32768,u' ')+u"\n今天",u"",false).empty(),"whole context bound");
 check(lp::suggest(std::u16string(255,u' ')+u"今天",u"",false).empty(),"current line bound");
 check(lp::suggest(u"今天",std::u16string(32769,u' '),false).empty(),"following context bound");
 print(engine,"nihao");print(engine,"ni``hao");print(engine,"`a``b`");
 std::cout<<"PROBE non-Han U+4DC0 han classification="<<lp::han(0x4dc0)<<" prediction count="<<engine.predict("䷀今天", "").size()<<"\n";
 std::cout<<"PROBE after an English full stop result count="<<engine.predict("Thank you. I would ","",true).size()<<" (conservative design limitation)\n";
 std::cout<<"PASS "<<assertions<<" focused assertions over "<<contexts<<" editorial prefix/mode contexts; structural and safety checks only, not language accuracy or device evidence\n";
 return 0;
 }catch(const std::exception& e){std::cerr<<"FAIL "<<e.what()<<"\n";return 1;}}
