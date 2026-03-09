package org.unlaxer.context;

public enum CreateMetaTokenSpecifier implements ParseContextEffector{
	
	createMetaOn(true),
	createMetaOff(false),
	;
	boolean createMeta;
	
	CreateMetaTokenSpecifier(boolean createMeta) {
		this.createMeta = createMeta;
	}

	@Override
	public void effect(ParseContext parseContext) {
		parseContext.createMetaToken = createMeta;
	}
	
	public static CreateMetaTokenSpecifier of(boolean createMeta){
		return createMeta ? createMetaOn : createMetaOff; 
	}
}