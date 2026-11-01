package org.wangshu.ime;

import android.content.Context;
import android.util.AtomicFile;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.*;

/** Explicitly authored local data only. No key logging, clipboard capture or audio. */
final class PersonalStore {
    static final int MAX_BYTES=1024*1024, MAX_WORDS=2000, MAX_PHRASES=1000;
    private static final int MAX_JSON_DEPTH=16, MAX_JSON_CONTAINERS=4096, MAX_JSON_SEPARATORS=32000;
    private static PersonalStore instance;
    private final AtomicFile file;
    private final File path,pendingMarker;
    static final class UnconfirmedSaveException extends IOException {UnconfirmedSaveException(){super("保存结果未确认，个人数据已暂停修改；请保留文件并检查");}UnconfirmedSaveException(Throwable cause){super("保存结果未确认，个人数据已暂停修改；请保留文件并检查",cause);}}
    private JSONObject data;
    private long revision;
    synchronized long revision(){return revision;}
    static synchronized PersonalStore get(Context context)throws IOException {
        if(instance==null)instance=new PersonalStore(new File(context.getFilesDir(),"personal-v1.json"));
        return instance;
    }
    PersonalStore(File path)throws IOException {
        this.path=path;pendingMarker=new File(path.getPath()+".pending");file=new AtomicFile(path);
        if(pendingMarker.exists())throw new UnconfirmedSaveException();
        if(path.exists()||new File(path+".bak").exists())try(InputStream in=file.openRead()){data=parse(read(in));}
        else data=empty();
    }
    static JSONObject empty(){try{return new JSONObject().put("kind","wangshu-personal").put("version",1).put("words",new JSONArray()).put("phrases",new JSONArray());}catch(JSONException e){throw new AssertionError(e);}}
    static byte[] read(InputStream stream)throws IOException {
        if(stream==null)throw new IOException("无法读取文件");
        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] block=new byte[8192];int n;
        while((n=stream.read(block))!=-1){if(out.size()+n>MAX_BYTES)throw new IOException("文件超过 1 MiB 限制");out.write(block,0,n);}return out.toByteArray();
    }
    static String text(String value,int limit,boolean multiline)throws IOException {
        if(value==null||value.trim().isEmpty()||value.codePointCount(0,value.length())>limit)throw new IOException("内容为空或超过长度限制");
        for(int i=0;i<value.length();){int c=value.codePointAt(i);if((c<32&&!(multiline&&(c=='\n'||c=='\t')))||(c>=127&&c<=159)||(c>=0xD800&&c<=0xDFFF))throw new IOException("内容包含不支持的控制字符");i+=Character.charCount(c);}return value;
    }
    // Only dictionary words are canonicalized; phrases and clipboard call text() unchanged.
    private static String wordText(String value)throws IOException {
        if(value==null)throw new IOException("词条不能为空");
        return text(Normalizer.normalize(value,Normalizer.Form.NFC),200,false);
    }
    static String key(String input)throws IOException {
        if(input==null)throw new IOException("拼音不能为空");
        String value=input.trim().toLowerCase(Locale.ROOT).replace("'","");
        if(!value.matches("[a-z]{1,64}"))throw new IOException("拼音仅支持 1–64 个英文字母，ü 请用 v");return value;
    }
    private static String field(JSONObject object,String name)throws JSONException,IOException {Object value=object.get(name);if(!(value instanceof String))throw new IOException("文本字段必须是字符串");return (String)value;}
    private static void fields(JSONObject object,String... allowed)throws IOException {for(Iterator<String> it=object.keys();it.hasNext();)if(!Arrays.asList(allowed).contains(it.next()))throw new IOException("条目包含未知字段，未导入");}
    private static String decode(byte[] bytes)throws IOException {
        if(bytes.length>MAX_BYTES)throw new IOException("文件超过 1 MiB 限制");
        java.nio.ByteBuffer input=java.nio.ByteBuffer.wrap(bytes);
        try{return StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(input).toString();}
        catch(java.nio.charset.CharacterCodingException e){int line=1;boolean carriage=false;for(int i=0;i<input.position();i++){int c=bytes[i]&255;if(c==13){line++;carriage=true;}else if(c==10){if(!carriage)line++;carriage=false;}else carriage=false;}throw new IOException("第 "+line+" 行：文件不是有效的 UTF-8",e);}
    }
    /** Bounds allocation and recursion before Android's permissive JSONTokener runs. */
    static final class JsonSyntax {
        private final String input;private int at,containers,separators;
        JsonSyntax(String value){input=value;}
        private void fail()throws IOException {throw new IOException("JSON 格式不正确，位置 "+(at+1));}
        private void space(){while(at<input.length()&&(input.charAt(at)==' '||input.charAt(at)=='\t'||input.charAt(at)=='\r'||input.charAt(at)=='\n'))at++;}
        private boolean take(char c){if(at<input.length()&&input.charAt(at)==c){at++;return true;}return false;}
        private void expect(char c)throws IOException {if(!take(c))fail();}
        private boolean digit(){return at<input.length()&&input.charAt(at)>='0'&&input.charAt(at)<='9';}
        private void string()throws IOException {
            expect('"');while(at<input.length()){char c=input.charAt(at++);if(c=='"')return;if(c<32)fail();if(c=='\\'){if(at==input.length())fail();char escape=input.charAt(at++);if(escape=='u'){for(int i=0;i<4;i++){if(at==input.length())fail();char hex=input.charAt(at++);if(!((hex>='0'&&hex<='9')||(hex>='a'&&hex<='f')||(hex>='A'&&hex<='F')))fail();}}else if("\"\\/bfnrt".indexOf(escape)<0)fail();}}fail();
        }
        private void number()throws IOException {
            take('-');if(!take('0')){if(!digit()||input.charAt(at)=='0')fail();while(digit())at++;}
            if(take('.')){if(!digit())fail();while(digit())at++;}
            if(take('e')||take('E')){if(!take('+'))take('-');if(!digit())fail();while(digit())at++;}
        }
        private void comma()throws IOException {expect(',');if(++separators>MAX_JSON_SEPARATORS)throw new IOException("JSON 项目数量超过格式限制");}
        private void value(int depth)throws IOException {
            space();if(at==input.length())fail();char first=input.charAt(at);
            if(first=='{'||first=='['){if(depth>=MAX_JSON_DEPTH||++containers>MAX_JSON_CONTAINERS)throw new IOException("JSON 层级或项目数量超过格式限制");at++;space();if(first=='{'){if(take('}'))return;while(true){string();space();expect(':');value(depth+1);space();if(take('}'))return;comma();space();}}else{if(take(']'))return;while(true){value(depth+1);space();if(take(']'))return;comma();}}}
            else if(first=='"')string();else if(first=='-'||(first>='0'&&first<='9'))number();
            else {for(String literal:new String[]{"true","false","null"})if(input.startsWith(literal,at)){at+=literal.length();return;}fail();}
        }
        void validate()throws IOException {value(0);space();if(at!=input.length())fail();}
    }
    static JSONObject parse(byte[] bytes)throws IOException {return parseJson(decode(bytes));}
    private static JSONObject parseJson(String decoded)throws IOException {
        new JsonSyntax(decoded).validate();
        try{
            JSONTokener tokens=new JSONTokener(decoded);Object value=tokens.nextValue();
            if(!(value instanceof JSONObject)||tokens.nextClean()!=0)throw new IOException("需要单个 JSON 对象");JSONObject input=(JSONObject)value;
            if(!"wangshu-personal".equals(input.getString("kind"))||!(input.get("version") instanceof Integer)||input.getInt("version")!=1)throw new IOException("不支持的个人词库格式或版本");
            fields(input,"kind","version","words","phrases");JSONArray words=input.getJSONArray("words"),phrases=input.getJSONArray("phrases");
            if(words.length()>MAX_WORDS||phrases.length()>MAX_PHRASES)throw new IOException("条目超过词库 2000 / 常用语 1000 限制");
            JSONObject clean=empty();Set<String> seen=new HashSet<>();
            for(int i=0;i<words.length();i++){JSONObject w=words.getJSONObject(i);fields(w,"pinyin","text");String k=key(field(w,"pinyin")),t=wordText(field(w,"text"));if(seen.add(k+"\u0000"+t))clean.getJSONArray("words").put(new JSONObject().put("pinyin",k).put("text",t));}
            seen.clear();
            for(int i=0;i<phrases.length();i++){JSONObject p=phrases.getJSONObject(i);fields(p,"text","shortcut");String t=text(field(p,"text"),4000,true),s=p.has("shortcut")?field(p,"shortcut").trim().toLowerCase(Locale.ROOT):"";if(!s.isEmpty()&&!s.matches("[a-z]{1,32}"))throw new IOException("常用语简称仅支持 1–32 个英文字母");if(seen.add(s+"\u0000"+t))clean.getJSONArray("phrases").put(new JSONObject().put("text",t).put("shortcut",s));}
            return clean;
        }catch(JSONException e){throw new IOException("个人词库文件不完整或格式不正确",e);}
    }
    static final class ImportData {
        final JSONObject data;final String format;
        ImportData(JSONObject value,String name){data=value;format=name;}
    }
    static ImportData importFile(byte[] bytes)throws IOException {
        String decoded=decode(bytes);if(decoded.startsWith("\uFEFF"))decoded=decoded.substring(1);
        String trimmed=decoded.trim();if(trimmed.startsWith("{")||trimmed.startsWith("["))return new ImportData(parseJson(decoded),"JSON");
        return new ImportData(parseTsv(decoded),"TSV");
    }
    static JSONObject parseImport(byte[] bytes)throws IOException {return importFile(bytes).data;}
    private static JSONObject parseTsv(String decoded)throws IOException {
        JSONObject result=empty();Set<String> seen=new HashSet<>();int lineNumber=0;boolean first=true;
        try(BufferedReader lines=new BufferedReader(new StringReader(decoded))){String line;
            while((line=lines.readLine())!=null){lineNumber++;String trimmed=line.trim();if(trimmed.isEmpty()||trimmed.startsWith("#"))continue;
                int tab=line.indexOf('\t');if(tab<0||line.indexOf('\t',tab+1)>=0)throw new IOException("第 "+lineNumber+" 行：需要恰好两列（拼音、词条），以一个制表符分隔；未实现权重或来源列");
                String pinyin=line.substring(0,tab),raw=line.substring(tab+1);boolean header=(pinyin.trim().equalsIgnoreCase("pinyin")&&raw.trim().equalsIgnoreCase("text"))||(pinyin.trim().equals("拼音")&&raw.trim().equals("词条"));
                if(header){if(!first)throw new IOException("第 "+lineNumber+" 行：表头只能出现在第一条记录之前");first=false;continue;}first=false;
                try{String k=key(pinyin),t=wordText(raw);if(seen.add(k+"\u0000"+t)){if(seen.size()>MAX_WORDS)throw new IOException("去重后词条超过 2000 条限制");result.getJSONArray("words").put(new JSONObject().put("pinyin",k).put("text",t));}}
                catch(IOException e){throw new IOException("第 "+lineNumber+" 行："+e.getMessage(),e);}
            }
            if(seen.isEmpty())throw new IOException("文件没有可导入词条；TSV 每行需要拼音和词条两列");
            return parse(bytes(result));
        }catch(JSONException e){throw new IOException("第 "+lineNumber+" 行：词条格式不正确",e);}
    }
    synchronized JSONObject snapshot()throws IOException {if(pendingMarker.exists())throw new UnconfirmedSaveException();return parse(bytes(data));}
    synchronized long phraseRevision()throws IOException {if(pendingMarker.exists())throw new UnconfirmedSaveException();return revision;}
    synchronized PersonalPhraseAssociation.PhraseData associationPhrases()throws IOException {
        if(pendingMarker.exists())throw new UnconfirmedSaveException();
        try{JSONArray rows=data.getJSONArray("phrases");ArrayList<String> out=new ArrayList<>();for(int i=0;i<rows.length();i++)out.add(rows.getJSONObject(i).getString("text"));return new PersonalPhraseAssociation.PhraseData(revision,out);}
        catch(JSONException invalid){throw new IOException("个人短语暂不可读，原数据保留",invalid);}
    }
    private static byte[] bytes(JSONObject value)throws IOException {try{java.nio.ByteBuffer encoded=StandardCharsets.UTF_8.newEncoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(value.toString()));byte[] bytes=new byte[encoded.remaining()];encoded.get(bytes);return bytes;}catch(java.nio.charset.CharacterCodingException e){throw new IOException("个人数据包含不完整的 Unicode 字符",e);}}
    private void save(JSONObject next)throws IOException {
        JSONObject checked=parse(bytes(next));byte[] payload=bytes(checked);FileOutputStream out=null;boolean reserved=false,started=false,finishInvoked=false;
        try{
            // Exclusive marker precedes the consistency check: separate instances/processes cannot
            // both pass a stale check and then overwrite each other. A leftover marker fails closed.
            if(!pendingMarker.createNewFile())throw new UnconfirmedSaveException();reserved=true;
            try(FileOutputStream marker=new FileOutputStream(pendingMarker)){marker.write(1);marker.getFD().sync();}
            JSONObject disk;if(path.exists()||new File(path+".bak").exists()){try(InputStream current=file.openRead()){disk=parse(read(current));}}else disk=empty();
            if(!Arrays.equals(bytes(disk),bytes(data))){if(!pendingMarker.delete())throw new UnconfirmedSaveException();reserved=false;throw new IOException("个人数据已在其他位置改变，请重新打开后再修改");}
            started=true;out=file.startWrite();out.write(payload);out.getFD().sync();finishInvoked=true;file.finishWrite(out);out=null;
            try(InputStream confirmed=file.openRead()){if(!Arrays.equals(payload,read(confirmed)))throw new IOException("个人词库保存结果未确认");}
            if(!pendingMarker.delete())throw new UnconfirmedSaveException();reserved=false;data=checked;revision++;
        }catch(IOException|RuntimeException e){if(out!=null&&!finishInvoked)file.failWrite(out);if(reserved||started||e instanceof UnconfirmedSaveException)throw new UnconfirmedSaveException(e);if(e instanceof IOException)throw (IOException)e;throw e;}
    }
    synchronized void putWord(int index,String pinyin,String word)throws IOException {
        try{JSONObject next=snapshot();JSONArray list=next.getJSONArray("words");JSONObject w=new JSONObject().put("pinyin",key(pinyin)).put("text",wordText(word));
            if(index>=0){if(index>=list.length())throw new IOException("条目已改变，请重新打开");list.put(index,w);}else list.put(w);save(next);
        }catch(JSONException e){throw new IOException("无法保存词条",e);}
    }
    synchronized void putPhrase(int index,String value,String shortcut)throws IOException {
        try{JSONObject next=snapshot();JSONArray list=next.getJSONArray("phrases");JSONObject p=new JSONObject().put("text",text(value,4000,true)).put("shortcut",shortcut.trim());
            if(index>=0){if(index>=list.length())throw new IOException("条目已改变，请重新打开");list.put(index,p);}else list.put(p);save(next);
        }catch(JSONException e){throw new IOException("无法保存常用语",e);}
    }
    synchronized void remove(String type,int index)throws IOException {
        try{JSONObject next=snapshot();JSONArray list=next.getJSONArray(type);if(index<0||index>=list.length())throw new IOException("条目已改变，请重新打开");list.remove(index);save(next);}catch(JSONException e){throw new IOException("无法删除条目",e);}
    }
    synchronized JSONObject previewMerge(JSONObject incoming)throws IOException {
        try{JSONObject current=snapshot(),checked=parse(bytes(incoming)),merged=empty();
            for(String type:new String[]{"words","phrases"}){Set<String> seen=new HashSet<>();JSONArray target=merged.getJSONArray(type);
                for(JSONObject source:new JSONObject[]{current,checked}){JSONArray entries=source.getJSONArray(type);for(int i=0;i<entries.length();i++){JSONObject item=entries.getJSONObject(i);String identity=item.getString(type.equals("words")?"pinyin":"shortcut")+"\u0000"+item.getString("text");if(seen.add(identity))target.put(item);}}
                if(target.length()>(type.equals("words")?MAX_WORDS:MAX_PHRASES))throw new IOException("合并后条目超过词库 2000 / 常用语 1000 限制");
            }return parse(bytes(merged));
        }catch(JSONException e){throw new IOException("无法合并文件",e);}
    }
    synchronized void merge(JSONObject incoming)throws IOException {save(previewMerge(incoming));}
    synchronized byte[] export()throws IOException {return bytes(snapshot());}
    synchronized String[] words(String input)throws IOException {
        if(pendingMarker.exists())throw new UnconfirmedSaveException();String k;try{k=key(input);}catch(IOException e){return new String[0];}
        LinkedHashSet<String> out=new LinkedHashSet<>();try{JSONArray list=data.getJSONArray("words");for(int i=0;i<list.length();i++){JSONObject w=list.getJSONObject(i);if(k.equals(w.getString("pinyin")))out.add(w.getString("text"));}list=data.getJSONArray("phrases");for(int i=0;i<list.length();i++){JSONObject p=list.getJSONObject(i);if(k.equals(p.optString("shortcut","").toLowerCase(Locale.ROOT)))out.add(p.getString("text"));}}catch(JSONException e){throw new IOException("无法读取个人词库",e);}return out.toArray(new String[0]);
    }
}
