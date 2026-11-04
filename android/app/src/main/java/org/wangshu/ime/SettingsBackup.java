package org.wangshu.ime;

import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;

/** Strict, bounded settings-only interchange. Includes user-authored symbol-list text, never key history, audio or capture enablement. */
final class SettingsBackup {
    static final int MAX_BYTES=65536;
    private static final String HEADER="WANGSHU-SETTINGS\t1";
    static final class Key {
        final String group,name;final Object fallback;final int min,max;
        Key(String g,String n,Object value,int low,int high){group=g;name=n;fallback=value;min=low;max=high;}
        String id(){return group+"\t"+name;}
        char type(){return fallback instanceof Boolean?'b':fallback instanceof Integer?'i':'s';}
        Object check(Object value)throws IOException {
            if(value==null||value.getClass()!=fallback.getClass())throw new IOException("设置类型不正确："+name);
            if(value instanceof Integer&&((Integer)value<min||(Integer)value>max))throw new IOException("设置超出范围："+name);
            if(value instanceof String){String text=(String)value;if(text.length()>4096)throw new IOException("符号设置过长");
                try{MathSidebar.parse(text);}catch(IllegalArgumentException bad){throw new IOException("符号设置无效",bad);}}
            return value;
        }
    }
    static final List<Key> KEYS;
    static {
        ArrayList<Key> keys=new ArrayList<>();
        keys.add(new Key("keyboard","numberSymbols",MathSidebar.DEFAULTS,0,0));
        keys.add(new Key("keyboard","pinyinDisplayPosition",0,0,1));
        keys.add(new Key("keyboard","enterActionsEnabled",false,0,0));
        keys.add(new Key("keyboard","languageKeyPosition",0,0,1));
        keys.add(new Key("keyboard","candidateFontDelta",0,-3,3));
        keys.add(new Key("keyboard","cursorStepDp",16,8,48));
        keys.add(new Key("keyboard","voiceRightAction",0,0,1));
        keys.add(new Key("associations","math",true,0,0));
        keys.add(new Key("associations","phraseChinese",false,0,0));
        keys.add(new Key("associations","phraseEnglish",false,0,0));
        for(String name:new String[]{"z_zh","c_ch","s_sh","n_l","f_h","an_ang","en_eng","in_ing"})
            keys.add(new Key("fuzzy-pinyin-v1","fuzzy."+name,false,0,0));
        // Do not transfer the history/learning enabled flags or any private content.
        keys.add(new Key("clipboard","days",7,1,30));
        KEYS=Collections.unmodifiableList(keys);
    }
    interface Values {Object read(Key key)throws IOException;}
    static byte[] encode(Values values)throws IOException {
        StringBuilder out=new StringBuilder(HEADER).append('\n');
        for(Key key:KEYS){Object value=key.check(values.read(key));
            if(key.group.equals("clipboard")&&!Arrays.asList(1,7,30).contains(value))throw new IOException("剪贴板期限无效");
            String text=value.toString();if(key.type()=='s')text=Base64.getEncoder().encodeToString(utf8(text));
            out.append(key.id()).append('\t').append(key.type()).append('\t').append(text).append('\n');}
        byte[] bytes=utf8(out.toString());if(bytes.length>MAX_BYTES)throw new IOException("设置文件过大");return bytes;
    }
    private static byte[] utf8(String text)throws IOException {
        try{ByteBuffer bytes=StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(text));byte[] out=new byte[bytes.remaining()];bytes.get(out);return out;}
        catch(CharacterCodingException bad){throw new IOException("设置不是有效 Unicode",bad);}
    }
    private static String decodeUtf8(byte[] bytes)throws IOException {
        try{return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();}
        catch(CharacterCodingException bad){throw new IOException("设置文件不是有效 UTF-8",bad);}
    }
    static Map<Key,Object> decode(byte[] bytes)throws IOException {
        if(bytes==null||bytes.length>MAX_BYTES)throw new IOException("设置文件超过 64 KiB");
        String text=decodeUtf8(bytes);if(text.startsWith("\uFEFF"))text=text.substring(1);
        String[] lines=text.split("\\r?\\n",-1);if(lines.length==0||!HEADER.equals(lines[0]))throw new IOException("不是受支持的望舒设置备份");
        LinkedHashMap<String,Key> allowed=new LinkedHashMap<>();for(Key key:KEYS)allowed.put(key.id(),key);
        LinkedHashMap<Key,Object> result=new LinkedHashMap<>();
        for(int i=1;i<lines.length;i++){if(i==lines.length-1&&lines[i].isEmpty())continue;
            String[] fields=lines[i].split("\t",-1);if(fields.length!=4)throw new IOException("设置文件第 "+(i+1)+" 行格式错误");
            Key key=allowed.get(fields[0]+"\t"+fields[1]);if(key==null||!fields[2].equals(String.valueOf(key.type()))||result.containsKey(key))throw new IOException("未知、重复或不兼容的设置");
            Object value;try{if(key.type()=='b'){if(!fields[3].equals("true")&&!fields[3].equals("false"))throw new IllegalArgumentException();value=Boolean.valueOf(fields[3]);}
                else if(key.type()=='i'){if(!fields[3].matches("-?(0|[1-9][0-9]*)"))throw new IllegalArgumentException();value=Integer.valueOf(fields[3]);}
                else {byte[] decoded=Base64.getDecoder().decode(fields[3]);if(!Base64.getEncoder().encodeToString(decoded).equals(fields[3]))throw new IllegalArgumentException();value=decodeUtf8(decoded);}}
            catch(IllegalArgumentException bad){throw new IOException("设置值无效："+key.name,bad);}
            key.check(value);if(key.group.equals("clipboard")&&!Arrays.asList(1,7,30).contains(value))throw new IOException("剪贴板期限无效");result.put(key,value);
        }
        if(result.size()!=KEYS.size())throw new IOException("设置备份不完整；未还原任何设置");
        return Collections.unmodifiableMap(result);
    }
    static byte[] read(InputStream stream)throws IOException {
        if(stream==null)throw new IOException("无法打开所选设置文件");ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] block=new byte[4096];int size;
        while((size=stream.read(block))!=-1){if(out.size()+size>MAX_BYTES)throw new IOException("设置文件超过 64 KiB");out.write(block,0,size);}return out.toByteArray();
    }
    private SettingsBackup(){}
}
