package org.wangshu.ime;
import android.app.Instrumentation;import android.content.*;import java.io.*;import java.util.*;
/** Prepared real SharedPreferences checks, isolated synthetic names; no real settings/cleanup. */
final class SettingsBackupChecks {
 static String run(Instrumentation test)throws Exception {
  Context target=test.getTargetContext();String prefix="settings-backup-check-"+UUID.randomUUID()+"-";
  File isolatedFiles=new File(target.getFilesDir(),prefix);if(!isolatedFiles.mkdir())throw new AssertionError("settings fixture folder");Context isolated=new ContextWrapper(target){@Override public Context getApplicationContext(){return this;}@Override public java.io.File getFilesDir(){return isolatedFiles;}@Override public java.io.File getDataDir(){return isolatedFiles;}@Override public SharedPreferences getSharedPreferences(String name,int mode){return super.getSharedPreferences(prefix+name,mode);}};
  int count=0;SharedPreferences legacy=isolated.getSharedPreferences("keyboard",0);if(!legacy.edit().putInt("cursorStepDp",24).putString("unknown","synthetic").commit())throw new AssertionError("fixture save");
  isolated.getSharedPreferences("clipboard",0).edit().putBoolean("enabled",true).commit();
  SharedPreferences keyboard=AppPreferences.open(isolated,"keyboard");if(keyboard.getInt("cursorStepDp",0)!=24)throw new AssertionError("migration value");count++;
  if(legacy.getInt("cursorStepDp",0)!=24||!legacy.getString("unknown","").equals("synthetic"))throw new AssertionError("original legacy not retained");count++;
  byte[] before=AppPreferences.snapshot(isolated);Map<SettingsBackup.Key,Object> next=SettingsBackup.decode(SettingsBackup.encode(key->key.name.equals("candidateFontDelta")?3:key.name.equals("phraseEnglish")?true:key.fallback));
  AppPreferences.restore(isolated,before,next);if(keyboard.getInt("candidateFontDelta",0)!=3||!AppPreferences.open(isolated,"associations").getBoolean("phraseEnglish",false))throw new AssertionError("multi-group restore");count++;
  if(!AppPreferences.open(isolated,"clipboard").getBoolean("enabled",false))throw new AssertionError("capture optin overwritten");count++;
  if(!keyboard.getString("unknown","").equals("synthetic"))throw new AssertionError("unknown key overwritten");count++;
  byte[] stale=AppPreferences.snapshot(isolated);keyboard.edit().putInt("cursorStepDp",25).commit();try{AppPreferences.restore(isolated,stale,next);throw new AssertionError("stale accepted");}catch(IOException expected){count++;}
  if(keyboard.getInt("cursorStepDp",0)!=25)throw new AssertionError("stale restore changed value");count++;
  byte[] old=AppPreferences.snapshot(isolated);try{SettingsBackup.decode("WANGSHU-SETTINGS\t1\nclipboard\tenabled\tb\ttrue\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));throw new AssertionError("privacy injection accepted");}catch(IOException expected){count++;}
  if(!Arrays.equals(old,AppPreferences.snapshot(isolated)))throw new AssertionError("bad file changed settings");count++;
  List<String> changed=new ArrayList<>();SharedPreferences.OnSharedPreferenceChangeListener listener=(p,key)->changed.add(key);keyboard.registerOnSharedPreferenceChangeListener(listener);keyboard.edit().putInt("candidateFontDelta",2).commit();test.waitForIdleSync();keyboard.unregisterOnSharedPreferenceChangeListener(listener);if(!changed.equals(Arrays.asList("candidateFontDelta")))throw new AssertionError("group listener mapping");count++;
  if(!isolated.getSharedPreferences(AppPreferences.FILE,0).getBoolean(AppPreferences.MIGRATED,false))throw new AssertionError("migration marker");count++;
  if(legacy.getInt("cursorStepDp",0)!=24)throw new AssertionError("original legacy changed later");count++;
  SharedPreferences.Editor reused=keyboard.edit().putInt("cursorStepDp",26);reused.commit();keyboard.edit().putInt("cursorStepDp",27).commit();reused.commit();if(keyboard.getInt("cursorStepDp",0)!=27)throw new AssertionError("editor put replay");count++;
  if(!new File(isolatedFiles,AppPreferences.FILE+".migration-attempted").isFile())throw new AssertionError("migration attempt marker missing");count++;
  return "PASS "+count+" real SharedPreferences checks; synthetic retained prefix="+prefix+". No UI/file-provider/process-restart/crash durability/microphone acceptance implied.";
 }
}
