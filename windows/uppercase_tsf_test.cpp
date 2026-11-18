// Production key routing with an in-memory TSF text store; no system IME registration.
#define main unused_basic_tsf
#include "../tests/tsf_edit_test.cpp"
#undef main

namespace {
struct Keyboard {
    BYTE saved[256]{};
    Keyboard(bool shift=false,bool ctrl=false,bool caps=false){
        BYTE keys[256]{};
        if(shift)keys[VK_SHIFT]=0x80;
        if(ctrl)keys[VK_CONTROL]=0x80;
        if(caps)keys[VK_CAPITAL]=1;
        if(!GetKeyboardState(saved)||!SetKeyboardState(keys))throw std::runtime_error("mock keyboard state");
    }
    ~Keyboard(){SetKeyboardState(saved);}
};
struct Fixture {
    TextService* service=new TextService;
    Manager* manager=new Manager;
    Context* context=new Context;

    explicit Fixture(bool english=true){
        moduleHandle=GetModuleHandleW(nullptr);
        manager->supportsUI=true;manager->document=new DocumentManager(context);
        context->documentManager=manager->document;
        if(FAILED(service->ActivateEx(manager,917,TF_TMAE_UIELEMENTENABLEDONLY)))throw std::runtime_error("activation");
        if(english&&!key(VK_SPACE,true))throw std::runtime_error("English toggle");
    }
    ~Fixture(){context->flush();service->Deactivate();service->Release();context->Release();manager->Release();}
    bool test(WPARAM key,LPARAM message=0,ITfContext* owner=nullptr){
        BOOL consumed=FALSE;
        if(FAILED(service->OnTestKeyDown(owner?owner:context,key,message,&consumed)))throw std::runtime_error("key preflight");
        return consumed!=FALSE;
    }
    bool dispatch(WPARAM key,LPARAM message=0,ITfContext* owner=nullptr){
        BOOL consumed=FALSE;
        if(FAILED(service->OnKeyDown(owner?owner:context,key,message,&consumed)))throw std::runtime_error("key dispatch");
        return consumed!=FALSE;
    }
    bool key(WPARAM letter,bool shift=false,bool ctrl=false,bool caps=false,LPARAM message=0){
        Keyboard keys(shift,ctrl,caps);
        return test(letter,message)&&dispatch(letter,message);
    }
    bool arm(){return key('U',true,true);}
};
}

int main(){
    try{
        {
            Fixture f;
            check(!f.key('A')&&!f.key('A',true)&&!f.key('A',false,false,true),
                  "ordinary English, Shift and locked case remain host input");
            check(f.arm()&&!f.key('4')&&f.key('A')&&f.context->doc.value==L"beforeAafter"&&!f.key('B'),
                  "one-shot case survives a digit and applies to one English letter");
        }
        {
            Fixture f;
            check(f.arm()&&f.key('U',true,true,false,0x40000000)&&f.key('A'),
                  "shortcut auto-repeat does not cancel or rearm");
            check(f.arm()&&f.arm()&&!f.key('B'),"second fresh shortcut cancels");
        }
        {
            Fixture f(false);
            check(!f.arm()&&f.key(VK_OEM_3)&&f.arm()&&f.key('A')&&f.key('B')&&
                  f.key(VK_OEM_3)&&f.key(VK_RETURN)&&f.context->doc.value==L"beforeAbafter",
                  "mixed mode starts from delimiter only, once, and confirms the original English segment");
        }
        {
            Fixture f(false);
            check(f.key(VK_OEM_3)&&f.key('A',false,false,true)&&f.key('B',true,false,true)&&
                  f.key(VK_OEM_3)&&f.key(VK_RETURN)&&f.context->doc.value==L"beforeAbafter",
                  "mixed mode retains Caps Lock and physical Shift XOR Caps Lock");
        }
        {
            Fixture f;check(f.arm()&&f.test('A',10),"claim a letter in the current field");
            f.context->doc.cursor=2;f.context->doc.selectionEnd=4;
            check(f.dispatch('A',10)&&f.context->doc.textWrites==0,
                  "a claimed letter cannot replace a newly selected range");
        }
        {
            Fixture f;check(f.arm()&&f.test('A',11),"claim before focus change");
            auto* other=new Context;
            drop(f.manager->document);f.manager->document=new DocumentManager(other);
            f.service->OnSetFocus(FALSE);
            check(f.test('A',11,other)&&f.dispatch('A',11,other)&&other->doc.textWrites==0&&
                  f.context->doc.textWrites==0,"duplicate preflight preserves ownership and consumes a late key without writing");
            check(!f.test('B',12,other),"new field has no armed case");
            other->Release();
        }
        {
            Fixture f;
            f.context->scope=IS_PASSWORD;
            check(!f.arm()&&!f.key('A')&&f.context->doc.textWrites==0,"password remains with the host");
            f.context->scope=IS_URL;
            check(!f.arm(),"non-prose URL scope retains its shortcut");
            f.context->scope=IS_DEFAULT;
            check(f.arm()&&f.test('A',13),"arm in prose field");
            f.context->scope=IS_PRIVATE;
            check(f.dispatch('A',13)&&f.context->doc.textWrites==0,"privacy changes before write suppress the case action");
        }
        {
            Fixture f;
            check(f.arm()&&f.test('A',11),"claim before key release");
            f.service->OnSetFocus(FALSE);
            BOOL eaten=TRUE;
            check(SUCCEEDED(f.service->OnTestKeyUp(f.context,'A',0,&eaten))&&!eaten&&
                  !f.test('A',11),"key release retires a claim without consuming a later identical press");
        }
        {
            Fixture f;check(f.arm(),"bind before native refusal");
            f.context->doc.noFull=true;
            check(!f.key('A')&&f.context->doc.textWrites==0,"unavailable proof leaves the untreated key to the host");
        }
        {
            Fixture f;
            check(f.arm()&&f.test('A',14),"claim before uncertain write");
            f.context->doc.writeThenFail=true;
            check(f.dispatch('A',14)&&!f.key('A')&&f.context->doc.textWrites==1&&
                  f.context->doc.value==L"beforeAafter","write-then-fail consumes once and never retries");
        }
        {
            Fixture f;check(f.arm()&&f.test('A',15),"claim before reentrant host read");
            f.context->doc.onRead=[&]{f.service->OnSetFocus(FALSE);};
            check(f.dispatch('A',15)&&f.context->doc.textWrites==0,"focus loss during protected read prevents writing");
        }
        check(objectCount==0,"all synthetic TSF references released");
        std::cout<<"TOTAL "<<checks<<" PASS; native mock TSF, registered app and real keyboard NOT_RUN\n";
        return 0;
    }catch(const std::exception& e){
        std::cerr<<"FAIL "<<e.what()<<"\n";
        return 1;
    }
}
