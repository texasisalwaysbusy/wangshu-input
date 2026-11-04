import java.util.*;
import javax.tools.*;
import com.sun.source.util.JavacTask;

/** Java parser only. No Android API resolution, resources, dex, package or device. */
public final class ParseSource {
    public static void main(String[] args) throws Exception {
        JavaCompiler compiler=ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics=new DiagnosticCollector<>();
        try(StandardJavaFileManager files=compiler.getStandardFileManager(diagnostics,null,null)) {
            JavacTask task=(JavacTask)compiler.getTask(null,files,diagnostics,
                Arrays.asList("-proc:none","-source","17"),null,files.getJavaFileObjects(args));
            int count=0;for(Object tree:task.parse())count++;
            for(Diagnostic<?> d:diagnostics.getDiagnostics())
                if(d.getKind()==Diagnostic.Kind.ERROR)throw new AssertionError(d.toString());
            System.out.println("PASS "+count+" Java sources parsed; syntax only, no Android build.");
        }
    }
}
