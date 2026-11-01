#include <jni.h>
#include <mutex>
#include "engine.h"
static wangshu::Engine engine;
static std::mutex gate;
static std::string str(JNIEnv* e,jstring s){if(!s)return {};const jchar* data=e->GetStringChars(s,nullptr);std::u16string v(reinterpret_cast<const char16_t*>(data),e->GetStringLength(s));e->ReleaseStringChars(s,data);return wangshu::utf8(v);}
static jstring java(JNIEnv* e,const std::string& s){auto u=wangshu::utf16(s);return e->NewString(reinterpret_cast<const jchar*>(u.data()),static_cast<jsize>(u.size()));}
extern "C" JNIEXPORT jlong JNICALL Java_org_wangshu_ime_NativeBridge_load(JNIEnv* e,jclass,jstring path){std::lock_guard<std::mutex> lock(gate);return engine.load(str(e,path))?static_cast<jlong>(engine.entries()):0;}
extern "C" JNIEXPORT jobjectArray JNICALL Java_org_wangshu_ime_NativeBridge_lookup(JNIEnv* e,jclass,jstring input,jint page){std::lock_guard<std::mutex> lock(gate);auto list=engine.lookup(str(e,input),9,page<0?0:static_cast<size_t>(page));jobjectArray out=e->NewObjectArray(static_cast<jsize>(list.size()*2),e->FindClass("java/lang/String"),nullptr);for(size_t i=0;i<list.size();i++){jstring a=java(e,list[i].text),b=java(e,list[i].annotation);e->SetObjectArrayElement(out,i*2,a);e->SetObjectArrayElement(out,i*2+1,b);e->DeleteLocalRef(a);e->DeleteLocalRef(b);}return out;}
extern "C" JNIEXPORT jobjectArray JNICALL Java_org_wangshu_ime_NativeBridge_symbols(JNIEnv* e,jclass,jstring category){std::lock_guard<std::mutex> lock(gate);auto list=engine.symbols(str(e,category));jobjectArray out=e->NewObjectArray(static_cast<jsize>(list.size()*2),e->FindClass("java/lang/String"),nullptr);for(size_t i=0;i<list.size();i++){jstring a=java(e,list[i].text),b=java(e,list[i].alias);e->SetObjectArrayElement(out,i*2,a);e->SetObjectArrayElement(out,i*2+1,b);e->DeleteLocalRef(a);e->DeleteLocalRef(b);}return out;}
extern "C" JNIEXPORT jstring JNICALL Java_org_wangshu_ime_NativeBridge_voiceMath(JNIEnv* e,jclass,jstring text){std::lock_guard<std::mutex> lock(gate);return java(e,engine.voiceMath(str(e,text)));}
extern "C" JNIEXPORT jstring JNICALL Java_org_wangshu_ime_NativeBridge_punctuation(JNIEnv* e,jclass,jint c,jboolean chinese){std::lock_guard<std::mutex> lock(gate);return java(e,engine.punctuation(static_cast<char>(c),chinese));}
