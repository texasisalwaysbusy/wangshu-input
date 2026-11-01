package org.wangshu.ime;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.*;
import android.view.View;
import android.widget.TextView;

/** Fixed neutral palettes follow system brightness without wallpaper-derived colors. */
final class KeyboardSkin {
    static int color(Context c,int id){return c.getColor(id);}
    static void key(Context c,View v,boolean selected){style(c,v,selected,false);}
    static void function(Context c,View v,boolean selected){style(c,v,selected,true);}
    static void tool(Context c,View v,boolean selected){
        GradientDrawable shape=new GradientDrawable();shape.setColor(selected?color(c,R.color.selected):android.graphics.Color.TRANSPARENT);shape.setCornerRadius(8*c.getResources().getDisplayMetrics().density);
        InsetDrawable inset=new InsetDrawable(shape,Math.round(3*c.getResources().getDisplayMetrics().density));
        int accent=color(c,R.color.accent);v.setBackgroundTintList(null);v.setBackground(new RippleDrawable(ColorStateList.valueOf((accent&0x00ffffff)|0x33000000),inset,null));
    }
    private static void style(Context c,View v,boolean selected,boolean function){
        int ink=color(c,R.color.ink),accent=color(c,R.color.accent);
        GradientDrawable shape=new GradientDrawable();shape.setColor(color(c,selected?R.color.selected:function?R.color.function_key:R.color.surface));shape.setCornerRadius(9*c.getResources().getDisplayMetrics().density);
        // Insets create breathing room while preserving the full native hit rectangle.
        InsetDrawable inset=new InsetDrawable(shape,Math.round(2*c.getResources().getDisplayMetrics().density));
        v.setBackgroundTintList(null);v.setBackground(new RippleDrawable(ColorStateList.valueOf((accent&0x00ffffff)|0x33000000),inset,null));
        if(v instanceof TextView)((TextView)v).setTextColor(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},new int[]{}},new int[]{color(c,R.color.muted),ink}));
    }
}
