#include "../engine.h"
#include <climits>
#include <fstream>
#include <iostream>
#include <string>
#include <filesystem>

int main(int argc,char** argv) {
    if(argc!=3)return 2;
    std::filesystem::path dir=argv[1];
    std::filesystem::create_directories(dir);
    std::ofstream dict(dir/"pinyin.tsv"), symbols(dir/"symbols.tsv");
    std::string mode=argv[2];
    if(mode=="beam"||mode=="beam-low") for(int i=0;i<10;++i)dict << "a\tword" << i << "\t" << (mode=="beam"?INT_MAX:INT_MIN) << "\n";
    else if(mode=="prefix") for(int i=0;i<10;++i)dict << "aa\tword" << i << "\t" << INT_MIN << "\n";
    else if(mode=="normal") {
        dict << "ni\tNiHigh\t100000\nni\tNiLow\t1000\n";
        for(int i=0;i<8;++i)dict << "z" << std::string(i+1,'z') << "\tother" << i << "\t100\n";
    }
    else return 2;
    dict.close(); symbols << "x\tx\tsymbol\n"; symbols.close();
    wangshu::Engine engine;
    if(!engine.load(dir.string()))return 3;
    if(mode=="normal") {
        auto exact=engine.lookup("ni"), prefix=engine.lookup("n"), phrase=engine.lookup("nini");
        if(exact.size()<2||exact[0].text!="NiHigh"||exact[1].text!="NiLow"||exact[0].score!=100000||exact[1].score!=1000)return 5;
        if(prefix.size()<2||prefix[0].text!="NiHigh"||prefix[0].score!=99960||prefix[1].text!="NiLow"||prefix[1].score!=960)return 6;
        if(phrase.empty()||phrase[0].text!="NiHighNiHigh"||phrase[0].score!=1472160)return 7;
        std::cout << "normal exact/prefix/segmentation ordering and scores verified\n";
        return 0;
    }
    auto result=engine.lookup(mode=="beam"||mode=="beam-low" ? std::string(128,'a') : "a");
    if(result.empty())return 4;
    if(mode=="beam"&&result[0].score!=INT_MAX)return 8;
    if(mode=="beam-low"&&result[0].score!=INT_MIN+1500000)return 9;
    if(mode=="prefix"&&result[0].score!=INT_MIN)return 10;
    if(mode=="beam") {
        auto bonus=engine.lookup(std::string(100,'a'));
        if(bonus.empty()||bonus[0].score!=INT_MAX)return 11;
    }
    std::cout << mode << " candidates=" << result.size() << " score=" << result[0].score << "\n";
}
