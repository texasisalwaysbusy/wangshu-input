package org.wangshu.ime;

import android.graphics.*;
import android.graphics.drawable.Drawable;

/** Font-independent hollow / filled / filled-and-underlined case states. */
final class CaseIcon extends Drawable {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int mode,size;
    CaseIcon(int state,float density,int color){mode=state;size=Math.round(24*density);paint.setColor(color);paint.setStrokeJoin(Paint.Join.ROUND);paint.setStrokeWidth(1.6f);}
    @Override public int getIntrinsicWidth(){return size;}
    @Override public int getIntrinsicHeight(){return size;}
    @Override public void draw(Canvas canvas){Rect b=getBounds();canvas.save();canvas.translate(b.left,b.top);canvas.scale(b.width()/24f,b.height()/24f);Path arrow=new Path();arrow.moveTo(12,2);arrow.lineTo(2,12);arrow.lineTo(7,12);arrow.lineTo(7,20);arrow.lineTo(17,20);arrow.lineTo(17,12);arrow.lineTo(22,12);arrow.close();paint.setStyle(mode==0?Paint.Style.STROKE:Paint.Style.FILL);canvas.drawPath(arrow,paint);if(mode==2){paint.setStyle(Paint.Style.FILL);canvas.drawRect(7,22,17,24,paint);}canvas.restore();}
    @Override public void setAlpha(int alpha){paint.setAlpha(alpha);invalidateSelf();}
    @Override public void setColorFilter(ColorFilter filter){paint.setColorFilter(filter);invalidateSelf();}
    @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
}
