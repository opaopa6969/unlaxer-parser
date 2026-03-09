package org.unlaxer.base;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class IDEntity extends Specifier<IDEntity>{

	private static final long serialVersionUID = -333078144066980293L;

	public IDEntity(Class<?> specifiedClass, Enum<?> subName) {
		super(specifiedClass, subName);
	}

	public IDEntity(Class<?> specifiedClass, String subName) {
		super(specifiedClass, subName);
	}

	public IDEntity(Class<?> specifiedClass) {
		super(specifiedClass);
	}

	public IDEntity(Enum<?> enumName) {
		super(enumName);
	}

	public IDEntity(String stringName) {
		super(stringName);
	}
	
	public static IDEntity of(String id){
		return specifierByString.computeIfAbsent(id , IDEntity::new);
	}
	
	public static IDEntity of(Enum<?> id){
		return specifierByEnum.computeIfAbsent(id , IDEntity::new);
	}
	
	public static IDEntity of(Class<?> clazz){
		return specifierByClass.computeIfAbsent(clazz , IDEntity::new);
	}
	
	public static IDEntity of(Class<?> clazz,Specifier<?> id){
		return specifierByString.get(clazz.getName()+"("+id.toString()+")");
	}
	
	public static IDEntity of(Class<?> clazz,String id){
		return specifierByString.get(clazz.getName()+"("+id+")");
	}
	
	public static IDEntity classBaseOf(Object object){
		return specifierByString.get(object.getClass().getName());
	}
	
	static Map<Class<?>,IDEntity> specifierByClass =  new HashMap<>();
	
	static Map<String,IDEntity> specifierByString =  new HashMap<>();
	
	static Map<Enum<?>,IDEntity> specifierByEnum =  new HashMap<>();
	
	public static IDEntity generate() {
		return new IDEntity(UUID.randomUUID().toString());
	}
}