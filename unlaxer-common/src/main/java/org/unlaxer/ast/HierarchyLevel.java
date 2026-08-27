package org.unlaxer.ast;

/** Relative level at which an operator or operand is represented in an AST. */
public enum HierarchyLevel {
  parent,
  self,
  child;

  /** Converts a legacy misspelled enum value without relying on ordinals. */
  public static HierarchyLevel fromLegacy(HierarcyLevel legacy) {
    return legacy.toHierarchyLevel();
  }
}
