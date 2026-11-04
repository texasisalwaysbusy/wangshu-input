package org.wangshu.ime;
import android.content.Context;
import android.graphics.*;
import android.widget.Button;

/** Original24dp action vectors. Text stays available to native accessibility. */
final class GlyphKey extends Button {
 private final Paint ink=new Paint(Paint.ANTI_ALIAS_FLAG);
 private final Path glyphPath=new Path();
 GlyphKey(Context c){super(c);ink.setStyle(Paint.Style.STROKE);ink.setStrokeWidth(1.8f);ink.setStrokeJoin(Paint.Join.ROUND);ink.setStrokeCap(Paint.Cap.ROUND);}
 @Override protected void onDraw(Canvas c){CharSequence label=getText();if(!"↵".contentEquals(label)&&!"⌫".contentEquals(label)&&!"⌄".contentEquals(label)&&!"⌃".contentEquals(label)){super.onDraw(c);return;}float size=Math.min(24*getResources().getDisplayMetrics().density,Math.min(getWidth(),getHeight())-4);if(size<=0)return;ink.setColor(getCurrentTextColor());c.save();c.translate((getWidth()-size)/2,(getHeight()-size)/2);c.scale(size/24,size/24);Path p=glyphPath;p.reset();
  if("↵".contentEquals(label)){p.moveTo(20,4);p.lineTo(20,14);p.lineTo(4,14);p.moveTo(9,9);p.lineTo(4,14);p.lineTo(9,19);}
  else if("⌫".contentEquals(label)){p.moveTo(9,5);p.lineTo(21,5);p.lineTo(21,19);p.lineTo(9,19);p.lineTo(2,12);p.close();p.moveTo(11,9);p.lineTo(17,15);p.moveTo(17,9);p.lineTo(11,15);}
  else{boolean up="⌃".contentEquals(label);p.moveTo(6,up?15:9);p.lineTo(12,up?9:15);p.lineTo(18,up?15:9);}
  c.drawPath(p,ink);c.restore();
 }
}
