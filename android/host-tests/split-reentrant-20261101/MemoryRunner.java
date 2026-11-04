import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import javax.tools.*;

/** Source launcher + in-memory javac: no generated class files. */
public final class MemoryRunner {
    static final class Source extends SimpleJavaFileObject {
        final String text;
        Source(String name,Path path)throws IOException{super(URI.create("string:///"+name.replace('.','/')+Kind.SOURCE.extension),Kind.SOURCE);text=Files.readString(path);}
        public CharSequence getCharContent(boolean ignored){return text;}
    }
    static final class Bytes extends SimpleJavaFileObject {
        final ByteArrayOutputStream output=new ByteArrayOutputStream();
        Bytes(String name,Kind kind){super(URI.create("memory:///"+name.replace('.','/')+kind.extension),kind);}
        public OutputStream openOutputStream(){return output;}
    }
    public static void main(String[] args)throws Exception{
        JavaCompiler compiler=ToolProvider.getSystemJavaCompiler();if(compiler==null)throw new IllegalStateException("JDK compiler unavailable");
        DiagnosticCollector<JavaFileObject> diagnostics=new DiagnosticCollector<>();Map<String,Bytes> compiled=new HashMap<>();
        try(StandardJavaFileManager standard=compiler.getStandardFileManager(diagnostics,null,null)){
            JavaFileManager memory=new ForwardingJavaFileManager<StandardJavaFileManager>(standard){
                public JavaFileObject getJavaFileForOutput(Location loc,String name,JavaFileObject.Kind kind,FileObject sibling){Bytes item=new Bytes(name,kind);compiled.put(name,item);return item;}
                public ClassLoader getClassLoader(Location loc){return new ClassLoader(MemoryRunner.class.getClassLoader()){
                    protected Class<?> findClass(String name)throws ClassNotFoundException{Bytes b=compiled.get(name);if(b==null)throw new ClassNotFoundException(name);byte[] bytes=b.output.toByteArray();return defineClass(name,bytes,0,bytes.length);}
                };}
            };
            String testClass=args.length>3?args[3]:"SplitReentrantChecks";
            List<JavaFileObject> sources=List.of(new Source("org.wangshu.ime.SplitSelection",Path.of(args[0])),new Source("org.wangshu.ime."+testClass,Path.of(args[1])));
            if(!compiler.getTask(null,memory,diagnostics,List.of("-proc:none"),null,sources).call()){for(var d:diagnostics.getDiagnostics())System.err.println(d);System.exit(2);}
            try{memory.getClassLoader(StandardLocation.CLASS_OUTPUT).loadClass("org.wangshu.ime."+testClass).getMethod("main",String[].class).invoke(null,(Object)(args[2].equals("--no-args")?new String[0]:new String[]{args[2]}));}
            catch(java.lang.reflect.InvocationTargetException e){e.getCause().printStackTrace();System.exit(1);}
        }
    }
}
