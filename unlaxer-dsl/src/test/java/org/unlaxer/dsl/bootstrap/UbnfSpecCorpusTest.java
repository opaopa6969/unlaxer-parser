package org.unlaxer.dsl.bootstrap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.Test;
import org.unlaxer.dsl.codegen.GrammarValidator;

/**
 * 仕様由来オラクル（`src/test/resources/spec-corpus/ubnf/*.json`）を Java bootstrap へ流す恒久テスト。
 *
 * <p>期待値は {@code unlaxer-dsl/specs/{ubnf-syntax,annotations,validation}.md} の規範文だけから
 * 導いたもので、どの実装の観測値も使っていない。原本は sibling repository {@code ubnfc} にあり、
 * 出所と更新の向きは {@code spec-corpus/ubnf/SOURCE.md} に書いてある。
 *
 * <p>同じ JSON・同じ期待値を Rust 側の {@code rust/unlaxer-ubnf/tests/spec_corpus.rs} も読む
 * （AGENTS.md「Java / Rust の機能対称性」）。片側だけの成功を完了根拠にしないための対です。
 *
 * <p>引用の実在検査もここで行う。仕様書の見出しや文を書き換えたら、sibling repository が
 * 後から気づくのではなく、**この** リポジトリのテストが落ちる。
 */
public class UbnfSpecCorpusTest {

    /**
     * 期待値を実装に合わせて書き換えず、かつ黙って skip もしない差分。
     *
     * <p>キーはケース ID、値は {@link Deviation}。{@code observation} は実測の文言そのもので、
     * 一致しなくなったら（= 実装が直った / 別の壊れ方をした）このテストは落ちる。直ったら
     * このエントリを消すこと。差分を放置しないための番人であって、skip の別名ではない。
     */
    private static final Map<String, Deviation> DOCUMENTED_DEVIATIONS = Map.of(
        "quantifier/huge-bound", new Deviation(
            "診断 E-BOUNDED-OVERFLOW を期待したが構文解析で落ちた: "
                + "NumberFormatException: For input string: \"2147483648\"",
            "仕様（validation.md の BOUNDED エラー）は INTEGER が Java の 32bit 符号付き整数を"
                + " 超えたとき E-BOUNDED-OVERFLOW を出すと定める。Java bootstrap は"
                + " UBNFMapper が量指定子を Integer へ変換する時点で NumberFormatException を投げ、"
                + " GrammarValidator まで到達しない。入力が拒否されること自体は満たしているが、"
                + " 診断コードとしては観測できない。層の違いであり、受理してしまう違反ではない。"
                + " Rust frontend は scope=validate を駆動しないのでこの差分は Java 側だけに出る。"));

    /** 記録済みの差分。{@code observation} は実測の文言、{@code reason} はなぜそうなるか。 */
    private record Deviation(String observation, String reason) {}

    /**
     * 構文木の比較でしか判定できず、`accept` / `reject` / `diagnostic` のどれにも決められないケース。
     * corpus 側で {@code scope: runtime} かつ {@code verdict: undetermined} として明示されている。
     */
    private static final Set<String> RUNTIME_SCOPE_IDS =
        Set.of("char-range/inclusive-bounds", "separator/expansion", "error/hint-is-last-alternative");

    private record SpecCase(
            String file, String id, String scope, String input,
            String verdict, String code, String question,
            List<String> canonicalContains, String sameCanonicalAs,
            String citationFile, String citationHeading, String citationSentence) {}

    // =========================================================================
    // 読み込み
    // =========================================================================

    private static Path resourceRoot() {
        return Path.of("src/test/resources/spec-corpus/ubnf");
    }

    /** テストの作業ディレクトリは unlaxer-dsl なので、`citation.file` の基準はその親。 */
    private static Path repositoryRoot() {
        return Path.of("..").toAbsolutePath().normalize();
    }

    private static List<SpecCase> load() {
        List<SpecCase> cases = new ArrayList<>();
        Gson gson = new Gson();
        try (var paths = Files.list(resourceRoot())) {
            List<Path> files = paths.filter(p -> p.getFileName().toString().endsWith(".json"))
                .sorted().collect(Collectors.toList());
            assertTrue("節ごとのファイルが少なすぎる: " + files.size(), files.size() >= 8);
            for (Path path : files) {
                JsonObject document =
                    gson.fromJson(Files.readString(path, StandardCharsets.UTF_8), JsonObject.class);
                assertEquals(path + ": family", "ubnf", document.get("family").getAsString());
                JsonArray array = document.getAsJsonArray("cases");
                assertTrue(path + ": ケースが 0 件", array.size() > 0);
                for (JsonElement element : array) {
                    cases.add(toCase(path.getFileName().toString(), element.getAsJsonObject()));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return cases;
    }

    private static SpecCase toCase(String file, JsonObject json) {
        JsonObject expectation = json.getAsJsonObject("expectation");
        JsonObject citation = json.getAsJsonObject("citation");
        List<String> contains = new ArrayList<>();
        if (expectation.has("canonicalContains")) {
            expectation.getAsJsonArray("canonicalContains")
                .forEach(e -> contains.add(e.getAsString()));
        }
        return new SpecCase(
            file,
            json.get("id").getAsString(),
            json.get("scope").getAsString(),
            json.has("input") ? json.get("input").getAsString() : "",
            expectation.get("verdict").getAsString(),
            expectation.has("code") ? expectation.get("code").getAsString() : null,
            expectation.has("question") ? expectation.get("question").getAsString() : null,
            contains,
            expectation.has("sameCanonicalAs") ? expectation.get("sameCanonicalAs").getAsString() : null,
            citation.get("file").getAsString(),
            citation.get("heading").getAsString(),
            citation.get("sentence").getAsString());
    }

    /** 引用の照合に使う正規化。改行や連続空白の違いだけを吸収する（ubnfc 側 `spec::normalize` と同じ）。 */
    private static String normalize(String text) {
        return String.join(" ", text.trim().split("\\s+"));
    }

    // =========================================================================
    // 1. 形
    // =========================================================================

    @Test
    public void corpusKeepsItsShapeAndDoesNotGuessWhereTheSpecIsSilent() {
        List<SpecCase> cases = load();
        assertTrue("ケースが少なすぎる: " + cases.size(), cases.size() >= 100);
        Set<String> ids = new LinkedHashSet<>();
        for (SpecCase specCase : cases) {
            assertTrue("ケース ID の重複: " + specCase.id(), ids.add(specCase.id()));
            assertTrue(specCase.id() + ": heading は見出し行そのものである必要がある",
                specCase.citationHeading().startsWith("#"));
            assertTrue(specCase.id() + ": 引用文が空", !specCase.citationSentence().isBlank());
            switch (specCase.verdict()) {
                case "diagnostic" ->
                    assertTrue(specCase.id() + ": diagnostic には code が要る", specCase.code() != null);
                case "undetermined" -> {
                    assertTrue(specCase.id() + ": undetermined には question が要る",
                        specCase.question() != null);
                    assertEquals(specCase.id() + ": undetermined は runtime scope のはず",
                        "runtime", specCase.scope());
                }
                case "reject" -> assertTrue(specCase.id() + ": reject に構文木の期待は書けない",
                    specCase.canonicalContains().isEmpty() && specCase.sameCanonicalAs() == null);
                default -> { }
            }
            if ("validate".equals(specCase.scope())) {
                assertEquals(specCase.id() + ": validate scope は diagnostic 期待であるべき",
                    "diagnostic", specCase.verdict());
            }
            if (specCase.sameCanonicalAs() != null) {
                assertTrue(specCase.id() + ": sameCanonicalAs の参照先が無い: " + specCase.sameCanonicalAs(),
                    cases.stream().anyMatch(other -> other.id().equals(specCase.sameCanonicalAs())));
            }
        }
        // 仕様が沈黙している問いを「推測で埋めない」ことが corpus の性質なので、
        // undetermined が 1 件も無い状態は書き方を誤った兆候として扱う。
        List<String> undetermined = cases.stream()
            .filter(c -> "undetermined".equals(c.verdict())).map(SpecCase::id).sorted().toList();
        assertEquals("undetermined の集合が SOURCE.md の説明と食い違う",
            RUNTIME_SCOPE_IDS.stream().sorted().toList(), undetermined);
    }

    // =========================================================================
    // 2. 引用が実在すること（仕様を編集したらここが落ちる）
    // =========================================================================

    @Test
    public void everyCitationResolvesToARealHeadingAndSentenceInTheSpec() throws IOException {
        Map<String, String> sources = new LinkedHashMap<>();
        Map<String, List<String>> headings = new LinkedHashMap<>();
        List<String> failures = new ArrayList<>();
        for (SpecCase specCase : load()) {
            Path path = repositoryRoot().resolve(specCase.citationFile());
            assertTrue("引用先の仕様書が無い: " + path, Files.isRegularFile(path));
            String text = sources.computeIfAbsent(specCase.citationFile(), key -> {
                try {
                    return Files.readString(path, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
            List<String> lines = headings.computeIfAbsent(specCase.citationFile(), key ->
                text.lines().filter(line -> line.startsWith("#")).map(String::trim).toList());
            if (!lines.contains(specCase.citationHeading())) {
                failures.add(specCase.id() + ": 見出しが " + specCase.citationFile() + " に無い: "
                    + specCase.citationHeading());
            }
            if (!normalize(text).contains(normalize(specCase.citationSentence()))) {
                failures.add(specCase.id() + ": 引用文が " + specCase.citationFile() + " に逐語で無い: "
                    + specCase.citationSentence());
            }
        }
        assertEquals("引用が仕様書と食い違う（仕様を直したら corpus も直す。原本は ubnfc 側）",
            List.of(), failures);
    }

    // =========================================================================
    // 3. Java bootstrap を駆動する
    // =========================================================================

    @Test
    public void javaBootstrapAgreesWithEverySpecDerivedExpectation() {
        List<SpecCase> cases = load();
        Map<String, String> canonicalById = new LinkedHashMap<>();
        Map<String, String> verdicts = new LinkedHashMap<>();
        List<String> failures = new ArrayList<>();
        Set<String> honoured = new LinkedHashSet<>();
        int driven = 0;

        for (SpecCase specCase : cases) {
            if (RUNTIME_SCOPE_IDS.contains(specCase.id())) {
                // 生成パーサの実行時挙動。UBNF を読む処理系の入出力からは観測できない。
                verdicts.put(specCase.id(), "skipped(runtime)");
                continue;
            }
            driven++;
            UBNFAST.UBNFFile parsed = null;
            String parseError = null;
            try {
                parsed = UBNFMapper.parse(specCase.input());
            } catch (RuntimeException | StackOverflowError error) {
                parseError = error.getClass().getSimpleName() + ": " + error.getMessage();
            }
            String observed = parsed != null ? "accept" : "reject";
            verdicts.put(specCase.id(), observed);
            if (parsed != null) {
                canonicalById.put(specCase.id(), canonical(parsed));
            }

            switch (specCase.verdict()) {
                case "accept" -> {
                    if (parsed == null) {
                        record(specCase, "受理されるべきだが拒否された: " + parseError, failures, honoured);
                    }
                }
                case "reject" -> {
                    if (parsed != null) {
                        record(specCase, "拒否されるべきだが受理された", failures, honoured);
                    }
                }
                case "diagnostic" -> {
                    if (parsed == null) {
                        record(specCase,
                            "診断 " + specCase.code() + " を期待したが構文解析で落ちた: " + parseError,
                            failures, honoured);
                        break;
                    }
                    List<String> codes = diagnosticCodes(parsed);
                    if (!codes.contains(specCase.code())) {
                        record(specCase, "期待した診断コードが出ない。実測: "
                            + (codes.isEmpty() ? "診断なし" : String.join(",", codes)), failures, honoured);
                    }
                }
                default -> record(specCase, "未知の verdict: " + specCase.verdict(), failures, honoured);
            }
        }

        // 構文木の期待（accept のときだけ書ける）。
        for (SpecCase specCase : cases) {
            String canonicalJson = canonicalById.get(specCase.id());
            if (canonicalJson == null) {
                continue;
            }
            for (String fragment : specCase.canonicalContains()) {
                if (!canonicalJson.contains(fragment)) {
                    record(specCase, "受理したが構造が違う。無い断片: " + fragment, failures, honoured);
                }
            }
            if (specCase.sameCanonicalAs() != null) {
                String other = canonicalById.get(specCase.sameCanonicalAs());
                if (other == null) {
                    record(specCase,
                        "比較先 " + specCase.sameCanonicalAs() + " が受理されないので比較できない",
                        failures, honoured);
                } else if (!other.equals(canonicalJson)) {
                    record(specCase, "受理したが " + specCase.sameCanonicalAs() + " と構文木が違う",
                        failures, honoured);
                }
            }
        }

        assertEquals("駆動したケース数", cases.size() - RUNTIME_SCOPE_IDS.size(), driven);
        assertEquals("Java bootstrap が仕様由来の期待と食い違う", List.of(), failures);
        // 差分を黙って skip しないための番人: 記録済みの差分は実在し、かつ今も起きていること。
        for (String id : DOCUMENTED_DEVIATIONS.keySet()) {
            assertTrue("DOCUMENTED_DEVIATIONS が実在しないケースを指している: " + id,
                verdicts.containsKey(id));
        }
        assertEquals("記録済みの差分が起きなくなった（直ったなら DOCUMENTED_DEVIATIONS から消す）",
            DOCUMENTED_DEVIATIONS.keySet(), honoured);
    }

    /** 期待と食い違った 1 件を、記録済みの差分なら {@code honoured} へ、そうでなければ失敗へ。 */
    private static void record(SpecCase specCase, String observation,
            List<String> failures, Set<String> honoured) {
        Deviation deviation = DOCUMENTED_DEVIATIONS.get(specCase.id());
        if (deviation != null && deviation.observation().equals(observation)) {
            honoured.add(specCase.id());
            return;
        }
        String where = specCase.citationFile() + " " + specCase.citationHeading();
        failures.add(specCase.id() + " [" + specCase.file() + "] 期待="
            + (specCase.code() != null ? specCase.code() : specCase.verdict())
            + " 入力=" + specCase.input().replace("\n", "\\n")
            + " 実測=" + observation + " 引用=" + where
            + (deviation == null ? ""
                : " 記録済みの差分と実測が違う。記録=" + deviation.observation()));
    }

    private static List<String> diagnosticCodes(UBNFAST.UBNFFile file) {
        List<String> codes = new ArrayList<>();
        for (UBNFAST.GrammarDecl grammar : file.grammars()) {
            for (GrammarValidator.ValidationIssue issue : GrammarValidator.validate(grammar)) {
                codes.add(issue.code());
            }
        }
        return codes;
    }

    // =========================================================================
    // 正準 JSON（ubnfc の examples/ubnf-crosscheck と同じ綴り）
    // =========================================================================

    private static String canonical(Object value) {
        try {
            return canonicalOf(value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String canonicalOf(Object value) throws ReflectiveOperationException {
        if (value == null) {
            return "null";
        }
        if (value instanceof Optional<?> optional) {
            return canonicalOf(optional.orElse(null));
        }
        if (value instanceof String text) {
            return quote(text);
        }
        if (value instanceof Character character) {
            return quote(String.valueOf(character));
        }
        if (value instanceof Number number) {
            return number.toString();
        }
        if (value instanceof Boolean flag) {
            return flag.toString();
        }
        if (value instanceof Enum<?> constant) {
            return quote(constant.name());
        }
        if (value instanceof List<?> list) {
            StringBuilder out = new StringBuilder("[");
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                out.append(canonicalOf(list.get(i)));
            }
            return out.append(']').toString();
        }
        if (value instanceof Map<?, ?> map) {
            StringBuilder out = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                out.append(quote(entry.getKey().toString())).append(':')
                    .append(canonicalOf(entry.getValue()));
            }
            return out.append('}').toString();
        }
        // 後置 ?/* は Java では合成 SequenceBody、明示括弧は ChoiceBody。両者を吸収する。
        if (value instanceof UBNFAST.RuleBody body) {
            StringBuilder out = new StringBuilder("[\"body\",[");
            if (body instanceof UBNFAST.ChoiceBody choice) {
                List<UBNFAST.SequenceBody> alternatives = choice.alternatives();
                for (int i = 0; i < alternatives.size(); i++) {
                    if (i > 0) {
                        out.append(',');
                    }
                    out.append(canonicalOf(alternatives.get(i).elements()));
                }
            } else {
                out.append(canonicalOf(((UBNFAST.SequenceBody) body).elements()));
            }
            return out.append("]]").toString();
        }
        if (!value.getClass().isRecord()) {
            throw new IllegalStateException("record ではありません: " + value.getClass());
        }
        StringBuilder out = new StringBuilder("[").append(quote(value.getClass().getSimpleName()));
        for (RecordComponent component : value.getClass().getRecordComponents()) {
            out.append(',').append(canonicalOf(component.getAccessor().invoke(value)));
        }
        return out.append(']').toString();
    }

    private static String quote(String text) {
        StringBuilder out = new StringBuilder("\"");
        text.codePoints().forEach(c -> {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", c));
                    } else {
                        out.appendCodePoint(c);
                    }
                }
            }
        });
        return out.append('"').toString();
    }
}
