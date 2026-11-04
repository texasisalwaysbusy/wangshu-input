package org.wangshu.ime;

import android.app.UiAutomation;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.*;
import android.view.accessibility.*;
import java.util.*;

/** Test-only, single OS pointer stream for the two non-voice math/letters tools.
 * Chosen before sending any action, never a fallback after ACTION_CLICK failure. */
final class StrictImeToolbarTouch {
    private static final String OWNER="org.wangshu.ime";
    private static boolean exact(AccessibilityNodeInfo node,int window,String description){
        return node.getWindowId()==window&&OWNER.contentEquals(node.getPackageName()==null?"":node.getPackageName())&&description.contentEquals(node.getContentDescription()==null?"":node.getContentDescription())&&"android.widget.ImageButton".contentEquals(node.getClassName()==null?"":node.getClassName())&&node.isVisibleToUser()&&node.isEnabled()&&node.isClickable()&&(node.getActions()&AccessibilityNodeInfo.ACTION_CLICK)!=0;
    }
    private static boolean inject(UiAutomation auto,long down,int action,int x,int y){
        MotionEvent event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y,0);event.setSource(InputDevice.SOURCE_TOUCHSCREEN);try{return auto.injectInputEvent(event,false);}finally{event.recycle();}
    }
    static void tap(UiAutomation auto,AccessibilityNodeInfo initiallyBound,String description,StringBuilder log){
        if(!"数学".equals(description)&&!"字母".equals(description))throw new AssertionError("Toolbar pointer scope is math/letters only; no action sent");
        if(initiallyBound==null)throw new AssertionError("Missing bound IME tool; no action sent");int id=initiallyBound.getWindowId();
        // Recheck current system windows and bind a fresh exact target while
        // refusing to migrate an old action to a replacement IME window.
        StrictImeWindowQuery.Result current=StrictImeWindowQuery.find(auto,AccessibilityWindowInfo.TYPE_INPUT_METHOD,OWNER,description,true);
        AccessibilityNodeInfo target=current.node;if(!current.systemGuardComplete||target==null||target.getWindowId()!=id)throw new AssertionError("IME tool/window changed before pointer; no action sent: "+current);
        List<AccessibilityWindowInfo> windows=new ArrayList<>(auto.getWindows());if(windows.size()>16)throw new AssertionError("Toolbar window-count limit; no action sent");AccessibilityWindowInfo owner=null;
        for(AccessibilityWindowInfo window:windows)if(window.getId()==id){if(owner!=null)throw new AssertionError("Duplicate IME window id; no action sent");owner=window;}
        if(owner==null||owner.getType()!=AccessibilityWindowInfo.TYPE_INPUT_METHOD)throw new AssertionError("Bound IME window no longer current; no action sent");
        AccessibilityNodeInfo root=ImeSelectorDiagnostics.currentRoot(owner);
        if(root==null||!root.refresh()||root.getWindowId()!=id||!OWNER.contentEquals(root.getPackageName()==null?"":root.getPackageName())||owner.getId()!=id||owner.getType()!=AccessibilityWindowInfo.TYPE_INPUT_METHOD)throw new AssertionError("Stale/foreign current IME root; no action sent");
        Rect keyboard=new Rect(),bounds=new Rect();owner.getBoundsInScreen(keyboard);
        if(!target.refresh()||!exact(target,id,description))throw new AssertionError("Current IME tool no longer exact/visible/enabled/clickable; no action sent");target.getBoundsInScreen(bounds);
        if(owner.getId()!=id||owner.getType()!=AccessibilityWindowInfo.TYPE_INPUT_METHOD||bounds.width()<1||bounds.height()<1||bounds.left<0||bounds.top<0||!keyboard.contains(bounds))throw new AssertionError("Tool screen bounds outside current IME; no action sent");
        log.append("POINTER_TARGET ").append(description).append(" window=").append(id).append(" type=").append(owner.getType()).append(" pkg=").append(target.getPackageName()).append(" class=").append(target.getClassName()).append(" screenBounds=").append(bounds.toShortString()).append(" imeScreenBounds=").append(keyboard.toShortString()).append(" ACTION_CLICK_not_sent=true query=").append(current).append('\n');
        long down=SystemClock.uptimeMillis();boolean[] ack={false,false};long[] up={-1};Throwable primary=null;
        try{
            try{ack[0]=inject(auto,down,MotionEvent.ACTION_DOWN,bounds.centerX(),bounds.centerY());}catch(RuntimeException|AssertionError failure){primary=failure;throw failure;}
            finally{up[0]=SystemClock.uptimeMillis();try{ack[1]=inject(auto,down,MotionEvent.ACTION_UP,bounds.centerX(),bounds.centerY());}catch(RuntimeException|AssertionError release){if(primary!=null)primary.addSuppressed(release);else throw release;}}
            if(!ack[0]||!ack[1]||up[0]-down>=ViewConfiguration.getLongPressTimeout())throw new AssertionError("One OS toolbar tap not acknowledged as a short pointer stream; no retry");
        }finally{log.append("POINTER ").append(description).append(" window=").append(id).append(" screenBounds=").append(bounds.toShortString()).append(" downAck=").append(ack[0]).append(" upAck=").append(ack[1]).append(" eventSpanMs=").append(up[0]<0?-1:up[0]-down).append(" callElapsedMs=").append(SystemClock.uptimeMillis()-down).append(" streams=1 repeated=false; post-state checked separately\n");}
    }
}
