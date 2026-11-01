#pragma once
#include <msctf.h>
// ABI from Microsoft's public Windows SDK msctf.h; this MinGW version omits it.
// https://learn.microsoft.com/windows/win32/api/msctf/nn-msctf-itftextinputprocessorex
#ifndef __ITfTextInputProcessorEx_INTERFACE_DEFINED__
#define __ITfTextInputProcessorEx_INTERFACE_DEFINED__
struct ITfTextInputProcessorEx : public ITfTextInputProcessor {
    virtual HRESULT STDMETHODCALLTYPE ActivateEx(ITfThreadMgr*,TfClientId,DWORD)=0;
};
inline constexpr IID IID_ITfTextInputProcessorEx={0x6e4e2102,0xf9cd,0x433d,{0xb4,0x96,0x30,0x3c,0xe0,0x3a,0x65,0x07}};
#endif

// Candidate-list ABI checked against Microsoft's generated SDK msctf.h on 2026-10-23.
// Only interface contracts are declared here; implementation is project-owned.
#ifndef __ITfCandidateListUIElement_INTERFACE_DEFINED__
#define __ITfCandidateListUIElement_INTERFACE_DEFINED__
struct ITfCandidateListUIElement : public ITfUIElement {
    virtual HRESULT STDMETHODCALLTYPE GetUpdatedFlags(DWORD*)=0;
    virtual HRESULT STDMETHODCALLTYPE GetDocumentMgr(ITfDocumentMgr**)=0;
    virtual HRESULT STDMETHODCALLTYPE GetCount(UINT*)=0;
    virtual HRESULT STDMETHODCALLTYPE GetSelection(UINT*)=0;
    virtual HRESULT STDMETHODCALLTYPE GetString(UINT,BSTR*)=0;
    virtual HRESULT STDMETHODCALLTYPE GetPageIndex(UINT*,UINT,UINT*)=0;
    virtual HRESULT STDMETHODCALLTYPE SetPageIndex(UINT*,UINT)=0;
    virtual HRESULT STDMETHODCALLTYPE GetCurrentPage(UINT*)=0;
};
inline constexpr IID IID_ITfCandidateListUIElement={0xea1ea138,0x19df,0x11d7,{0xa6,0xd2,0x00,0x06,0x5b,0x84,0x43,0x5c}};
#endif
#ifndef __ITfCandidateListUIElementBehavior_INTERFACE_DEFINED__
#define __ITfCandidateListUIElementBehavior_INTERFACE_DEFINED__
struct ITfCandidateListUIElementBehavior : public ITfCandidateListUIElement {
    virtual HRESULT STDMETHODCALLTYPE SetSelection(UINT)=0;
    virtual HRESULT STDMETHODCALLTYPE Finalize()=0;
    virtual HRESULT STDMETHODCALLTYPE Abort()=0;
};
inline constexpr IID IID_ITfCandidateListUIElementBehavior={0x85fad185,0x58ce,0x497a,{0x94,0x60,0x35,0x53,0x66,0xb6,0x4b,0x9a}};
#endif
