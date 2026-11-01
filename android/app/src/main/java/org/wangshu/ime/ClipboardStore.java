package org.wangshu.ime;

import android.content.*;
import android.os.PersistableBundle;
import android.util.AtomicFile;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Opt-in bounded plain-text history; private app storage, never follows URIs. */
final class ClipboardStore {
    static final int MAX_ENTRIES=100,MAX_TEXT=8000;
    static final long DAY=86400000L;
    private static ClipboardStore instance;
    private final AtomicFile file;
    private JSONArray rows;
    static synchronized ClipboardStore get(Context context)throws IOException {if(instance==null)instance=new ClipboardStore(new File(context.getFilesDir(),"clipboard-v1.json"));return instance;}
    ClipboardStore(File target)throws IOException {
        file=new AtomicFile(target);rows=new JSONArray();if(target.exists()||new File(target+".bak").exists())try(InputStream in=file.openRead()){JSONObject source=new JSONObject(new String(PersonalStore.read(in),StandardCharsets.UTF_8));if(!(source.get("version") instanceof Integer)||source.getInt("version")!=1)throw new IOException("不支持的剪贴板版本");rows=validate(source.getJSONArray("items"));}catch(JSONException e){throw new IOException("剪贴板历史不可读，原文件保留",e);}
    }
    static String allowedText(ClipData clip,boolean enabled,boolean privateEditor){
        if(!enabled||privateEditor||clip==null||clip.getItemCount()!=1)return null;
        ClipDescription description=clip.getDescription();PersistableBundle extras=description.getExtras();if(extras!=null&&extras.getBoolean("android.content.extra.IS_SENSITIVE",false))return null;
        if(!description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN))return null;
        ClipData.Item item=clip.getItemAt(0);if(item.getUri()!=null||item.getIntent()!=null||item.getText()==null)return null;
        try{return PersonalStore.text(item.getText().toString(),MAX_TEXT,true);}catch(IOException e){return null;}
    }
    private static JSONArray validate(JSONArray source)throws IOException {
        if(source.length()>MAX_ENTRIES)throw new IOException("历史条目超过100条");JSONArray out=new JSONArray();HashSet<String> ids=new HashSet<>();try{for(int i=0;i<source.length();i++){JSONObject item=source.getJSONObject(i);if(!(item.get("id") instanceof String)||!(item.get("text") instanceof String)||(!(item.get("at") instanceof Long)&&!(item.get("at") instanceof Integer)))throw new IOException("历史字段类型无效");String id=item.getString("id");if(!UUID.fromString(id).toString().equals(id)||!ids.add(id))throw new IOException("历史编号无效");String text=PersonalStore.text(item.getString("text"),MAX_TEXT,true);long at=item.getLong("at");if(at<0||!(item.get("pinned") instanceof Boolean))throw new IOException("历史记录格式无效");out.put(new JSONObject().put("id",id).put("text",text).put("at",at).put("pinned",item.getBoolean("pinned")));}}catch(JSONException|IllegalArgumentException e){throw new IOException("历史记录格式无效",e);}return out;
    }
    private void save(JSONArray next)throws IOException {
        JSONArray checked=validate(next);FileOutputStream out=null;try{byte[] bytes=new JSONObject().put("version",1).put("items",checked).toString().getBytes(StandardCharsets.UTF_8);if(bytes.length>PersonalStore.MAX_BYTES)throw new IOException("历史文件超过1MiB");out=file.startWrite();out.write(bytes);out.getFD().sync();file.finishWrite(out);rows=checked;}catch(JSONException e){throw new IOException("历史保存失败",e);}catch(IOException e){if(out!=null)file.failWrite(out);throw e;}
    }
    private JSONArray copy()throws IOException {try{return new JSONArray(rows.toString());}catch(JSONException e){throw new IOException("历史读取失败",e);}}
    private static long window(int days)throws IOException {if(days!=1&&days!=7&&days!=30)throw new IOException("无效保存期限");return days*DAY;}
    private JSONArray visible(long now,int days)throws IOException {
        long age=window(days);JSONArray result=new JSONArray();try{for(int pinned=1;pinned>=0;pinned--)for(int i=0;i<rows.length();i++){JSONObject item=rows.getJSONObject(i);boolean pin=item.getBoolean("pinned");if(pin!=(pinned==1))continue;if(pin||now-item.getLong("at")<=age)result.put(new JSONObject(item.toString()));}}catch(JSONException e){throw new IOException("历史读取失败",e);}return result;
    }
    synchronized JSONArray list(long now,int days)throws IOException {JSONArray current=visible(now,days);if(current.length()!=rows.length())save(current);return current;}
    synchronized boolean add(String value,long now,int days)throws IOException {
        PersonalStore.text(value,MAX_TEXT,true);JSONArray fresh=visible(now,days),next=new JSONArray();boolean pin=false;String id=UUID.randomUUID().toString();try{for(int i=0;i<fresh.length();i++){JSONObject item=fresh.getJSONObject(i);if(value.equals(item.getString("text"))){pin=item.getBoolean("pinned");id=item.getString("id");}else next.put(item);}if(next.length()>=MAX_ENTRIES){int victim=-1;long oldest=Long.MAX_VALUE;for(int i=0;i<next.length();i++){JSONObject item=next.getJSONObject(i);if(!item.getBoolean("pinned")&&item.getLong("at")<oldest){victim=i;oldest=item.getLong("at");}}if(victim<0)return false;next.remove(victim);}JSONArray ordered=new JSONArray().put(new JSONObject().put("id",id).put("text",value).put("at",now).put("pinned",pin));for(int i=0;i<next.length();i++)ordered.put(next.getJSONObject(i));save(ordered);return true;}catch(JSONException e){throw new IOException("历史保存失败",e);}
    }
    synchronized void edit(String id,String expected,String value)throws IOException {PersonalStore.text(value,MAX_TEXT,true);JSONArray next=copy();try{for(int i=0;i<next.length();i++){JSONObject other=next.getJSONObject(i);if(!id.equals(other.getString("id"))&&value.equals(other.getString("text")))throw new IOException("已有相同内容的条目");}for(int i=0;i<next.length();i++){JSONObject item=next.getJSONObject(i);if(id.equals(item.getString("id"))){if(!expected.equals(item.getString("text")))throw new IOException("条目已经改变，请重新打开");item.put("text",value);save(next);return;}}throw new IOException("条目不存在");}catch(JSONException e){throw new IOException("历史保存失败",e);}}
    synchronized void pin(String id,boolean pin)throws IOException {JSONArray next=copy();try{for(int i=0;i<next.length();i++){JSONObject item=next.getJSONObject(i);if(id.equals(item.getString("id"))){item.put("pinned",pin);save(next);return;}}throw new IOException("条目不存在");}catch(JSONException e){throw new IOException("历史保存失败",e);}}
    synchronized void remove(String id,String expected)throws IOException {JSONArray next=copy();try{for(int i=0;i<next.length();i++)if(id.equals(next.getJSONObject(i).getString("id"))){if(!expected.equals(next.getJSONObject(i).getString("text")))throw new IOException("条目已改变，请重新打开");next.remove(i);save(next);return;}}catch(JSONException e){throw new IOException("历史保存失败",e);}}
    synchronized void clear()throws IOException {save(new JSONArray());}
}
