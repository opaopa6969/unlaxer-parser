import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import org.unlaxer.*;
import org.unlaxer.context.*;
import org.unlaxer.dsl.runtime.ScopeStore;
import org.unlaxer.tinyexpression.generated.p4.*;

/** Direct Classic generated parser/map entry; exceptions are never successful samples. */
public final class JavaConsumer {
    private static final Gson JSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();

    private static JsonElement canonical(Object value,
            TinyExpressionP4Mapper.SourceMappedAst<?> mapped) throws Exception {
        if (value == null) return JsonNull.INSTANCE;
        if (value instanceof Optional<?> optional) return canonical(optional.orElse(null), mapped);
        if (value instanceof List<?> values) {
            var result = new JsonArray();
            for (Object child : values) result.add(canonical(child, mapped));
            return result;
        }
        if (value instanceof String text) return new JsonPrimitive(text);
        if (value instanceof Number number) return new JsonPrimitive(number);
        if (value instanceof Boolean bool) return new JsonPrimitive(bool);
        if (!(value instanceof TinyExpressionP4AST) || !value.getClass().isRecord())
            throw new IllegalArgumentException("unhandled generated AST value " + value.getClass());
        var result = new JsonObject();
        result.addProperty("type", value.getClass().getSimpleName());
        result.add("span", JSON.toJsonTree(mapped.sourceSpanOf(value).orElse(null)));
        var fields = new JsonObject();
        for (var component : value.getClass().getRecordComponents())
            fields.add(component.getName(), canonical(component.getAccessor().invoke(value), mapped));
        result.add("fields", fields);
        return result;
    }

    private static JsonObject operation(String source, Memoization memo,
            ParseOptions.Diagnostics diagnostics, String entry) throws Exception {
        long start = System.nanoTime();
        try (var context = ParseContext.withOptions(StringSource.createRootSource(source),
                ParseOptions.withMemoization(memo).withDiagnostics(diagnostics))) {
            var parser = entry.equals("Formula") ? TinyExpressionP4Parsers.getRootParser()
                    : org.unlaxer.parser.Parser.get(Class.forName(TinyExpressionP4Parsers.class.getName()
                            + "$" + entry + "Parser").asSubclass(org.unlaxer.parser.Parser.class));
            var parsed = parser.parse(context);
            int consumed = context.position(), matched = context.getMatchedPosition().value();
            boolean accepted = parsed.isSucceeded() && consumed == source.codePointCount(0, source.length());
            TinyExpressionP4Mapper.SourceMappedAst<?> mapped = null;
            if (accepted) {
                Token root = context.getCurrent().getTokens().stream()
                        .filter(token -> token.parser == parser).findFirst().orElseThrow(
                                () -> new IllegalStateException("missing committed generated root"));
                mapped = entry.equals("Formula") ? TinyExpressionP4Mapper.mapParsedTokenWithSourceMap(root)
                        : TinyExpressionP4Mapper.mapSubtreeTokenWithSourceMap(root);
                if (mapped.ast() == null) throw new IllegalStateException("null mapped AST");
            }
            long elapsed = System.nanoTime() - start;
            var result = new JsonObject();
            result.addProperty("entry", entry);
            result.addProperty("accepted", accepted);
            result.addProperty("memo_effective", context.getOptions().memoization().name());
            result.addProperty("memo_session_safe", context.isMemoizationSessionSafe());
            result.addProperty("diagnostics_effective", context.getOptions().diagnostics().name());
            result.addProperty("parser_succeeded", parsed.isSucceeded());
            result.addProperty("consumed_cp", consumed);
            result.addProperty("matched_cp", matched);
            result.addProperty("parse_map_ns", elapsed);
            result.add("ast", mapped == null ? JsonNull.INSTANCE : canonical(mapped.ast(), mapped));
            var failure = context.getParseFailureDiagnostics();
            var syntax = new JsonObject();
            syntax.addProperty("offset", failure.getFarthestOffset());
            syntax.add("expected", JSON.toJsonTree(failure.getExpectedTokens()));
            result.add("syntax", syntax);
            var semantic = new JsonArray();
            for (var diagnostic : ScopeStore.getDiagnostics(context)) {
                var row = new JsonObject();
                row.addProperty("message", diagnostic.message());
                row.addProperty("offset", diagnostic.offset());
                row.addProperty("length", diagnostic.length());
                row.addProperty("severity", diagnostic.severity().name().toLowerCase(Locale.ROOT));
                semantic.add(row);
            }
            result.add("semantic", semantic);
            var table = context.getPackratMemoTable();
            result.addProperty("failure_hits", table == null ? 0 : table.failureHits());
            result.addProperty("success_hits", table == null ? 0 : table.successHits());
            return result;
        }
    }

    public static void main(String[] args) {
        try {
            String source = Files.readString(Path.of(args[0]));
            Memoization memo = Memoization.valueOf(args[1]);
            var diagnostics = ParseOptions.Diagnostics.valueOf(args[2]);
            int warmups = Integer.parseInt(args[3]), iterations = Integer.parseInt(args[4]);
            for (int index = 0; index < 1 + warmups + iterations; index++) {
                var result = operation(source, memo, diagnostics, args[5]);
                result.addProperty("phase", index == 0 ? "cold" : index <= warmups ? "warmup" : "warm");
                result.addProperty("iteration", index);
                System.out.println(JSON.toJson(result));
            }
        } catch (Throwable failure) {
            var result = new JsonObject();
            result.addProperty("error", failure.getClass().getName());
            result.addProperty("message", String.valueOf(failure.getMessage()));
            System.out.println(JSON.toJson(result));
            failure.printStackTrace(System.err);
            System.exit(2);
        }
    }
}
