package org.unlaxer.dsl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;
import org.unlaxer.dsl.ir.ParserIrScopeEvents;

public class ParserIrScopeEventsTest {
    private enum TestScopeMode {
        LEXICAL,
        DYNAMIC
    }

    @Test
    public void testEmitSyntheticEnterLeaveEventsUsesNodeSpanAndMode() {
        Map<String, String> scopeModeByNodeId = Map.of("G::Start", "lexical");
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", "G::Start");
        node.put("kind", "RuleDecl");
        node.put("span", Map.of("start", 10L, "end", 20L));

        List<Object> events = ParserIrScopeEvents.emitSyntheticEnterLeaveEvents(scopeModeByNodeId, List.of(node));
        assertEquals(2, events.size());

        @SuppressWarnings("unchecked")
        Map<String, Object> enter = (Map<String, Object>) events.get(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> leave = (Map<String, Object>) events.get(1);
        assertEquals("enterScope", enter.get("event"));
        assertEquals("leaveScope", leave.get("event"));
        assertEquals("scope:G::Start", enter.get("scopeId"));
        assertEquals("lexical", enter.get("scopeMode"));
        assertEquals("lexical", leave.get("scopeMode"));

        @SuppressWarnings("unchecked")
        Map<String, Object> span = (Map<String, Object>) enter.get("span");
        assertEquals(10L, ((Number) span.get("start")).longValue());
        assertEquals(20L, ((Number) span.get("end")).longValue());
    }

    @Test
    public void testEmitSyntheticEnterLeaveEventsRejectsUnsupportedMode() {
        Map<String, String> scopeModeByNodeId = Map.of("G::Start", "global");
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", "G::Start");
        node.put("kind", "RuleDecl");
        node.put("span", Map.of("start", 0L, "end", 0L));

        try {
            ParserIrScopeEvents.emitSyntheticEnterLeaveEvents(scopeModeByNodeId, List.of(node));
            fail("expected unsupported scope mode failure");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("unsupported scope mode"));
        }
    }

    @Test
    public void testEmitSyntheticEnterLeaveEventsFallsBackToDefaultSpan() {
        Map<String, String> scopeModeByNodeId = Map.of("G::Start", "dynamic");
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", "G::Start");
        node.put("kind", "RuleDecl");
        node.put("span", Map.of("start", -1L, "end", -1L));

        List<Object> events = ParserIrScopeEvents.emitSyntheticEnterLeaveEvents(scopeModeByNodeId, List.of(node));
        @SuppressWarnings("unchecked")
        Map<String, Object> enter = (Map<String, Object>) events.get(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> span = (Map<String, Object>) enter.get("span");
        assertEquals(0L, ((Number) span.get("start")).longValue());
        assertEquals(0L, ((Number) span.get("end")).longValue());
    }

    @Test
    public void testEmitSyntheticEnterLeaveEventsForRulesUsesGrammarRuleNodeIds() {
        Map<String, String> scopeModeByRuleName = Map.of("Start", "lexical");
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", "Tiny::Start");
        node.put("kind", "RuleDecl");
        node.put("span", Map.of("start", 1L, "end", 2L));

        List<Object> events = ParserIrScopeEvents.emitSyntheticEnterLeaveEventsForRules(
            "Tiny",
            scopeModeByRuleName,
            List.of(node)
        );
        assertEquals(2, events.size());
        @SuppressWarnings("unchecked")
        Map<String, Object> first = (Map<String, Object>) events.get(0);
        assertEquals("scope:Tiny::Start", first.get("scopeId"));
        assertEquals("lexical", first.get("scopeMode"));
    }

    @Test
    public void testEmitSyntheticEnterLeaveEventsForRulesRejectsBlankGrammarName() {
        try {
            ParserIrScopeEvents.emitSyntheticEnterLeaveEventsForRules(" ", Map.of("Start", "lexical"), List.of());
            fail("expected grammar name validation failure");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("grammarName must not be blank"));
        }
    }

    @Test
    public void testToScopeModeByRuleNameAcceptsEnumValues() {
        Map<String, String> converted = ParserIrScopeEvents.toScopeModeByRuleName(
            Map.of("Start", TestScopeMode.LEXICAL, "Inner", TestScopeMode.DYNAMIC)
        );
        assertEquals("lexical", converted.get("Start"));
        assertEquals("dynamic", converted.get("Inner"));
    }

    @Test
    public void testEmitSyntheticEnterLeaveEventsForRulesAnyModeAcceptsEnumValues() {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", "Tiny::Start");
        node.put("kind", "RuleDecl");
        node.put("span", Map.of("start", 0L, "end", 1L));

        List<Object> events = ParserIrScopeEvents.emitSyntheticEnterLeaveEventsForRulesAnyMode(
            "Tiny",
            Map.of("Start", TestScopeMode.LEXICAL),
            List.of(node)
        );
        assertEquals(2, events.size());
        @SuppressWarnings("unchecked")
        Map<String, Object> first = (Map<String, Object>) events.get(0);
        assertEquals("lexical", first.get("scopeMode"));
    }

    @Test
    public void testToScopeModeByNodeIdFromScopeIdAcceptsEnumValues() {
        Map<String, String> converted = ParserIrScopeEvents.toScopeModeByNodeIdFromScopeId(
            Map.of("scope:Tiny::Start", TestScopeMode.DYNAMIC)
        );
        assertEquals("dynamic", converted.get("Tiny::Start"));
    }

    @Test
    public void testEmitSyntheticEnterLeaveEventsForScopeIdsAnyModeAcceptsScopeIdMap() {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", "Tiny::Start");
        node.put("kind", "RuleDecl");
        node.put("span", Map.of("start", 2L, "end", 3L));

        List<Object> events = ParserIrScopeEvents.emitSyntheticEnterLeaveEventsForScopeIdsAnyMode(
            Map.of("scope:Tiny::Start", "dynamic"),
            List.of(node)
        );
        assertEquals(2, events.size());
        @SuppressWarnings("unchecked")
        Map<String, Object> first = (Map<String, Object>) events.get(0);
        assertEquals("scope:Tiny::Start", first.get("scopeId"));
        assertEquals("dynamic", first.get("scopeMode"));
    }

    @Test
    public void testToScopeModeByNodeIdFromScopeIdRejectsInvalidScopeId() {
        try {
            ParserIrScopeEvents.toScopeModeByNodeIdFromScopeId(Map.of("Tiny::Start", "lexical"));
            fail("expected invalid scope id failure");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("scopeId must match scope:{nodeId}"));
        }
    }
}
