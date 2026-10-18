package guardprobe;

import java.util.*;
import org.unlaxer.*;
import org.unlaxer.context.ParseContext;

public final class JavaProbe {
    private static Token parsed(String input) {
        var parser = MapperGuardParsers.getRootParser();
        try (var context = new ParseContext(StringSource.createRootSource(input))) {
            if (!parser.parse(context).isSucceeded()) throw new AssertionError("parse");
            return context.getCurrent().getTokens().stream().filter(t -> t.parser == parser).findFirst().orElseThrow();
        }
    }
    private static Token corrupted(String input, String id) {
        Token root = parsed(input);
        switch (id) {
            case "null-root": return null;
            case "metadata": root.parser = null; break;
            case "child": root.getOriginalChildren().add(null); break;
            case "cycle": root.getOriginalChildren().add(root); break;
            case "capture-edge": root.filteredChildren.add(parsed(input)); break;
            case "span": return new Token(root.tokenKind, StringSource.createSubSource("😀", root.source.root(), new CodePointOffset(Integer.MAX_VALUE)), root.parser);
            case "depth":
                for (int i = 0; i < 256; i++) root = new Token(root.tokenKind, root.source, root.parser, TokenList.of(root));
                break;
            default: throw new AssertionError(id);
        }
        return root;
    }
    private static void dag() {
        var parser = MapperDagParsers.getRootParser();
        Token root;
        try(var context = new ParseContext(StringSource.createRootSource("😀"))) {
            if(!parser.parse(context).isSucceeded()) throw new AssertionError("dag parse");
            root = context.getCurrent().getTokens().stream().filter(t -> t.parser == parser).findFirst().orElseThrow();
        }
        Token original = root;
        for(int i=0;i<__DAG_DEPTH__;i++) root = new Token(root.tokenKind, root.source, parser, TokenList.of(root,root));
        try { MapperDagMapper.mapParsedTree(root); throw new AssertionError("unbounded DAG snapshot"); }
        catch(IllegalArgumentException expected) { if(!expected.getMessage().contains("E-MAPPING-LIMIT"))throw expected; }
        if(!((MapperDagAST.Leaf)MapperDagMapper.mapParsedToken(original).ast()).text().equals("😀"))throw new AssertionError("dag cleanup");
    }
    public static List<String> run(String input, String[] ids) {
        var retained = MapperGuardMapper.mapParsedTokenWithSourceMap(parsed(input));
        var result = new ArrayList<String>();
        String[] entries = {"ordinary","source-map","subtree","subtree-source-map","preferred","subtree-preferred"};
        for (String id : ids) for (String entry : entries) {
            Token bad = corrupted(input, id);
            try {
                switch (entry) {
                    case "ordinary" -> MapperGuardMapper.mapParsedToken(bad);
                    case "source-map" -> MapperGuardMapper.mapParsedTokenWithSourceMap(bad);
                    case "subtree" -> MapperGuardMapper.mapSubtreeToken(bad);
                    case "subtree-source-map" -> MapperGuardMapper.mapSubtreeTokenWithSourceMap(bad);
                    case "preferred" -> MapperGuardMapper.mapParsedTree(bad);
                    case "subtree-preferred" -> MapperGuardMapper.mapSubtreeTree(bad);
                    default -> throw new AssertionError(entry);
                }
                throw new AssertionError("accepted " + id + "/" + entry);
            } catch (IllegalArgumentException expected) {
                String category = expected.getMessage().split(":",2)[0];
                result.add(id + "\t" + entry + "\t" + category);
            }
            var fresh = MapperGuardMapper.mapParsedTokenWithSourceMap(parsed(input));
            var root = (MapperGuardAST.Root) fresh.ast();
            var values = root.value().orElseThrow();
            if (values.size() != 2 || !values.get(0).text().equals("😀") || !values.get(1).text().equals("😀")) throw new AssertionError("values");
            if (!Arrays.equals(new int[]{__ROOT_START__,__ROOT_END__}, fresh.sourceSpanOf(root).orElseThrow())) throw new AssertionError("root span");
            for (int i=0;i<2;i++) if (!Arrays.equals(new int[][]{__LEAF_SPANS__}[i],fresh.sourceSpanOf(values.get(i)).orElseThrow())) throw new AssertionError("leaf span");
            if (!Arrays.equals(new int[]{__ROOT_START__,__ROOT_END__},retained.sourceSpanOf(retained.ast()).orElseThrow())) throw new AssertionError("retained span");
            if (MapperGuardMapper.mapParsedTree(parsed(input)).candidates().size() != 3) throw new AssertionError("candidates");
        }
        dag();
        return result;
    }
}
