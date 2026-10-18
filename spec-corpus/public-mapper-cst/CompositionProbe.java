package guardcomposition;
import com.google.gson.*;
import java.util.*;
import org.unlaxer.*;
import org.unlaxer.context.ParseContext;
import org.unlaxer.parser.elementary.EmptyParser;
import org.unlaxer.parser.combinator.RecoveryDiagnostic;
public final class CompositionProbe {
    private static int flattenCalls;
    private static class CountingToken extends Token {
        CountingToken(Token...children) { super(TokenKind.consumed,StringSource.createRootSource(""),new EmptyParser(),TokenList.of(children)); }
        @Override public TokenList flattenDepth(ChildrenKind kind) {
            if (++flattenCalls > 32) throw new AssertionError("unbudgeted recovery DAG flatten calls="+flattenCalls);
            return super.flattenDepth(kind);
        }
    }
    private static Object call(Token token,String entry) {
        return switch(entry) {
            case "ordinary" -> CompositionMapper.mapParsedToken(token);
            case "source-map" -> CompositionMapper.mapParsedTokenWithSourceMap(token);
            case "subtree" -> CompositionMapper.mapSubtreeToken(token);
            case "subtree-source-map" -> CompositionMapper.mapSubtreeTokenWithSourceMap(token);
            case "preferred" -> CompositionMapper.mapParsedTree(token);
            case "subtree-preferred" -> CompositionMapper.mapSubtreeTree(token);
            default -> throw new AssertionError(entry);
        };
    }
    private static JsonElement value(Object value,Object mapped)throws Exception {
        if(value==null)return JsonNull.INSTANCE;
        if(value instanceof Optional<?> item)return item.isPresent()?value(item.orElseThrow(),mapped):JsonNull.INSTANCE;
        if(value instanceof List<?> list){var out=new JsonArray();for(var item:list)out.add(value(item,mapped));return out;}
        if(!value.getClass().isRecord())return new Gson().toJsonTree(value);
        var out=new JsonObject();String full=value.getClass().getName();
        out.addProperty("type",full.substring(full.indexOf("AST$")+4).replace("$","__"));
        var span=(int[])((Optional<?>)mapped.getClass().getMethod("sourceSpanOf",Object.class).invoke(mapped,value)).orElseThrow();out.add("span",new Gson().toJsonTree(span));
        var fields=new JsonObject();for(var field:value.getClass().getRecordComponents())fields.add(field.getName(),value(field.getAccessor().invoke(value),mapped));out.add("fields",fields);return out;
    }
    public static List<String> run(String input,String expectedJson,String kind)throws Exception {
        flattenCalls=0;
        var parser=CompositionParsers.getRootParser();Token root;
        try(var context=new ParseContext(StringSource.createRootSource(input))){
            if(!parser.parse(context).isSucceeded())throw new AssertionError("parse");
            root=context.getCurrent().getTokens().stream().filter(t->t.parser==parser).findFirst().orElseThrow();
        }
        if(kind.equals("recovery")){
            Token dag=new CountingToken();for(int i=0;i<6;i++)dag=new CountingToken(dag,dag);
            root.getOriginalChildren().add(dag);
        }
        var retained=CompositionMapper.mapParsedTokenWithSourceMap(root);var expected=JsonParser.parseString(expectedJson);
        if(!value(retained.ast(),retained).equals(expected))throw new AssertionError("retained value "+value(retained.ast(),retained));
        var rows=new ArrayList<String>();
        for(String entry:List.of("ordinary","source-map","subtree","subtree-source-map","preferred","subtree-preferred")){
            var original=root.parser;root.parser=null;
            try{call(root,entry);throw new AssertionError("bad metadata accepted");}
            catch(IllegalArgumentException error){if(!error.getMessage().startsWith("E-MAPPING-CST"))throw error;}
            finally{root.parser=original;}
            if(call(root,entry)==null)throw new AssertionError("valid public entry");
            var fresh=CompositionMapper.mapParsedTokenWithSourceMap(root);
            if(!value(fresh.ast(),fresh).equals(expected)||!value(retained.ast(),retained).equals(expected))throw new AssertionError("reuse/snapshot");
            rows.add(entry+"\tE-MAPPING-CST\t"+value(fresh.ast(),fresh));
        }
        if(kind.equals("recovery")){
            Token marker=new Token(TokenKind.consumed,StringSource.createRootSource(""),new RecoveryDiagnostic.Marker("recover"));root.getOriginalChildren().add(marker);
            for(String entry:List.of("ordinary","source-map","subtree","subtree-source-map","preferred","subtree-preferred")){
                try{call(root,entry);throw new AssertionError("recovery marker accepted");}
                catch(IllegalArgumentException error){if(!error.getMessage().equals("cannot map recovered syntax"))throw error;}
            }
            root.getOriginalChildren().remove(marker);
            var restored=CompositionMapper.mapParsedTokenWithSourceMap(root);
            if(!value(restored.ast(),restored).equals(expected))throw new AssertionError("recovery cleanup");
            if(flattenCalls!=0)throw new AssertionError("recovery DAG flattened "+flattenCalls);
        }
        return rows;
    }
}
