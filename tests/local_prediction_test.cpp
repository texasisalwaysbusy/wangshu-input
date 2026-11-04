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
 check(has(e.predict("昨天我们", ""),"一起"),"suffix context works outside entire sentence prefix");
 check(has(e.predict("今天的 ",""),"meeting"),"mixed Chinese to English next unit");
 check(e.predict("今","").empty(),"one Han is too ambiguous");
 check(e.predict("I","",true).empty(),"one ASCII letter suppressed");
 check(e.predict("https://host/Thank you ","",true).empty(),"URL context not truncated into prose");
 check(e.predict("foo.Thank you ","",true).empty(),"member access domain suppressed");
 check(e.predict("email@Thank you ","",true).empty(),"email suppressed");
 check(e.predict("I would ","like",true).empty(),"word-internal insertion suppressed");
 check(e.predict("I would "," like",true).empty(),"duplicate following word suppressed");
 check(!e.predict("I would ",", later",true).empty(),"clear middle boundary append allowed");
 check(e.predict(std::string(257,'a'),"",true).empty(),"overlong current line rejected");
 check(e.predict(std::string("\xff"),"",true).empty(),"malformed UTF8 refused");
 check(has(e.predict("PLEASE CHECK THE ","",true),"ATTACHED"),"uppercase context preserved");
 check(e.predict("I would ","",true,0).empty()&&e.predict("I would ","",true,9).empty(),"output bound validated");
 auto start=std::chrono::steady_clock::now();for(int i=0;i<1000;++i){auto value=e.predict(i%2?"I would ":"今天","",i%2);if(value.empty())throw std::runtime_error("benchmark empty");}
 std::cout<<"METRIC 1000 local predictions ms="<<std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now()-start).count()<<" Linux host only\n";
 std::cout<<"TOTAL "<<n<<" PASS; no device claim\n";return 0;}catch(const std::exception& x){std::cerr<<"FAIL "<<x.what()<<"\n";return 1;}}
