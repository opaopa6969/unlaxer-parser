package org.unlaxer.base;

import java.util.HashMap;
import java.util.Map;

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
		return specifierByString.computeIfAbsent(tag , Tag::new);
	}
	
	public static Tag of(Enum<?> tag){
		return specifierByEnum.computeIfAbsent(tag, Tag::new);
	}
	
	public static Tag of(Class<?> clazz){
		return specifierByClass.computeIfAbsent(clazz , Tag::new);
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
	
	static Map<Class<?>,Tag> specifierByClass = new HashMap<>();
	
	static Map<String,Tag> specifierByString = new HashMap<>();
	
	static Map<Enum<?>,Tag> specifierByEnum =  new HashMap<>();

}