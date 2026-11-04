// Real production TSF via public key callbacks and a selection-aware COM host fixture.
// The existing candidate-only suite is included for fixtures; none of its cases are run here.
#define WANGSHU_SELECTION_FIXTURES_ONLY
#include "selection_scope_test.cpp"

static int passed=0,failed=0;
static void test(const std::string& label,const std::function<void()>& body){
    try{body();passed++;std::cout<<"PASS "<<label<<std::endl;}
    catch(const std::exception& error){failed++;std::cout<<"FAIL "<<label<<" :: "<<error.what()<<std::endl;}
}
static BOOL key(SelectionFixture& f,WPARAM value){BOOL eaten=FALSE;auto h=f.service->OnKeyDown(f.context,value,0,&eaten);requireSelection(SUCCEEDED(h),"OnKeyDown failed COM boundary");return eaten;}
static void inert(SelectionFixture& f,const std::wstring& before,LONG start,LONG end){
    auto& d=f.context->document;
    requireSelection(d.text==before&&d.start==start&&d.end==end&&d.textWrites==0&&d.selectionWrites==0,"Stale composition operation changed current host text/selection");
    requireSelection(TextServiceTestAccess::pendingText(f.service)=="nihao","Rejected operation lost recoverable pinyin");
}
int main(){
    const std::vector<std::pair<std::string,WPARAM>> actions={{"continue letter",'K'},{"backspace",VK_BACK},{"raw Enter",VK_RETURN},{"Escape cancellation",VK_ESCAPE}};
    for(auto action:actions){
        test(action.first+" valid end-caret edits exactly once",[action]{SelectionFixture f;auto eaten=key(f,action.second);auto& d=f.context->document;
            std::wstring expected=action.second=='K'?L"beforenihaokafter":action.second==VK_BACK?L"beforenihaafter":action.second==VK_RETURN?L"beforenihaoafter":L"beforeafter";
            requireSelection(eaten&&d.text==expected&&d.textWrites==1&&d.selectionWrites==1,"Valid operation failed or was duplicated");});
        for(auto mutation:std::vector<std::pair<std::string,int>>{{"caret before",0},{"caret inside",1},{"caret after",2},{"forward selection",3},{"reverse selection",4},{"host changed composition",5},{"host cannot read selection",6},{"unsupported range compare",7}}){
            test(action.first+" rejects "+mutation.first,[action,mutation]{SelectionFixture f;auto& d=f.context->document;
                switch(mutation.second){case 0:d.start=d.end=1;break;case 1:d.start=d.end=8;break;case 2:d.start=d.end=13;break;case 3:d.start=2;d.end=5;break;case 4:d.start=9;d.end=7;break;case 5:d.text.replace(6,5,L"fresh");break;case 6:d.noSelection=true;break;case 7:d.denyEndCompare=true;break;}
                auto before=d.text;LONG start=d.start,end=d.end;key(f,action.second);inert(f,before,start,end);});
    }
    }
    test("failed stale operation can retry at restored exact end",[]{SelectionFixture f;auto& d=f.context->document;d.start=d.end=1;auto before=d.text;key(f,VK_BACK);inert(f,before,1,1);d.start=d.end=11;requireSelection(key(f,VK_BACK)&&d.text==L"beforenihaafter"&&d.textWrites==1,"Safe retry failed");});
    test("normal continuation tracks new owned text for subsequent backspace",[]{SelectionFixture f;key(f,'K');key(f,VK_BACK);requireSelection(f.context->document.text==L"beforenihaoafter"&&TextServiceTestAccess::pendingText(f.service)=="nihao"&&f.context->document.textWrites==2,"Owned text tracking got stale after valid continuation");});
    test("all isolated key fixture references released",[]{requireSelection(objectCount==0,"COM fixture/service ownership leak");});
    std::cout<<"RESULT passed="<<passed<<" failed="<<failed<<" total="<<(passed+failed)<<"; actual PE production TSF mock-host, real Windows NOT_RUN"<<std::endl;
    return failed?1:0;
}
