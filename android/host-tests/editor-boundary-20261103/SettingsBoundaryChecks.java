package org.wangshu.ime;
import java.io.*;import java.nio.charset.StandardCharsets;import java.util.*;
/** Fixed-seed codec mutations only. Never writes preferences or repairs storage. */
public final class SettingsBoundaryChecks {
 static int checks,accepted,rejected;static void check(boolean v,String n){if(!v)throw new AssertionError(n);checks++;}
 static void roundtrip(byte[] bytes)throws IOException {Map<SettingsBackup.Key,Object> values=SettingsBackup.decode(bytes);check(values.size()==19,"complete fixed allowlist");byte[] canonical=SettingsBackup.encode(values::get);Map<SettingsBackup.Key,Object> reread=SettingsBackup.decode(canonical);check(values.equals(reread),"semantic canonical roundtrip");check(Arrays.equals(canonical,SettingsBackup.encode(reread::get)),"stable canonical serialization");for(SettingsBackup.Key k:values.keySet())check(!k.name.equals("enabled")&&!k.group.equals("candidate-learning"),"capture/learning consent absent");}
 static void refuses(byte[] b,String n)throws Exception {try{SettingsBackup.decode(b);throw new AssertionError(n);}catch(IOException expected){checks++;}}
 public static void main(String[] args)throws Exception {
  byte[] original=SettingsBackup.encode(k->k.fallback);roundtrip(original);String base=new String(original,StandardCharsets.UTF_8);
  for(String extra:new String[]{"clipboard\tenabled\tb\ttrue\n","candidate-learning\tenabled\tb\ttrue\n","keyboard\tpassword\ts\tc2VjcmV0\n","keyboard\tcursorStepDp\ti\t24\n"})refuses((base+extra).getBytes(StandardCharsets.UTF_8),"no injected unknown or duplicate field");
  for(String n:new String[]{"+16","016","-01","16 "," 16","2147483648","-2147483649","0x10","1e1"})refuses(base.replace("cursorStepDp\ti\t16","cursorStepDp\ti\t"+n).getBytes(StandardCharsets.UTF_8),"strict bounded numeric grammar");
  for(int size:new int[]{1,12}){String glyph="😀".repeat(size);byte[] b=SettingsBackup.encode(k->k.name.equals("numberSymbols")?glyph:k.fallback);roundtrip(b);check(new String(b,StandardCharsets.UTF_8).contains("keyboard\tnumberSymbols\ts\t"),"non-BMP symbols encoded only under allowlisted key");}
  try{SettingsBackup.encode(k->k.name.equals("numberSymbols")?"😀".repeat(13):k.fallback);throw new AssertionError("oversize per-line symbol accepted");}catch(IOException expected){checks++;}
  try{SettingsBackup.encode(k->k.name.equals("numberSymbols")?"\ud800":k.fallback);throw new AssertionError("invalid UTF16 symbol accepted");}catch(IOException expected){checks++;}
  Random random=new Random(0x57534855L);
  for(int i=0;i<1024;i++){byte[] mutation=original.clone();int at=random.nextInt(mutation.length);mutation[at]^=(byte)(1<<random.nextInt(8));try{roundtrip(mutation);accepted++;}catch(IOException expected){rejected++;checks++;}check(Arrays.equals(original,SettingsBackup.encode(k->k.fallback)),"mutation decode has no ambient settings state");}
  refuses(new byte[SettingsBackup.MAX_BYTES+1],"oversize backup bound");refuses(null,"null backup bound");
  check(accepted+rejected==1024&&rejected>900,"fixed-seed mutation accounting");
  System.out.println("PASS settings boundary "+checks+" assertions; 1024 fixed-seed byte mutations (accepted="+accepted+", rejected="+rejected+"); production codec only, no restore/storage/Android acceptance");
 }
}
