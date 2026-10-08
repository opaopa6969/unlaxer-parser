package example.resolvers;

import com.google.gson.*;
import java.nio.file.Path;
import java.util.Map;
import org.unlaxer.StringSource;
import org.unlaxer.Token;
import org.unlaxer.context.ParseContext;
import org.unlaxer.context.ParseOptions;

public class Main {
    public static void main(String[] args) {
        if (args.length != 2) { System.err.println("usage: CONFIG.json INPUT"); System.exit(2); }
        try {
            var snapshot = Resolvers.load(Path.of(args[0]));
            var output = parse(args[1], snapshot);
            System.out.println(output);
            if (!output.get("accepted").getAsBoolean()) System.exit(1);
        } catch (Resolvers.Failure e) {
            System.out.println("{\"stage\":\"resolve\",\"code\":\"" + e.code + "\"}");
            System.exit(2);
        }
    }
    public static JsonObject parse(String source, Resolvers.Snapshot snapshot) {
        try (var context = ParseContext.withBindings(StringSource.createRootSource(source),
                Map.of(TownParser.BINDING, snapshot.words()), ParseOptions.DEFAULT)) {
            var parser = AddressParsers.getRootParser();
            var parsed = parser.parse(context);
            var output = new JsonObject();
            output.addProperty("stage", "parse"); output.addProperty("revision", snapshot.revision());
            output.addProperty("accepted", parsed.isSucceeded());
            output.addProperty("consumed", context.position()); output.addProperty("matched", context.matchedPosition());
            output.add("ast", JsonNull.INSTANCE); output.add("captures", new JsonArray());
            if (parsed.isSucceeded()) {
                Token root = parsed.getRootToken(false);
                for (var committed : context.getCurrent().getTokens()) if (committed.parser == parser) { root = committed; break; }
                var mapped = AddressMapper.mapParsedTokenWithSourceMap(root);
                var ast = (AddressAST.Address) mapped.ast();
                var value = new JsonObject(); value.addProperty("town", ast.town()); value.addProperty("house", ast.house());
                output.add("ast", value); captures(root, output.getAsJsonArray("captures"));
            }
            return output;
        }
    }
    private static void captures(Token token, JsonArray output) {
        if (token.parser.getClass().getSimpleName().equals("__CaptureSite")) {
            var capture = new JsonObject();
            capture.addProperty("text", token.source.sourceAsString());
            int start = token.source.offsetFromRoot().value();
            var span = new JsonArray(); span.add(start); span.add(start + token.source.codePointLength().value());
            capture.add("span", span); output.add(capture);
        }
        for (var child : token.getOriginalChildren()) captures(child, output);
    }
}
