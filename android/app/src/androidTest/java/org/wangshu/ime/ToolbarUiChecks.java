package org.wangshu.ime;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.*;
import android.content.*;
import android.graphics.*;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.view.accessibility.*;
import java.util.*;

/** Native icon toolbar, independent editor UID and single OS touch streams; no local UI handlers. */
final class ToolbarUiChecks {
    private static final String IME="org.wangshu.ime",HOST="org.wangshu.scan.test",EDITOR="独立应用编辑器",CENTER="光标与文本编辑",HIDE="收起输入键盘";
    private static Instrumentation test;private static Context context;private static int count;private static StringBuilder log;
    private interface Condition {boolean yes()throws Exception;}
    private static void check(boolean ok,String label){if(!ok)throw new AssertionError(label);count++;log.append("PASS ").append(label).append('\n');Bundle update=new Bundle();update.putString("stream","TOOLBAR_PASS "+label+"\n");test.sendStatus(0,update);}
    private static void await(Condition ready,String label)throws Exception{long until=SystemClock.elapsedRealtime()+20000;while(SystemClock.elapsedRealtime()<until){if(ready.yes())return;SystemClock.sleep(150);}throw new AssertionError("Toolbar UI wait: "+label+"; last query: "+lastQuery+"; no guessed touch or repeated click");}
    private static boolean matches(AccessibilityNodeInfo node,String owner,String label,boolean description){CharSequence value=description?node.getContentDescription():node.getText();return value!=null&&label.contentEquals(value)&&node.getPackageName()!=null&&owner.contentEquals(node.getPackageName())&&node.isVisibleToUser()&&node.isEnabled();}
    private static AccessibilityNodeInfo node(int type,String owner,String label,boolean description){
        StrictImeWindowQuery.Result result=StrictImeWindowQuery.find(LayoutChecks.auto,type,owner,label,description);
        lastQuery=result.toString();return result.node;
    }
    private static String lastQuery="no IME query yet";
    private static AccessibilityNodeInfo awaitNode(int type,String owner,String label,boolean description)throws Exception{StrictImeWindowQuery.beginObservation(LayoutChecks.auto);AccessibilityNodeInfo[] result={null};await(()->(result[0]=node(type,owner,label,description))!=null,label);return result[0];}
    private static AccessibilityNodeInfo key(String description)throws Exception{return awaitNode(AccessibilityWindowInfo.TYPE_INPUT_METHOD,IME,description,true);}
    private static AccessibilityNodeInfo editor()throws Exception{return awaitNode(AccessibilityWindowInfo.TYPE_APPLICATION,HOST,EDITOR,true);}
    private static Rect imeBounds(){
        for(AccessibilityWindowInfo window:LayoutChecks.auto.getWindows()){
            if(window.getType()!=AccessibilityWindowInfo.TYPE_INPUT_METHOD)continue;int id=window.getId();
            AccessibilityNodeInfo root=ImeSelectorDiagnostics.currentRoot(window);
            if(root!=null&&root.getWindowId()==id&&root.getPackageName()!=null&&IME.contentEquals(root.getPackageName())&&window.getId()==id&&window.getType()==AccessibilityWindowInfo.TYPE_INPUT_METHOD){Rect value=new Rect();window.getBoundsInScreen(value);return value;}
        }return null;
    }
    private static boolean visibleIme(){return imeBounds()!=null;}
    private static int screenWidth,screenHeight;
    private static void dimensions(){Bitmap bitmap=LayoutChecks.auto.takeScreenshot();if(bitmap==null)throw new AssertionError("No actual compositor screenshot");try{screenWidth=bitmap.getWidth();screenHeight=bitmap.getHeight();}finally{bitmap.recycle();}}
    private static Rect icon(String description)throws Exception{
        AccessibilityNodeInfo target=key(description);Rect bounds=new Rect();target.getBoundsInScreen(bounds);Rect keyboard=imeBounds();float density=context.getResources().getDisplayMetrics().density;
        check("android.widget.ImageButton".contentEquals(target.getClassName()==null?"":target.getClassName()),"toolbar "+description+" is native ImageButton");
        check(target.getText()==null||target.getText().length()==0,"toolbar "+description+" exposes no large text label");
        check(target.getContentDescription()!=null&&target.getContentDescription().length()>0&&target.getTooltipText()!=null&&target.getTooltipText().length()>0,"toolbar "+description+" has description and tooltip");
        check(target.isClickable()&&bounds.width()+1>=48f*density&&bounds.height()+1>=48f*density&&keyboard!=null&&keyboard.contains(bounds)&&bounds.left>=0&&bounds.top>=0&&bounds.right<=screenWidth&&bounds.bottom<=screenHeight,"toolbar "+description+" retains visible touch bounds at least 48dp in both dimensions (1px rounding tolerance)");
        log.append("BOUNDS ").append(description).append(' ').append(bounds.toShortString()).append(" widthDp=").append(bounds.width()/density).append('\n');return bounds;
    }
    private static void tap(String label,boolean description)throws Exception{
        AccessibilityNodeInfo target=description?key(label):awaitNode(AccessibilityWindowInfo.TYPE_INPUT_METHOD,IME,label,false);if(!target.refresh()||!matches(target,IME,label,description)||!target.isClickable())throw new AssertionError("Pointer target became stale; no action sent");Rect bounds=new Rect();target.getBoundsInScreen(bounds);Rect keyboard=imeBounds();if(keyboard==null||!keyboard.contains(bounds)||bounds.width()<1||bounds.height()<1)throw new AssertionError("Pointer target outside real keyboard");
        long down=SystemClock.uptimeMillis();MotionEvent press=MotionEvent.obtain(down,down,MotionEvent.ACTION_DOWN,bounds.centerX(),bounds.centerY(),0);press.setSource(InputDevice.SOURCE_TOUCHSCREEN);boolean downAck;try{downAck=LayoutChecks.auto.injectInputEvent(press,false);}finally{press.recycle();}
        long up=SystemClock.uptimeMillis();MotionEvent release=MotionEvent.obtain(down,up,MotionEvent.ACTION_UP,bounds.centerX(),bounds.centerY(),0);release.setSource(InputDevice.SOURCE_TOUCHSCREEN);boolean upAck;try{upAck=LayoutChecks.auto.injectInputEvent(release,false);}finally{release.recycle();}
        log.append("POINTER ").append(label).append(" bounds=").append(bounds.toShortString()).append(" downAck=").append(downAck).append(" upAck=").append(upAck).append(" durationMs=").append(up-down).append(" repeated=false\n");
        if(!downAck||!upAck||up-down>=ViewConfiguration.getLongPressTimeout())throw new AssertionError("OS did not accept one short pointer tap; no retry");
    }
    private static void screenshot(String panel)throws Exception{LayoutChecks.screenshot(context,"native-toolbar-"+panel+".png");check(true,"actual toolbar "+panel+" screenshot saved for pixel review");}
    static String run(Instrumentation instrumentation)throws Exception{
        test=instrumentation;context=test.getTargetContext();count=0;log=new StringBuilder();
        try{
            if(Build.VERSION.SDK_INT<28)throw new AssertionError("Tooltip node verification requires API28+, target is existing Android16");check(DataFiles.initialize(context)>100000,"existing dictionary loaded for native toolbar state");String selected=Settings.Secure.getString(context.getContentResolver(),Settings.Secure.DEFAULT_INPUT_METHOD);check(selected!=null&&selected.startsWith(IME+"/"),"OS selected real Wangshu InputMethodService");
            LayoutChecks.auto=test.getUiAutomation();AccessibilityServiceInfo config=LayoutChecks.auto.getServiceInfo();config.flags|=AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;LayoutChecks.auto.setServiceInfo(config);
            context.startActivity(new Intent().setComponent(new ComponentName(HOST,"org.wangshu.scan.test.ImeEditorFixture")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP));StrictImeWindowQuery.beginObservation(LayoutChecks.auto);await(()->{AccessibilityNodeInfo value=node(AccessibilityWindowInfo.TYPE_APPLICATION,HOST,EDITOR,true);return value!=null&&value.isFocused();},"separate-UID editor focus before toolbar actions");AccessibilityNodeInfo field=editor();check(field.isFocused(),"actual separate-UID scanner fixture editor is focused");String original=String.valueOf(field.getText()==null?"":field.getText());key("数学");dimensions();
            check(node(AccessibilityWindowInfo.TYPE_INPUT_METHOD,IME,"中",true)==null&&node(AccessibilityWindowInfo.TYPE_INPUT_METHOD,IME,"英",true)==null&&node(AccessibilityWindowInfo.TYPE_INPUT_METHOD,IME,"密码·英",true)==null,"no legacy toolbar language control");
            for(String label:new String[]{"设置","数学","剪贴板与常用语",CENTER,"语音",HIDE})icon(label);
            Rect settingsBounds=new Rect(),mathBounds=new Rect(),languageBounds=new Rect();key("设置").getBoundsInScreen(settingsBounds);key("数学").getBoundsInScreen(mathBounds);AccessibilityNodeInfo language=key("中英文切换");language.getBoundsInScreen(languageBounds);
            check(settingsBounds.right<=mathBounds.left&&Math.abs(settingsBounds.exactCenterY()-mathBounds.exactCenterY())<=1f,"Settings occupies the upper-left toolbar corner");
            check("android.widget.Button".contentEquals(language.getClassName()==null?"":language.getClassName())&&languageBounds.top>=settingsBounds.bottom,"dedicated language key remains below toolbar");Rect center=new Rect();key(CENTER).getBoundsInScreen(center);check(Math.abs(center.width()-48f*context.getResources().getDisplayMetrics().density)<=1f,"central cursor touch target remains exactly 48dp wide (1px rounding tolerance)");check(Math.abs(center.exactCenterX()-screenWidth/2f)<=1f,"central cursor tool is centered on the actual screen");screenshot("letters");
            tap("数学",true);key("字母");key("数学语音");icon("字母");icon("数学语音");check(true,"one OS tap changes math icon to letters and voice accessibility to math voice");screenshot("math");
            tap("字母",true);key("数学");key("语音");key("空格；按住本地语音，松手结束");
            tap("剪贴板与常用语",true);key("剪贴板标签");key("常用语标签");check(true,"one OS tap opens actual clipboard and phrases panel without editing data");screenshot("clipboard");
            tap(CENTER,true);key("开始选择文字");check(true,"one OS tap switches clipboard panel to actual cursor editing panel");screenshot("editor");
            tap("返回",false);key("空格；按住本地语音，松手结束");check(true,"editor Return restores letter keyboard");tap(HIDE,true);await(()->!visibleIme(),"actual IME window hidden");AccessibilityNodeInfo remaining=editor();check(!visibleIme()&&original.equals(String.valueOf(remaining.getText()==null?"":remaining.getText())),"native hide preserves independent fixture and its unchanged text");screenshot("hidden");
            return "PASS "+count+" actual separate-UID icon toolbar checks\n"+log+"Scope: image class/empty text, descriptions/tooltips, 48dp touch targets, screen centering, single native pointer panel/hide transitions and screenshots. No settings/microphone action, no edits/clear/delete, no color/style pixel acceptance or real-phone claim.\n";
        }catch(Exception|AssertionError failure){try{if(LayoutChecks.auto!=null)log.append(ImeSelectorDiagnostics.snapshot(LayoutChecks.auto));}catch(Exception|AssertionError metadata){if(metadata!=failure)failure.addSuppressed(metadata);log.append("Metadata unavailable=").append(metadata.getClass().getSimpleName()).append('\n');}try{if(LayoutChecks.auto!=null)LayoutChecks.screenshot(context,"native-toolbar-failure.png");}catch(Exception|AssertionError screenshot){if(screenshot!=failure)failure.addSuppressed(screenshot);log.append("Screenshot unavailable=").append(screenshot.getClass().getSimpleName()).append('\n');}throw new AssertionError("Toolbar UI partial="+count+"\n"+log,failure);}
    }
}
