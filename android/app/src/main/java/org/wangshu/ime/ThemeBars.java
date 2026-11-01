package org.wangshu.ime;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;
import android.view.*;

/** API26 has the runtime light-nav flag; its XML attribute arrives in27. */
final class ThemeBars {
 static void navigation(Context context,Window window){if(window==null)return;boolean light=(context.getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)!=Configuration.UI_MODE_NIGHT_YES;View decor=window.getDecorView();int mask=View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;decor.setSystemUiVisibility((decor.getSystemUiVisibility()&~mask)|(light?mask:0));if(Build.VERSION.SDK_INT<35)window.setNavigationBarColor(context.getColor(R.color.paper));if(Build.VERSION.SDK_INT>=30)decor.post(()->{WindowInsetsController c=decor.getWindowInsetsController();if(c!=null)c.setSystemBarsAppearance(light?WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS:0,WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);});}
}
