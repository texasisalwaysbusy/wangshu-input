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
  require(parsed.size()==23,"all supported settings present");require(Arrays.equals(defaults,SettingsBackup.encode(key->parsed.get(key))),"default exact roundtrip");
  require(!text(defaults).contains("enabled")&&!text(defaults).contains("candidate-learning"),"capture enablement excluded");
  for(SettingsBackup.Key key:SettingsBackup.KEYS){Object value=parsed.get(key);require(value.equals(key.fallback),"default "+key.name);}
  for(int delta=-3;delta<=3;delta++){final int n=delta;byte[] bytes=SettingsBackup.encode(key->key.name.equals("candidateFontDelta")?n:key.fallback);require(SettingsBackup.decode(bytes).entrySet().stream().anyMatch(e->e.getKey().name.equals("candidateFontDelta")&&e.getValue().equals(n)),"delta roundtrip");}
  for(int days:new int[]{1,7,30}){byte[] bytes=SettingsBackup.encode(key->key.name.equals("days")?days:key.fallback);require(Arrays.equals(bytes,SettingsBackup.encode(key->SettingsBackup.decode(bytes).get(key))),"days roundtrip");}
  byte[] unicode=SettingsBackup.encode(key->key.name.equals("numberSymbols")?"α\n😀\n≤\n(╯°□°）╯":key.fallback);require(Arrays.equals(unicode,SettingsBackup.encode(key->SettingsBackup.decode(unicode).get(key))),"unicode newline symbols exact");
  require(SettingsBackup.decode(file("\uFEFF"+text(defaults).replace("\n","\r\n"))).size()==23,"UTF8 BOM CRLF");
  String prediction="associations\tbuiltinPredictions\tb\tfalse\n";
  String numeric="associations\tnumericPredictions\tb\tfalse\n";
  String cursor="associations\tcursorPredictions\tb\ttrue\n";
  String legacyV4=text(defaults).replace("WANGSHU-SETTINGS\t5","WANGSHU-SETTINGS\t4").replace(cursor,"");
  String legacyV3=legacyV4.replace("WANGSHU-SETTINGS\t4","WANGSHU-SETTINGS\t3").replace(numeric,"");
  String legacyV2=legacyV3.replace("WANGSHU-SETTINGS\t3","WANGSHU-SETTINGS\t2").replace(prediction,"");
  String legacy=legacyV2.replace("WANGSHU-SETTINGS\t2","WANGSHU-SETTINGS\t1").replace("keyboard\tvoiceOutputMode\ti\t0\n","");
  Map<SettingsBackup.Key,Object> migrated=SettingsBackup.decode(file(legacy));
  require(Arrays.equals(defaults,SettingsBackup.encode(migrated::get)),"v1 fills raw voice mode and disables local prediction");
  require(Arrays.equals(defaults,SettingsBackup.encode(SettingsBackup.decode(file(legacyV2))::get)),"v2 fills disabled local prediction");
  require(Arrays.equals(defaults,SettingsBackup.encode(SettingsBackup.decode(file(legacyV3))::get)),"v3 fills disabled numeric prediction");
  require(Arrays.equals(defaults,SettingsBackup.encode(SettingsBackup.decode(file(legacyV4))::get)),"v4 preserves prior enabled caret behavior");
  reject(()->SettingsBackup.decode(file(legacyV4+cursor)),"v4 cannot inject cursor prediction setting");
  reject(()->SettingsBackup.decode(file(text(defaults).replace(cursor,""))),"v5 requires explicit cursor prediction setting");
  reject(()->SettingsBackup.decode(file(legacyV3+numeric)),"v3 cannot inject numeric prediction setting");
  reject(()->SettingsBackup.decode(file(legacyV4.replace(numeric,""))),"v4 requires explicit numeric prediction setting");
  reject(()->SettingsBackup.decode(file(legacy.replace("clipboard\tdays\ti\t7\n",""))),"incomplete v1 remains rejected");
  reject(()->SettingsBackup.decode(file(legacy+"keyboard\tvoiceOutputMode\ti\t0\n")),"v1 cannot inject new setting");
  reject(()->SettingsBackup.decode(file(legacyV2+prediction)),"v2 cannot inject prediction setting");
  reject(()->SettingsBackup.decode(file(legacyV2.replace("keyboard\tvoiceOutputMode\ti\t0\n",""))),"v2 requires explicit voice output");
  reject(()->SettingsBackup.decode(file(legacyV3.replace(prediction,""))),"v3 requires explicit prediction setting");
  byte[] optedIn=SettingsBackup.encode(key->key.name.equals("cursorPredictions")?false:key.name.endsWith("Predictions")?true:key.fallback);
  require(text(optedIn).contains("associations\tbuiltinPredictions\tb\ttrue\n"),"v5 exports explicit local prediction setting");
  require(text(optedIn).contains("associations\tnumericPredictions\tb\ttrue\n"),"v5 exports explicit numeric prediction setting");
  require(text(optedIn).contains("associations\tcursorPredictions\tb\tfalse\n"),"v5 exports disabled caret setting");
  require(Arrays.equals(optedIn,SettingsBackup.encode(SettingsBackup.decode(optedIn)::get)),"v5 preserves independent prediction settings");
  byte[] mathMode=SettingsBackup.encode(key->key.name.equals("voiceOutputMode")?1:key.fallback);
  require(Arrays.equals(mathMode,SettingsBackup.encode(SettingsBackup.decode(mathMode)::get)),"voice mode survives backup roundtrip");
  byte[] numberMode=SettingsBackup.encode(key->key.name.equals("voiceOutputMode")?2:key.fallback);
  require(Arrays.equals(numberMode,SettingsBackup.encode(SettingsBackup.decode(numberMode)::get)),"numeric voice mode survives backup roundtrip");
  require(Arrays.equals(numberMode,SettingsBackup.encode(SettingsBackup.decode(file(legacyV2.replace("voiceOutputMode\ti\t0","voiceOutputMode\ti\t2")))::get)),"v2 preserves numeric voice mode");
  reject(()->SettingsBackup.decode(file(text(numberMode).replace("voiceOutputMode\ti\t2","voiceOutputMode\ti\t3"))),"unknown voice mode");
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
  reject(()->SettingsBackup.decode(file(text(defaults).replace("WANGSHU-SETTINGS\t5","WANGSHU-SETTINGS\t6"))),"unknown version");
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
  byte[] portable=SettingsBackup.encodePortable(numberMode);
  require(SettingsBackup.isPortable(portable)&&!text(portable).contains("numberSymbols")&&!text(portable).contains("enabled"),"portable settings only");
  String extended="\uFEFF"+(text(portable)+"windows\tphraseAssociations\tb\ttrue\n").replace("\n","\r\n");
  Map<SettingsBackup.Key,Object> merged=SettingsBackup.mergePortable(file(extended),unicode);
  require(Arrays.equals(SettingsBackup.encode(merged::get),SettingsBackup.encode(key->key.name.equals("voiceOutputMode")?2:SettingsBackup.decode(unicode).get(key))),"Windows extension validates without changing Android-only values");
  reject(()->SettingsBackup.decode(portable),"portable must not enter full recovery");
  reject(()->SettingsBackup.mergePortable(file(text(portable)+"clipboard\tenabled\tb\ttrue\n"),defaults),"portable capture enablement");
  reject(()->SettingsBackup.mergePortable(file(text(portable)+"windows\tphraseAssociations\tb\tTRUE\n"),defaults),"invalid Windows extension");
  reject(()->SettingsBackup.mergePortable(file(text(portable).replace("voiceOutputMode\ti\t2","voiceOutputMode\ti\t02")),defaults),"invalid portable voice type");
  reject(()->SettingsBackup.mergePortable(file(text(portable).replace("associations\tcursorPredictions\tb\ttrue\n","")),defaults),"incomplete portable");
  if(args.length==2){
   byte[] fromWindows=java.nio.file.Files.readAllBytes(java.nio.file.Path.of(args[0]));
   var imported=SettingsBackup.mergePortable(fromWindows,unicode);
   var map=new HashMap<>(imported);
   for(var entry:imported.entrySet()){
    String key=entry.getKey().name;Object value=entry.getValue();
    if(key.equals("voiceOutputMode")){require(value.equals(2),"Windows voice mode applied");map.put(entry.getKey(),1);}
    if(key.equals("builtinPredictions"))require(value.equals(true),"Windows next-word toggle applied");
    if(key.equals("cursorPredictions"))require(value.equals(false),"Windows cursor toggle applied");
   }
   java.nio.file.Files.write(java.nio.file.Path.of(args[1]),SettingsBackup.encodePortable(SettingsBackup.encode(map::get)),java.nio.file.StandardOpenOption.CREATE_NEW);
  }
  System.out.println("PASS settings backup codec: "+checks+" assertions; production codec and MathSidebar, synthetic files only; Android NOT_RUN");
 }
}
