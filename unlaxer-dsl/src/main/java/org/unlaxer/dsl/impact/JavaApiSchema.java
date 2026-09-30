package org.unlaxer.dsl.impact;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.Trees;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.lang.model.element.Modifier;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.unlaxer.dsl.bootstrap.UBNFAST.EnumAnnotation;
import org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl;
import org.unlaxer.dsl.bootstrap.UBNFSourceSnapshot;
import org.unlaxer.dsl.codegen.ASTGenerator;
import org.unlaxer.dsl.codegen.CodeGenerator.GeneratedSource;
import org.unlaxer.dsl.codegen.EvaluatorGenerator;

/** Extracts the Java AST and protected evaluator API from generated source, without compilation. */
public final class JavaApiSchema {
    private JavaApiSchema() {}

    public static ApiImpact.Snapshot build(UBNFSourceSnapshot snapshot) {
        if (snapshot.ast().grammars().size() != 1) {
            throw new IllegalArgumentException("expected one grammar");
        }
        GrammarDecl grammar = snapshot.ast().grammars().get(0);
        GeneratedSource astSource = new ASTGenerator().generate(grammar);
        GeneratedSource evaluatorSource = new EvaluatorGenerator(17).generate(grammar);
        Parsed ast = parse(astSource);
        Parsed evaluator = parse(evaluatorSource);
        ClassTree astRoot = ast.topLevel(astSource.className());
        ClassTree evaluatorRoot = evaluator.topLevel(evaluatorSource.className());

        List<ApiImpact.Node> nodes = new ArrayList<>();
        collectNodes(snapshot, grammar, ast, astRoot, "", astSource.className(), nodes);
        List<ApiImpact.Method> methods = collectMethods(snapshot, evaluator, evaluatorRoot);
        rejectDuplicateApiNames(nodes, methods);
        return new ApiImpact.Snapshot(grammar.name(), qualified(astSource), qualified(evaluatorSource),
            nodes, methods);
    }

    private static void rejectDuplicateApiNames(List<ApiImpact.Node> nodes, List<ApiImpact.Method> methods) {
        Set<String> nodeNames = new HashSet<>();
        for (ApiImpact.Node node : nodes) {
            if (!nodeNames.add(node.name())) throw new IllegalStateException("duplicate generated node: " + node.name());
            Set<String> variants = new HashSet<>();
            for (String variant : node.variants()) {
                if (!variants.add(variant)) {
                    throw new IllegalStateException("duplicate generated variant: " + node.name() + "." + variant);
                }
            }
            Set<String> fieldNames = new HashSet<>();
            for (ApiImpact.Field field : node.fields()) {
                if (!fieldNames.add(field.name())) {
                    throw new IllegalStateException("duplicate generated field: " + node.name() + "." + field.name());
                }
            }
        }
        Set<String> methodNames = new HashSet<>();
        for (ApiImpact.Method method : methods) {
            if (!methodNames.add(method.name())) {
                throw new IllegalStateException("duplicate generated method: " + method.name());
            }
        }
    }

    private static String qualified(GeneratedSource source) {
        return source.packageName() + "." + source.className();
    }

    private static void collectNodes(UBNFSourceSnapshot snapshot, GrammarDecl grammar, Parsed parsed,
            ClassTree owner, String parentName, String astClass, List<ApiImpact.Node> nodes) {
        for (Tree member : owner.getMembers()) {
            if (!(member instanceof ClassTree type) || !isApiNode(type)) continue;
            String name = parentName.isEmpty() ? type.getSimpleName().toString()
                : parentName + "." + type.getSimpleName();
            String kind = switch (type.getKind()) {
                case RECORD -> "record";
                case ENUM -> "enum";
                default -> "interface";
            };
            List<ApiImpact.Field> fields = kind.equals("record") ? recordFields(parsed, type) : List.of();
            List<String> parents = new ArrayList<>();
            if (type.getExtendsClause() != null) parents.add(type.getExtendsClause().toString());
            for (Tree implemented : type.getImplementsClause()) parents.add(implemented.toString());
            List<String> variants = new ArrayList<>();
            if (kind.equals("interface")) {
                for (Tree permitted : type.getPermitsClause()) {
                    variants.add(relativeType(permitted.toString(), astClass));
                }
            } else if (kind.equals("enum")) {
                for (Tree value : type.getMembers()) {
                    if (value instanceof VariableTree constant
                        && constant.getModifiers().getFlags().containsAll(List.of(
                            Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL))) {
                        variants.add(constant.getName().toString());
                    }
                }
            }
            List<ApiImpact.Origin> origins = kind.equals("enum")
                ? enumOrigins(snapshot, grammar, name) : ApiImpact.origins(snapshot, name);
            nodes.add(new ApiImpact.Node(name, kind, fields,
                parents.stream().sorted().toList(), variants.stream().sorted().toList(),
                origins, parsed.location(type)));
            collectNodes(snapshot, grammar, parsed, type, name, astClass, nodes);
        }
    }

    private static boolean isApiNode(ClassTree type) {
        return type.getKind() == Tree.Kind.RECORD || type.getKind() == Tree.Kind.ENUM
            || type.getKind() == Tree.Kind.INTERFACE;
    }

    private static String relativeType(String name, String astClass) {
        return name.startsWith(astClass + ".") ? name.substring(astClass.length() + 1) : name;
    }

    private static List<ApiImpact.Origin> enumOrigins(UBNFSourceSnapshot snapshot, GrammarDecl grammar,
            String enumName) {
        return grammar.rules().stream()
            .filter(rule -> rule.name().equals(enumName)
                && rule.annotations().stream().anyMatch(EnumAnnotation.class::isInstance))
            .map(rule -> {
                var span = snapshot.spanOf(rule).orElseThrow(() -> new IllegalStateException("missing rule origin"));
                return new ApiImpact.Origin(rule.name(), new ApiImpact.Span(span.start(), span.end()));
            }).sorted(Comparator.comparing(ApiImpact.Origin::rule)).toList();
    }

    private static List<ApiImpact.Field> recordFields(Parsed parsed, ClassTree type) {
        List<ApiImpact.Field> fields = new ArrayList<>();
        for (Tree member : type.getMembers()) {
            if (!(member instanceof VariableTree variable)) continue;
            String fieldType = variable.getType().toString();
            String outer = fieldType.replace(" ", "");
            String cardinality = outer.startsWith("Optional<") || outer.startsWith("java.util.Optional<")
                ? "optional" : outer.startsWith("List<") || outer.startsWith("java.util.List<")
                    ? "many" : "one";
            fields.add(new ApiImpact.Field(variable.getName().toString(), fieldType, cardinality,
                parsed.location(variable)));
        }
        return fields;
    }

    private static List<ApiImpact.Method> collectMethods(UBNFSourceSnapshot snapshot, Parsed parsed,
            ClassTree evaluator) {
        List<ApiImpact.Method> methods = new ArrayList<>();
        for (Tree member : evaluator.getMembers()) {
            if (!(member instanceof MethodTree method)
                || !method.getModifiers().getFlags().contains(Modifier.PROTECTED)) continue;
            String name = method.getName().toString();
            List<ApiImpact.Parameter> parameters = method.getParameters().stream()
                .map(parameter -> new ApiImpact.Parameter(parameter.getName().toString(),
                    parameter.getType().toString())).toList();
            String mapping = mappingForMethod(snapshot, name);
            methods.add(new ApiImpact.Method(name, method.getReturnType().toString(), parameters,
                method.getBody() == null, ApiImpact.origins(snapshot, mapping), parsed.location(method)));
        }
        return methods;
    }

    private static String mappingForMethod(UBNFSourceSnapshot snapshot, String methodName) {
        for (var rule : snapshot.ast().grammars().get(0).rules()) {
            Optional<org.unlaxer.dsl.bootstrap.UBNFAST.MappingAnnotation> mapping = rule.annotations().stream()
                .filter(org.unlaxer.dsl.bootstrap.UBNFAST.MappingAnnotation.class::isInstance)
                .map(org.unlaxer.dsl.bootstrap.UBNFAST.MappingAnnotation.class::cast).findFirst();
            if (mapping.isPresent() && methodName.equals("eval" + mapping.get().className().replace(".", ""))) {
                return mapping.get().className();
            }
        }
        // Helpers such as evalLeaf/applyBinary are shared obligations of the grammar.
        return null;
    }

    private static Parsed parse(GeneratedSource source) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("JDK compiler tree API unavailable");
        String path = source.packageName().replace('.', '/') + "/" + source.className() + ".java";
        JavaFileObject file = new SimpleJavaFileObject(URI.create("string:///" + path),
                JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return source.source();
            }
        };
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager manager = compiler.getStandardFileManager(
                diagnostics, null, StandardCharsets.UTF_8)) {
            JavacTask task = (JavacTask) compiler.getTask(null, manager, diagnostics,
                List.of("--release", "17", "-proc:none"), null, List.of(file));
            CompilationUnitTree unit = task.parse().iterator().next();
            if (diagnostics.getDiagnostics().stream().anyMatch(diagnostic ->
                    diagnostic.getKind() == javax.tools.Diagnostic.Kind.ERROR)) {
                throw new IllegalStateException("generated Java source is not parseable: " + path);
            }
            return new Parsed(path, source.source(), unit, Trees.instance(task).getSourcePositions());
        } catch (IOException error) {
            throw new IllegalStateException("generated Java source cannot be read: " + path, error);
        }
    }

    private record Parsed(String path, String source, CompilationUnitTree unit, SourcePositions positions) {
        ClassTree topLevel(String name) {
            return unit.getTypeDecls().stream().filter(ClassTree.class::isInstance).map(ClassTree.class::cast)
                .filter(type -> type.getSimpleName().contentEquals(name)).findFirst()
                .orElseThrow(() -> new IllegalStateException("generated type missing: " + name));
        }

        ApiImpact.Location location(Tree tree) {
            long start = positions.getStartPosition(unit, tree);
            long end = positions.getEndPosition(unit, tree);
            if (start < 0 || end < start || end > source.length()) {
                throw new IllegalStateException("generated declaration has no source position");
            }
            return ApiImpact.location(path, source, Math.toIntExact(start), Math.toIntExact(end));
        }
    }
}
