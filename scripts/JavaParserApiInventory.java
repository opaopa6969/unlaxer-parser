import com.sun.source.tree.*;
import com.sun.source.util.JavacTask;
import com.sun.source.util.Trees;
import java.nio.file.Path;
import java.util.*;
import javax.lang.model.element.Modifier;
import javax.tools.*;

/** javac の構文木だけで公開宣言を列挙する。依存解決・annotation processor は実行しない。 */
class JavaParserApiInventory {
    public static void main(String[] args) throws Exception {
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("JDK 21 以上が必要です");
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (var files = compiler.getStandardFileManager(diagnostics, Locale.ROOT, java.nio.charset.StandardCharsets.UTF_8)) {
            var task = (JavacTask) compiler.getTask(null, files, diagnostics,
                List.of("-proc:none", "--release", "21"), null,
                files.getJavaFileObjectsFromStrings(Arrays.asList(args)));
            var trees = Trees.instance(task);
            for (var unit : task.parse()) {
                String pkg = unit.getPackageName().toString();
                for (var tree : unit.getTypeDecls()) {
                    if (tree instanceof ClassTree type) emit(type, pkg, false, unit, trees);
                }
            }
            for (var diagnostic : diagnostics.getDiagnostics()) {
                if (diagnostic.getKind() == Diagnostic.Kind.ERROR)
                    throw new IllegalArgumentException(diagnostic.toString());
            }
        }
    }

    static void emit(ClassTree type, String owner, boolean implicitPublic,
                     CompilationUnitTree unit, Trees trees) {
        var modifiers = type.getModifiers().getFlags();
        if (modifiers.contains(Modifier.PRIVATE) || modifiers.contains(Modifier.PROTECTED)
                || (!modifiers.contains(Modifier.PUBLIC) && !implicitPublic)) return;
        String name = owner + "." + type.getSimpleName();
        row(name, "type", type.getKind().name(), type, unit, trees);
        boolean iface = type.getKind() == Tree.Kind.INTERFACE || type.getKind() == Tree.Kind.ANNOTATION_TYPE;
        for (var member : type.getMembers()) {
            if (member instanceof ClassTree nested) emit(nested, name, iface, unit, trees);
            if (member instanceof MethodTree method) {
                var flags = method.getModifiers().getFlags();
                // factory の命名規約で取りこぼさないよう公開 static method を全て含める。
                if (!flags.contains(Modifier.STATIC) || flags.contains(Modifier.PRIVATE)
                        || flags.contains(Modifier.PROTECTED)
                        || (!flags.contains(Modifier.PUBLIC) && !iface)) continue;
                String params = String.join(",", method.getParameters().stream()
                    .map(p -> p.getType().toString().replaceAll("\\s+", " ")).toList());
                String id = name + "#" + method.getName() + "(" + params + ")";
                String signature = method.getTypeParameters() + " " + method.getReturnType() + " "
                    + method.getName() + "(" + params + ") throws " + method.getThrows();
                row(id, "static", signature, method, unit, trees);
            }
        }
    }

    static void row(String id, String kind, String signature, Tree tree,
                    CompilationUnitTree unit, Trees trees) {
        long offset = trees.getSourcePositions().getStartPosition(unit, tree);
        System.out.println(String.join("\t", id, kind, signature.replaceAll("\\s+", " "),
            Path.of(unit.getSourceFile().toUri()).toString(),
            Long.toString(unit.getLineMap().getLineNumber(offset))));
    }
}
