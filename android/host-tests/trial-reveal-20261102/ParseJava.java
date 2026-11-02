// Source syntax only; Android types are deliberately not resolved.
import java.util.*;
import java.nio.charset.StandardCharsets;
import javax.tools.*;
import com.sun.source.util.JavacTask;
class ParseJava {
    public static void main(String[] files)throws Exception {
        JavaCompiler compiler=ToolProvider.getSystemJavaCompiler();if(compiler==null)throw new AssertionError("JDK compiler unavailable");
        DiagnosticCollector<JavaFileObject> diagnostics=new DiagnosticCollector<>();
        try(StandardJavaFileManager manager=compiler.getStandardFileManager(diagnostics,null,StandardCharsets.UTF_8)){
            JavacTask task=(JavacTask)compiler.getTask(null,manager,diagnostics,List.of("-proc:none","-source","17"),null,manager.getJavaFileObjects(files));
            int count=0;for(var parsed:task.parse())count++;
            for(var diagnostic:diagnostics.getDiagnostics())if(diagnostic.getKind()==Diagnostic.Kind.ERROR)throw new AssertionError(diagnostic.toString());
            System.out.println("PASS syntax parse "+count+" Java files; Android type checking NOT RUN");
        }
    }
}
