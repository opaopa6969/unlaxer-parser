package org.unlaxer;

import org.unlaxer.util.FactoryBoundCache;

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
		return specifierByString.get(name);
	}
	
	public static Name of(Enum<?> name){
		return specifierByEnum.get(name);
	}
	
	public static Name of(Class<?> clazz){
		return specifierByClass.get(clazz);
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
	
	static FactoryBoundCache<Class<?>,Name> specifierByClass = 
			new FactoryBoundCache<>(Name::new);
	
	static FactoryBoundCache<String,Name> specifierByString = 
			new FactoryBoundCache<>(Name::new);
	
	static FactoryBoundCache<Enum<?>,Name> specifierByEnum = 
			new FactoryBoundCache<>(Name::new);


}