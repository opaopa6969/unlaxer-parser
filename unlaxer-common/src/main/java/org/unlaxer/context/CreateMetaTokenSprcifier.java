package org.unlaxer.context;

/**
 * @deprecated Use {@link CreateMetaTokenSpecifier}. This misspelled type remains
 *             available for source and binary compatibility with downstream users.
 */
@Deprecated
public enum CreateMetaTokenSprcifier implements ParseContextEffector {

  createMetaOn(true),
  createMetaOff(false),
  ;

  boolean createMeta;

  CreateMetaTokenSprcifier(boolean createMeta) {
    this.createMeta = createMeta;
  }

  @Override
  public void effect(ParseContext parseContext) {
    parseContext.createMetaToken = createMeta;
  }

  public static CreateMetaTokenSprcifier of(boolean createMeta) {
    return createMeta ? createMetaOn : createMetaOff;
  }
}
