import java.io.*;import java.net.URI;import java.nio.file.*;import java.security.*;import java.util.*;import javax.tools.*;
/** Source-file launcher + in-memory compiler; extracted production control flow. */
public final class RawFileRunner {
 static final class Source extends SimpleJavaFileObject{final String v;Source(String n,String s){super(URI.create("string:///"+n+Kind.SOURCE.extension),Kind.SOURCE);v=s;}public CharSequence getCharContent(boolean ignored){return v;}}
 static final class Output extends SimpleJavaFileObject{final ByteArrayOutputStream v=new ByteArrayOutputStream();Output(String n,Kind k){super(URI.create("memory:///"+n.replace('.','/')+k.extension),k);}public OutputStream openOutputStream(){return v;}}
 public static void main(String[] args)throws Exception{
  byte[] original=Files.readAllBytes(Path.of(args[0]));String production=new String(original,java.nio.charset.StandardCharsets.UTF_8);
  int a=production.indexOf("    private static boolean regularFileExists"), b=production.indexOf('\n',a);int c=production.indexOf("    private byte[] readBase()",b),dEnd=production.indexOf('\n',c);
  if(a<0||b<0||c<0||dEnd<0)throw new IllegalStateException("raw helpers not found");
  String template=Files.readString(Path.of(args[1]));String source=template.replace("/* STAT */",production.substring(a,b)).replace("/* READ */",production.substring(c,dEnd));
  JavaCompiler compiler=ToolProvider.getSystemJavaCompiler();if(compiler==null)throw new IllegalStateException("JDK required");Map<String,Output> classes=new HashMap<>();DiagnosticCollector<JavaFileObject> diagnostics=new DiagnosticCollector<>();
  try(StandardJavaFileManager std=compiler.getStandardFileManager(diagnostics,null,null)){
   JavaFileManager memory=new ForwardingJavaFileManager<StandardJavaFileManager>(std){public JavaFileObject getJavaFileForOutput(Location l,String n,JavaFileObject.Kind k,FileObject s){Output o=new Output(n,k);classes.put(n,o);return o;}public ClassLoader getClassLoader(Location l){return new ClassLoader(RawFileRunner.class.getClassLoader()){protected Class<?> findClass(String n)throws ClassNotFoundException{Output o=classes.get(n);if(o==null)throw new ClassNotFoundException(n);byte[] v=o.v.toByteArray();return defineClass(n,v,0,v.length);}};}};
   if(!compiler.getTask(null,memory,diagnostics,List.of("-proc:none"),null,List.of(new Source("RawFileChecks",source))).call()){for(var d:diagnostics.getDiagnostics())System.err.println(d);System.exit(2);}
   System.out.println("Actual ClipboardStore.java SHA-256 "+HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(original)));
   try{var m=memory.getClassLoader(StandardLocation.CLASS_OUTPUT).loadClass("RawFileChecks").getDeclaredMethod("main",String[].class);m.setAccessible(true);m.invoke(null,(Object)Arrays.copyOfRange(args,2,args.length));}catch(java.lang.reflect.InvocationTargetException failure){failure.getCause().printStackTrace();System.exit(1);}
  }
 }
}
