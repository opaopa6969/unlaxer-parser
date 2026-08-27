package org.unlaxer.ast;

/**
 * @deprecated Use {@link HierarchyLevel}.
 */
@Deprecated(since = "3.0.15", forRemoval = false)
public enum HierarcyLevel {
  parent(HierarchyLevel.parent),
  self(HierarchyLevel.self),
  child(HierarchyLevel.child);

  private final HierarchyLevel hierarchyLevel;

  HierarcyLevel(HierarchyLevel hierarchyLevel) {
    this.hierarchyLevel = hierarchyLevel;
  }

  /** Returns the corrected enum value represented by this legacy value. */
  public HierarchyLevel toHierarchyLevel() {
    return hierarchyLevel;
  }
}
