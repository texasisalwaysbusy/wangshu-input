package org.wangshu.ime;
import java.io.*;import java.nio.charset.*;import java.util.*;
public final class SettingsBackupChecks {
 static int checks;static void require(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
 interface Checked{void run()throws Exception;}
 static void reject(Checked action,String label)throws Exception{try{action.run();throw new AssertionError("accepted "+label);}catch(IOException expected){checks++;}}
 static byte[] file(String text){return text.getBytes(StandardCharsets.UTF_8);}
 static String text(byte[] bytes){return new String(bytes,StandardCharsets.UTF_8);}
 public static void main(String[] args)throws Exception{
  byte[] defaults=SettingsBackup.encode(key->key.fallback);Map<SettingsBackup.Key,Object> parsed=SettingsBackup.decode(defaults);
  require(parsed.size()==19,"all supported settings present");require(Arrays.equals(defaults,SettingsBackup.encode(key->parsed.get(key))),"default exact roundtrip");
  require(!text(defaults).contains("enabled")&&!text(defaults).contains("candidate-learning"),"capture enablement excluded");
  for(SettingsBackup.Key key:SettingsBackup.KEYS){Object value=parsed.get(key);require(value.equals(key.fallback),"default "+key.name);}
  for(int delta=-3;delta<=3;delta++){final int n=delta;byte[] bytes=SettingsBackup.encode(key->key.name.equals("candidateFontDelta")?n:key.fallback);require(SettingsBackup.decode(bytes).entrySet().stream().anyMatch(e->e.getKey().name.equals("candidateFontDelta")&&e.getValue().equals(n)),"delta roundtrip");}
  for(int days:new int[]{1,7,30}){byte[] bytes=SettingsBackup.encode(key->key.name.equals("days")?days:key.fallback);require(Arrays.equals(bytes,SettingsBackup.encode(key->SettingsBackup.decode(bytes).get(key))),"days roundtrip");}
  byte[] unicode=SettingsBackup.encode(key->key.name.equals("numberSymbols")?"α\n😀\n≤\n(╯°□°）╯":key.fallback);require(Arrays.equals(unicode,SettingsBackup.encode(key->SettingsBackup.decode(unicode).get(key))),"unicode newline symbols exact");
  require(SettingsBackup.decode(file("\uFEFF"+text(defaults).replace("\n","\r\n"))).size()==19,"UTF8 BOM CRLF");
  String first=text(defaults).split("\n")[1];
  reject(()->SettingsBackup.decode(file(text(defaults)+first+"\n")),"duplicate");
  reject(()->SettingsBackup.decode(file(text(defaults).replace("keyboard\tenterActionsEnabled\tb\tfalse","keyboard\tenterActionsEnabled\tb\tTRUE"))),"malformed bool");
  reject(()->SettingsBackup.decode(file(text(defaults).replace("keyboard\tvoiceRightAction\ti\t0","keyboard\tvoiceRightAction\ti\t2"))),"unknown action");
  reject(()->SettingsBackup.decode(file(text(defaults).replace("clipboard\tdays\ti\t7","clipboard\tdays\ti\t2"))),"unsupported days");
  reject(()->SettingsBackup.decode(file(text(defaults).replace("\t16\n","\t016\n"))),"noncanonical integer");
  reject(()->SettingsBackup.decode(file(text(defaults).replace("\t16\n","\t99999999999999999999\n"))),"integer overflow");
  reject(()->SettingsBackup.decode(file(text(defaults).replace(first+"\n",""))),"missing setting");
  reject(()->SettingsBackup.decode(file(text(defaults)+"clipboard\tenabled\tb\ttrue\n")),"capture enable injection");
  reject(()->SettingsBackup.decode(file(text(defaults)+"candidate-learning\tenabled\tb\ttrue\n")),"learning enable injection");
  reject(()->SettingsBackup.decode(file(text(defaults).replace("WANGSHU-SETTINGS\t1","WANGSHU-SETTINGS\t2"))),"unknown version");
  reject(()->SettingsBackup.decode(new byte[]{(byte)0xc3,0x28}),"invalid utf8");
  reject(()->SettingsBackup.decode(new byte[SettingsBackup.MAX_BYTES+1]),"over max bytes");
  reject(()->SettingsBackup.read(new ByteArrayInputStream(new byte[SettingsBackup.MAX_BYTES+1])),"bounded stream");
  reject(()->SettingsBackup.read(null),"null provider stream");
  reject(()->SettingsBackup.encode(key->key.name.equals("cursorStepDp")?49:key.fallback),"out of range export");
  reject(()->SettingsBackup.encode(key->key.name.equals("cursorStepDp")?"16":key.fallback),"type corruption export");
  reject(()->SettingsBackup.encode(key->key.name.equals("numberSymbols")?"\ud800":key.fallback),"unpaired surrogate");
  reject(()->SettingsBackup.encode(key->key.name.equals("numberSymbols")?"":key.fallback),"empty symbols");
  reject(()->SettingsBackup.decode(file(text(defaults).replace(first,first.substring(0,first.lastIndexOf('\t')+1)+"***"))),"base64 corruption");
  require(Arrays.equals(SettingsBackup.read(new ByteArrayInputStream(defaults)),defaults),"input stream roundtrip");
  System.out.println("PASS settings backup codec: "+checks+" assertions; production codec and MathSidebar, synthetic files only; Android NOT_RUN");
 }
}
