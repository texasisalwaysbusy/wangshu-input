#define main existing_tsf_edit_main
#include "../tests/tsf_edit_test.cpp"
#undef main

namespace {
struct Fixture {
    TextService* service=new TextService;
    Manager* manager=new Manager;
    Context* first=new Context;
    Context* second=new Context;
    DocumentManager* document=new DocumentManager(first);

    Fixture(){
        manager->supportsUI=true;
        manager->document=document;
        first->documentManager=document;
        second->documentManager=document;
        if(FAILED(service->ActivateEx(manager,91,TF_TMAE_UIELEMENTENABLEDONLY)))
            throw std::runtime_error("activate mock TSF host");
    }
    ~Fixture(){
        first->flush();
        second->flush();
        service->Deactivate();
        service->Release();
        manager->Release();
        first->Release();
        second->Release();
    }
    bool type(Context* context,std::initializer_list<WPARAM> keys){
        for(auto key:keys){
            BOOL eaten=FALSE;
            if(FAILED(scriptedKeyDown(service,context,key,&eaten))||!eaten)return false;
        }
        return true;
    }
};
}

int main(){
    int passed=0,failed=0;
    auto test=[&](const char* description,const std::function<bool()>& scenario){
        try{
            if(!scenario()||objectCount!=0)throw std::runtime_error("unexpected document, composition or COM state");
            passed++;
            std::cout<<"PASS "<<description<<std::endl;
        }catch(const std::exception& error){
            failed++;
            std::cout<<"FAIL "<<description<<" :: "<<error.what()<<std::endl;
        }
    };
    test("a new top in the same document gets a fresh composition",[]{
        Fixture f;
        if(!f.type(f.first,{'N','I'}))return false;
        f.document->setTop(f.second);
        f.service->OnPushContext(f.second);
        return f.type(f.second,{'H','A','O',VK_SPACE})&&
            f.first->doc.value==L"beforeniafter"&&f.second->doc.value==L"before好after";
    });
    test("a push in a background document preserves foreground composition",[]{
        Fixture f;
        auto background=new DocumentManager(f.second);
        f.second->documentManager=background;
        const bool started=f.type(f.first,{'N','I'});
        f.service->OnPushContext(f.second);
        background->Release();
        return started&&f.type(f.first,{VK_SPACE})&&
            f.first->doc.value==L"before你after"&&f.second->doc.value==L"beforeafter";
    });
    test("a first key resolves a push received before the new top is visible",[]{
        Fixture f;
        if(!f.type(f.first,{'N','I'}))return false;
        f.service->OnPushContext(f.second);
        f.document->setTop(f.second);
        return f.type(f.second,{'H','A','O',VK_SPACE})&&
            f.first->doc.value==L"beforeniafter"&&f.second->doc.value==L"before好after";
    });
    test("popping the composing context restores input in the underlying context",[]{
        Fixture f;
        f.document->setTop(f.second);
        if(!f.type(f.second,{'N','I'}))return false;
        f.document->setTop(f.first);
        f.service->OnPopContext(f.second);
        return f.type(f.first,{'H','A','O',VK_SPACE})&&
            f.first->doc.value==L"before好after"&&f.second->doc.value==L"beforeniafter";
    });
    test("delayed candidate and composition cleanup leave the new context intact",[]{
        Fixture f;
        if(!f.type(f.first,{'N','I'}))return false;
        f.first->deferred=true;
        if(!TextServiceTestAccess::click(f.service,f.first,"你"))return false;
        f.document->setTop(f.second);
        f.service->OnPushContext(f.second);
        if(!f.type(f.second,{'H','A','O'}))return false;
        auto deferred=f.first->flush();
        return deferred.size()==2&&deferred[0]==E_ABORT&&SUCCEEDED(deferred[1])&&
            f.first->doc.value==L"beforeniafter"&&f.second->doc.value==L"beforehaoafter"&&
            f.type(f.second,{VK_SPACE})&&f.second->doc.value==L"before好after";
    });
    std::cout<<"TOTAL "<<passed<<" PASS, "<<failed<<" FAIL; synthetic TSF context stack"<<std::endl;
    return failed?1:0;
}
