package org.wangshu.ime;
import android.graphics.*;
import android.graphics.drawable.Drawable;
/** Original centered caret-navigation icon; no third-party asset. */
final class CursorIcon extends Drawable {
    final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);final int size;
    CursorIcon(float density,int color){size=Math.round(24*density);p.setColor(color);p.setStrokeWidth(1.8f);p.setStrokeCap(Paint.Cap.ROUND);}
    public int getIntrinsicWidth(){return size;}public int getIntrinsicHeight(){return size;}
    public void draw(Canvas c){Rect r=getBounds();c.save();c.translate(r.left,r.top);c.scale(r.width()/24f,r.height()/24f);c.drawLine(12,4,12,20,p);c.drawLine(9,4,15,4,p);c.drawLine(9,20,15,20,p);c.drawLine(2,12,8,12,p);c.drawLine(2,12,5,9,p);c.drawLine(2,12,5,15,p);c.drawLine(16,12,22,12,p);c.drawLine(22,12,19,9,p);c.drawLine(22,12,19,15,p);c.restore();}
    public void setAlpha(int a){p.setAlpha(a);}public void setColorFilter(ColorFilter f){p.setColorFilter(f);}public int getOpacity(){return PixelFormat.TRANSLUCENT;}
}
