package org.wangshu.ime;

import android.graphics.*;
import android.graphics.drawable.Drawable;

/** Original 24dp line drawings. Names and help belong to the native button. */
final class ToolbarIcon extends Drawable {
    enum Kind { LANGUAGE, MATH, LETTERS, CLIPBOARD, MICROPHONE, STOP, BUSY, SETTINGS, HIDE }
    private final Kind kind;
    private final Paint ink=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int size;
    ToolbarIcon(Kind kind,float density,int color){
        this.kind=kind;size=Math.round(24*density);ink.setColor(color);
        ink.setStyle(Paint.Style.STROKE);ink.setStrokeWidth(1.7f);
        ink.setStrokeCap(Paint.Cap.ROUND);ink.setStrokeJoin(Paint.Join.ROUND);
    }
    @Override public int getIntrinsicWidth(){return size;}
    @Override public int getIntrinsicHeight(){return size;}
    @Override public void draw(Canvas canvas){
        Rect bounds=getBounds();canvas.save();canvas.translate(bounds.left,bounds.top);
        canvas.scale(bounds.width()/24f,bounds.height()/24f);Path path=new Path();
        switch(kind){
            case LANGUAGE:
                canvas.drawCircle(12,12,8.5f,ink);canvas.drawOval(8,3.5f,16,20.5f,ink);
                canvas.drawLine(3.5f,12,20.5f,12,ink);break;
            case MATH:
                path.moveTo(19,4);path.lineTo(5,4);path.lineTo(12,12);path.lineTo(5,20);path.lineTo(19,20);break;
            case LETTERS:
                // Explicit alphabet action, not the ambiguous former A/I drawing.
                ink.setStyle(Paint.Style.FILL);ink.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));ink.setTextSize(10.5f);ink.setTextAlign(Paint.Align.CENTER);
                canvas.drawText("ABC",12,15.7f,ink);ink.setStyle(Paint.Style.STROKE);break;
            case CLIPBOARD:
                canvas.drawRoundRect(6,5,19,21,2,2,ink);canvas.drawRoundRect(9,3,16,7,1,1,ink);
                canvas.drawLine(9,12,16,12,ink);canvas.drawLine(9,16,15,16,ink);break;
            case MICROPHONE:
                canvas.drawRoundRect(9,3,15,14,3,3,ink);canvas.drawArc(6,7,18,18,0,180,false,ink);
                canvas.drawLine(12,18,12,21,ink);canvas.drawLine(8,21,16,21,ink);break;
            case STOP:canvas.drawRoundRect(6,6,18,18,2,2,ink);break;
            case BUSY:
                canvas.drawArc(4,4,20,20,-70,285,false,ink);path.moveTo(17,3);path.lineTo(20,6);path.lineTo(16,7);break;
            case SETTINGS:
                for(int i=0;i<8;i++){double a=i*Math.PI/4;canvas.drawLine(12+(float)Math.cos(a)*7,12+(float)Math.sin(a)*7,12+(float)Math.cos(a)*9,12+(float)Math.sin(a)*9,ink);}
                canvas.drawCircle(12,12,6.5f,ink);canvas.drawCircle(12,12,2.5f,ink);break;
            case HIDE:path.moveTo(6,9);path.lineTo(12,15);path.lineTo(18,9);break;
        }
        canvas.drawPath(path,ink);canvas.restore();
    }
    @Override public void setAlpha(int alpha){ink.setAlpha(alpha);invalidateSelf();}
    @Override public void setColorFilter(ColorFilter filter){ink.setColorFilter(filter);invalidateSelf();}
    @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
}
