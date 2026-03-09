package org.unlaxer.context;

public enum CreateMetaTokenSprcifier implements ParseContextEffector{
	
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
	
	public static CreateMetaTokenSprcifier of(boolean createMeta){
		return createMeta ? createMetaOn : createMetaOff; 
	}
}