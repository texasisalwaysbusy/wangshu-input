package org.wangshu.ime;

import android.graphics.Rect;
import android.view.View;
import android.view.ViewParent;
import java.util.Arrays;

/** Test-only, axis-aligned View capture. Call on the UI thread. Root/global visible
 * coordinates are not screen coordinates in a floating window. No actions here. */
final class TestViewScreenBounds {
    static final class Snapshot {
        final Rect globalVisible=new Rect(),localVisible=new Rect(),screenVisible=new Rect(),screenFull=new Rect();
        final int[] screen=new int[2],window=new int[2],rootScreen=new int[2],rootWindow=new int[2];
        boolean globalShown,localShown,attached,shown;String invalid;
        Rect requireScreen(){if(invalid!=null)throw new AssertionError("Unsafe native fixture bounds; no action sent: "+this);return new Rect(screenVisible);}
        boolean containsRaw(float x,float y){return invalid==null&&x>=screenVisible.left&&x<screenVisible.right&&y>=screenVisible.top&&y<screenVisible.bottom;}
        public String toString(){return "attached="+attached+" shown="+shown+" globalRoot="+globalVisible.toShortString()+" local="+localVisible.toShortString()+" locationScreen="+Arrays.toString(screen)+" locationWindow="+Arrays.toString(window)+" rootScreen="+Arrays.toString(rootScreen)+" rootWindow="+Arrays.toString(rootWindow)+" windowToScreen=("+(screen[0]-window[0])+","+(screen[1]-window[1])+") screenVisible="+screenVisible.toShortString()+" screenFull="+screenFull.toShortString()+" invalid="+invalid;}
    }
    static Snapshot capture(View view){
        Snapshot result=new Snapshot();result.attached=view.isAttachedToWindow();result.shown=view.isShown();
        view.getLocationOnScreen(result.screen);view.getLocationInWindow(result.window);View root=view.getRootView();root.getLocationOnScreen(result.rootScreen);root.getLocationInWindow(result.rootWindow);
        result.screenFull.set(result.screen[0],result.screen[1],result.screen[0]+view.getWidth(),result.screen[1]+view.getHeight());
        result.globalShown=view.getGlobalVisibleRect(result.globalVisible);result.localShown=view.getLocalVisibleRect(result.localVisible);
        if(!result.attached||!result.shown||!result.globalShown||!result.localShown||view.getWidth()<1||view.getHeight()<1){result.invalid="detached/hidden/empty";return result;}
        // These owned fixture buttons/surfaces are not transformed or internally
        // scrolled. Refuse unsupported geometry rather than inject guessed points.
        View current=view;int depth=0;
        while(true){
            if(++depth>64){result.invalid="ancestor limit";return result;}
            if(!current.getMatrix().isIdentity()){result.invalid="non-identity View matrix";return result;}
            ViewParent parent=current.getParent();if(!(parent instanceof View))break;current=(View)parent;
        }
        if(view.getScrollX()!=0||view.getScrollY()!=0){result.invalid="target internal scroll unsupported";return result;}
        int dx=result.screen[0]-result.window[0],dy=result.screen[1]-result.window[1];
        if(dx!=result.rootScreen[0]-result.rootWindow[0]||dy!=result.rootScreen[1]-result.rootWindow[1]){result.invalid="window offset changed during capture";return result;}
        // Two independent captures must agree: local visible + actual view screen
        // origin, and root/global visible + observed window-to-screen translation.
        result.screenVisible.set(result.localVisible);result.screenVisible.offset(result.screen[0],result.screen[1]);
        Rect translatedGlobal=new Rect(result.globalVisible);translatedGlobal.offset(dx,dy);
        if(!result.screenVisible.equals(translatedGlobal)){result.invalid="local/global/screen conversion mismatch";return result;}
        if(!result.screenVisible.intersect(result.screenFull)||result.screenVisible.width()<1||result.screenVisible.height()<1){result.invalid="empty visible screen intersection";return result;}
        return result;
    }
}
