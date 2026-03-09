package org.unlaxer.util;

import java.io.Serializable;
import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Stream;


public class NameSpecifier implements Serializable{
	
	private static final long serialVersionUID = 1439648359231210888L;

	public enum SpecifierKind{
		stringType,
		enumType,
		classType,
		;
	}
	
	SpecifierKind specifierKind;
	
	public Optional<String> stringName;
	
	public Optional<Enum<?>> enumName;
	
	int hashCode;
	
	public NameSpecifier() {
		super();
	}
	public NameSpecifier(String stringName) {
		super();
		this.stringName = Optional.of(stringName);
		enumName = Optional.empty();
		hashCode = toString().hashCode();
		specifierKind = SpecifierKind.stringType;
	}
	
	public NameSpecifier(Enum<?> enumName) {
		super();
		this.enumName = Optional.of(enumName);
		stringName = Optional.empty();
		hashCode = toString().hashCode();
		specifierKind = SpecifierKind.enumType;
	}
	
	/** 
	 * check equivalent
	 */
	@Override
	public boolean equals(Object other) {
		if(other instanceof NameSpecifier && stringName.isPresent()){
			return ((NameSpecifier)other).toString().equals(toString());
		}
		if(other instanceof NameSpecifier && enumName.isPresent()){
			return ((NameSpecifier)other).equals(enumName);
		}
		if(other instanceof Enum<?> && stringName.isPresent()){
			
			return ((Enum<?>)other).name().equals(toString());
		}
		if(other instanceof Enum<?> && enumName.isPresent()){
			
			return ((Enum<?>)other)== enumName.get();
		}

		if(other instanceof String){
			return ((String)other).equals(toString());
		}
		return false;
	}
	@Override
	public String toString() {
		return stringName.isPresent() ? stringName.get() : enumName.get().name();
	}
	
	public static NameSpecifier of(String name){
		
		return specifierByString.get(name);
	}
	
	
	public static NameSpecifier of(Enum<?> name){
		return specifierByEnum.get(name);
	}
	
	public static NameSpecifier of(Class<?> clazz){
		return specifierByString.get(clazz.getName());
	}

	
	public static NameSpecifier classBaseOf(Object object){
		return specifierByString.get(object.getClass().getName());
	}
	
	public static Stream<NameSpecifier> streamOf(Enum<?>... enums){
		return Arrays.asList(enums).stream().map(NameSpecifier::of);
	}
	
	static FactoryBoundCache<String,NameSpecifier> specifierByString = 
			new FactoryBoundCache<>(NameSpecifier::new);
	
	static FactoryBoundCache<Enum<?>,NameSpecifier> specifierByEnum = 
			new FactoryBoundCache<>(NameSpecifier::new);

	@Override
	public int hashCode() {
		return hashCode;
	}
}