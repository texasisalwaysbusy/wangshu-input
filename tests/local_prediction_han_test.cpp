#include "../core/engine.h"
#include "../core/local_predictions.h"
#include <iostream>

int main(){
 namespace lp=wangshu::local_prediction;
 wangshu::Engine engine;
 unsigned checks=0;
 for(char16_t c:{char16_t(0x3400),char16_t(0x4dbf),char16_t(0x4e00),char16_t(0x9fff)}){
  if(!lp::han(c)){std::cerr<<"FAIL valid Han range endpoint rejected\n";return 1;}
  ++checks;
 }
 for(char16_t c=0x4dc0;c<=0x4dff;++c){
  if(lp::han(c)){std::cerr<<"FAIL I Ching hexagram classified as Han: "<<std::hex<<unsigned(c)<<"\n";return 1;}
  ++checks;
  auto unsafe=std::u16string(1,c)+u"今天";
  if(lp::safeLine(unsafe)||!engine.predict(wangshu::utf8(unsafe),"").empty()){
   std::cerr<<"FAIL non-Han symbol admitted to prose context\n";return 1;
  }
  ++checks;
 }
 if(engine.predict("今天","").empty()||engine.predict("I would ","",true).empty()){
  std::cerr<<"FAIL ordinary seed continuations regressed\n";return 1;
 }
 ++checks;
 std::cout<<"PASS "<<checks<<" Han classification and unsafe-prefix regression checks; host only\n";
}
