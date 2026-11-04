package org.wangshu.ime;

import android.app.*;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.*;
import android.graphics.*;
import android.os.*;
import android.view.*;
import android.view.accessibility.*;
import java.io.*;

/** OS pointer input into a separate application, with the caller's actual display settings. */
final class LayoutChecks {
    static UiAutomation auto;
    static AccessibilityNodeInfo find(AccessibilityNodeInfo root,String value,boolean description){
        // API26 may retain a parent's old child IDs after the IME rebuilds its
        // rows. Refresh the ancestry too, not only a previously matched leaf.
        if(root==null||!root.refresh())return null;CharSequence label=description?root.getContentDescription():root.getText();
        if(label!=null&&value.contentEquals(label)&&root.isVisibleToUser())return root;
        for(int i=0;i<root.getChildCount();i++){AccessibilityNodeInfo result=find(root.getChild(i),value,description);if(result!=null)return result;}return null;
    }
    static AccessibilityNodeInfo await(String value,boolean description)throws Exception{
        long until=SystemClock.elapsedRealtime()+30000;
        while(SystemClock.elapsedRealtime()<until){for(AccessibilityWindowInfo window:auto.getWindows()){AccessibilityNodeInfo result=find(window.getRoot(),value,description);if(result!=null)return result;}SystemClock.sleep(300);}
        throw new AssertionError("actual visible node unavailable: "+value+"; no guessed touch");
    }
    static Rect bounds(String value)throws Exception{AccessibilityNodeInfo node=await(value,false);Rect result=new Rect();node.getBoundsInScreen(result);Bitmap screen=auto.takeScreenshot();if(screen==null)throw new AssertionError("screenshot unavailable");try{if(result.width()<1||result.height()<1||result.left<0||result.top<0||result.right>screen.getWidth()||result.bottom>screen.getHeight())throw new AssertionError("key outside screen: "+value+" "+result);}finally{screen.recycle();}return result;}
    static void tap(Instrumentation test,String value)throws Exception{Rect r=bounds(value);long now=SystemClock.uptimeMillis();MotionEvent event=MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,r.centerX(),r.centerY(),0);event.setSource(InputDevice.SOURCE_TOUCHSCREEN);test.sendPointerSync(event);event.recycle();event=MotionEvent.obtain(now,SystemClock.uptimeMillis(),MotionEvent.ACTION_UP,r.centerX(),r.centerY(),0);event.setSource(InputDevice.SOURCE_TOUCHSCREEN);test.sendPointerSync(event);event.recycle();SystemClock.sleep(500);}
    static void screenshot(Context c,String name)throws Exception{File directory=new File(c.getFilesDir(),"test-evidence");directory.mkdirs();Bitmap b=auto.takeScreenshot();if(b==null)throw new AssertionError("no screenshot");try(OutputStream stream=new FileOutputStream(new File(directory,name))){b.compress(Bitmap.CompressFormat.PNG,100,stream);}finally{b.recycle();}}
    static String run(Instrumentation test)throws Exception{
        Context c=test.getTargetContext();if(DataFiles.initialize(c)<100000)throw new AssertionError("dictionary unavailable");auto=test.getUiAutomation();AccessibilityServiceInfo config=auto.getServiceInfo();config.flags|=AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;auto.setServiceInfo(config);
        c.startActivity(new Intent().setComponent(new ComponentName("org.wangshu.scan.test","org.wangshu.scan.test.ImeEditorFixture")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP));
        AccessibilityNodeInfo editor=await("独立应用编辑器",true);if(!String.valueOf(editor.getPackageName()).equals("org.wangshu.scan.test"))throw new AssertionError("not independent editor");
        for(String key:new String[]{"数学","设置","123","空格","↵"})bounds(key);
        String label=c.getResources().getConfiguration().orientation==2?"landscape":"portrait";float scale=c.getResources().getConfiguration().fontScale;
        screenshot(c,"layout-"+label+"-letters.png");tap(test,"数学");for(String key:new String[]{"数学语音","空格","α","字母"})bounds(key);screenshot(c,"layout-"+label+"-math.png");tap(test,"α");
        long until=SystemClock.elapsedRealtime()+10000;String text="";while(SystemClock.elapsedRealtime()<until){editor=await("独立应用编辑器",true);text=String.valueOf(editor.getText());if(text.equals("α"))break;SystemClock.sleep(250);}if(!text.equals("α"))throw new AssertionError("real separate-UID editor result: "+text);
        screenshot(c,"layout-"+label+"-inserted.png");
        return "PASS separate UID Android editor served by real system IME\nPASS visible alphabet controls inside actual screen\nPASS native pointer opens mathematical panel\nPASS visible math/voice/space controls inside actual screen\nPASS actual pointer alpha inserts into independent editor\nPASS native screenshots saved for visual inspection\nDISPLAY "+label+" fontScale="+scale+" nightMask="+(c.getResources().getConfiguration().uiMode&48)+"; cloud emulator only; real phone NOT RUN\n";
    }
}
