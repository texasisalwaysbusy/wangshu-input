#include <jni.h>
#include <memory>
#include <mutex>
#include "engine.h"
static std::shared_ptr<const wangshu::Engine> activeEngine=std::make_shared<wangshu::Engine>();
static std::mutex loadMutex;

static std::shared_ptr<const wangshu::Engine> engineSnapshot(){
    return std::atomic_load(&activeEngine);
}

static std::string str(JNIEnv* e,jstring s){if(!s)return {};const jchar* data=e->GetStringChars(s,nullptr);std::u16string v(reinterpret_cast<const char16_t*>(data),e->GetStringLength(s));e->ReleaseStringChars(s,data);return wangshu::utf8(v);}
static jstring java(JNIEnv* e,const std::string& s){auto u=wangshu::utf16(s);return e->NewString(reinterpret_cast<const jchar*>(u.data()),static_cast<jsize>(u.size()));}
extern "C" JNIEXPORT jlong JNICALL Java_org_wangshu_ime_NativeBridge_load(JNIEnv* e,jclass,jstring path){
    std::lock_guard<std::mutex> lock(loadMutex);
    auto replacement=std::make_shared<wangshu::Engine>();
    if(!replacement->load(str(e,path)))return 0;
    auto entries=static_cast<jlong>(replacement->entries());
    // Readers keep their own immutable engine while a replacement is built.
    std::atomic_store(&activeEngine,std::shared_ptr<const wangshu::Engine>(std::move(replacement)));
    return entries;
}
extern "C" JNIEXPORT jobjectArray JNICALL Java_org_wangshu_ime_NativeBridge_lookup(JNIEnv* e,jclass,jstring input,jint page){auto list=engineSnapshot()->lookup(str(e,input),9,page<0?0:static_cast<size_t>(page));jobjectArray out=e->NewObjectArray(static_cast<jsize>(list.size()*2),e->FindClass("java/lang/String"),nullptr);for(size_t i=0;i<list.size();i++){jstring a=java(e,list[i].text),b=java(e,list[i].annotation);e->SetObjectArrayElement(out,i*2,a);e->SetObjectArrayElement(out,i*2+1,b);e->DeleteLocalRef(a);e->DeleteLocalRef(b);}return out;}
extern "C" JNIEXPORT jobjectArray JNICALL Java_org_wangshu_ime_NativeBridge_symbols(JNIEnv* e,jclass,jstring category){auto list=engineSnapshot()->symbols(str(e,category));jobjectArray out=e->NewObjectArray(static_cast<jsize>(list.size()*2),e->FindClass("java/lang/String"),nullptr);for(size_t i=0;i<list.size();i++){jstring a=java(e,list[i].text),b=java(e,list[i].alias);e->SetObjectArrayElement(out,i*2,a);e->SetObjectArrayElement(out,i*2+1,b);e->DeleteLocalRef(a);e->DeleteLocalRef(b);}return out;}
extern "C" JNIEXPORT jstring JNICALL Java_org_wangshu_ime_NativeBridge_voiceMath(JNIEnv* e,jclass,jstring text){return java(e,engineSnapshot()->voiceMath(str(e,text)));}
extern "C" JNIEXPORT jstring JNICALL Java_org_wangshu_ime_NativeBridge_punctuation(JNIEnv* e,jclass,jint c,jboolean chinese){return java(e,engineSnapshot()->punctuation(static_cast<char>(c),chinese));}

// Prediction contexts must be validated before UTF-16 is converted: utf8()
// deliberately repairs lone surrogates for existing APIs, which would otherwise
// hide malformed earlier lines from the prediction provider's whole-input guard.
static bool predictionText(JNIEnv* e,jstring source,std::string& output){
    if(!source){output.clear();return true;}
    jsize length=e->GetStringLength(source);if(length>32768)return false;
    const jchar* data=e->GetStringChars(source,nullptr);if(!data)return false;
    bool valid=true;
    for(jsize i=0;i<length;i++){
        jchar c=data[i];
        if(c>=0xd800&&c<=0xdbff){if(++i>=length||data[i]<0xdc00||data[i]>0xdfff){valid=false;break;}}
        else if(c>=0xdc00&&c<=0xdfff){valid=false;break;}
    }
    if(valid)output=wangshu::utf8(std::u16string(reinterpret_cast<const char16_t*>(data),static_cast<size_t>(length)));
    e->ReleaseStringChars(source,data);return valid;
}
static jobjectArray prediction(JNIEnv* env,jstring before,jstring after,bool english,bool numericPanel){
    std::string left,right;
    if(!predictionText(env,before,left)||!predictionText(env,after,right)){
        if(env->ExceptionCheck())return nullptr;
        return env->NewObjectArray(0,env->FindClass("java/lang/String"),nullptr);
    }
    auto list=engineSnapshot()->predictForDisplay(left,right,english,numericPanel);
    jobjectArray out=env->NewObjectArray(static_cast<jsize>(list.size()*2),env->FindClass("java/lang/String"),nullptr);
    if(!out)return nullptr;
    for(size_t i=0;i<list.size();i++){
        jstring value=java(env,list[i].text),label=java(env,list[i].annotation);
        env->SetObjectArrayElement(out,i*2,value);
        env->SetObjectArrayElement(out,i*2+1,label);
        env->DeleteLocalRef(value);
        env->DeleteLocalRef(label);
    }
    return out;
}
extern "C" JNIEXPORT jobjectArray JNICALL Java_org_wangshu_ime_NativeBridge_predict(JNIEnv* env,jclass,jstring before,jstring after,jboolean english){
    return prediction(env,before,after,english,false);
}
extern "C" JNIEXPORT jobjectArray JNICALL Java_org_wangshu_ime_NativeBridge_predictInNumbers(JNIEnv* env,jclass,jstring before,jstring after,jboolean english){
    return prediction(env,before,after,english,true);
}
