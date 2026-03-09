package org.unlaxer;

import org.unlaxer.util.FactoryBoundCache;

public class Tag extends Specifier<Tag>{

	private static final long serialVersionUID = -8303558188306106846L;

	public Tag(Class<?> specifiedClass, Enum<?> subName) {
		super(specifiedClass, subName);
	}

	public Tag(Class<?> specifiedClass, String subName) {
		super(specifiedClass, subName);
	}

	public Tag(Class<?> specifiedClass) {
		super(specifiedClass);
	}

	public Tag(Enum<?> enumName) {
		super(enumName);
	}

	public Tag(String stringName) {
		super(stringName);
	}
	
	public static Tag of(String tag){
		return specifierByString.get(tag);
	}
	
	public static Tag of(Enum<?> tag){
		return specifierByEnum.get(tag);
	}
	
	public static Tag of(Class<?> clazz){
		return specifierByClass.get(clazz);
	}
	
	public static Tag of(Class<?> clazz,Specifier<?> tag){
		return specifierByString.get(clazz.getName()+"("+tag.toString()+")");
	}
	
	public static Tag of(Class<?> clazz,String tag){
		return specifierByString.get(clazz.getName()+"("+tag+")");
	}
	
	public static Tag classBaseOf(Object object){
		return specifierByString.get(object.getClass().getName());
	}
	
	static FactoryBoundCache<Class<?>,Tag> specifierByClass = 
			new FactoryBoundCache<>(Tag::new);
	
	static FactoryBoundCache<String,Tag> specifierByString = 
			new FactoryBoundCache<>(Tag::new);
	
	static FactoryBoundCache<Enum<?>,Tag> specifierByEnum = 
			new FactoryBoundCache<>(Tag::new);

	public <T extends Enum<T>> T toEnum(Class<T> enumClass ) {
		return Enum.valueOf(enumClass, enumName.orElseThrow().name());
	}

}