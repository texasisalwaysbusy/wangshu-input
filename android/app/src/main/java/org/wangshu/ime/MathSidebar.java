package org.wangshu.ime;
import android.content.Context;
import java.util.*;
/** Short default scrollable list; local explicit user customization, no learning. */
final class MathSidebar {
    static final String DEFAULTS="+\n−\n=\n×\n÷\n(\n)\n[\n]\n^\n√\nπ\n∑\n∫\n≈\n≠\n≤\n≥";
    static String[] parse(String value){LinkedHashSet<String> result=new LinkedHashSet<>();for(String line:value.split("\\r?\\n")){String s=line.trim();if(s.isEmpty())continue;if(s.codePointCount(0,s.length())>12||s.codePoints().anyMatch(Character::isISOControl))throw new IllegalArgumentException("每行一个符号或短符号组合，最多12个字符，不含控制字符");result.add(s);if(result.size()>100)throw new IllegalArgumentException("最多保存100项常用符号");}if(result.isEmpty())throw new IllegalArgumentException("请至少保留一个符号");return result.toArray(new String[0]);}
    static String[] load(Context c){try{return parse(c.getSharedPreferences("keyboard",0).getString("numberSymbols",DEFAULTS));}catch(IllegalArgumentException e){return parse(DEFAULTS);}}
}
