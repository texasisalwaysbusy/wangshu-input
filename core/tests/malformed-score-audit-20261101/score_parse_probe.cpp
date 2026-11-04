#include "../../engine.h"
#include <filesystem>
#include <fstream>
#include <iostream>
#include <string>
int main(int argc,char** argv){
 if(argc!=2)return 2;std::filesystem::path dir=argv[1];
 std::ofstream dict(dir/"pinyin.tsv"), symbols(dir/"symbols.tsv");
 for(int i=0;i<10;i++)dict<<"a\tValid"<<i<<"\t"<<(100-i)<<(i==0?"\r\n":"\n");
 dict<<"a\tValidPlus\t+5\na\tValidMinus\t-5\na\tValidMaximum\t2147483647\na\tValidMinimum\t-2147483648\na\tValidZeros\t0002\n";
 dict<<"a\tMalformedSuffix\t2147483647oops\n";
 dict<<"a\tScientificLooking\t1e9\n";
 dict<<"a\tLeadingWhitespace\t 999\na\tTrailingWhitespace\t999 \na\tOverflow\t2147483648\na\tUnderflow\t-2147483649\na\tOnlySign\t+\na\tNoScore\t\n";
 dict.close();symbols<<"alpha\tα\tmath\n";symbols.close();
 wangshu::Engine e;if(!e.load(dir.string()))return 3;
 auto results=e.lookup("a",50);bool malformed=false,minimum=false,maximum=false,plus=false,minus=false,zeros=false;
 for(const auto& candidate:results){if(candidate.text=="MalformedSuffix"||candidate.text=="ScientificLooking"||candidate.text=="LeadingWhitespace"||candidate.text=="TrailingWhitespace"||candidate.text=="Overflow"||candidate.text=="Underflow"||candidate.text=="OnlySign"||candidate.text=="NoScore"){
  std::cout<<"accepted malformed score: "<<candidate.text<<" = "<<candidate.score<<"\n";malformed=true;
 }if(candidate.text=="ValidMinimum")minimum=candidate.score==-2147483647-1;if(candidate.text=="ValidMaximum")maximum=candidate.score==2147483647;
 if(candidate.text=="ValidPlus")plus=candidate.score==5;if(candidate.text=="ValidMinus")minus=candidate.score==-5;if(candidate.text=="ValidZeros")zeros=candidate.score==2;}
 if(malformed){std::cerr<<"FAIL malformed score accepted by actual Engine::load\n";return 1;}
 if(!minimum||!maximum||!plus||!minus||!zeros||results.size()!=15||results[0].text!="ValidMaximum"||results.back().text!="ValidMinimum")return 4;
 std::cout<<"PASS malformed rows rejected; signed bounds, zeroes and score ordering preserved\n";return 0;
}
