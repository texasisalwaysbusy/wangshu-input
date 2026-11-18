#pragma once

#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>
#include <objbase.h>
#include <algorithm>
#include <stdexcept>
#include <string>
#include <vector>

namespace wangshu::local_store_io {
struct Handle {
    HANDLE value=INVALID_HANDLE_VALUE;
    explicit Handle(HANDLE h):value(h){}
    ~Handle(){if(value!=INVALID_HANDLE_VALUE&&value!=nullptr)CloseHandle(value);}
    Handle(const Handle&)=delete;Handle& operator=(const Handle&)=delete;
};
struct WriteLock {
    Handle handle;
    bool owned=false;
    explicit WriteLock(const std::wstring& path,DWORD timeout=5000):handle([&]{
        std::vector<wchar_t> buffer(32768);DWORD n=GetFullPathNameW(path.c_str(),static_cast<DWORD>(buffer.size()),buffer.data(),nullptr);
        if(!n||n>=buffer.size())throw std::runtime_error("Personal path unavailable");
        unsigned long long hash=1469598103934665603ULL;
        for(DWORD i=0;i<n;i++){wchar_t c=buffer[i];if(c>=L'A'&&c<=L'Z')c+=32;if(c==L'/')c=L'\\';hash^=static_cast<unsigned short>(c);hash*=1099511628211ULL;}
        auto name=L"Local\\WangshuImePersonal-"+std::to_wstring(hash);
        return CreateMutexW(nullptr,FALSE,name.c_str());
    }()){
        if(!handle.value)throw std::runtime_error("Personal write lock unavailable");
        DWORD result=WaitForSingleObject(handle.value,timeout);
        if(result!=WAIT_OBJECT_0&&result!=WAIT_ABANDONED)throw std::runtime_error("Another personal-data operation is still running");
        owned=true;
    }
    ~WriteLock(){if(owned)ReleaseMutex(handle.value);}
};
inline std::wstring uniqueSuffix(){GUID id{};if(FAILED(CoCreateGuid(&id)))throw std::runtime_error("Temporary identifier unavailable");wchar_t buffer[40];StringFromGUID2(id,buffer,40);return buffer;}
struct Stamp {bool exists=false;unsigned long long time=0,size=0;};
inline Stamp metadata(const std::wstring& path){WIN32_FILE_ATTRIBUTE_DATA value{};if(!GetFileAttributesExW(path.c_str(),GetFileExInfoStandard,&value)){DWORD error=GetLastError();if(error==ERROR_FILE_NOT_FOUND||error==ERROR_PATH_NOT_FOUND)return {};throw std::runtime_error("Personal-data file metadata is unavailable");}if(value.dwFileAttributes&FILE_ATTRIBUTE_DIRECTORY)throw std::runtime_error("Expected a personal-data file, not a directory");Stamp out;out.exists=true;out.time=(static_cast<unsigned long long>(value.ftLastWriteTime.dwHighDateTime)<<32)|value.ftLastWriteTime.dwLowDateTime;out.size=(static_cast<unsigned long long>(value.nFileSizeHigh)<<32)|value.nFileSizeLow;return out;}
inline std::string signature(const std::wstring& path){auto a=metadata(path),b=metadata(path+L".bak");return std::to_string(a.exists)+":"+std::to_string(a.time)+":"+std::to_string(a.size)+":"+std::to_string(b.exists)+":"+std::to_string(b.time)+":"+std::to_string(b.size);}
inline std::string readBytes(const std::wstring& path){
    Handle handle(CreateFileW(path.c_str(),GENERIC_READ,FILE_SHARE_READ|FILE_SHARE_WRITE|FILE_SHARE_DELETE,nullptr,OPEN_EXISTING,FILE_ATTRIBUTE_NORMAL,nullptr));
    if(handle.value==INVALID_HANDLE_VALUE)throw std::runtime_error("Cannot read selected personal-data file");
    LARGE_INTEGER size{};if(!GetFileSizeEx(handle.value,&size)||size.QuadPart<0||size.QuadPart>1024*1024)throw std::runtime_error("Personal file exceeds 1MiB or size is unavailable");
    std::string bytes;bytes.reserve(static_cast<size_t>(size.QuadPart));char buffer[16384];DWORD n=0;
    do{if(!ReadFile(handle.value,buffer,sizeof(buffer),&n,nullptr))throw std::runtime_error("Personal-data read did not complete");if(bytes.size()+n>1024*1024)throw std::runtime_error("Personal file grew beyond 1MiB");bytes.append(buffer,n);}while(n);
    return bytes;
}
inline void writeCreated(const std::wstring& path,const std::string& bytes){
    Handle handle(CreateFileW(path.c_str(),GENERIC_WRITE,0,nullptr,CREATE_NEW,FILE_ATTRIBUTE_NORMAL,nullptr));
    if(handle.value==INVALID_HANDLE_VALUE)throw std::runtime_error("Output already exists or cannot be created; nothing overwritten");
    size_t done=0;while(done<bytes.size()){DWORD n=0,part=static_cast<DWORD>(std::min<size_t>(16384,bytes.size()-done));if(!WriteFile(handle.value,bytes.data()+done,part,&n,nullptr)||n!=part)throw std::runtime_error("Personal-data write did not complete");done+=n;}
    if(!FlushFileBuffers(handle.value))throw std::runtime_error("Personal-data flush did not complete");
}
// The handle identifies only this operation's CREATE_NEW file. Never scan or
// delete by path: a different file can appear at the same path after a race.
struct OwnedTemporary {
    Handle handle;
    std::wstring path;
    bool owned=true;
    explicit OwnedTemporary(std::wstring name):handle(CreateFileW(name.c_str(),GENERIC_WRITE|DELETE,FILE_SHARE_DELETE,nullptr,CREATE_NEW,FILE_ATTRIBUTE_NORMAL,nullptr)),path(std::move(name)){
        if(handle.value==INVALID_HANDLE_VALUE){owned=false;throw std::runtime_error("Temporary file already exists or cannot be created");}
    }
    void write(const std::string& bytes){
        size_t done=0;while(done<bytes.size()){DWORD n=0,part=static_cast<DWORD>(std::min<size_t>(16384,bytes.size()-done));if(!WriteFile(handle.value,bytes.data()+done,part,&n,nullptr)||n!=part)throw std::runtime_error("Personal-data write did not complete");done+=n;}
        if(!FlushFileBuffers(handle.value))throw std::runtime_error("Personal-data flush did not complete");
    }
    void publish(const std::wstring& destination,bool replace=true){
        if(!MoveFileExW(path.c_str(),destination.c_str(),(replace?MOVEFILE_REPLACE_EXISTING:0)|MOVEFILE_WRITE_THROUGH))throw std::runtime_error("Atomic replacement failed; prior data retained");
        owned=false; // Destination now owns the bytes; never delete it on readback failure.
        bool closed=CloseHandle(handle.value)!=FALSE;handle.value=INVALID_HANDLE_VALUE;
        if(!closed)throw std::runtime_error("Replacement completed but temporary handle close failed; refresh before retrying");
    }
    bool discard() noexcept {
        if(!owned)return true;
        FILE_DISPOSITION_INFO info{};info.DeleteFile=TRUE;
        bool marked=SetFileInformationByHandle(handle.value,FileDispositionInfo,&info,sizeof(info))!=FALSE;
        bool closed=CloseHandle(handle.value)!=FALSE;handle.value=INVALID_HANDLE_VALUE;owned=false;
        // Deletion can be delayed by another handle or denied; report uncertainty.
        if(!marked||!closed)return false;
        DWORD attributes=GetFileAttributesW(path.c_str());if(attributes!=INVALID_FILE_ATTRIBUTES)return false;
        DWORD error=GetLastError();return error==ERROR_FILE_NOT_FOUND||error==ERROR_PATH_NOT_FOUND;
    }
    ~OwnedTemporary(){if(owned)discard();}
};

}
