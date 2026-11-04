import java.io.*;import java.net.URI;import java.nio.file.*;import java.security.*;import java.util.*;import javax.tools.*;
/** Source-file launcher + in-memory compiler; extracted production control flow. */
public final class MemoryRunner {
 static final class Source extends SimpleJavaFileObject{final String v;Source(String n,String s){super(URI.create("string:///"+n+Kind.SOURCE.extension),Kind.SOURCE);v=s;}public CharSequence getCharContent(boolean ignored){return v;}}
 static final class Output extends SimpleJavaFileObject{final ByteArrayOutputStream v=new ByteArrayOutputStream();Output(String n,Kind k){super(URI.create("memory:///"+n.replace('.','/')+k.extension),k);}public OutputStream openOutputStream(){return v;}}
 public static void main(String[] args)throws Exception{
  byte[] original=Files.readAllBytes(Path.of(args[0]));String production=new String(original,java.nio.charset.StandardCharsets.UTF_8);
  int cs=production.indexOf("    ClipboardStore(File target)"),ce=production.indexOf("    static String allowedText(",cs);int ss=production.indexOf("    private void save(JSONArray next)"),se=production.indexOf("    private JSONArray copy()",ss);
  if(cs<0||ce<0||ss<0||se<0)throw new IllegalStateException("production methods not found");
  int helper=production.indexOf("    private static boolean regularFileExists",cs);if(helper<0||helper>ce)throw new IllegalStateException("helper not found");
  String constructor=production.substring(cs,helper).replace("ClipboardStore(File target)","ClipboardHarness(File target)");
  int readyStart=production.indexOf("    private synchronized void ensureReady()",helper),readyEnd=production.indexOf('\n',readyStart);if(readyStart<0||readyEnd<0)throw new IllegalStateException("guard missing");
  String ready=production.substring(readyStart,readyEnd);
  String save=production.substring(ss,se).replace("FileOutputStream out=null","Sink out=null").replace("FileOutputStream pending=new FileOutputStream(marker)","Sink pending=new Sink(marker)");
  int clearStart=production.indexOf("    synchronized void clear()throws IOException {");int clearEnd=production.indexOf('\n',clearStart);
  if(clearStart<0||clearEnd<0)throw new IllegalStateException("production clear method not found");
  String clear=production.substring(clearStart,clearEnd);
  int listStart=production.indexOf("    synchronized JSONArray list(long now,int days)"),listEnd=production.indexOf('\n',listStart);if(listStart<0||listEnd<0)throw new IllegalStateException("list method missing");String list=production.substring(listStart,listEnd);
  if(constructor.contains("openRead")||save.contains("failWrite")||!save.contains("file.finishWrite(out)")||!ready.contains(".pending")&& !constructor.contains(".pending"))throw new IllegalStateException("production assumptions changed");
  for(String name:List.of("synchronized boolean add(","synchronized void edit(","synchronized void pin(","synchronized void remove(")){int at=production.indexOf(name);if(at<0||!production.substring(at,Math.min(production.length(),at+200)).contains("ensureReady()"))throw new IllegalStateException("guard absent from "+name);}
  String template=Files.readString(Path.of(args[1]));String source=template.replace("/* CONSTRUCTOR */",constructor).replace("/* READY */",ready).replace("/* SAVE */",save).replace("/* CLEAR */",clear).replace("/* LIST */",list);
  JavaCompiler compiler=ToolProvider.getSystemJavaCompiler();if(compiler==null)throw new IllegalStateException("JDK required");Map<String,Output> classes=new HashMap<>();DiagnosticCollector<JavaFileObject> diagnostics=new DiagnosticCollector<>();
  try(StandardJavaFileManager std=compiler.getStandardFileManager(diagnostics,null,null)){
   JavaFileManager memory=new ForwardingJavaFileManager<StandardJavaFileManager>(std){public JavaFileObject getJavaFileForOutput(Location l,String n,JavaFileObject.Kind k,FileObject s){Output o=new Output(n,k);classes.put(n,o);return o;}public ClassLoader getClassLoader(Location l){return new ClassLoader(MemoryRunner.class.getClassLoader()){protected Class<?> findClass(String n)throws ClassNotFoundException{Output o=classes.get(n);if(o==null)throw new ClassNotFoundException(n);byte[] v=o.v.toByteArray();return defineClass(n,v,0,v.length);}};}};
   if(!compiler.getTask(null,memory,diagnostics,List.of("-proc:none"),null,List.of(new Source("ClipboardHarnessTest",source))).call()){for(var d:diagnostics.getDiagnostics())System.err.println(d);System.exit(2);}
   System.out.println("Actual ClipboardStore.java SHA-256 "+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(original)));
   try{var m=memory.getClassLoader(StandardLocation.CLASS_OUTPUT).loadClass("ClipboardHarnessTest").getDeclaredMethod("main",String[].class);m.setAccessible(true);m.invoke(null,(Object)Arrays.copyOfRange(args,2,args.length));}catch(java.lang.reflect.InvocationTargetException failure){failure.getCause().printStackTrace();System.exit(1);}
  }
 }
}
