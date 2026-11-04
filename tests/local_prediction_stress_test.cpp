#include "../core/engine.h"
#include "../core/mixed_composition.h"
#include <random>
#include <iostream>
#include <stdexcept>
int main(int argc,char**argv){wangshu::Engine e;if(argc!=2||!e.load(argv[1]))return 1;std::mt19937 random(4093);
 for(int n=0;n<6000;++n){std::string text;size_t size=random()%280;for(size_t i=0;i<size;++i)text+=static_cast<char>(random()%256);auto c=e.lookup(text,9,random()%105);for(const auto& value:c)if(value.text.size()>512&&text.find('`')!=std::string::npos)throw std::runtime_error("mixed output bound");auto p=e.predict(text,n%3?"":" suffix",n%2);if(p.size()>4)throw std::runtime_error("prediction limit");}
 for(int n=0;n<2000;++n){std::string raw="nihao`";for(int i=0;i<n%110;++i)raw+=static_cast<char>(32+random()%95);auto c=e.lookup(raw);for(const auto& value:c)if(value.text.empty()||value.text.size()>512)throw std::runtime_error("mixed result invalid");}
 std::cout<<"PASS 8000 fixed-seed malformed/boundary mixed and prediction probes; ASan/UBSan host only\n";
}
