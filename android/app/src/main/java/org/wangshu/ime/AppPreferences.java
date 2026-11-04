package org.wangshu.ime;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.IOException;
import java.io.File;
import java.util.*;

/** One atomic settings file, with legacy group views. Original files are never removed. */
final class AppPreferences {
    static final String FILE="wangshu-settings-v2",MIGRATED="@legacy-imported",RECOVERY_FENCE="@recovery-fence";
    private static final Object LOCK=new Object();
    private static final String[] LEGACY={"keyboard","associations","fuzzy-pinyin-v1","clipboard","candidate-learning"};
    private static final WeakHashMap<SharedPreferences,Store> STORES=new WeakHashMap<>();
    private static final class Store {
        final SharedPreferences file;final Context context;final Map<String,Group> groups=new HashMap<>();final boolean ready;boolean recoveryAttempted;
        Store(Context context,SharedPreferences file){this.context=context;this.file=file;boolean ok=false;
            try{
                File files=context.getFilesDir(),data=context.getDataDir();
                if(files==null||data==null||!files.isDirectory())throw new IOException("App settings storage unavailable");
                File marker=new File(files,FILE+".migration-attempted");
                File xml=new File(new File(data,"shared_prefs"),FILE+".xml");
                boolean existing=xml.exists()||new File(xml.getPath()+".bak").exists();
                // A repair attempt leaves an append-only fence before touching preferences.
                // Only the corresponding atomic settings transaction can acknowledge it.
                String fence=SettingsRecoveryFiles.currentFence(files);
                if(!Objects.equals(fence,file.getString(RECOVERY_FENCE,null)))throw new IOException("Unconfirmed settings recovery");
                if(file.getBoolean(MIGRATED,false)){
                    if(!marker.exists())markAttempt(marker);ok=true;
                }else if(!existing&&!marker.exists()){
                    // Persist the attempt before copying. A crash, corrupt/absent XML,
                    // or failed commit must never re-import old capture consent later.
                    markAttempt(marker);SharedPreferences.Editor edit=file.edit();
                    for(String group:LEGACY)for(Map.Entry<String,?> entry:context.getSharedPreferences(group,0).getAll().entrySet())put(edit,group+':'+entry.getKey(),entry.getValue());
                    ok=edit.putBoolean(MIGRATED,true).commit();
                }
            }catch(IOException|RuntimeException unavailable){ok=false;}ready=ok;
        }
    }
    private static void markAttempt(File path)throws IOException {
        try(java.nio.channels.FileChannel channel=java.nio.channels.FileChannel.open(path.toPath(),java.nio.file.StandardOpenOption.CREATE_NEW,java.nio.file.StandardOpenOption.WRITE)){
            java.nio.ByteBuffer bytes=java.nio.charset.StandardCharsets.UTF_8.encode("WANGSHU-SETTINGS-MIGRATION\t2\n");while(bytes.hasRemaining())channel.write(bytes);channel.force(true);
        }
        SettingsRecoveryFiles.forceDirectory(path.getParentFile().toPath());
    }
    static boolean managed(String group){return Arrays.asList(LEGACY).contains(group);}
    static SharedPreferences open(Context context,String group){
        synchronized(LOCK){Context owner=context.getApplicationContext();if(owner==null)owner=context;
            SharedPreferences file=owner.getSharedPreferences(FILE,0);Store store=STORES.get(file);
            if(store==null){store=new Store(owner,file);STORES.put(file,store);}
            Group view=store.groups.get(group);if(view==null){view=new Group(store,group);store.groups.put(group,view);}return view;
        }
    }
    static boolean writable(Context context){synchronized(LOCK){return ((Group)open(context,LEGACY[0])).store.ready;}}
    private static void put(SharedPreferences.Editor editor,String key,Object value){
        if(value==null)editor.remove(key);else if(value instanceof String)editor.putString(key,(String)value);
        else if(value instanceof Boolean)editor.putBoolean(key,(Boolean)value);else if(value instanceof Integer)editor.putInt(key,(Integer)value);
        else if(value instanceof Long)editor.putLong(key,(Long)value);else if(value instanceof Float)editor.putFloat(key,(Float)value);
        else if(value instanceof Set){HashSet<String> strings=new HashSet<>();for(Object item:(Set<?>)value){if(!(item instanceof String))throw new IllegalArgumentException("Unsupported settings set");strings.add((String)item);}editor.putStringSet(key,strings);}
        else throw new IllegalArgumentException("Unsupported settings type");
    }
    private static final class Group implements SharedPreferences {
        final Store store;final String name,prefix;final Map<OnSharedPreferenceChangeListener,OnSharedPreferenceChangeListener> listeners=new IdentityHashMap<>();
        Group(Store store,String name){this.store=store;this.name=name;prefix=name+':';}
        SharedPreferences source(){return store.file;}
        String key(String value){return prefix+value;}
        public Map<String,?> getAll(){synchronized(LOCK){Map<String,Object> copy=new HashMap<>();if(!store.ready)return copy;for(Map.Entry<String,?> entry:source().getAll().entrySet())if(entry.getKey().startsWith(prefix)){Object value=entry.getValue();if(value instanceof Set)value=new HashSet<>((Set<?>)value);copy.put(entry.getKey().substring(prefix.length()),value);}return copy;}}
        public String getString(String key,String fallback){synchronized(LOCK){return store.ready?source().getString(key(key),fallback):fallback;}}
        public Set<String> getStringSet(String key,Set<String> fallback){synchronized(LOCK){Set<String> value=store.ready?source().getStringSet(key(key),fallback):fallback;return value==null?null:new HashSet<>(value);}}
        public int getInt(String key,int fallback){synchronized(LOCK){return store.ready?source().getInt(key(key),fallback):fallback;}}
        public long getLong(String key,long fallback){synchronized(LOCK){return store.ready?source().getLong(key(key),fallback):fallback;}}
        public float getFloat(String key,float fallback){synchronized(LOCK){return store.ready?source().getFloat(key(key),fallback):fallback;}}
        public boolean getBoolean(String key,boolean fallback){synchronized(LOCK){if(!store.ready&&key.equals("enabled")&&(name.equals("clipboard")||name.equals("candidate-learning")))return false;return store.ready?source().getBoolean(key(key),fallback):fallback;}}
        public boolean contains(String key){synchronized(LOCK){return store.ready&&source().contains(key(key));}}
        public Editor edit(){return new Changes(this);}
        public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener){synchronized(LOCK){if(!store.ready||listeners.containsKey(listener))return;OnSharedPreferenceChangeListener bridge=(prefs,key)->{if(key!=null&&(!store.ready||key.startsWith(prefix)))listener.onSharedPreferenceChanged(this,store.ready?key.substring(prefix.length()):key);};listeners.put(listener,bridge);source().registerOnSharedPreferenceChangeListener(bridge);}}
        public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener){synchronized(LOCK){OnSharedPreferenceChangeListener bridge=listeners.remove(listener);if(bridge!=null)source().unregisterOnSharedPreferenceChangeListener(bridge);}}
    }
    private static final class Changes implements SharedPreferences.Editor {
        final Group group;final Map<String,Object> values=new HashMap<>();boolean clear;
        Changes(Group group){this.group=group;}
        public SharedPreferences.Editor putString(String key,String value){synchronized(LOCK){values.put(key,value);return this;}}
        public SharedPreferences.Editor putStringSet(String key,Set<String> value){synchronized(LOCK){values.put(key,value==null?null:new HashSet<>(value));return this;}}
        public SharedPreferences.Editor putInt(String key,int value){synchronized(LOCK){values.put(key,value);return this;}}
        public SharedPreferences.Editor putLong(String key,long value){synchronized(LOCK){values.put(key,value);return this;}}
        public SharedPreferences.Editor putFloat(String key,float value){synchronized(LOCK){values.put(key,value);return this;}}
        public SharedPreferences.Editor putBoolean(String key,boolean value){synchronized(LOCK){values.put(key,value);return this;}}
        public SharedPreferences.Editor remove(String key){synchronized(LOCK){values.put(key,null);return this;}}
        public SharedPreferences.Editor clear(){synchronized(LOCK){clear=true;return this;}}
        private SharedPreferences.Editor prepared(){SharedPreferences.Editor edit=group.store.file.edit();if(clear)for(String key:group.store.file.getAll().keySet())if(key.startsWith(group.prefix))edit.remove(key);for(Map.Entry<String,Object> entry:values.entrySet())put(edit,group.prefix+entry.getKey(),entry.getValue());values.clear();clear=false;return edit;}
        public boolean commit(){synchronized(LOCK){return group.store.ready&&prepared().commit();}}
        public void apply(){synchronized(LOCK){if(group.store.ready)prepared().apply();}}
    }
    static byte[] snapshot(Context context)throws IOException {
        synchronized(LOCK){return SettingsBackup.encode(key->{SharedPreferences preferences=open(context,key.group);Group group=(Group)preferences;if(!group.store.ready)throw new IOException("设置完整性或迁移保存未确认；原文件保留，自动记录及设置写入已暂停，请核对后修复");Object value=preferences.getAll().get(key.name);return value==null?key.fallback:value;});}
    }
    static void restore(Context context,byte[] expected,Map<SettingsBackup.Key,Object> incoming)throws IOException {
        synchronized(LOCK){if(!Arrays.equals(expected,snapshot(context)))throw new IOException("预览后本机设置已变化，请重新选择文件并核对");
            // Validate again through the production codec before constructing the single transaction.
            Map<SettingsBackup.Key,Object> checked=SettingsBackup.decode(SettingsBackup.encode(incoming::get));
            Store store=((Group)open(context,SettingsBackup.KEYS.get(0).group)).store;
            SharedPreferences.Editor edit=store.file.edit();for(Map.Entry<SettingsBackup.Key,Object> item:checked.entrySet())put(edit,item.getKey().group+':'+item.getKey().name,item.getValue());
            if(!edit.commit())throw new IOException("整组设置保存结果未确认；当前进程可能已生效，重启后可能保留旧组。请逐项核对，未自动重试");
            if(!Arrays.equals(SettingsBackup.encode(checked::get),snapshot(context)))throw new IOException("整组设置读回不一致，请核对；未自动重试");
        }
    }
    /** Opaque, single-use confirmation token. Constructed only from a fully valid backup. */
    static final class RecoveryPreview {
        private final Store store;private final Map<String,Object> before;
        private final SettingsRecoveryFiles.State evidence;private boolean used;
        final Map<SettingsBackup.Key,Object> incoming;
        private RecoveryPreview(Store store,Map<String,Object> before,SettingsRecoveryFiles.State evidence,Map<SettingsBackup.Key,Object> incoming){this.store=store;this.before=before;this.evidence=evidence;this.incoming=incoming;}
    }
    private static Map<String,Object> memory(Store store)throws IOException {
        try{Map<String,Object> copy=new HashMap<>();for(Map.Entry<String,?> entry:store.file.getAll().entrySet()){Object value=entry.getValue();if(value instanceof Set)value=Collections.unmodifiableSet(new HashSet<>((Set<?>)value));copy.put(entry.getKey(),value);}return Collections.unmodifiableMap(copy);}
        catch(RuntimeException error){throw new IOException("无法核对本地设置；尚未写入设置",error);}
    }
    static RecoveryPreview previewRecovery(Context context,byte[] backup)throws IOException {
        synchronized(LOCK){
            Map<SettingsBackup.Key,Object> checked=SettingsBackup.decode(backup);
            Store store=((Group)open(context,LEGACY[0])).store;
            if(store.ready)throw new IOException("本地设置未暂停，请使用普通还原并重新核对");
            if(store.recoveryAttempted)throw new IOException("本次进程已尝试修复，请先自行重启应用及输入法后核对；未自动重试");
            return new RecoveryPreview(store,memory(store),SettingsRecoveryFiles.inspect(store.context.getFilesDir(),store.context.getDataDir()),checked);
        }
    }
    static File recover(Context context,RecoveryPreview preview)throws IOException {
        synchronized(LOCK){
            Store store=((Group)open(context,LEGACY[0])).store;
            if(preview==null||preview.store!=store||preview.used||store.ready||store.recoveryAttempted)throw new IOException("修复确认已失效，请重新选择备份并核对；若已尝试保存，请先自行重启应用及输入法");
            preview.used=true;
            if(!preview.before.equals(memory(store)))throw new IOException("预览后本机设置已变化，请重新选择备份并核对；尚未写入设置");
            Map<SettingsBackup.Key,Object> checked=SettingsBackup.decode(SettingsBackup.encode(preview.incoming::get));
            File saved=SettingsRecoveryFiles.preserve(preview.evidence);
            // No editor is constructed before all observed surviving bytes are preserved.
            if(!preview.before.equals(memory(store)))throw new IOException("保存故障证据时本机设置已变化；尚未写入设置，故障副本保留");
            // The fence survives failed commits and rejects an older consent-bearing XML
            // after restart. Any failure from here is uncertain and is never retried here.
            store.recoveryAttempted=true;
            try{
                String fence=SettingsRecoveryFiles.beginRecovery(preview.evidence);
                File marker=new File(store.context.getFilesDir(),FILE+".migration-attempted");
                if(!marker.exists())markAttempt(marker);
                SharedPreferences.Editor edit=store.file.edit();
                for(Map.Entry<SettingsBackup.Key,Object> item:checked.entrySet())put(edit,item.getKey().group+':'+item.getKey().name,item.getValue());
                edit.putBoolean("clipboard:enabled",false).putBoolean("candidate-learning:enabled",false).putBoolean(MIGRATED,true).putString(RECOVERY_FENCE,fence);
                // commit(false) can still replace process memory. Never retry in this process,
                // and keep all Group views fail-closed even after a successful recovery.
                if(!edit.commit())throw new IOException("修复保存结果未确认；内存或磁盘可能已变化，不能视作回滚。故障副本已保留；未自动重试，请自行重启应用及输入法后核对");
                Map<String,Object> readback=memory(store);
                if(!fence.equals(readback.get(RECOVERY_FENCE))||!fence.equals(SettingsRecoveryFiles.currentFence(store.context.getFilesDir()))||!Boolean.TRUE.equals(readback.get(MIGRATED))||!Boolean.FALSE.equals(readback.get("clipboard:enabled"))||!Boolean.FALSE.equals(readback.get("candidate-learning:enabled")))throw new IOException("修复读回未确认；故障副本已保留，未自动重试，请自行重启应用及输入法后核对");
                for(Map.Entry<SettingsBackup.Key,Object> item:checked.entrySet())if(!item.getValue().equals(readback.get(item.getKey().group+':'+item.getKey().name)))throw new IOException("修复读回未确认；故障副本已保留，未自动重试，请自行重启应用及输入法后核对");
            }catch(IOException|RuntimeException uncertain){throw new IOException("修复保存结果未确认；内存或磁盘可能已变化，不能视作回滚。故障副本已保留（"+saved.getName()+"）；未自动重试，请自行重启应用及输入法后核对",uncertain);}
            return saved;
        }
    }
    private AppPreferences(){}
}
