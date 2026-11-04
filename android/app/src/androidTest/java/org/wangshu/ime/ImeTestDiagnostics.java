package org.wangshu.ime;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.WindowInsets;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Test APK only. Explicit synthetic-emulator opt-in; no direct text writes or device setup. */
final class ImeTestDiagnostics implements AutoCloseable {
    private static final int MAX_DUMP=512*1024,MAX_WINDOWS=16;
    private final Instrumentation test;private final Context context;private final UiAutomation auto;
    private final String owner,expectedIme;private final File directory;private final int priorFlags;
    private Activity activity;private EditText editor;private int sequence,inputAttempts,fixtureMutations;
    private boolean serverVerified,closed,syntheticEditorVerified;private String last="NOT_BOUND";
    ImeTestDiagnostics(Instrumentation test,String suite,boolean syntheticOnly)throws IOException{
        if(!syntheticOnly)throw new ImeTestPolicy.SetupFailure("diagnostic APK requires -e diagnostics synthetic-only in the approved fresh emulator; no keys or capture");
        if(Build.VERSION.SDK_INT!=36||android.os.Process.myUid()>=100000)throw new ImeTestPolicy.SetupFailure("diagnostic service parser supports API36/user0 only; unknown environment, no actions");
        if(!suite.equals("core")&&!suite.equals("candidates"))throw new IllegalArgumentException("diagnostic suite");
        this.test=test;context=test.getTargetContext();owner=context.getPackageName();expectedIme=new ComponentName(context,WangshuIme.class).flattenToString();
        File parent=new File(context.getFilesDir(),"test-evidence");
        if(!parent.isDirectory()&&!parent.mkdir())throw new IOException("Cannot create test evidence directory");
        directory=new File(parent,"diagnostic-"+suite+"-"+UUID.randomUUID());
        if(!directory.mkdir())throw new IOException("Unique diagnostic directory unavailable; not overwritten");
        write("scope.txt","SYNTHETIC-ONLY explicit opt-in; original test assertions retained\nsuite="+suite+" package="+owner+" pid="+android.os.Process.myPid()+" uid="+android.os.Process.myUid()+"\nRaw service/window dumps never persisted. No installation, IME selection or global settings modifications.\n");
        auto=test.getUiAutomation();AccessibilityServiceInfo info=auto.getServiceInfo();
        if(info==null)throw new IOException("No test accessibility service configuration");
        priorFlags=info.flags;info.flags|=AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS|AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        try{auto.setServiceInfo(info);LayoutChecks.auto=auto;}catch(RuntimeException failure){try{info.flags=priorFlags;auto.setServiceInfo(info);}catch(RuntimeException restore){failure.addSuppressed(restore);}throw failure;}
    }
    String summary(){return "diagnostics="+directory.getAbsolutePath()+" attemptedInputActions="+inputAttempts+" attemptedFixtureMutations="+fixtureMutations+" serverVerified="+serverVerified;}
    private void write(String suffix,String value)throws IOException{
        File path=new File(directory,String.format(Locale.ROOT,"%04d-",sequence++)+suffix);
        if(!path.createNewFile())throw new IOException("Evidence path exists; not overwritten");
        try(FileOutputStream out=new FileOutputStream(path)){out.write(value.getBytes(StandardCharsets.UTF_8));out.getFD().sync();}
    }
    private boolean exactSelected(){String selected=Settings.Secure.getString(context.getContentResolver(),Settings.Secure.DEFAULT_INPUT_METHOD);return new ComponentName(context,WangshuIme.class).equals(ComponentName.unflattenFromString(selected==null?"":selected));}
    private ImeTestPolicy.Facts observe(){
        final boolean[] local={false,false};final StringBuilder detail=new StringBuilder("elapsedMs="+SystemClock.elapsedRealtime()+" attempts="+inputAttempts+"\n");
        test.runOnMainSync(()->{
            if(activity==null||editor==null){detail.append("editor=UNBOUND\n");return;}
            InputMethodManager imm=(InputMethodManager)activity.getSystemService(Context.INPUT_METHOD_SERVICE);
            boolean attached=editor.isAttachedToWindow(),focused=editor.hasFocus(),windowFocused=editor.hasWindowFocus();
            boolean active=imm!=null&&imm.isActive(editor),accepting=imm!=null&&imm.isAcceptingText();
            local[0]=owner.equals(activity.getPackageName())&&editor.getId()==R.id.test_editor&&attached&&focused&&windowFocused&&active&&accepting&&!activity.isFinishing()&&!activity.isDestroyed();
            WindowInsets insets=editor.getRootWindowInsets();local[1]=Build.VERSION.SDK_INT<30||insets!=null&&insets.isVisible(WindowInsets.Type.ime());
            Rect visible=new Rect();boolean global=editor.getGlobalVisibleRect(visible);
            detail.append("editorId=").append(editor.getId()).append(" attached=").append(attached).append(" focused=").append(focused).append(" windowFocused=").append(windowFocused).append(" shown=").append(editor.isShown()).append(" active=").append(active).append(" accepting=").append(accepting).append(" token=").append(editor.getWindowToken()).append(" globalVisible=").append(global).append(" bounds=").append(visible).append('\n');
            detail.append("syntheticText=").append(syntheticEditorVerified&&local[0]?ImeTestPolicy.escaped(editor.getText().toString(),512):"REDACTED_UNVERIFIED_OR_UNFOCUSED_EDITOR").append(" selection=").append(editor.getSelectionStart()).append(',').append(editor.getSelectionEnd()).append(" composing=").append(BaseInputConnection.getComposingSpanStart(editor.getText())).append(',').append(BaseInputConnection.getComposingSpanEnd(editor.getText())).append(" DataFiles.ready=").append(DataFiles.ready).append('\n');
        });
        boolean selected=exactSelected(),app=false,ime=false;List<AccessibilityWindowInfo> windows=auto.getWindows();
        if(windows.size()>MAX_WINDOWS){last=detail.append("WINDOW_LIMIT\n").toString();return new ImeTestPolicy.Facts(local[0],selected,false,false,local[1]);}
        for(AccessibilityWindowInfo w:windows){
            Rect bounds=new Rect();w.getBoundsInScreen(bounds);AccessibilityNodeInfo root=w.getRoot();
            boolean current=root!=null&&root.refresh()&&root.getWindowId()==w.getId();
            String pkg=root==null?"":String.valueOf(root.getPackageName());boolean visible=current&&root.isVisibleToUser()&&!bounds.isEmpty();
            detail.append("window id=").append(w.getId()).append(" type=").append(w.getType()).append(" active=").append(w.isActive()).append(" focused=").append(w.isFocused()).append(" owner=").append(ImeTestPolicy.ownerKind(pkg,owner)).append(" current=").append(current).append(" visible=").append(visible).append(" bounds=").append(bounds).append('\n');
            if(current&&visible&&owner.equals(pkg)){if(w.getType()==AccessibilityWindowInfo.TYPE_APPLICATION&&w.isFocused())app=true;if(w.getType()==AccessibilityWindowInfo.TYPE_INPUT_METHOD)ime=true;}
        }
        ImeTestPolicy.Facts facts=new ImeTestPolicy.Facts(local[0],selected,app,ime,local[1]);last=detail.append(facts).append('\n').toString();return facts;
    }
    boolean record(String label){try{observe();write("state.txt","stage="+label+"\n"+last);return true;}catch(Throwable unavailable){try{write("capture-error.txt","stage="+label+" type="+unavailable.getClass().getSimpleName()+"\n");}catch(IOException ignored){android.util.Log.e("WangshuImeTest","Diagnostic write unavailable",unavailable);}return false;}}
    void awaitReady(Activity activity,EditText editor)throws Exception{
        this.activity=activity;this.editor=editor;
        if(editor==null||!owner.equals(activity.getPackageName()))throw new ImeTestPolicy.SetupFailure("unexpected editor owner");
        final boolean[] empty={false};test.runOnMainSync(()->empty[0]=editor.getText().length()==0);
        if(!empty[0])throw new ImeTestPolicy.SetupFailure("fresh synthetic editor not empty; do not start or reset it");
        syntheticEditorVerified=true;
        test.runOnMainSync(editor::requestFocus);record("focused-before-show");
        long deadline=SystemClock.elapsedRealtime()+30000,lastServer=0;boolean showRequested=false;
        while(SystemClock.elapsedRealtime()<deadline){
            final boolean[] focus={false},show={false};
            test.runOnMainSync(()->focus[0]=editor.hasWindowFocus());
            if(!showRequested&&focus[0]){showRequested=true;test.runOnMainSync(()->show[0]=((InputMethodManager)activity.getSystemService(Context.INPUT_METHOD_SERVICE)).showSoftInput(editor,InputMethodManager.SHOW_IMPLICIT));write("show-request.txt","one request; returned="+show[0]+"; NOT visibility proof\n");}
            ImeTestPolicy.Facts facts=observe();write("readiness.txt",last);
            if(facts.ready()&&SystemClock.elapsedRealtime()-lastServer>=1000){
                lastServer=SystemClock.elapsedRealtime();serverVerified=verifyServer("setup-input-method.txt");
                if(serverVerified&&observe().ready()){record("setup-ready");return;}
            }
            SystemClock.sleep(200);
        }
        throw new ImeTestPolicy.SetupFailure("30-second exact editor/IME/server precondition timeout; attemptedInputActions="+inputAttempts+"; last="+last);
    }
    void requireReady(String label){
        try{boolean ready=observe().ready();serverVerified=ready&&verifyServer("action-input-method.txt");
            if(!serverVerified||!observe().ready()){record("guard-failed "+label);throw new ImeTestPolicy.SetupFailure("precondition changed at "+label+"; attemptedInputActions="+inputAttempts+"; remaining actions suppressed");}
        }catch(RuntimeException unavailable){throw new ImeTestPolicy.SetupFailure("readiness observation unavailable at "+label+"; attemptedInputActions="+inputAttempts+"; remaining actions suppressed; type="+unavailable.getClass().getSimpleName());}
    }
    private boolean verifyServer(String name){String dump=shellSnapshot("dumpsys -t 2 input_method");boolean match=ImeTestPolicy.serviceMatches(dump,owner,android.os.Process.myUid(),android.os.Process.myPid(),R.id.test_editor,expectedIme);try{write(name,"exactCurrentServerMatch="+match+"\n"+ImeTestPolicy.sanitizedDump(dump,owner));}catch(IOException unavailable){return false;}return match;}
    void beforeAction(String label){if(!record("before-attempt "+label))throw new ImeTestPolicy.SetupFailure("pre-action evidence unavailable; no new input attempted");requireReady(label);}
    void markInputAttempt(){inputAttempts++;}
    private void afterAction(String label){if(!record(label))throw new ImeTestPolicy.SetupFailure("post-action evidence unavailable; attemptedInputActions="+inputAttempts+"; no replay or remaining actions");}
    void sendString(String value){for(int i=0;i<value.length();i++)if(value.charAt(i)<32||value.charAt(i)>126)throw new ImeTestPolicy.SetupFailure("unsupported diagnostic key sequence; no keys sent");for(int i=0;i<value.length();i++){beforeAction("character-index="+i);markInputAttempt();test.sendStringSync(value.substring(i,i+1));afterAction("after-character");}}
    void sendKey(int key){beforeAction("key-code="+key);markInputAttempt();test.sendKeyDownUpSync(key);afterAction("after-key-code");}
    void mutateFixture(Runnable mutation){if(!record("before-synthetic-fixture-mutation"))throw new ImeTestPolicy.SetupFailure("pre-fixture evidence unavailable; mutation not sent");requireReady("synthetic-fixture-mutation");fixtureMutations++;test.runOnMainSync(mutation);afterAction("after-synthetic-fixture-mutation");}
    AccessibilityNodeInfo awaitImeLabel(String label,boolean description)throws Exception{
        StrictImeWindowQuery.beginObservation(auto);long deadline=SystemClock.elapsedRealtime()+30000;
        do{StrictImeWindowQuery.Result result=StrictImeWindowQuery.find(auto,AccessibilityWindowInfo.TYPE_INPUT_METHOD,owner,label,description);if(result.node!=null)return result.node;SystemClock.sleep(200);}while(SystemClock.elapsedRealtime()<deadline);
        record("missing-exact-ime-label");throw new AssertionError("actual target IME node unavailable: "+label);
    }
    private static final class ScreenRegion {final Rect rect;final String signature;ScreenRegion(Rect rect,String signature){this.rect=rect;this.signature=signature;}}
    private ScreenRegion screenRegion(){
        List<AccessibilityWindowInfo> windows=auto.getWindows();if(windows.size()>MAX_WINDOWS)return null;
        Rect crop=null;StringBuilder signature=new StringBuilder();List<Rect> excluded=new ArrayList<>();List<String> kinds=new ArrayList<>();
        for(AccessibilityWindowInfo w:windows){AccessibilityNodeInfo root=w.getRoot();if(root==null||!root.refresh()||root.getWindowId()!=w.getId())return null;if(!root.isVisibleToUser())continue;
            Rect bounds=new Rect();w.getBoundsInScreen(bounds);String pkg=String.valueOf(root.getPackageName());String kind=owner.equals(pkg)?"OWNED":w.getType()==AccessibilityWindowInfo.TYPE_SYSTEM&&pkg.equals("com.android.systemui")?"SYSTEM_BAR_OR_OVERLAY":"UNKNOWN";
            signature.append(w.getId()).append(':').append(w.getType()).append(':').append(w.isFocused()).append(':').append(kind).append(':').append(bounds).append(';');
            if(w.getType()==AccessibilityWindowInfo.TYPE_APPLICATION&&w.isFocused()&&kind.equals("OWNED"))crop=new Rect(bounds);
            if(!kind.equals("OWNED")){excluded.add(bounds);kinds.add(kind);}
        }
        if(crop==null||crop.isEmpty())return null;
        for(int i=0;i<excluded.size();i++){Rect r=excluded.get(i);if(!Rect.intersects(crop,r))continue;if(!kinds.get(i).equals("SYSTEM_BAR_OR_OVERLAY")||r.left>crop.left||r.right<crop.right)return null;
            if(r.top<=crop.top&&r.bottom<crop.bottom)crop.top=Math.max(crop.top,r.bottom);else if(r.bottom>=crop.bottom&&r.top>crop.top)crop.bottom=Math.min(crop.bottom,r.top);else return null;
        }
        return crop.isEmpty()?null:new ScreenRegion(crop,signature.toString());
    }
    void screenshot(String label)throws IOException{
        if(!syntheticEditorVerified)throw new IOException("Screenshot skipped: synthetic editor not verified");
        if(!label.matches("[a-z-]{1,60}"))throw new IllegalArgumentException("screenshot label");
        ScreenRegion before=screenRegion();if(before==null)throw new IOException("Screenshot skipped: unknown/overlapping/unfocused window");
        Bitmap bitmap=auto.takeScreenshot();if(bitmap==null)throw new IOException("Screenshot unavailable");Bitmap cropped=null;
        try{ScreenRegion after=screenRegion();if(after==null||!before.signature.equals(after.signature)||!before.rect.equals(after.rect))throw new IOException("Screenshot skipped: window state changed");Rect r=before.rect;if(r.left<0||r.top<0||r.right>bitmap.getWidth()||r.bottom>bitmap.getHeight())throw new IOException("Screenshot bounds unknown");cropped=Bitmap.createBitmap(bitmap,r.left,r.top,r.width(),r.height());File file=new File(directory,String.format(Locale.ROOT,"%04d-",sequence++)+label+".png");if(!file.createNewFile())throw new IOException("Screenshot exists");try(OutputStream out=new FileOutputStream(file)){if(!cropped.compress(Bitmap.CompressFormat.PNG,100,out))throw new IOException("Screenshot compression failed");}}finally{if(cropped!=null&&cropped!=bitmap)cropped.recycle();bitmap.recycle();}
    }
    private String shellSnapshot(String command){
        if(!command.equals("dumpsys -t 2 input_method")&&!command.equals("dumpsys -t 2 window"))throw new IllegalArgumentException("non-diagnostic command");
        AtomicReference<ParcelFileDescriptor> descriptor=new AtomicReference<>();AtomicBoolean cancelled=new AtomicBoolean();
        FutureTask<String> future=new FutureTask<>(()->{
            ParcelFileDescriptor fd=auto.executeShellCommand(command);descriptor.set(fd);
            if(cancelled.get()){fd.close();return "CAPTURE_CANCELLED\n";}
            try(InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(fd);ByteArrayOutputStream out=new ByteArrayOutputStream()){
                byte[] buffer=new byte[4096];int n;while(out.size()<=MAX_DUMP&&(n=in.read(buffer,0,Math.min(buffer.length,MAX_DUMP+1-out.size())))>0)out.write(buffer,0,n);
                if(out.size()>MAX_DUMP)return "CAPTURE_TRUNCATED; not a valid complete snapshot\n";
                return out.toString("UTF-8").replace("\r\n","\n");
            }finally{descriptor.compareAndSet(fd,null);}
        });
        Thread worker=new Thread(future,"ime-test-readonly-dump");worker.setDaemon(true);worker.start();
        try{return future.get(3500,TimeUnit.MILLISECONDS);}catch(Exception unavailable){
            cancelled.set(true);ParcelFileDescriptor fd=descriptor.getAndSet(null);if(fd!=null)try{fd.close();}catch(IOException ignored){}future.cancel(true);
            if(unavailable instanceof InterruptedException)Thread.currentThread().interrupt();
            return "CAPTURE_UNAVAILABLE "+unavailable.getClass().getSimpleName()+"; UNKNOWN, not ready\n";
        }
    }
    void failure(Throwable cause){
        record("failure-before-finish; state lease may already be restored; earlier per-action snapshots retained");
        try{write("failure.txt",summary()+"\n"+android.util.Log.getStackTraceString(cause));if(syntheticEditorVerified){write("failure-input-method.txt",ImeTestPolicy.sanitizedDump(shellSnapshot("dumpsys -t 2 input_method"),owner));write("failure-window.txt",ImeTestPolicy.sanitizedDump(shellSnapshot("dumpsys -t 2 window"),owner));}else write("service-window-capture-skipped.txt","synthetic editor not verified; no global diagnostic capture\n");
            if(syntheticEditorVerified)screenshot("failure-before-finish");else write("screenshot-skipped.txt","synthetic editor not verified\n");
        }catch(Throwable unavailable){try{write("capture-incomplete.txt","Failure capture incomplete: "+unavailable.getClass().getSimpleName()+"\n");}catch(IOException ignored){}android.util.Log.e("WangshuImeTest","Failure evidence incomplete: "+summary(),unavailable);}
    }
    @Override public void close(){if(closed)return;closed=true;try{AccessibilityServiceInfo info=auto.getServiceInfo();if(info!=null){info.flags=priorFlags;auto.setServiceInfo(info);}}catch(RuntimeException unavailable){android.util.Log.e("WangshuImeTest","Test accessibility flags not restored",unavailable);}}
}
