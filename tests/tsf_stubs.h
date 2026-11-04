// Test-only inert TSF interface scaffolding, generated from MinGW public API signatures.
#pragma once
#include <msctf.h>
class RangeStub:public ITfRange {
 LONG refs=1;
public:
 virtual ~RangeStub()=default;
 HRESULT STDMETHODCALLTYPE QueryInterface(REFIID id,void** p) override{if(!p)return E_POINTER;*p=nullptr;if(id==IID_IUnknown)*p=static_cast<ITfRange*>(this);else if(id==IID_ITfRange)*p=static_cast<ITfRange*>(this);else return E_NOINTERFACE;AddRef();return S_OK;}
 ULONG STDMETHODCALLTYPE AddRef() override{return ++refs;}
 ULONG STDMETHODCALLTYPE Release() override{ULONG n=--refs;if(!n)delete this;return n;}
 HRESULT STDMETHODCALLTYPE GetText(TfEditCookie ec, DWORD dwFlags, WCHAR *pchText, ULONG cchMax, ULONG *pcch) override{return E_NOTIMPL;}
 HRESULT STDMETHODCALLTYPE SetText(TfEditCookie ec, DWORD dwFlags, const WCHAR *pchText, LONG cch) override{return E_NOTIMPL;}
 HRESULT STDMETHODCALLTYPE GetFormattedText(TfEditCookie ec, IDataObject **ppDataObject) override{return E_NOTIMPL;}
 HRESULT STDMETHODCALLTYPE GetEmbedded(TfEditCookie ec, REFGUID rguidService, REFIID riid, IUnknown **ppunk) override{return E_NOTIMPL;}
 HRESULT STDMETHODCALLTYPE InsertEmbedded(TfEditCookie ec, DWORD dwFlags, IDataObject *pDataObject) override{return E_NOTIMPL;}
 HRESULT STDMETHODCALLTYPE ShiftStart(TfEditCookie ec, LONG cchReq, LONG *pcch, const TF_HALTCOND *pHalt) override{return E_NOTIMPL;}
 HRESULT STDMETHODCALLTYPE ShiftEnd(TfEditCookie ec, LONG cchReq, LONG *pcch, const TF_HALTCOND *pHalt) override{return E_NOTIMPL;}
 HRESULT STDMETHODCALLTYPE ShiftStartToRange(TfEditCookie ec, ITfRange *pRange, TfAnchor aPos) override{return E_NOTIMPL;}
 HRESULT STDMETHODCALLTYPE ShiftEndToRange(TfEditCookie ec, ITfRange *pRange, TfAnchor aPos) override{return E_NOTIMPL;}
 HRESULT STDMETHODCALLTYPE ShiftStartRegion(TfEditCookie ec, TfShiftDir dir, WINBOOL *pfNoRegion) override{return E_NOTIMPL;}
 HRESULT STDMETHODCALLTYPE ShiftEndRegion(TfEditCookie ec, TfShiftDir dir, WINBOOL *pfNoRegion) override{return E_NOTIMPL;}
 HRESULT STDMETHODCALLTYPE IsEmpty(TfEditCookie ec, WINBOOL *pfEmpty) override{return E_NOTIMPL;}
 HRESULT STDMETHODCALLTYPE Collapse(TfEditCookie ec, TfAnchor aPos) override{return E_NOTIMPL;}
 HRESULT STDMETHODCALLTYPE IsEqualStart(TfEditCookie ec, ITfRange *pWith, TfAnchor aPos, WINBOOL *pfEqual) override{return E_NOTIMPL;}
 HRESULT STDMETHODCALLTYPE IsEqualEnd(TfEditCookie ec, ITfRange *pWith, TfAnchor aPos, WINBOOL *pfEqual) override{return E_NOTIMPL;}
 HRESULT STDMETHODCALLTYPE CompareStart(TfEditCookie ec, ITfRange *pWith, TfAnchor aPos, LONG *plResult) override{return E_NOTIMPL;}
 HRESULT STDMETHODCALLTYPE CompareEnd(TfEditCookie ec, ITfRange *pWith, TfAnchor aPos, LONG *plResult) override{return E_NOTIMPL;}
 HRESULT STDMETHODCALLTYPE AdjustForInsert(TfEditCookie ec, ULONG cchInsert, WINBOOL *pfInsertOk) override{return E_NOTIMPL;}
 HRESULT STDMETHODCALLTYPE GetGravity(TfGravity *pgStart, TfGravity *pgEnd) override{return E_NOTIMPL;}
 HRESULT STDMETHODCALLTYPE SetGravity(TfEditCookie ec, TfGravity gStart, TfGravity gEnd) override{return E_NOTIMPL;}
 HRESULT STDMETHODCALLTYPE Clone(ITfRange **ppClone) override{return E_NOTIMPL;}
 HRESULT STDMETHODCALLTYPE GetContext(ITfContext **ppContext) override{return E_NOTIMPL;}
};
class ContextStub:public ITfContext,public ITfInsertAtSelection,public ITfContextComposition {
 LONG refs=1;
public:
 virtual ~ContextStub()=default;
 HRESULT STDMETHODCALLTYPE QueryInterface(REFIID id,void** p) override{if(!p)return E_POINTER;*p=nullptr;if(id==IID_IUnknown)*p=static_cast<ITfContext*>(this);else if(id==IID_ITfContext)*p=static_cast<ITfContext*>(this);else if(id==IID_ITfInsertAtSelection)*p=static_cast<ITfInsertAtSelection*>(this);else if(id==IID_ITfContextComposition)*p=static_cast<ITfContextComposition*>(this);else return E_NOINTERFACE;AddRef();return S_OK;}
 ULONG STDMETHODCALLTYPE AddRef() override{return ++refs;}
 ULONG STDMETHODCALLTYPE Release() override{ULONG n=--refs;if(!n)delete this;return n;}
 HRESULT STDMETHODCALLTYPE RequestEditSession(TfClientId tid, ITfEditSession *pes, DWORD dwFlags, HRESULT *phrSession) override{return E_NOTIMPL;}
 HRESULT STDMETHODCALLTYPE InWriteSession(TfClientId tid, WINBOOL *pfWriteSession) override{return E_NOTIMPL;}
 HRESULT STDMETHODCALLTYPE GetSelection(TfEditCookie ec, ULONG ulIndex, ULONG ulCount, TF_SELECTION *pSelection, ULONG *pcFetched) override{return E_NOTIMPL;}
 HRESULT STDMETHODCALLTYPE SetSelection(TfEditCookie ec, ULONG ulCount, const TF_SELECTION *pSelection) override{return E_NOTIMPL;}
 HRESULT STDMETHODCALLTYPE GetStart(TfEditCookie ec, ITfRange **ppStart) override{return E_NOTIMPL;}
 HRESULT STDMETHODCALLTYPE GetEnd(TfEditCookie ec, ITfRange **ppEnd) override{return E_NOTIMPL;}
 HRESULT STDMETHODCALLTYPE GetActiveView(ITfContextView **ppView) override{return E_NOTIMPL;}
 HRESULT STDMETHODCALLTYPE EnumViews(IEnumTfContextViews **ppEnum) override{return E_NOTIMPL;}
 HRESULT STDMETHODCALLTYPE GetStatus(TF_STATUS *pdcs) override{return E_NOTIMPL;}
 HRESULT STDMETHODCALLTYPE GetProperty(REFGUID guidProp, ITfProperty **ppProp) override{return E_NOTIMPL;}
 HRESULT STDMETHODCALLTYPE GetAppProperty(REFGUID guidProp, ITfReadOnlyProperty **ppProp) override{return E_NOTIMPL;}
 HRESULT STDMETHODCALLTYPE TrackProperties(const GUID **prgProp, ULONG cProp, const GUID **prgAppProp, ULONG cAppProp, ITfReadOnlyProperty **ppProperty) override{return E_NOTIMPL;}
 HRESULT STDMETHODCALLTYPE EnumProperties(IEnumTfProperties **ppEnum) override{return E_NOTIMPL;}
 HRESULT STDMETHODCALLTYPE GetDocumentMgr(ITfDocumentMgr **ppDm) override{return E_NOTIMPL;}
 HRESULT STDMETHODCALLTYPE CreateRangeBackup(TfEditCookie ec, ITfRange *pRange, ITfRangeBackup **ppBackup) override{return E_NOTIMPL;}
 HRESULT STDMETHODCALLTYPE InsertTextAtSelection(TfEditCookie ec, DWORD dwFlags, const WCHAR *pchText, LONG cch, ITfRange **ppRange) override{return E_NOTIMPL;}
 HRESULT STDMETHODCALLTYPE InsertEmbeddedAtSelection(TfEditCookie ec, DWORD dwFlags, IDataObject *pDataObject, ITfRange **ppRange) override{return E_NOTIMPL;}
 HRESULT STDMETHODCALLTYPE StartComposition(TfEditCookie ecWrite, ITfRange *pCompositionRange, ITfCompositionSink *pSink, ITfComposition **ppComposition) override{return E_NOTIMPL;}
 HRESULT STDMETHODCALLTYPE EnumCompositions(IEnumITfCompositionView **ppEnum) override{return E_NOTIMPL;}
 HRESULT STDMETHODCALLTYPE FindComposition(TfEditCookie ecRead, ITfRange *pTestRange, IEnumITfCompositionView **ppEnum) override{return E_NOTIMPL;}
 HRESULT STDMETHODCALLTYPE TakeOwnership(TfEditCookie ecWrite, ITfCompositionView *pComposition, ITfCompositionSink *pSink, ITfComposition **ppComposition) override{return E_NOTIMPL;}
};
class CompositionStub:public ITfComposition {
 LONG refs=1;
public:
 virtual ~CompositionStub()=default;
 HRESULT STDMETHODCALLTYPE QueryInterface(REFIID id,void** p) override{if(!p)return E_POINTER;*p=nullptr;if(id==IID_IUnknown)*p=static_cast<ITfComposition*>(this);else if(id==IID_ITfComposition)*p=static_cast<ITfComposition*>(this);else return E_NOINTERFACE;AddRef();return S_OK;}
 ULONG STDMETHODCALLTYPE AddRef() override{return ++refs;}
 ULONG STDMETHODCALLTYPE Release() override{ULONG n=--refs;if(!n)delete this;return n;}
 HRESULT STDMETHODCALLTYPE GetRange(ITfRange **ppRange) override{return E_NOTIMPL;}
 HRESULT STDMETHODCALLTYPE ShiftStart(TfEditCookie ecWrite, ITfRange *pNewStart) override{return E_NOTIMPL;}
 HRESULT STDMETHODCALLTYPE ShiftEnd(TfEditCookie ecWrite, ITfRange *pNewEnd) override{return E_NOTIMPL;}
 HRESULT STDMETHODCALLTYPE EndComposition(TfEditCookie ecWrite) override{return E_NOTIMPL;}
};
class ManagerStub:public ITfThreadMgr,public ITfSource,public ITfKeystrokeMgr {
 LONG refs=1;
public:
 virtual ~ManagerStub()=default;
 HRESULT STDMETHODCALLTYPE QueryInterface(REFIID id,void** p) override{if(!p)return E_POINTER;*p=nullptr;if(id==IID_IUnknown)*p=static_cast<ITfThreadMgr*>(this);else if(id==IID_ITfThreadMgr)*p=static_cast<ITfThreadMgr*>(this);else if(id==IID_ITfSource)*p=static_cast<ITfSource*>(this);else if(id==IID_ITfKeystrokeMgr)*p=static_cast<ITfKeystrokeMgr*>(this);else return E_NOINTERFACE;AddRef();return S_OK;}
 ULONG STDMETHODCALLTYPE AddRef() override{return ++refs;}
 ULONG STDMETHODCALLTYPE Release() override{ULONG n=--refs;if(!n)delete this;return n;}
 HRESULT STDMETHODCALLTYPE Activate(TfClientId *ptid) override{return E_NOTIMPL;}
 HRESULT STDMETHODCALLTYPE Deactivate() override{return E_NOTIMPL;}
 HRESULT STDMETHODCALLTYPE CreateDocumentMgr(ITfDocumentMgr **ppdim) override{return E_NOTIMPL;}
 HRESULT STDMETHODCALLTYPE EnumDocumentMgrs(IEnumTfDocumentMgrs **ppEnum) override{return E_NOTIMPL;}
 HRESULT STDMETHODCALLTYPE GetFocus(ITfDocumentMgr **ppdimFocus) override{return E_NOTIMPL;}
 HRESULT STDMETHODCALLTYPE SetFocus(ITfDocumentMgr *pdimFocus) override{return E_NOTIMPL;}
 HRESULT STDMETHODCALLTYPE AssociateFocus(HWND hwnd, ITfDocumentMgr *pdimNew, ITfDocumentMgr **ppdimPrev) override{return E_NOTIMPL;}
 HRESULT STDMETHODCALLTYPE IsThreadFocus(WINBOOL *pfThreadFocus) override{return E_NOTIMPL;}
 HRESULT STDMETHODCALLTYPE GetFunctionProvider(REFCLSID clsid, ITfFunctionProvider **ppFuncProv) override{return E_NOTIMPL;}
 HRESULT STDMETHODCALLTYPE EnumFunctionProviders(IEnumTfFunctionProviders **ppEnum) override{return E_NOTIMPL;}
 HRESULT STDMETHODCALLTYPE GetGlobalCompartment(ITfCompartmentMgr **ppCompMgr) override{return E_NOTIMPL;}
 HRESULT STDMETHODCALLTYPE AdviseSink(REFIID riid, IUnknown *punk, DWORD *pdwCookie) override{return E_NOTIMPL;}
 HRESULT STDMETHODCALLTYPE UnadviseSink(DWORD dwCookie) override{return E_NOTIMPL;}
 HRESULT STDMETHODCALLTYPE AdviseKeyEventSink(TfClientId tid, ITfKeyEventSink *pSink, WINBOOL fForeground) override{return E_NOTIMPL;}
 HRESULT STDMETHODCALLTYPE UnadviseKeyEventSink(TfClientId tid) override{return E_NOTIMPL;}
 HRESULT STDMETHODCALLTYPE GetForeground(CLSID *pclsid) override{return E_NOTIMPL;}
 HRESULT STDMETHODCALLTYPE TestKeyDown(WPARAM wParam, LPARAM lParam, WINBOOL *pfEaten) override{return E_NOTIMPL;}
 HRESULT STDMETHODCALLTYPE TestKeyUp(WPARAM wParam, LPARAM lParam, WINBOOL *pfEaten) override{return E_NOTIMPL;}
 HRESULT STDMETHODCALLTYPE KeyDown(WPARAM wParam, LPARAM lParam, WINBOOL *pfEaten) override{return E_NOTIMPL;}
 HRESULT STDMETHODCALLTYPE KeyUp(WPARAM wParam, LPARAM lParam, WINBOOL *pfEaten) override{return E_NOTIMPL;}
 HRESULT STDMETHODCALLTYPE GetPreservedKey(ITfContext *pic, const TF_PRESERVEDKEY *pprekey, GUID *pguid) override{return E_NOTIMPL;}
 HRESULT STDMETHODCALLTYPE IsPreservedKey(REFGUID rguid, const TF_PRESERVEDKEY *pprekey, WINBOOL *pfRegistered) override{return E_NOTIMPL;}
 HRESULT STDMETHODCALLTYPE PreserveKey(TfClientId tid, REFGUID rguid, const TF_PRESERVEDKEY *prekey, const WCHAR *pchDesc, ULONG cchDesc) override{return E_NOTIMPL;}
 HRESULT STDMETHODCALLTYPE UnpreserveKey(REFGUID rguid, const TF_PRESERVEDKEY *pprekey) override{return E_NOTIMPL;}
 HRESULT STDMETHODCALLTYPE SetPreservedKeyDescription(REFGUID rguid, const WCHAR *pchDesc, ULONG cchDesc) override{return E_NOTIMPL;}
 HRESULT STDMETHODCALLTYPE GetPreservedKeyDescription(REFGUID rguid, BSTR *pbstrDesc) override{return E_NOTIMPL;}
 HRESULT STDMETHODCALLTYPE SimulatePreservedKey(ITfContext *pic, REFGUID rguid, WINBOOL *pfEaten) override{return E_NOTIMPL;}
};
