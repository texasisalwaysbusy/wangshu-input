#include "../core/engine.h"
#include "../core/mixed_composition.h"
#include <iostream>
#include <chrono>
#include <stdexcept>
#include <algorithm>
int n=0;void check(bool b,const char* label){if(!b)throw std::runtime_error(label);std::cout<<"PASS "<<++n<<" "<<label<<"\n";}
bool has(const std::vector<wangshu::Candidate>& values,const std::string& text){return std::any_of(values.begin(),values.end(),[&](const auto& v){return v.text==text;});}
int main(int argc,char**argv){try{wangshu::Engine e;check(argc==2&&e.load(argv[1]),"load actual dictionary");
 check(has(e.lookup("nihao`OpenAI`zhongwen"),"你好OpenAI中文"),"Chinese English Chinese in one composition");
 check(has(e.lookup("`OpenAI`zhongwen"),"OpenAI中文"),"English initial literal then Chinese");
 check(has(e.lookup("nihao`hello world 123`"),"你好hello world 123"),"English multiword digit literal preserved");
 check(has(e.lookup("nihao`OpenAI"),"你好OpenAI"),"unclosed final literal is previewable");
 check(has(e.lookup("nihao``zhongwen"),"你好中文"),"empty adjacent boundary harmless");
 check(e.lookup("ni'hao").front().text=="你好","existing apostrophe pinyin unchanged");
 check(has(e.lookup("nihao`x<script>@a.com_${x}`"),"你好x<script>@a.com_${x}"),"code-like literal preserved only as text never evaluated");
 check(e.lookup("nihao`\npassword`").empty(),"literal control rejected");
 check(e.lookup("nihao`中文`").empty(),"nonASCII literal rejected without splitting Unicode");
 check(e.lookup("nihao`"+std::string(128,'a')).empty(),"mixed 128 byte bound");
 check(e.lookup("`").empty(),"empty marker no spurious candidate");
 check(e.lookup("bad!`hello`").empty(),"nonliteral punctuation rejected");
 check(e.lookup("\\alpha`x`").empty(),"math alias cannot inject mixed syntax");
 check(e.lookup("a`b`a`b`a`b`a`b`a`b`").empty(),"segment fanout bounded");
 check(wangshu::mixed::raw("nihao`OpenAI`")=="nihaoOpenAI","raw commit removes only segment metadata");
 check(wangshu::mixed::active("nihao`OpenAI")&&!wangshu::mixed::active("nihao`OpenAI`"),"segment mode explicit");
 auto zh=e.predict("今天","");check(has(zh,"天气")&&has(zh,"天气很好，适合散步。"),"Chinese next word and full sentence separate");
 auto en=e.predict("I would ","",true);check(has(en,"like")&&has(en,"like to discuss the project."),"English next word and full sentence separate");
 check(has(e.predict("Please check the at","",true),"tached"),"English partial-word completion");
 const auto unrelated=e.predict("In the margin, sketch the ","",true);
 check(!unrelated.empty()&&std::none_of(unrelated.begin(),unrelated.end(),[](const auto& row){
     return row.annotation.find("本地例句")==0;
 }),"an unrelated English clause ending in an article reaches the corpus");
 check(has(e.predict("昨天我们", ""),"一起"),"suffix context works outside entire sentence prefix");
 check(has(e.predict("今天的 ",""),"meeting"),"mixed Chinese to English next unit");
 check(e.predict("今","").empty(),"one Han is too ambiguous");
 const auto completion=e.predict("换上太阳能电","");
 check(!completion.empty()&&completion.front().text=="池","longest Han prefix completes a dictionary word outside the examples");
 check(has(e.predict("换上太阳能电",", 再检查"),"池"),"dictionary completion can append at a separated middle caret");
 check(e.predict("换上太阳能电","池").empty(),"dictionary completion refuses an interior caret");
 check(e.predict("换上太阳能电","，池").empty(),"existing suffix is suppressed without falling back to a shorter prefix");
 check(e.predict("文件/太阳能电","").empty(),"dictionary completion rejects unsafe current line");
 check(e.predict("换上太阳能电","",true).empty(),"Chinese dictionary does not run in English mode");
 check(e.predict("I","",true).empty(),"one ASCII letter suppressed");
 check(e.predict("https://host/Thank you ","",true).empty(),"URL context not truncated into prose");
 check(e.predict("foo.Thank you ","",true).empty(),"member access domain suppressed");
 check(e.predict("email@Thank you ","",true).empty(),"email suppressed");
 const auto sameSuggestions=[](const std::vector<wangshu::Candidate>& a,const std::vector<wangshu::Candidate>& b){
     return !a.empty()&&a.size()==b.size()&&std::equal(a.begin(),a.end(),b.begin(),[](const auto& left,const auto& right){
         return left.text==right.text&&left.annotation==right.annotation&&left.score==right.score;
     });
 };
 check(sameSuggestions(e.predict("I would ","",true),e.predict("The note ended. I would ","",true)),
       "begin a fresh English next-word context after a prose period");
 check(sameSuggestions(e.predict("The weath","",true),e.predict("The note ended.\t The weath","",true)),
       "complete an English word after sentence whitespace");
 check(sameSuggestions(e.predict("换上太阳能电","",false),e.predict("前一句写完了. 换上太阳能电","",false)),
       "complete a Chinese word after an ASCII sentence period");
 check(sameSuggestions(e.predict("The weath","",true),e.predict("The weath",". Thanks.",true))&&
       sameSuggestions(e.predict("The weath","",true),e.predict("The weath",".",true)),
       "allow a proper period at the insertion boundary");
 check(e.predict("The weath",".com",true).empty()&&e.predict("The weath","... Thanks",true).empty(),
       "do not treat a domain or an ellipsis as a right-side sentence boundary");
 check(e.predict("https://example.com. I would ","",true).empty()&&
       e.predict("mail@example.com. I would ","",true).empty()&&
       e.predict("The value is 3.14. I would ","",true).empty()&&
       e.predict("obj.member. I would ","",true).empty(),
       "reject unsafe material before a later prose period");
 check(e.predict("I would. ","",true).empty()&&e.predict("I would ",". like",true).empty(),
       "do not continue across an empty sentence or duplicate a following word");
 check(e.predict("I would ","like",true).empty(),"word-internal insertion suppressed");
 check(e.predict("I would "," like",true).empty(),"duplicate following word suppressed");
 check(!e.predict("I would ",", later",true).empty(),"clear middle boundary append allowed");
 check(e.predict(std::string(257,'a'),"",true).empty(),"overlong current line rejected");
 check(e.predict(std::string("\xff"),"",true).empty(),"malformed UTF8 refused");
 check(has(e.predict("PLEASE CHECK THE ","",true),"ATTACHED"),"uppercase context preserved");
 const auto englishCorpus=e.predict("THE WEATH","",true);
 check(!englishCorpus.empty()&&englishCorpus.front().text=="ER"&&englishCorpus.front().annotation=="语料补全",
       "corpus completes outside the examples and preserves all-caps");
 check(e.predict("The weath","er",true).empty(),"corpus declines a caret inside an existing word");
 check(!has(e.predict("The weath"," er",true),"er"),"corpus filters a duplicate after a separator");
 const auto longContext=e.predict("Would you like some ","",true,2);
 const auto shortContext=e.predict("zxq like some ","",true,2);
 check(longContext.size()==2&&longContext[1].text=="tea"&&
       shortContext.size()==2&&shortContext[1].text=="sugar",
       "three-word English context and unknown-word backoff use distinct rankings");
 const auto chineseCorpus=e.predict("你想不想","");
 check(has(chineseCorpus,"一起")&&std::any_of(chineseCorpus.begin(),chineseCorpus.end(),[](const auto& row){
     return row.text=="一起"&&row.annotation=="语料下一词";
 }),"corpus next word follows Chinese context outside the examples");
 check(e.predict("I would ","",true,0).empty()&&e.predict("I would ","",true,9).empty(),"output bound validated");
 check(has(e.predict("日期：2026年11","，备注",false,4,true),"月"),"numeric panel adds a Chinese date suffix at a separated caret");
 check(has(e.predict("时间：19时05分08","",false,4,true),"秒"),"numeric panel adds a Chinese time suffix");
 check(has(e.predict("ISO date: 2026-11","",true,4,true),"-")&&has(e.predict("Time: 09","",true,4,true),":"),"numeric panel offers English-labelled date and time separators");
 check(has(e.predict("日期：2024年2月29","",false,4,true),"日")&&e.predict("日期：2025年2月29","",false,4,true).empty(),"Chinese date respects leap days");
 check(e.predict("日期：2026","年",false,4,true).empty()&&e.predict("日期：2026","，年",false,4,true).empty(),"existing date suffix is not repeated");
 check(e.predict("日期：2026","",false).empty()&&e.predict("2026","",false,4,true).empty()&&e.predict("2+2=","",false,4,true).empty(),"labelled suggestions require the numeric mode and never infer a value");
 check(e.predict("https://x/日期：2026","",false,4,true).empty()&&e.predict("时间：25","",false,4,true).empty(),"malformed label or component is ineligible");
 check(has(e.predict("今天","",false,4,true),"天气")&&has(e.predict("I would ","",true,4,true),"like"),"ordinary bilingual predictions remain available in numeric mode");
 auto start=std::chrono::steady_clock::now();for(int i=0;i<1000;++i){auto value=e.predict(i%2?"I would ":"今天","",i%2);if(value.empty())throw std::runtime_error("benchmark empty");}
 std::cout<<"METRIC 1000 local predictions ms="<<std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now()-start).count()<<" shared-core host only\n";
 std::cout<<"TOTAL "<<n<<" PASS; no device claim\n";return 0;}catch(const std::exception& x){std::cerr<<"FAIL "<<x.what()<<"\n";return 1;}}
