#define main existing_tsf_edit_main
#include "../tests/tsf_edit_test.cpp"
#undef main

namespace {
struct Fixture {
    TextService* service=new TextService;
    Manager* manager=new Manager;
    Context* field=new Context;
    DocumentManager* document=new DocumentManager(field);

    Fixture(){
        manager->supportsUI=true;
        manager->document=document;
        field->documentManager=document;
        if(FAILED(service->ActivateEx(manager,92,TF_TMAE_UIELEMENTENABLEDONLY)))
            throw std::runtime_error("activate synthetic TSF");
        field->doc.value=L"A👩🏽‍💻éZ";
        field->doc.cursor=1;
        field->doc.selectionEnd=10;
    }
    ~Fixture(){
        field->flush();
        service->Deactivate();
        service->Release();
        manager->Release();
        field->Release();
    }
    bool type(Context* target,std::initializer_list<WPARAM> keys){
        for(auto key:keys){
            BOOL eaten=FALSE;
            if(FAILED(scriptedKeyDown(service,target,key,&eaten))||!eaten)return false;
        }
        return true;
    }
    bool type(std::initializer_list<WPARAM> keys){return type(field,keys);}
    bool unchanged() const {
        return field->doc.value==L"A👩🏽‍💻éZ"&&field->doc.cursor==1&&
            field->doc.selectionEnd==10&&field->doc.textWrites==0&&field->starts==0;
    }
};
}

int main(){
    int pass=0,fail=0;
    auto test=[&](const char* label,const std::function<bool()>& scenario){
        try{
            if(!scenario()||objectCount!=0)throw std::runtime_error("unexpected text, UTF-16 selection, write count or COM lifetime");
            ++pass;
            std::cout<<"PASS "<<label<<std::endl;
        }catch(const std::exception& error){
            ++fail;
            std::cout<<"FAIL "<<label<<" :: "<<error.what()<<std::endl;
        }
    };
    test("preedit and Escape leave selected Unicode text intact",[]{
        Fixture f;
        return f.type({'N','I'})&&f.unchanged()&&f.type({VK_ESCAPE})&&
            f.unchanged()&&TextServiceTestAccess::pendingText(f.service).empty();
    });
    test("deleting the last preedit key leaves selected text and offsets intact",[]{
        Fixture f;
        return f.type({'N',VK_BACK})&&f.unchanged()&&TextServiceTestAccess::pendingText(f.service).empty();
    });
    test("confirmed candidate replaces exactly the original selection once",[]{
        Fixture f;
        return f.type({'N','I'})&&f.unchanged()&&f.type({VK_SPACE})&&
            f.field->doc.value==L"A你Z"&&f.field->doc.textWrites==1&&
            f.field->doc.cursor==2&&f.field->doc.selectionEnd==-1;
    });
    test("raw mixed text stays local until confirmed",[]{
        Fixture f;
        return f.type({VK_OEM_3,'A','B',VK_OEM_3})&&f.unchanged()&&f.type({VK_RETURN})&&
            f.field->doc.value==L"AabZ"&&f.field->doc.textWrites==1;
    });
    test("focused selection moved over identical text refuses confirmation and consumes space",[]{
        Fixture f;
        f.field->doc.value=L"AfooBfooC";
        f.field->doc.selectionEnd=4;
        if(!f.type({'N','I'}))return false;
        f.field->doc.cursor=5;
        f.field->doc.selectionEnd=8;
        return f.type({VK_SPACE})&&f.field->doc.value==L"AfooBfooC"&&f.field->doc.textWrites==0&&
            f.field->doc.cursor==5&&f.field->doc.selectionEnd==8&&f.type({VK_ESCAPE});
    });
    test("host changes selected text before a queued candidate is applied",[]{
        Fixture f;
        if(!f.type({'N','I'}))return false;
        f.field->deferred=true;
        if(!TextServiceTestAccess::click(f.service,f.field,"你"))return false;
        f.field->doc.value[8]=L'x';
        const auto edited=f.field->doc.value;
        auto result=f.field->flush();
        return result.size()==1&&FAILED(result[0])&&f.field->doc.value==edited&&f.field->doc.textWrites==0;
    });
    test("focus loss during selected-text confirmation prevents a late write",[]{
        Fixture f;
        if(!f.type({'N','I'}))return false;
        f.field->doc.onRead=[&]{f.service->OnSetFocus(FALSE);};
        return f.type({VK_RETURN})&&f.unchanged();
    });
    test("unreadable selection is preserved and never replaced by a failed confirmation or Space",[]{
        Fixture f;
        f.field->doc.noRead=true;
        return f.type({'N','I',VK_SPACE})&&f.unchanged()&&f.type({VK_ESCAPE})&&f.unchanged();
    });
    test("switching fields drops selected preedit and its queued candidate",[]{
        Fixture f;
        auto* next=new Context;
        next->documentManager=f.document;
        const bool typed=f.type({'N','I'});
        f.field->deferred=true;
        const bool queued=typed&&TextServiceTestAccess::click(f.service,f.field,"你");
        f.document->setTop(next);
        f.service->OnPushContext(next);
        const bool confirmed=f.type(next,{'H','A','O',VK_SPACE});
        const auto stale=f.field->flush();
        const bool result=queued&&confirmed&&stale.size()==1&&FAILED(stale[0])&&f.unchanged()&&
            next->doc.value==L"before好after"&&next->doc.cursor==7;
        next->Release();
        return result;
    });
    test("adjusted insertion query cannot override the actual selection",[]{
        Fixture f;
        f.field->queryCollapsed=true;
        if(!f.type({'N','I',VK_SPACE})||!f.unchanged())return false;
        f.field->queryCollapsed=false;
        return f.type({VK_SPACE})&&f.field->doc.value==L"A你Z"&&f.field->doc.textWrites==1;
    });
    test("private selection is never read or replaced by the preview",[]{
        Fixture f;
        f.field->scope=IS_PRIVATE;
        bool read=false;
        f.field->doc.onRead=[&]{read=true;};
        return f.type({'N','I',VK_SPACE,VK_ESCAPE})&&f.unchanged()&&!read;
    });
    test("focus changes inside the first selected-text read discard the original key",[]{
        Fixture f;
        f.field->doc.onRead=[&]{f.service->OnSetFocus(FALSE);};
        return f.type({'N'})&&f.unchanged()&&TextServiceTestAccess::pendingText(f.service).empty();
    });
    std::cout<<"TOTAL "<<pass<<" PASS, "<<fail<<" FAIL; synthetic TSF selection preedit"<<std::endl;
    return fail?1:0;
}
