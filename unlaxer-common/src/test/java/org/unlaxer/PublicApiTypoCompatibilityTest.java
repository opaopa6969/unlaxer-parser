package org.unlaxer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;

import org.junit.Test;
import org.unlaxer.ast.HierarcyLevel;
import org.unlaxer.ast.HierarchyLevel;
import org.unlaxer.ast.OperatorOperandPattern;
import org.unlaxer.parser.NonTerminalSymbol;
import org.unlaxer.parser.NonTerminallSymbol;
import org.unlaxer.parser.combinator.Chain;

@SuppressWarnings("deprecation")
public class PublicApiTypoCompatibilityTest {

  @Test
  public void correctedSourceMethodKeepsLegacyBridge() throws Exception {
    Source source = StringSource.createDetachedSource("correct");

    assertEquals("correct", source.sourceToString().apply(source));
    assertEquals("correct", source.sourceToStgring().apply(source));

    Method corrected = Source.class.getMethod("sourceToString");
    Method legacy = Source.class.getMethod("sourceToStgring");
    assertTrue("legacy Source implementations inherit the corrected method", corrected.isDefault());
    assertNotNull(legacy.getAnnotation(Deprecated.class));
  }

  @Test
  public void correctedNonTerminalMarkerIncludesLegacyImplementations() {
    assertTrue(NonTerminalSymbol.class.isAssignableFrom(NonTerminallSymbol.class));
    assertTrue(NonTerminalSymbol.class.isAssignableFrom(Chain.class));
    assertTrue(NonTerminallSymbol.class.isAssignableFrom(Chain.class));
  }

  @Test
  public void hierarchyLevelConvertsLegacyValuesWithoutChangingPatternSemantics() {
    for (HierarcyLevel legacy : HierarcyLevel.values()) {
      assertEquals(HierarchyLevel.valueOf(legacy.name()), legacy.toHierarchyLevel());
      assertEquals(legacy.toHierarchyLevel(), HierarchyLevel.fromLegacy(legacy));
    }

    assertEquals(HierarchyLevel.self, OperatorOperandPattern.Tree.operatorLevel());
    assertEquals(HierarchyLevel.child, OperatorOperandPattern.Tree.operandLevel());
  }
}
