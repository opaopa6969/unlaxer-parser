package org.unlaxer;

import org.unlaxer.util.FactoryBoundCache;

public class Kind extends Specifier<Kind>{

	private static final long serialVersionUID = 3434126976077675934L;

	public Kind(Class<?> specifiedClass, Enum<?> subKind) {
		super(specifiedClass, subKind);
	}

	public Kind(Class<?> specifiedClass, String subKind) {
		super(specifiedClass, subKind);
	}

	public Kind(Class<?> specifiedClass) {
		super(specifiedClass);
	}

	public Kind(Enum<?> enumKind) {
		super(enumKind);
	}

	public Kind(String stringKind) {
		super(stringKind);
	}
	
	public static Kind of(String kind){
		return specifierByString.get(kind);
	}
	
	public static Kind of(Enum<?> kind){
		return specifierByEnum.get(kind);
	}
	
	public static Kind of(Class<?> clazz){
		return specifierByClass.get(clazz);
	}
	
	public static Kind of(Class<?> clazz,Specifier<?> kind){
		return specifierByString.get(clazz.getName()+"("+kind.toString()+")");
	}
	
	public static Kind of(Class<?> clazz,String kind){
		return specifierByString.get(clazz.getName()+"("+kind+")");
	}
	
	public static Kind classBaseOf(Object object){
		return specifierByString.get(object.getClass().getName());
	}
	
	static FactoryBoundCache<Class<?>,Kind> specifierByClass = 
			new FactoryBoundCache<>(Kind::new);
	
	static FactoryBoundCache<String,Kind> specifierByString = 
			new FactoryBoundCache<>(Kind::new);
	
	static FactoryBoundCache<Enum<?>,Kind> specifierByEnum = 
			new FactoryBoundCache<>(Kind::new);
	
	
}