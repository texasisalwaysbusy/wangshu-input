package org.wangshu.ime;

import android.content.Context;
import android.graphics.Rect;
import android.view.*;
import android.widget.FrameLayout;

/** Keep this outer surface attached while the inner keyboard keys are replaced by the voice layer. */
public final class VoiceHoldTouchSurface extends FrameLayout {
    private VoiceHoldController controller;private View spaceKey,pressedKey;private VoiceHoldController.Token active,owned;
    private boolean consuming;private int pointer;private long downTime;
    private BackspaceGesture eraser;private View eraseKey,pressedErase;private boolean erasing;private int erasePointer;private long eraseToken,eraseDownTime;
    public VoiceHoldTouchSurface(Context context){super(context);}
    public void controller(VoiceHoldController value){if(controller!=null&&controller!=value)controller.cancel(owned,VoiceHoldController.CancelReason.WINDOW_CHANGED);releasePressed();active=owned=null;consuming=false;controller=value;}
    public void spaceKey(View value){spaceKey=value;}
    public void backspaceController(BackspaceGesture value){if(eraser!=null)eraser.cancel();endErase();eraser=value;}
    public void backspaceKey(View value){eraseKey=value;}
    private boolean over(View key,float x,float y){if(key==null||!key.isShown())return false;Rect r=new Rect();key.getDrawingRect(r);try{offsetDescendantRectToMyCoords(key,r);}catch(IllegalArgumentException detached){return false;}return x>=r.left&&x<r.right&&y>=r.top&&y<r.bottom;}
    private boolean overSpace(float x,float y){return over(spaceKey,x,y);}
    private void endErase(){if(pressedErase!=null)pressedErase.setPressed(false);pressedErase=null;erasing=false;if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(false);}
    private void releasePressed(){if(pressedKey!=null){pressedKey.setPressed(false);pressedKey=null;}}
    private void endCapture(){releasePressed();active=null;consuming=false;}
    @Override public boolean dispatchTouchEvent(MotionEvent event){
        int action=event.getActionMasked();
        if(erasing&&action==MotionEvent.ACTION_DOWN){if(event.getDownTime()<=eraseDownTime)return true;eraser.cancel(eraseToken);endErase();}
        if(erasing){
            if(event.getDownTime()!=eraseDownTime)return true;
            if(action==MotionEvent.ACTION_CANCEL){eraser.cancel(eraseToken);endErase();return true;}
            if(action==MotionEvent.ACTION_POINTER_DOWN||action==MotionEvent.ACTION_POINTER_UP){eraser.cancel(eraseToken);if(pressedErase!=null)pressedErase.setPressed(false);return true;}
            int index=event.findPointerIndex(erasePointer);
            if(index<0){eraser.cancel(eraseToken);if(action==MotionEvent.ACTION_UP)endErase();return true;}
            float rawOffsetX=event.getRawX()-event.getX(),rawOffsetY=event.getRawY()-event.getY();
            if(action==MotionEvent.ACTION_MOVE){for(int h=0;h<event.getHistorySize();h++)eraser.move(eraseToken,erasePointer,event.getHistoricalX(index,h)+rawOffsetX,event.getHistoricalY(index,h)+rawOffsetY);eraser.move(eraseToken,erasePointer,event.getX(index)+rawOffsetX,event.getY(index)+rawOffsetY);return true;}
            if(action==MotionEvent.ACTION_UP){eraser.up(eraseToken,erasePointer,event.getX(index)+rawOffsetX,event.getY(index)+rawOffsetY);endErase();return true;}
            return true;
        }
        if(action==MotionEvent.ACTION_DOWN&&!consuming&&eraser!=null&&over(eraseKey,event.getX(),event.getY())){
            long token=eraser.down(event.getPointerId(0),event.getRawX(),event.getRawY());
            if(token>=0){erasePointer=event.getPointerId(0);eraseToken=token;eraseDownTime=event.getDownTime();erasing=true;pressedErase=eraseKey;pressedErase.setPressed(true);if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(true);return true;}
        }
        if(controller==null)return super.dispatchTouchEvent(event);
        if(action==MotionEvent.ACTION_DOWN){
            if(consuming&&event.getDownTime()<downTime)return true; // Older queued DOWN cannot cancel a newer stream.
            if(consuming&&event.getDownTime()==downTime)return true; // Duplicate DOWN in the same physical stream is inert.
            if(consuming){controller.cancel(active,VoiceHoldController.CancelReason.POINTER_LOST);endCapture();}
            if(overSpace(event.getX(),event.getY())){pointer=event.getPointerId(0);VoiceHoldController.Token next=controller.down(pointer,event.getX(),event.getY(),event.getEventTime());if(next!=null||controller.busy()){active=next;if(next!=null)owned=next;consuming=true;downTime=event.getDownTime();pressedKey=spaceKey;pressedKey.setPressed(true);if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(true);return true;}}
            return super.dispatchTouchEvent(event);
        }
        if(!consuming)return super.dispatchTouchEvent(event);
        if(event.getDownTime()!=downTime)return true; // A queued event from an older physical pointer stream cannot end this one.
        if(action==MotionEvent.ACTION_CANCEL){controller.cancel(active,VoiceHoldController.CancelReason.ACTION_CANCEL);endCapture();return true;}
        if(action==MotionEvent.ACTION_POINTER_DOWN){controller.additionalPointer(active);releasePressed();return true;}
        if(action==MotionEvent.ACTION_MOVE){int index=event.findPointerIndex(pointer);if(index<0){controller.cancel(active,VoiceHoldController.CancelReason.POINTER_LOST);releasePressed();}else{for(int h=0;h<event.getHistorySize();h++)controller.move(active,pointer,event.getHistoricalX(index,h),event.getHistoricalY(index,h),event.getHistoricalEventTime(h));controller.move(active,pointer,event.getX(index),event.getY(index),event.getEventTime());}return true;}
        if(action==MotionEvent.ACTION_POINTER_UP){if(event.getPointerId(event.getActionIndex())==pointer){controller.cancel(active,VoiceHoldController.CancelReason.POINTER_LOST);releasePressed();}return true;}
        if(action==MotionEvent.ACTION_UP){if(event.getPointerId(event.getActionIndex())==pointer)controller.up(active,pointer,event.getX(event.getActionIndex()),event.getY(event.getActionIndex()),event.getEventTime());else controller.cancel(active,VoiceHoldController.CancelReason.POINTER_LOST);endCapture();return true;}
        return true;
    }
    @Override protected void onWindowVisibilityChanged(int visibility){super.onWindowVisibilityChanged(visibility);if(visibility!=VISIBLE){if(controller!=null)controller.cancel(owned,VoiceHoldController.CancelReason.WINDOW_CHANGED);if(eraser!=null)eraser.cancel();endErase();endCapture();}}
    @Override protected void onDetachedFromWindow(){if(controller!=null)controller.cancel(owned,VoiceHoldController.CancelReason.WINDOW_CHANGED);if(eraser!=null)eraser.cancel();endErase();endCapture();super.onDetachedFromWindow();}
}
