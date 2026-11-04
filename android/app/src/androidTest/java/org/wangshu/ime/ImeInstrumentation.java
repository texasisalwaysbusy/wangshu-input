package org.wangshu.ime;
import android.app.*;
import android.content.*;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.view.inputmethod.*;
import android.widget.*;
import android.text.InputType;

/** Tests the registered OS IME and its real InputConnection, no direct commit to the editor. */
public final class ImeInstrumentation extends Instrumentation {
    private String suite="core";
    private boolean diagnosticSyntheticOnly;private ImeTestDiagnostics diagnostics;
    private int count=0;private final StringBuilder log=new StringBuilder();
    private void check(boolean ok,String label){if(!ok){if(diagnostics!=null)diagnostics.record("assertion-failed "+label);throw new AssertionError(label);}count++;log.append("PASS ").append(label).append('\n');android.util.Log.i("WangshuImeTest","PASS "+label);}
    @Override public void onCreate(Bundle args){super.onCreate(args);if(args!=null){suite=args.getString("suite","core");diagnosticSyntheticOnly="synthetic-only".equals(args.getString("diagnostics"));}start();}
    private String text(EditText editor){final String[] result={""};runOnMainSync(()->result[0]=editor.getText().toString());return result[0];}
    private void settle(){waitForIdleSync();SystemClock.sleep(400);}
    private void awaitEditor(Activity activity,EditText editor)throws Exception{diagnostics.awaitReady(activity,editor);}
    @Override public void onStart(){Bundle result=new Bundle();try{
        if(BuildConfig.BASIC_TYPING_PREVIEW&&!suite.equals("core")&&!suite.equals("candidates"))throw new UnsupportedOperationException("Basic Typing Preview supports only core/candidates here; voice and other suites NOT RUN");
        if(suite.equals("core")||suite.equals("candidates"))diagnostics=new ImeTestDiagnostics(this,suite,diagnosticSyntheticOnly);
        if(suite.equals("toolbarui")){result.putString("result",ToolbarUiChecks.run(this));finish(Activity.RESULT_OK,result);return;}
        if(suite.equals("learningui")){result.putString("result",LearningUiChecks.run(this));finish(Activity.RESULT_OK,result);return;}
        if(suite.equals("mathui")){result.putString("result",MathAssociationUiChecks.run(this));finish(Activity.RESULT_OK,result);return;}
        if(suite.equals("math-external")){result.putString("result",ExternalMathUiChecks.run(this));finish(Activity.RESULT_OK,result);return;}
        if(suite.equals("emojiui")){result.putString("result",EmojiUiChecks.run(this));finish(Activity.RESULT_OK,result);return;}
        if(suite.equals("learningstore")){result.putString("result",LearningStoreChecks.run(this));finish(Activity.RESULT_OK,result);return;}
        if(suite.equals("mathassociation")){result.putString("result",MathAssociationChecks.run(this));finish(Activity.RESULT_OK,result);return;}
        if(suite.equals("backspaceui")){result.putString("result",BackspaceUiChecks.run(this));finish(Activity.RESULT_OK,result);return;}
        if(suite.equals("backspacerebuild")){result.putString("result",BackspaceUiChecks.runRebuild(this));finish(Activity.RESULT_OK,result);return;}
        if(suite.equals("fuzzy")){result.putString("result",FuzzyCandidateChecks.run(this));finish(Activity.RESULT_OK,result);return;}
        if(suite.equals("beforecaret")){result.putString("result",BeforeCaretEditorChecks.run(this));finish(Activity.RESULT_OK,result);return;}
        if(suite.equals("voicehold")){result.putString("result",VoiceHoldChecks.run(this));finish(Activity.RESULT_OK,result);return;}
        if(suite.equals("personalcapacity")){result.putString("result",PersonalCapacityRegressionChecks.run(this));finish(Activity.RESULT_OK,result);return;}
        if(suite.equals("clipboard-fence-fake")){result.putString("result",ClipboardFenceChecks.run(this));finish(Activity.RESULT_OK,result);return;}
        if(suite.equals("clipboardui")){result.putString("result",ClipboardUiChecks.run(this));finish(Activity.RESULT_OK,result);return;}
        if(suite.equals("clipboardstore")){result.putString("result",ClipboardStoreChecks.run(this));finish(Activity.RESULT_OK,result);return;}
        if(suite.equals("personalui")){result.putString("result",PersonalUiChecks.run(this));finish(Activity.RESULT_OK,result);return;}
        if(suite.equals("personalstore")){result.putString("result",PersonalStoreChecks.run(this));finish(Activity.RESULT_OK,result);return;}
        if(suite.equals("settingsbackup")){result.putString("result",SettingsBackupChecks.run(this));finish(Activity.RESULT_OK,result);return;}
        if(suite.equals("settingsui")){result.putString("result",SettingsUiChecks.run(this));finish(Activity.RESULT_OK,result);return;}
        if(suite.equals("numeric")){result.putString("result",NumericPanelChecks.run(this));finish(Activity.RESULT_OK,result);return;}
        if(suite.equals("editor")){result.putString("result",EditorPanelChecks.run(this));finish(Activity.RESULT_OK,result);return;}
        if(suite.equals("case")){result.putString("result",CaseModeChecks.run(this));finish(Activity.RESULT_OK,result);return;}
        if(suite.equals("candidates")){result.putString("result",CandidatePanelChecks.run(this,diagnostics));result.putString("diagnostics",diagnostics.summary());diagnostics.record("success-before-finish");diagnostics.close();finish(Activity.RESULT_OK,result);return;}
        if(suite.equals("hide")){result.putString("result",HideChecks.run(this));finish(Activity.RESULT_OK,result);return;}
        if(suite.equals("layout")){result.putString("result",LayoutChecks.run(this));finish(Activity.RESULT_OK,result);return;}
        if(suite.equals("switcher")){result.putString("result",SwitcherChecks.run(this));finish(Activity.RESULT_OK,result);return;}
        if(suite.equals("voice")){result.putString("result",VoiceChecks.run(this));finish(Activity.RESULT_OK,result);return;}
        if(suite.equals("voice-readonly")){result.putString("result",VoiceChecks.run(this,false));finish(Activity.RESULT_OK,result);return;}
        if(suite.equals("voice-english-readonly")){result.putString("result",EnglishVoiceChecks.run(this));finish(Activity.RESULT_OK,result);return;}
        check(DataFiles.initialize(getTargetContext())>100000,"full shared Chinese dictionary loaded on Android");
        check(NativeBridge.lookup("nihao",0)[0].equals("你好"),"JNI candidate matches portable engine");
        check(NativeBridge.lookup("\\X",0)[0].equals("𝕏"),"JNI preserves non-BMP UTF16 mathematical character");
        check(NativeBridge.voiceMath("根号，大于等于").equals("√，≥"),"Android mathematical voice text transform (not microphone recognition)");
        String current=Settings.Secure.getString(getTargetContext().getContentResolver(),Settings.Secure.DEFAULT_INPUT_METHOD);check(new ComponentName(getTargetContext(),WangshuIme.class).equals(ComponentName.unflattenFromString(current==null?"":current)),"OS selected the exact target InputMethodService");
        Activity activity=startActivitySync(new Intent(getTargetContext(),SettingsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));EditText editor=activity.findViewById(R.id.test_editor);
        awaitEditor(activity,editor);settle();
        ImeTestPolicy.BooleanSlot readiness=new ImeTestPolicy.BooleanSlot(){public boolean get(){final boolean[] value={false};runOnMainSync(()->value[0]=DataFiles.ready);return value[0];}public void set(boolean value){runOnMainSync(()->DataFiles.ready=value);}};
        try(ImeTestPolicy.BooleanLease lease=new ImeTestPolicy.BooleanLease(readiness)){
            lease.force(false);diagnostics.record("loading-state-forced-false");diagnostics.sendString("nihao");lease.require(false);diagnostics.sendKey(KeyEvent.KEYCODE_SPACE);settle();lease.require(false);diagnostics.awaitImeLabel("nihao",false);check(text(editor).isEmpty(),"loading dictionary keeps local preedit without writing raw letters to the host; actual="+text(editor));
            lease.force(true);diagnostics.sendKey(KeyEvent.KEYCODE_SPACE);settle();lease.require(true);check(text(editor).equals("你好"),"candidate readiness race resolves pending composition instead of raw English; actual="+text(editor));
        }finally{diagnostics.record("loading-state-restoration-finished");}
        diagnostics.mutateFixture(()->editor.setText(""));settle();
        diagnostics.sendString("nihao");diagnostics.sendKey(KeyEvent.KEYCODE_SPACE);settle();check(text(editor).equals("你好"),"actual OS key routing -> IME composition -> Chinese commit; actual="+text(editor));
        diagnostics.sendString(",");settle();check(text(editor).equals("你好，"),"real editor Chinese punctuation");
        diagnostics.mutateFixture(()->editor.setText(""));settle();diagnostics.sendString("\\X");diagnostics.sendKey(KeyEvent.KEYCODE_SPACE);settle();check(text(editor).equals("𝕏"),"OS IME commits non-BMP alias");diagnostics.sendKey(KeyEvent.KEYCODE_DEL);settle();check(text(editor).isEmpty(),"backspace deletes complete non-BMP code point");
        diagnostics.sendString("\\sub9");diagnostics.sendKey(KeyEvent.KEYCODE_SPACE);settle();check(text(editor).equals("₉"),"math command digits extend alias instead of choosing an unrelated candidate");
        diagnostics.mutateFixture(()->editor.setText(""));settle();diagnostics.sendString("zhidao");diagnostics.sendKey(KeyEvent.KEYCODE_SPACE);settle();check(text(editor).equals("知道"),"frequency-ranked Chinese word commits through OS service");
        diagnostics.mutateFixture(()->{editor.setText("prefix");editor.setSelection(6);});settle();diagnostics.sendString("ni");diagnostics.sendKey(KeyEvent.KEYCODE_ESCAPE);settle();check(text(editor).equals("prefix"),"escape cancels only composition, preserving existing text");
        diagnostics.mutateFixture(()->{editor.setText("ABC");editor.setSelection(1,2);});settle();diagnostics.sendString("nihao");diagnostics.sendKey(KeyEvent.KEYCODE_SPACE);settle();check(text(editor).equals("A你好C"),"selected text replacement preserves text on both sides");
        diagnostics.mutateFixture(()->editor.setText(""));settle();diagnostics.sendString("shi");diagnostics.sendKey(KeyEvent.KEYCODE_PAGE_DOWN);diagnostics.sendKey(KeyEvent.KEYCODE_1);settle();check(text(editor).equals(NativeBridge.lookup("shi",1)[0]),"hardware candidate pagination and numeric selection");
        diagnostics.mutateFixture(()->editor.setText(""));settle();diagnostics.sendString("232091");settle();check(text(editor).equals("232091"),"hardware digits in Chinese mode add no leading or interstitial spaces");
        diagnostics.mutateFixture(()->editor.setText(""));settle();diagnostics.sendString("1+2=3");settle();check(text(editor).equals("1+2=3"),"hardware mathematical punctuation passes through without phantom spaces");
        diagnostics.mutateFixture(()->editor.setText(""));settle();diagnostics.sendString("ni-");settle();check(text(editor).equals("你-"),"nonletter confirms existing candidate once without adding a space");
        diagnostics.mutateFixture(()->{editor.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);editor.setText("");((InputMethodManager)activity.getSystemService(Context.INPUT_METHOD_SERVICE)).restartInput(editor);});settle();diagnostics.sendString("nihao");settle();check(text(editor).equals("nihao"),"password field uses direct English, no candidate composition");
        result.putString("diagnostics",diagnostics.summary());diagnostics.record("success-before-finish");diagnostics.close();result.putString("result","PASS "+count+" Android IME checks; real phone and real microphone NOT RUN\n"+log);finish(Activity.RESULT_OK,result);
    }catch(Throwable e){result.putString("failureCategory",e instanceof ImeTestPolicy.SetupFailure?"SETUP_FAILED":e instanceof AssertionError?"ASSERTION_FAILED":"DIAGNOSTIC_OR_INFRASTRUCTURE_FAILED");if(diagnostics!=null){diagnostics.failure(e);result.putString("diagnostics",diagnostics.summary());diagnostics.close();}if(suite.equals("clipboardui")){log.append("Clipboard partial checks=").append(ClipboardUiChecks.count).append("\n").append(ClipboardUiChecks.log);try{LayoutChecks.screenshot(getTargetContext(),"native-clipboard-failure.png");}catch(Exception ignored){}}if(suite.equals("settingsui")){log.append("Settings partial evidence: checks=").append(SettingsUiChecks.count).append("\n").append(SettingsUiChecks.log);try{LayoutChecks.screenshot(getTargetContext(),"native-settings-failure.png");}catch(Exception ignored){}}if(suite.equals("editor")){log.append("Editor partial evidence: checks=").append(EditorPanelChecks.checks).append("\n").append(EditorPanelChecks.log).append(CaseModeChecks.actions);try{LayoutChecks.screenshot(getTargetContext(),"native-editor-failure.png");}catch(Exception ignored){}}result.putString("result","FAIL in suite "+suite+"; general checks="+count+" (specialized suites count separately)\n"+log+android.util.Log.getStackTraceString(e));finish(Activity.RESULT_CANCELED,result);}}
}
