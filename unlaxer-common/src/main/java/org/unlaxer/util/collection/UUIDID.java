package org.unlaxer.util.collection;

import java.util.UUID;

import org.unlaxer.Specifier;
import org.unlaxer.util.FactoryBoundCache;

public class UUIDID extends ID{

	private static final long serialVersionUID = -4336847195055302483L;
	
	UUID uuid;

	private UUIDID(Class<?> specifiedClass, Enum<?> subName) {
		super(specifiedClass, subName);
	}

	private UUIDID(Class<?> specifiedClass, String subName) {
		super(specifiedClass, subName);
	}

	private UUIDID(Class<?> specifiedClass) {
		super(specifiedClass);
	}

	private UUIDID(Enum<?> enumName) {
		super(enumName);
	}

	public UUIDID(String uuidString) {
		super(uuidString);
		uuid = UUID.fromString(uuidString);
	}
	
	public UUIDID(UUID uuid) {
		super(uuid.toString());
		this.uuid = uuid;
	}
	
	public UUID asUUID() {
		return uuid;
	}
	
	public static UUIDID of(String uuidString){
		UUID.fromString(uuidString);
		return specifierByString.get(uuidString);
	}
	
	public static UUIDID of(Enum<?> id){
		throw new UnsupportedOperationException();
	}
	
	public static UUIDID of(Class<?> clazz){
		throw new UnsupportedOperationException();
	}
	
	public static ID of(Class<?> clazz,Specifier<?> id){
		throw new UnsupportedOperationException();
	}
	
	public static ID of(Class<?> clazz,String id){
		throw new UnsupportedOperationException();
	}
	
	public static ID generate() {
		UUID randomUUID = UUID.randomUUID();
		return new ID(randomUUID.toString());
	}
	static FactoryBoundCache<String,UUIDID> specifierByString = 
			new FactoryBoundCache<>(UUIDID::new);
	
}