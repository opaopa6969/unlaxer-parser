package org.unlaxer.dsl.impact;

import static org.junit.Assert.*;

import java.util.List;
import org.junit.Test;

/** Handwritten source-API expectations, independent of a generator's naming strategy. */
public class ApiImpactOverloadTest {
    @Test public void overloadAdditionRemovalAndReorderingKeepEveryMethod() {
        var text = method("String", "literal", "T", true);
        var node = method("GAST.Leaf", "node", "T", false);
        assertEquals(List.of("METHOD_ADDED:evalLeaf(String)"), changes(List.of(node), List.of(text, node)));
        assertEquals(List.of("METHOD_REMOVED:evalLeaf(String)"), changes(List.of(node, text), List.of(node)));
        assertTrue(ApiImpact.changes(snapshot(List.of(text, node)), snapshot(List.of(node, text))).isEmpty());
        assertEquals(List.of("GAST.Leaf", "String"), snapshot(List.of(text, node)).methods().stream()
            .map(m -> m.parameters().get(0).type()).toList());
    }

    @Test public void parameterChangePairsOnlyTheUnambiguousRemainder() {
        var text = method("String", "literal", "T", true);
        var node = method("GAST.Leaf", "node", "T", false);
        var object = method("Object", "literal", "T", true);
        var report = ApiImpact.changes(snapshot(List.of(text, node)), snapshot(List.of(node, object)));
        assertEquals(1, report.size());
        assertEquals("METHOD_SIGNATURE_CHANGED", report.get(0).kind());
        assertEquals("evalLeaf(String)", report.get(0).subject());
        assertEquals("(literal:String)->T", report.get(0).before());
        assertEquals("(literal:Object)->T", report.get(0).after());
        assertEquals(text.origins(), report.get(0).beforeOrigins());
        assertEquals(object.generated(), report.get(0).afterGenerated());

        assertEquals(List.of("METHOD_ADDED:evalLeaf(Integer)", "METHOD_ADDED:evalLeaf(Object)",
                "METHOD_REMOVED:evalLeaf(GAST.Leaf)", "METHOD_REMOVED:evalLeaf(String)"),
            changes(List.of(text, node), List.of(object, method("Integer", "value", "T", true))));
    }

    @Test public void nameReturnTypeAndRequirementChangesDoNotLoseTheOtherOverload() {
        var node = method("GAST.Leaf", "node", "T", false);
        var before = method("String", "literal", "T", true);
        var after = method("String", "text", "Object", false);
        assertEquals(List.of("METHOD_REQUIRED_CHANGED:evalLeaf(String)",
                "METHOD_SIGNATURE_CHANGED:evalLeaf(String)"), changes(List.of(before, node), List.of(node, after)));
        // Preserve the existing non-overloaded report shape, including parameter changes.
        assertEquals(List.of("METHOD_REQUIRED_CHANGED:evalLeaf", "METHOD_SIGNATURE_CHANGED:evalLeaf"),
            changes(List.of(before), List.of(method("Object", "text", "Object", false))));
    }

    @Test public void javaRejectsTrueDuplicateSignaturesIncludingErasureAndVarargs() {
        var text = method("String", "text", "T", true);
        assertThrows(IllegalStateException.class, () -> JavaApiSchema.rejectDuplicateMethods(
            List.of(text, method("String", "renamed", "Object", false))));
        assertThrows(IllegalStateException.class, () -> JavaApiSchema.rejectDuplicateMethods(
            List.of(method("List<String>", "a", "T", true), method("List<Integer>", "b", "T", true))));
        assertThrows(IllegalStateException.class, () -> JavaApiSchema.rejectDuplicateMethods(
            List.of(method("String...", "a", "T", true), method("String[]", "b", "T", true))));
        assertThrows(IllegalStateException.class, () -> JavaApiSchema.rejectDuplicateMethods(
            List.of(method("T[]", "a", "T", true), method("Object[]", "b", "T", true))));
        JavaApiSchema.rejectDuplicateMethods(List.of(text, method("GAST.Leaf", "node", "T", false)));
        assertThrows(IllegalStateException.class, () -> ApiImpact.changes(snapshot(List.of(text, text)), snapshot(List.of(text))));
    }

    private static ApiImpact.Method method(String type, String parameter, String result, boolean required) {
        return new ApiImpact.Method("evalLeaf", result, List.of(new ApiImpact.Parameter(parameter, type)),
            required, List.of(new ApiImpact.Origin(type, new ApiImpact.Span(1, 2))),
            new ApiImpact.Location("GEvaluator.java", new ApiImpact.Span(3, 4), 1, 4));
    }
    private static ApiImpact.Snapshot snapshot(List<ApiImpact.Method> methods) {
        return new ApiImpact.Snapshot("G", "GAST", "GEvaluator", List.of(), methods);
    }
    private static List<String> changes(List<ApiImpact.Method> before, List<ApiImpact.Method> after) {
        return ApiImpact.changes(snapshot(before), snapshot(after)).stream()
            .map(c -> c.kind() + ":" + c.subject()).toList();
    }
}
