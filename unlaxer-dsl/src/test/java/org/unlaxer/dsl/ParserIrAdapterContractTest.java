package org.unlaxer.dsl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Test;
import org.unlaxer.dsl.ir.ParseRequest;
import org.unlaxer.dsl.ir.ParserIrAdapter;
import org.unlaxer.dsl.ir.ParserIrAdapterMetadata;
import org.unlaxer.dsl.ir.ParserIrConformanceValidator;
import org.unlaxer.dsl.ir.ParserIrDocument;
import org.unlaxer.dsl.ir.ParserIrFeature;
import org.unlaxer.dsl.ir.ParserIrScopeEvents;

public class ParserIrAdapterContractTest {

    @Test
    public void testAdapterCanReturnConformantParserIr() throws Exception {
        ParserIrAdapter adapter = new FixtureBackedAdapter("valid-minimal.json");
        ParseRequest request = new ParseRequest("fixture://valid-minimal", "let a = 1;", Map.of());

        ParserIrAdapterMetadata metadata = adapter.metadata();
        assertEquals("fixture-adapter", metadata.adapterId());
        assertTrue(metadata.supportedIrVersions().contains("1.0"));

        ParserIrDocument document = adapter.parseToIr(request);
        ParserIrConformanceValidator.validate(document);
    }

    @Test
    public void testConformanceRejectsInvalidParserIrFixture() throws Exception {
        ParserIrAdapter adapter = new FixtureBackedAdapter("invalid-source-blank.json");
        ParseRequest request = new ParseRequest("fixture://invalid-source-blank", "let a = 1;", Map.of());

        try {
            ParserIrConformanceValidator.validate(adapter.parseToIr(request));
            fail("expected source blank failure");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("source must not be blank"));
        }
    }

    @Test
    public void testScopeTreeSampleAdapterBuildsConformantScopeEvents() {
        ParserIrAdapter adapter = new ScopeTreeSampleAdapter();
        ParseRequest request = new ParseRequest("sample://scope-tree", "ok", Map.of("scopeMode", "dynamic"));

        ParserIrDocument document = adapter.parseToIr(request);
        ParserIrConformanceValidator.validate(document);
        assertScopeTreePayload(document, "dynamic");
    }

    @Test
    public void testScopeTreeSampleAdapterDefaultsToLexicalMode() {
        ParserIrAdapter adapter = new ScopeTreeSampleAdapter();
        ParseRequest request = new ParseRequest("sample://scope-tree-default", "ok", Map.of());

        ParserIrDocument document = adapter.parseToIr(request);
        ParserIrConformanceValidator.validate(document);
        assertScopeTreePayload(document, "lexical");
    }

    private static void assertScopeTreePayload(ParserIrDocument document, String expectedMode) {
        List<Object> scopeEvents = JsonTestUtil.getArray(document.payload(), "scopeEvents");
        assertEquals(4, scopeEvents.size());
        @SuppressWarnings("unchecked")
        Map<String, Object> first = (Map<String, Object>) scopeEvents.get(0);
        assertEquals("enterScope", first.get("event"));
        assertEquals(expectedMode, first.get("scopeMode"));
        @SuppressWarnings("unchecked")
        Map<String, Object> define = (Map<String, Object>) scopeEvents.get(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> use = (Map<String, Object>) scopeEvents.get(2);
        assertEquals("define", define.get("event"));
        assertEquals("x", define.get("symbol"));
        assertEquals("variable", define.get("kind"));
        assertEquals("use", use.get("event"));
        assertEquals("x", use.get("symbol"));
        assertEquals("scope:Sample::Start", use.get("targetScopeId"));

        List<Object> annotations = JsonTestUtil.getArray(document.payload(), "annotations");
        assertEquals(1, annotations.size());
        @SuppressWarnings("unchecked")
        Map<String, Object> annotation = (Map<String, Object>) annotations.get(0);
        assertEquals("Sample::Start", annotation.get("targetId"));
        assertEquals("scope-tree", annotation.get("name"));
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) annotation.get("payload");
        assertEquals(expectedMode, payload.get("mode"));
    }

    private static final class FixtureBackedAdapter implements ParserIrAdapter {
        private final String fixtureName;

        private FixtureBackedAdapter(String fixtureName) {
            this.fixtureName = fixtureName;
        }

        @Override
        public ParserIrAdapterMetadata metadata() {
            return new ParserIrAdapterMetadata(
                "fixture-adapter",
                Set.of("1.0"),
                Set.of(ParserIrFeature.ANNOTATIONS, ParserIrFeature.DIAGNOSTICS, ParserIrFeature.SCOPE_EVENTS)
            );
        }

        @Override
        public ParserIrDocument parseToIr(ParseRequest request) {
            if (request.content().isBlank()) {
                throw new IllegalArgumentException("content must not be blank");
            }
            try {
                String json = Files.readString(Path.of("src/test/resources/schema/parser-ir").resolve(fixtureName));
                return new ParserIrDocument(JsonTestUtil.parseObject(json));
            } catch (Exception e) {
                throw new IllegalStateException("failed to load fixture: " + fixtureName, e);
            }
        }
    }

    private static final class ScopeTreeSampleAdapter implements ParserIrAdapter {
        @Override
        public ParserIrAdapterMetadata metadata() {
            return new ParserIrAdapterMetadata(
                "scope-tree-sample-adapter",
                Set.of("1.0"),
                Set.of(ParserIrFeature.SCOPE_TREE, ParserIrFeature.SCOPE_EVENTS, ParserIrFeature.ANNOTATIONS)
            );
        }

        @Override
        public ParserIrDocument parseToIr(ParseRequest request) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", "Sample::Start");
            node.put("kind", "RuleDecl");
            node.put("span", Map.of("start", 0L, "end", (long) request.content().length()));
            List<Object> nodes = List.of(node);

            String mode = String.valueOf(request.options().getOrDefault("scopeMode", "lexical"));
            Map<String, String> scopeModeByNodeId = Map.of("Sample::Start", mode);
            List<Object> baseScopeEvents = ParserIrScopeEvents.emitSyntheticEnterLeaveEvents(scopeModeByNodeId, nodes);
            @SuppressWarnings("unchecked")
            Map<String, Object> enter = (Map<String, Object>) baseScopeEvents.get(0);
            @SuppressWarnings("unchecked")
            Map<String, Object> leave = (Map<String, Object>) baseScopeEvents.get(1);
            String scopeId = String.valueOf(enter.get("scopeId"));

            Map<String, Object> symbolSpan = Map.of("start", 0L, "end", Math.min(1L, (long) request.content().length()));
            Map<String, Object> define = new LinkedHashMap<>();
            define.put("event", "define");
            define.put("scopeId", scopeId);
            define.put("span", symbolSpan);
            define.put("symbol", "x");
            define.put("kind", "variable");

            Map<String, Object> use = new LinkedHashMap<>();
            use.put("event", "use");
            use.put("scopeId", scopeId);
            use.put("span", symbolSpan);
            use.put("symbol", "x");
            use.put("targetScopeId", scopeId);

            List<Object> scopeEvents = List.of(enter, define, use, leave);
            List<Object> annotations = List.of(Map.of(
                "targetId", "Sample::Start",
                "name", "scope-tree",
                "payload", Map.of("mode", mode)
            ));

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("irVersion", "1.0");
            payload.put("source", request.sourceId());
            payload.put("nodes", nodes);
            payload.put("scopeEvents", scopeEvents);
            payload.put("annotations", annotations);
            payload.put("diagnostics", List.of());
            return new ParserIrDocument(payload);
        }
    }
}
