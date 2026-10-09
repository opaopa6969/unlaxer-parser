package org.unlaxer.dsl.provider;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.io.StringWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import javax.lang.model.element.Element;
import javax.tools.*;
import org.unlaxer.source.*;
import org.unlaxer.source.DocumentSnapshot.Span;
import org.unlaxer.source.LanguageRegions.Operation;
import org.unlaxer.source.ProviderProtocol.*;
import org.unlaxer.source.SegmentSourceMap.Location;

/** Analysis-only javac adapter. It never generates classes or invokes annotation processors. */
public final class JavacProvider {
    public static final String VERSION = "21.0.9";
    private static final Set<String> CAPABILITIES = Set.of("PARSE", "TYPE_CHECK", "HOVER", "DEFINITION", "COMPLETION");
    private JavacProvider() {}
    private static Response empty(Status status) { return new Response(status, CAPABILITIES, List.of(), List.of()); }
    public static void main(String[] args) throws Exception {
        byte[] bytes = System.in.readNBytes(512 * 1024 + 5);
        String wire = new String(bytes, StandardCharsets.UTF_8);
        Request request = ProviderProtocol.readRequest(wire);
        long timeout = Long.parseLong(request.parameters().getOrDefault("timeoutMs", "10000"));
        if (timeout < 1 || timeout > 60000) { throw new IllegalArgumentException("invalid timeout"); }
        ExecutorService executor = Executors.newSingleThreadExecutor(task -> { Thread thread = new Thread(task); thread.setDaemon(true); return thread; });
        Response response;
        try {
            Future<Response> future = executor.submit(() -> analyze(request));
            try { response = future.get(timeout, TimeUnit.MILLISECONDS); }
            catch (TimeoutException error) { future.cancel(true); response = empty(Status.TIMEOUT); }
            catch (ExecutionException error) { response = empty(Status.FAILED); }
        } finally { executor.shutdownNow(); }
        System.out.print(ProviderProtocol.reply(request, wire, response));
    }
    private static final class SourceFile extends SimpleJavaFileObject {
        final DocumentSnapshot snapshot;
        SourceFile(DocumentSnapshot snapshot, String name) { super(URI.create("mem:///" + name), Kind.SOURCE); this.snapshot = snapshot; }
        @Override public CharSequence getCharContent(boolean ignored) { return snapshot.text(); }
    }
    public static Response analyze(Request request) throws Exception {
        if (!request.provider().id().equals("javac") || !request.provider().version().equals(VERSION)
                || !Runtime.version().toString().startsWith(VERSION) || ToolProvider.getSystemJavaCompiler() == null) return empty(Status.UNAVAILABLE);
        if (!request.language().id().equals("java") || !request.language().entry().equals("CompilationUnit") || request.executeUserCode() || !Set.of(Operation.PARSE, Operation.VALIDATE, Operation.COMPLETION, Operation.HOVER, Operation.DEFINITION).contains(request.operation())) return empty(Status.UNSUPPORTED);
        if (!Set.of("sourceLevel", "classpath").containsAll(request.project().configuration().keySet())
                || !Set.of("fileName", "prefix", "timeoutMs").containsAll(request.parameters().keySet())) return empty(Status.UNSUPPORTED);
        String release = request.project().configuration().getOrDefault("sourceLevel", "21");
        if (!Set.of("8", "11", "17", "21").contains(release)) return empty(Status.UNSUPPORTED);
        Map<String, SourceFile> files = new LinkedHashMap<>();
        SourceFile primary = source(request.snapshot(), request.parameters().getOrDefault("fileName", "Main.java"));
        files.put(primary.getName(), primary);
        for (DocumentSnapshot snapshot : request.project().documents().values().stream().sorted(Comparator.comparing(DocumentSnapshot::uri)).toList()) {
            String sourcePath = URI.create(snapshot.uri()).getPath();
            if (snapshot.equals(request.snapshot()) || sourcePath == null || !sourcePath.endsWith(".java")) continue;
            SourceFile file = source(snapshot, "Dependency.java");
            if (files.putIfAbsent(file.getName(), file) != null) throw new IllegalArgumentException("duplicate Java file name");
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> collector = new DiagnosticCollector<>();
        try (StandardJavaFileManager manager = compiler.getStandardFileManager(collector, Locale.ROOT, StandardCharsets.UTF_8)) {
            String classpath = request.project().configuration().getOrDefault("classpath", "");
            manager.setLocationFromPaths(StandardLocation.CLASS_PATH, classpath.isEmpty() ? List.of() : Arrays.stream(classpath.split(java.io.File.pathSeparator)).map(Path::of).toList());
            manager.setLocationFromPaths(StandardLocation.SOURCE_PATH, List.of());
            JavacTask task = (JavacTask) compiler.getTask(new StringWriter(), manager, collector, List.of("-proc:none", "--release", release), null, files.values());
            List<CompilationUnitTree> units = new ArrayList<>(); task.parse().forEach(units::add);
            if (request.operation() != Operation.PARSE) task.analyze();
            List<ProviderProtocol.Diagnostic> diagnostics = new ArrayList<>();
            for (javax.tools.Diagnostic<? extends JavaFileObject> diagnostic : collector.getDiagnostics()) {
                SourceFile diagnosticSource = diagnostic.getSource() == null ? null : files.get(diagnostic.getSource().getName());
                DocumentSnapshot snapshot = diagnosticSource == null ? request.snapshot() : diagnosticSource.snapshot;
                long start = diagnostic.getStartPosition(), end = diagnostic.getEndPosition();
                if (start < 0 || end < start) { start = 0; end = 0; }
                Location location = new Location(snapshot, new Span(snapshot.fromUtf16(Math.toIntExact(start)), snapshot.fromUtf16(Math.toIntExact(end))));
                diagnostics.add(new ProviderProtocol.Diagnostic(diagnostic.getCode(), diagnostic.getMessage(Locale.ROOT), diagnostic.getKind().name(), List.of(location)));
            }
            List<LanguageQueries.Item> items = new ArrayList<>();
            if (Set.of(Operation.HOVER, Operation.DEFINITION, Operation.COMPLETION).contains(request.operation())) {
                Trees trees = Trees.instance(task);
                for (CompilationUnitTree unit : units) {
                    if (!unit.getSourceFile().toUri().equals(primary.toUri())) continue;
                    TreePath path = pathAt(unit, trees, request.snapshot().utf16(request.cursor()));
                    if (path == null) continue;
                    if (request.operation() == Operation.COMPLETION) complete(request, path, trees, files, items);
                    else {
                        Element element = trees.getElement(path);
                        if (element != null) {
                            List<Location> locations = definition(trees, element, files);
                            if (request.operation() == Operation.HOVER || !locations.isEmpty()) items.add(new LanguageQueries.Item(element.getSimpleName().toString(), element.asType().toString(), locations, List.of()));
                        }
                    }
                }
            }
            return new Response(diagnostics.isEmpty() ? Status.OK : Status.DIAGNOSTICS, CAPABILITIES, diagnostics, items);
        }
    }
    private static SourceFile source(DocumentSnapshot snapshot, String fallback) {
        String path = URI.create(snapshot.uri()).getPath();
        String name = path != null && path.endsWith(".java") ? path.substring(path.lastIndexOf('/') + 1) : fallback;
        if (!name.matches("[A-Za-z_$][A-Za-z0-9_$]*\\.java")) throw new IllegalArgumentException("invalid Java source file name");
        return new SourceFile(snapshot, name);
    }
    private static TreePath pathAt(CompilationUnitTree unit, Trees trees, int cursor) {
        TreePath[] selected = new TreePath[1];
        new TreePathScanner<Void, Void>() {
            @Override public Void scan(Tree tree, Void ignored) {
                if (tree == null) return null;
                long start = trees.getSourcePositions().getStartPosition(unit, tree), end = trees.getSourcePositions().getEndPosition(unit, tree);
                if (start < 0 || cursor < start || cursor > end) return null;
                selected[0] = getCurrentPath() == null ? new TreePath(unit) : new TreePath(getCurrentPath(), tree);
                return super.scan(tree, ignored);
            }
        }.scan(unit, null);
        return selected[0];
    }
    private static List<Location> definition(Trees trees, Element element, Map<String, SourceFile> files) {
        TreePath definition = trees.getPath(element);
        if (definition == null) return List.of();
        SourceFile file = files.get(definition.getCompilationUnit().getSourceFile().getName());
        if (file == null) return List.of();
        long start = trees.getSourcePositions().getStartPosition(definition.getCompilationUnit(), definition.getLeaf());
        long end = trees.getSourcePositions().getEndPosition(definition.getCompilationUnit(), definition.getLeaf());
        if (start < 0 || end < start) return List.of();
        return List.of(new Location(file.snapshot, new Span(file.snapshot.fromUtf16(Math.toIntExact(start)), file.snapshot.fromUtf16(Math.toIntExact(end)))));
    }
    private static void complete(Request request, TreePath path, Trees trees, Map<String, SourceFile> files, List<LanguageQueries.Item> items) {
        String prefix = request.parameters().getOrDefault("prefix", "");
        int start = request.cursor() - prefix.codePointCount(0, prefix.length());
        if (start < 0 || !request.snapshot().slice(new Span(start, request.cursor())).equals(prefix)) return;
        var scope = trees.getScope(path);
        Map<String, Element> candidates = new TreeMap<>();
        while (scope != null) {
            for (Element element : scope.getLocalElements()) candidates.putIfAbsent(element.getSimpleName().toString(), element);
            if (scope.getEnclosingClass() != null) for (Element element : scope.getEnclosingClass().getEnclosedElements()) candidates.putIfAbsent(element.getSimpleName().toString(), element);
            scope = scope.getEnclosingScope();
        }
        for (var candidate : candidates.entrySet()) {
            if (items.size() >= 100) break;
            String name = candidate.getKey();
            if (!name.startsWith(prefix) || name.isEmpty() || !Character.isJavaIdentifierStart(name.codePointAt(0))) continue;
            items.add(new LanguageQueries.Item(name, candidate.getValue().asType().toString(), definition(trees, candidate.getValue(), files),
                List.of(new LanguageQueries.TextEdit(new Location(request.snapshot(), new Span(start, request.cursor())), name))));
        }
    }
}
