package org.unlaxer.util.collection;

import java.util.UUID;

import org.unlaxer.Specifier;
import org.unlaxer.util.FactoryBoundCache;

public class ID extends Specifier<ID>{

	private static final long serialVersionUID = -333078144066980293L;

	public ID(Class<?> specifiedClass, Enum<?> subName) {
		super(specifiedClass, subName);
	}

	public ID(Class<?> specifiedClass, String subName) {
		super(specifiedClass, subName);
	}

	public ID(Class<?> specifiedClass) {
		super(specifiedClass);
	}

	public ID(Enum<?> enumName) {
		super(enumName);
	}

	public ID(String stringName) {
		super(stringName);
	}
	
	public static ID of(int id) {
		return of(String.valueOf(id));
	}
	
	public static ID of(long id) {
		return of(String.valueOf(id));
	}
	
	public static ID of(short id) {
		return of(String.valueOf(id));
	}
	
	public static ID of(byte id) {
		return of(String.valueOf(id));
	}
	
	public static ID of(float id) {
		return of(String.valueOf(id));
	}

	public static ID of(double id) {
		return of(String.valueOf(id));
	}
	
	public static ID of(char id) {
		return of(String.valueOf(id));
	}
	
	public static ID of(String id){
		return specifierByString.get(id);
	}
	
	public static ID of(Enum<?> id){
		return specifierByEnum.get(id);
	}
	
	public static ID of(Class<?> clazz){
		return specifierByClass.get(clazz);
	}
	
	public static ID of(Class<?> clazz,Specifier<?> id){
		return specifierByString.get(clazz.getName()+"("+id.toString()+")");
	}
	
	public static ID of(Class<?> clazz,String id){
		return specifierByString.get(clazz.getName()+"("+id+")");
	}
	
	public static ID classBaseOf(Object object){
		return specifierByString.get(object.getClass().getName());
	}
	
	static FactoryBoundCache<Class<?>,ID> specifierByClass = 
			new FactoryBoundCache<>(ID::new);
	
	static FactoryBoundCache<String,ID> specifierByString = 
			new FactoryBoundCache<>(ID::new);
	
	static FactoryBoundCache<Enum<?>,ID> specifierByEnum = 
			new FactoryBoundCache<>(ID::new);
	
	public static ID generate() {
		UUID randomUUID = UUID.randomUUID();
		return new ID(randomUUID.toString());
	}
}