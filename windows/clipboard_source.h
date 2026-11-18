#pragma once

#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>
#include <algorithm>
#include <optional>
#include <string>

namespace wangshu {

// A foreground, explicit reader. The store checks current opt-in before
// invoking it. It opens the clipboard read-only and never changes its contents.
inline std::optional<std::string> readClipboardText(HWND foreground,bool sameWindowOwner=false) {
    if (!foreground||GetForegroundWindow()!=foreground) return {};
    const UINT exclude=RegisterClipboardFormatW(L"ExcludeClipboardContentFromMonitorProcessing");
    const UINT history=RegisterClipboardFormatW(L"CanIncludeInClipboardHistory");
    if (!exclude||!history||!OpenClipboard(foreground)) return {};
    struct ClipboardClose {~ClipboardClose(){CloseClipboard();}} close;
    const auto sequence=GetClipboardSequenceNumber();
    if (!sequence||IsClipboardFormatAvailable(exclude)) return {};
    if (sameWindowOwner) {
        const auto owner=GetClipboardOwner();
        if (!owner||GetAncestor(owner,GA_ROOT)!=foreground) return {};
    }
    auto allows=[](UINT marker) {
        if (!IsClipboardFormatAvailable(marker)) return true;
        const auto handle=GetClipboardData(marker);
        if (!handle||GlobalSize(handle)<sizeof(DWORD)) return false;
        const auto* value=static_cast<const DWORD*>(GlobalLock(handle));
        if (!value) return false;
        const bool allowed=*value==1;
        GlobalUnlock(handle);
        return allowed;
    };
    if (!allows(history)) return {};
    for (UINT format:{CF_HDROP,CF_BITMAP,CF_DIB,CF_DIBV5,CF_ENHMETAFILE,CF_METAFILEPICT})
        if (IsClipboardFormatAvailable(format)) return {};
    if (!IsClipboardFormatAvailable(CF_UNICODETEXT)) return {};
    const auto handle=GetClipboardData(CF_UNICODETEXT);
    if (!handle) return {};
    const auto limit=std::min<size_t>(GlobalSize(handle)/sizeof(wchar_t),16001);
    const auto* value=static_cast<const wchar_t*>(GlobalLock(handle));
    if (!value) return {};
    struct Unlock {HGLOBAL handle;~Unlock(){GlobalUnlock(handle);}} unlock{handle};
    const auto* end=std::find(value,value+limit,L'\0');
    if (end==value||end==value+limit) return {};
    const auto units=static_cast<int>(end-value);
    const auto count=WideCharToMultiByte(CP_UTF8,WC_ERR_INVALID_CHARS,value,units,nullptr,0,nullptr,nullptr);
    if (!count) return {};
    std::string result(static_cast<size_t>(count),'\0');
    if (WideCharToMultiByte(CP_UTF8,WC_ERR_INVALID_CHARS,value,units,result.data(),count,nullptr,nullptr)!=count||
        GetClipboardSequenceNumber()!=sequence||GetForegroundWindow()!=foreground) return {};
    return result;
}

}
