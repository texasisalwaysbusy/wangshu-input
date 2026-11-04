// Actual production EndCompositionSession, with a COM-only object that cannot access text.
#define main unused_tsf_main
#include "../tests/tsf_edit_test.cpp"
#undef main
#include <functional>
struct EndCounts{int calls=0,reads=0,destroyed=0;TfEditCookie cookie=0;HRESULT result=S_OK;};
class EndOnlyComposition final:public CompositionStub {
    EndCounts& counts;
public:
    explicit EndOnlyComposition(EndCounts& value):counts(value){}
    ~EndOnlyComposition(){++counts.destroyed;}
    HRESULT STDMETHODCALLTYPE GetRange(ITfRange**)override{++counts.reads;return E_ACCESSDENIED;}
    HRESULT STDMETHODCALLTYPE EndComposition(TfEditCookie cookie)override{++counts.calls;counts.cookie=cookie;return counts.result;}
};
static int good=0,bad=0;
static void scenario(const char* name,const std::function<void()>& run){try{run();++good;std::cout<<"PASS "<<name<<std::endl;}catch(const std::exception& error){++bad;std::cout<<"FAIL "<<name<<" :: "<<error.what()<<std::endl;}}
static void expect(bool value,const char* why){if(!value)throw std::runtime_error(why);}
int main(){
    scenario("end-only session owns exact composition and forwards successful cookie once",[]{EndCounts count;auto* object=new EndOnlyComposition(count);auto* session=new EndCompositionSession(object);object->Release();expect(count.destroyed==0,"Session failed to retain owned composition");auto result=session->DoEditSession(919);session->Release();expect(result==S_OK&&count.calls==1&&count.reads==0&&count.cookie==919&&count.destroyed==1,"Incorrect ownership/read/cookie or success propagation");});
    for(auto value:std::vector<std::pair<const char*,HRESULT>>{{"end refusal is returned without text reads or retry",E_ACCESSDENIED},{"already ended outcome is returned without inventing success",E_UNEXPECTED}}){scenario(value.first,[value]{EndCounts count;count.result=value.second;auto* object=new EndOnlyComposition(count);auto* session=new EndCompositionSession(object);object->Release();auto result=session->DoEditSession(920);session->Release();expect(result==value.second&&count.calls==1&&count.reads==0&&count.cookie==920&&count.destroyed==1,"Failure was swallowed/retried or ownership leaked");});}
    scenario("abandoned queued session releases composition without executing it",[]{EndCounts count;auto* object=new EndOnlyComposition(count);auto* session=new EndCompositionSession(object);object->Release();session->Release();expect(count.calls==0&&count.reads==0&&count.destroyed==1,"Abandoned request ran cleanup or leaked ownership");});
    std::cout<<"RESULT passed="<<good<<" failed="<<bad<<" total="<<(good+bad)<<"; actual PE production end-only adapter; real Windows NOT_RUN"<<std::endl;return bad?1:0;
}
