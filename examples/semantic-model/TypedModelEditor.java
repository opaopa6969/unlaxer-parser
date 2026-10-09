package example.semantic;

import java.util.*;
import org.unlaxer.editor.EditorCst;
import org.unlaxer.dsl.semantic.EditorParseResult;
import org.unlaxer.dsl.semantic.SemanticModel;
import org.unlaxer.dsl.semantic.SemanticModel.*;

/** Model-specific adapter: synthetic declarations and captures never become healthy symbols. */
public final class TypedModelEditor {
    public record Parsed(EditorParseResult<TypedModelAST> result, EditorCst cst) {}
    public static Parsed parse(String uri, long version, String source, String region, EditorCst.Options options) {
        return parseWithCompletions(uri, version, source, region, options, List.of("?", ")", ";", "}", "a", ":", "{"));
    }
    public static Parsed parseWithCompletions(String uri, long version, String source, String region, EditorCst.Options options, List<String> completions) {
        // These are explicit fragments from model.ubnf, not values presented to the user.
        EditorCst cst = TypedModelMapper.parseEditorCst(source, completions, options);
        List<Type> types = new ArrayList<>(); List<Symbol> symbols = new ArrayList<>();
        List<Signature> signatures = new ArrayList<>(); List<Call> calls = new ArrayList<>();
        List<EditorParseResult.CallSite> sites = new ArrayList<>();
        for (EditorCst.Node node : cst.nodes()) {
            if (!healthy(node, cst)) continue;
            Span span = span(node.span());
            if (node.rule().equals("TypeDecl")) {
                List<Field> fields = cst.nodes().stream().filter(child -> child.rule().equals("Field") && contained(child, node) && healthy(child, cst))
                    .map(child -> new Field(value(child, "name"), value(child, "type"), span(child.span()))).toList();
                types.add(new Type(value(node, "name"), TypeKind.valueOf(value(node, "kind").toUpperCase(Locale.ROOT)), values(node, "parents"), fields, span));
            } else if (node.rule().equals("ValueDecl")) {
                symbols.add(new Symbol("value:" + span.start(), value(node, "name"), value(node, "type"), "root", span, span.end()));
            } else if (node.rule().equals("FunctionDecl")) {
                signatures.add(new Signature("fn:" + span.start(), value(node, "name"), values(node, "parameters"), value(node, "result"), span));
            }
        }
        for (EditorCst.Node node : cst.nodes()) {
            if (!node.rule().equals("Call") || damaged(node.span(), cst)) continue;
            List<EditorCst.Capture> names = captures(node, "name");
            if (names.size() != 1 || names.get(0).synthetic()) continue;
            List<Argument> arguments = new ArrayList<>();
            for (EditorCst.Capture capture : captures(node, "arguments")) {
                String name = capture.text().trim();
                String type = capture.synthetic() ? SemanticModel.UNKNOWN : symbols.stream()
                    .filter(symbol -> symbol.name().equals(name) && symbol.visibleFrom() <= capture.span().start())
                    .map(Symbol::type).findFirst().orElse(SemanticModel.UNKNOWN);
                arguments.add(new Argument(type, span(capture.span())));
            }
            Span span = span(node.span()); String id = "call:" + span.start();
            calls.add(new Call(id, signatures.stream().filter(signature -> signature.name().equals(names.get(0).text().trim()))
                .map(Signature::id).toList(), arguments, span));
            for (int index = 0; index < arguments.size(); index++) sites.add(new EditorParseResult.CallSite(id, index, region));
        }
        SemanticModel model = new SemanticModel(uri, version, source, types,
            List.of(new Scope("root", null, new Span(0, source.codePointCount(0, source.length())))), symbols, signatures, calls);
        List<EditorParseResult.Node> defects = cst.defects().stream().map(defect -> new EditorParseResult.Node(
            EditorParseResult.NodeKind.valueOf(defect.kind().name()), span(defect.span()), defect.candidateRules(), region)).toList();
        TypedModelAST ast = cst.status() == EditorCst.Status.COMPLETE ? TypedModelMapper.parse(source) : null;
        return new Parsed(new EditorParseResult<>(uri, version, source, EditorParseResult.Status.valueOf(cst.status().name()), ast, defects, model, sites), cst);
    }
    private static boolean contained(EditorCst.Node child, EditorCst.Node parent) { return parent.span().start() <= child.span().start() && child.span().end() <= parent.span().end(); }
    private static boolean damaged(EditorCst.Span span, EditorCst cst) {
        return cst.defects().stream().anyMatch(defect -> defect.kind() == EditorCst.DefectKind.ERROR && span.start() < defect.span().end() && defect.span().start() < span.end());
    }
    private static boolean healthy(EditorCst.Node node, EditorCst cst) { return !node.synthetic() && !damaged(node.span(), cst) && node.captures().stream().noneMatch(EditorCst.Capture::synthetic); }
    private static Span span(EditorCst.Span span) { return new Span(span.start(), span.end()); }
    private static List<EditorCst.Capture> captures(EditorCst.Node node, String name) { return node.captures().stream().filter(capture -> capture.name().equals(name)).toList(); }
    private static List<String> values(EditorCst.Node node, String name) { return captures(node, name).stream().filter(capture -> !capture.synthetic()).map(capture -> capture.text().trim()).toList(); }
    private static String value(EditorCst.Node node, String name) { List<String> found = values(node, name); if(found.isEmpty())throw new IllegalArgumentException(name+": "+node); return found.get(0); }
}
