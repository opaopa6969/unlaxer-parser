package org.unlaxer.base;

import java.util.HashMap;
import java.util.Map;

public class Name extends Specifier<Name>{

	private static final long serialVersionUID = 8183659027717929237L;

	public Name(Class<?> specifiedClass, Enum<?> subName) {
		super(specifiedClass, subName);
	}

	public Name(Class<?> specifiedClass, String subName) {
		super(specifiedClass, subName);
	}

	public Name(Class<?> specifiedClass) {
		super(specifiedClass);
	}

	public Name(Enum<?> enumName) {
		super(enumName);
	}

	public Name(String stringName) {
		super(stringName);
	}
	
	public static Name of(String name){
		return specifierByString.computeIfAbsent(name  , Name::new);
	}
	
	public static Name of(Enum<?> name){
		return specifierByEnum.computeIfAbsent(name ,  Name::new);
	}
	
	public static Name of(Class<?> clazz){
		return specifierByClass.computeIfAbsent(clazz , Name::new);
	}
	
	public static Name of(Class<?> clazz,Specifier<?> name){
		return specifierByString.get(clazz.getName()+"("+name.toString()+")");
	}
	
	public static Name of(Class<?> clazz,String name){
		return specifierByString.get(clazz.getName()+"("+name+")");
	}
	
	public static Name classBaseOf(Object object){
		return specifierByString.get(object.getClass().getName());
	}
	
	static Map<Class<?>,Name> specifierByClass =  new HashMap<>();
	
	static Map<String,Name> specifierByString =new HashMap<>();
	
	static Map<Enum<?>,Name> specifierByEnum = new HashMap<>();

}