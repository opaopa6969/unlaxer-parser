import java.util.*;
import org.unlaxer.*;
import org.unlaxer.context.ParseContext;
import org.unlaxer.tinyexpression.loader.*;
import org.unlaxer.tinyexpression.p4.ubnfc.generated.TinyExpressionP4Parser;
import org.unlaxer.tinyexpression.p4.ubnfc.generated.api.ParseOptions;
import org.unlaxer.source.*;
import org.unlaxer.source.DocumentSnapshot.Span;
import org.unlaxer.source.LanguageRegions.*;

/** Explicit native integration: production TinyExpression parsers, no evaluation or compilation. */
public final class TinyProductionBridge {
    public static final Language FORMULA = new Language("formulainfo", "tinyexpression", "f86ce8a5ab0ab7d23fdba2cd477187df8aa7607c", "FormulaInfo", "Document");
    public static final Language TINY = new Language("tinyexpression", "tinyexpression", "f86ce8a5ab0ab7d23fdba2cd477187df8aa7607c", "TinyExpressionP4", "Formula");
    public static final Language JAVA = new Language("java", "lang/java", "0.1.0", "Java21", "CompilationUnit");
    public record Binding(DocumentSnapshot host, List<Region> regions, Map<String,String> javaFiles, Set<String> openEnds) {
        public Binding { regions = List.copyOf(regions); javaFiles = Map.copyOf(javaFiles); openEnds = Set.copyOf(openEnds); }
        public Binding(DocumentSnapshot host, List<Region> regions, Map<String,String> javaFiles) { this(host,regions,javaFiles,Set.of()); }
        public LanguageRegions tree() { return new LanguageRegions(host, regions, openEnds); }
        public LanguageQueries queries(LanguageQueries.Project project, LanguageQueries.Provider javaProvider) {
            return new LanguageQueries(tree(), project, Map.of(JAVA, new LanguageQueries.Provider() {
                public Set<Operation> capabilities() { return javaProvider.capabilities(); }
                public LanguageQueries.Response query(LanguageQueries.Request request) { return javaProvider.query(javaRequest(request)); }
                public LanguageQueries.DiagnosticResponse diagnostics(LanguageQueries.Request request) { return javaProvider.diagnostics(javaRequest(request)); }
            }));
        }
        public LanguageQueries.Request javaRequest(LanguageQueries.Request request) {
            if (!regions.contains(request.region()) || !JAVA.equals(request.region().language())) throw new IllegalArgumentException("foreign region");
            var parameters = new HashMap<>(request.parameters());
            String file = javaFiles.get(request.region().id());
            if (file == null || parameters.containsKey("fileName") && !file.equals(parameters.get("fileName"))) throw new IllegalArgumentException("foreign Java filename");
            parameters.put("fileName", file);
            return new LanguageQueries.Request(request.region(), request.operation(), request.cursor(), request.project(), parameters);
        }
    }
    public static Binding parse(DocumentSnapshot host) { return parse(host, false); }
    /** Explicit editor recovery retains only CodeBlock prefixes certified by the production entry parser. */
    public static Binding parseEditor(DocumentSnapshot host) { return parse(host, true); }
    private static Binding parse(DocumentSnapshot host, boolean editor) {
        if (host.length() > 1_048_576) throw new IllegalArgumentException("production document limit");
        Parsed parsed;
        try (var context = new ParseContext(StringSource.createRootSource(host.text()))) {
            parsed = new FormulaInfoBlocksParser().parse(context);
        }
        if (!parsed.isSucceeded() || parsed.getConsumed() == null || !parsed.getConsumed().source.sourceAsString().equals(host.text()))
            throw new IllegalArgumentException("invalid FormulaInfo document");
        var regions = new ArrayList<Region>(); var files = new LinkedHashMap<String,String>(); var openEnds = new HashSet<String>();
        regions.add(region(host, "root", null, FORMULA, new Span(0,host.length()), new Span(0,host.length()), State.COMPLETE));
        int index = 0;
        for (var block : parsed.getRootToken().typed(FormulaInfoBlocksParser.class).getChildrenWithParserAsListTyped(FormulaInfoBlockParser.class)) {
            Token formula = null;
            for (Token token : FormulaInfoElementOrCommentParser.elements(block)) {
                if (!(token.parser instanceof FormulaInfoElementParser)) continue;
                var element = token.typed(FormulaInfoElementParser.class);
                FormulaInfoElementParser.KeyValue pair;
                try { pair = element.getParser().extract(element); }
                catch (FormulaInfoParseException failure) { throw new IllegalArgumentException("invalid FormulaInfo value", failure); }
                if (pair.getKey().equals("formula")) {
                    if (formula != null) throw new IllegalArgumentException("duplicate formula");
                    formula = element.getChild(TokenPredicators.hasTag(FormulaInfoParser.Kind.value.tag()));
                }
            }
            if (formula == null) continue;
            if (++index > 256) throw new IllegalArgumentException("production section limit");
            int start = formula.source.offsetFromRoot().value();
            String raw = formula.source.sourceAsString();
            int skip = raw.startsWith("\r\n") ? 2 : raw.startsWith("\n") || raw.startsWith("\r") ? 1 : 0;
            start += skip; raw = raw.substring(skip);
            Span body = new Span(start, start + raw.codePointCount(0,raw.length()));
            if (!host.slice(body).equals(raw)) throw new IllegalArgumentException("FormulaInfo source mismatch");
            String id = "formula/" + index;
            String input = maskComments(raw);
            var p4 = TinyExpressionP4Parser.parse(input, new ParseOptions(true,true,true,true,org.unlaxer.tinyexpression.p4.ubnfc.P4Scanners.ALL,512));
            regions.add(region(host,id,"root",TINY,body,body,p4.ok() ? State.COMPLETE : State.FAILED));
            if (!p4.ok()) {
                if (editor) {
                    int parentIndex = regions.size()-1;
                    recoverPrefix(host, id, start, input, regions, files, openEnds);
                    if (regions.size() > parentIndex+1) regions.set(parentIndex, region(host,id,"root",TINY,body,body,State.PARTIAL));
                }
                continue;
            }
            var tokens = p4.lexical().stream().filter(t -> t.token() != null && t.ruleId().equals("TinyExpressionP4::CodeBlock")).toList();
            if (tokens.size() % 3 != 0) throw new IllegalArgumentException("P4 lexical contract changed");
            for (int n=0;n<tokens.size();n+=3) {
                var open=tokens.get(n); var close=tokens.get(n+2);
                String header=input.substring(input.offsetByCodePoints(0,open.span().start()),input.offsetByCodePoints(0,open.span().end())).strip();
                if (!header.startsWith("```java:")) continue;
                String file=header.substring(Math.max(8,header.lastIndexOf('.')+1))+".java";
                String child=id+"/java/"+(n/3+1);
                Span full=new Span(start+open.span().start(),start+close.span().end());
                Span javaBody=new Span(start+open.span().end(),start+close.span().start());
                regions.add(region(host,child,id,JAVA,full,javaBody,State.COMPLETE)); files.put(child,file);
            }
        }
        if (index==0) throw new IllegalArgumentException("no formula section");
        var result = new Binding(host,regions,files,openEnds); result.tree(); return result;
    }
    private static void recoverPrefix(DocumentSnapshot host, String parent, int start, String input,
            List<Region> regions, Map<String,String> files, Set<String> openEnds) {
        int from = 0, number = 0;
        var options = new ParseOptions(false,false,true,true,org.unlaxer.tinyexpression.p4.ubnfc.P4Scanners.ALL,512);
        while (from < input.length()) {
            if (number >= 256) throw new IllegalArgumentException("production code block limit");
            String remaining = input.substring(from), recognized = remaining;
            var parsed = TinyExpressionP4Parser.parseEntry("TinyExpressionP4","CodeBlock",recognized,options);
            boolean partial = !parsed.ok();
            if (partial) {
                recognized = remaining + "\n```\n";
                parsed = TinyExpressionP4Parser.parseEntry("TinyExpressionP4","CodeBlock",recognized,options);
            }
            if (!parsed.ok()) break;
            var tokens = parsed.lexical().stream().filter(t -> t.token()!=null && t.ruleId().equals("TinyExpressionP4::CodeBlock")).toList();
            if (tokens.size()!=3) throw new IllegalArgumentException("P4 CodeBlock lexical contract changed");
            var open=tokens.get(0);var close=tokens.get(2);
            int size=remaining.codePointCount(0,remaining.length());
            if (partial && (close.span().start()<size || open.span().end()>size)) break;
            int consumed=partial ? size : close.span().end();
            if (consumed<=0 || consumed>size) break;
            String header=recognized.substring(recognized.offsetByCodePoints(0,open.span().start()),recognized.offsetByCodePoints(0,open.span().end())).strip();
            number++;
            if (header.startsWith("```java:")) {
                int offset=start+input.codePointCount(0,from);
                String child=parent+"/java/"+number;
                Span full=new Span(offset+open.span().start(),offset+consumed);
                Span body=new Span(offset+open.span().end(),offset+(partial?size:close.span().start()));
                regions.add(region(host,child,parent,JAVA,full,body,partial?State.PARTIAL:State.COMPLETE));
                files.put(child,header.substring(Math.max(8,header.lastIndexOf('.')+1))+".java");
                if (partial) openEnds.add(child);
            }
            if (partial) break;
            from += remaining.offsetByCodePoints(0,consumed);
        }
    }
    private static String maskComments(String raw) {
        var out = new StringBuilder();
        for (String line : raw.split("(?<=\n)",-1)) {
            if (line.stripLeading().startsWith("#")) line.codePoints().forEach(c -> out.appendCodePoint(c=='\r'||c=='\n'?c:' '));
            else out.append(line);
        }
        return out.toString();
    }
    private static Region region(DocumentSnapshot host,String id,String parent,Language language,Span full,Span body,State state) {
        var virtual=new DocumentSnapshot(host.uri()+"#production/"+id,host.version(),host.slice(body));
        var map=new SegmentSourceMap(virtual,List.of(new SegmentSourceMap.Segment(new Span(0,virtual.length()),SegmentSourceMap.Kind.COPY,new SegmentSourceMap.Location(host,body))));
        return new Region(id,parent,language,full,body,map,state);
    }
}
