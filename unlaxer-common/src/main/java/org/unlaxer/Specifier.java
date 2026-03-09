package org.unlaxer;

import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import org.unlaxer.util.cache.FactoryBoundCache;



public class Specifier<E extends Specifier<E>> implements Serializable{
	
	private static final long serialVersionUID = 6044165049875603706L;

	public enum SpecifierKind{
		stringType,
		enumType,
		classType,
		classStringType,
		classEnumType,
		;
	}
	
	SpecifierKind specifierKind;
	
	public Optional<String> stringName;
	
	public Optional<Enum<?>> enumName;
	
	int hashCode;
	
	String name;
	
	String simpleName;
	
	Optional<Class<?>> specifiedClass;
	
	byte[] bytes;
	
	@SuppressWarnings("unused")
	private Specifier() {
		super();
	}
	
	public Specifier(Class<?> specifiedClass,String subName) {
		super();
		this.specifiedClass = Optional.of(specifiedClass);
		stringName = Optional.of(subName);
		enumName = Optional.empty();
		name = specifiedClass.getName() + "("+subName+")";
		simpleName = specifiedClass.getSimpleName() + "("+subName+")"; 
		hashCode = toString().hashCode();
		specifierKind = SpecifierKind.classType;
		bytes = name.getBytes(StandardCharsets.UTF_8);
	}
	
	public Specifier(Class<?> specifiedClass,Enum<?> subName) {
		super();
		this.specifiedClass = Optional.of(specifiedClass);
		stringName = Optional.empty();
		enumName = Optional.of(subName);
		name = specifiedClass.getName() + "("+name(subName)+")";
		simpleName = specifiedClass.getSimpleName() + "("+simpleName(subName)+")"; 
		hashCode = toString().hashCode();
		specifierKind = SpecifierKind.classType;
		bytes = name.getBytes(StandardCharsets.UTF_8);
	}
	
	public Specifier(Class<?> specifiedClass) {
		super();
		this.specifiedClass = Optional.of(specifiedClass);
		stringName = Optional.empty();
		enumName = Optional.empty();
		name = specifiedClass.getName();
		simpleName = specifiedClass.getSimpleName(); 
		hashCode = toString().hashCode();
		specifierKind = SpecifierKind.classType;
		bytes = name.getBytes(StandardCharsets.UTF_8);
	}

	
	public Specifier(String stringName) {
		super();
		specifiedClass = Optional.empty();
		this.stringName = Optional.of(stringName);
		name = stringName;
		simpleName = name;
		enumName = Optional.empty();
		hashCode = toString().hashCode();
		specifierKind = SpecifierKind.stringType;
		bytes = name.getBytes(StandardCharsets.UTF_8);
	}
	
	public Specifier(Enum<?> enumName) {
		super();
		specifiedClass = Optional.empty();
		stringName = Optional.empty();
		this.enumName = Optional.of(enumName);
		name = name(enumName);
		simpleName = simpleName(enumName);
		hashCode = toString().hashCode();
		specifierKind = SpecifierKind.enumType;
		bytes = name.getBytes(StandardCharsets.UTF_8);
	}
	
	static String name(Enum<?> _enum) {
		
		return _enum.getClass().getName()+"("+_enum.name()+")";
	}
	
	static String simpleName(Enum<?> _enum) {
		
		return _enum.getClass().getSimpleName()+"("+_enum.name()+")";
	}

	
	/** 
	 * check equivalent
	 */
	@Override
	public boolean equals(Object other) {
		
		if(other instanceof Specifier) {
			
			Specifier<?> otherSpecifier = (Specifier<?>)other;
			
			if(specifierKind == otherSpecifier.specifierKind) {
				return name.equals(otherSpecifier.name);
			}
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
		return name;
	}
	
	public byte[] getBytes() {
		return bytes;
	}
	
	public String getSimpleName(){
		return simpleName;
	}
	
	public String getName(){
		return name;
	}
	
	@SuppressWarnings("unchecked")
	static <E extends Specifier<E>> E _of(String name){
		
		return (E) _specifierByString.get(name);
	}
	
	@SuppressWarnings("unchecked")
	static <E extends Specifier<E>> E _of(Enum<?> name){
		return (E) _specifierByEnum.get(name);
	}
	
	@SuppressWarnings("unchecked")
	static <E extends Specifier<E>> E _of(Class<?> clazz){
		return (E) _specifierByClass.get(clazz);
	}
	
	@SuppressWarnings("unchecked")
	static <E extends Specifier<E>> E _of(Class<?> clazz,Specifier<?> name){
		return (E) _specifierByString.get(clazz.getName()+"("+name.toString()+")");
	}
	
	@SuppressWarnings("unchecked")
	static <E extends Specifier<E>> E _of(Class<?> clazz,String name){
		return (E) _specifierByString.get(clazz.getName()+"("+name+")");
	}
	
	@SuppressWarnings("unchecked")
	static <E extends Specifier<E>> E _classBaseOf(Object object){
		return (E)_specifierByString.get(object.getClass().getName());
	}
	
	static FactoryBoundCache<Class<?>,Specifier<?>> _specifierByClass = 
			new FactoryBoundCache<>(Specifier::new);
	
	static FactoryBoundCache<String,Specifier<?>> _specifierByString = 
			new FactoryBoundCache<>(Specifier::new);
	
	static FactoryBoundCache<Enum<?>,Specifier<?>> _specifierByEnum = 
			new FactoryBoundCache<>(Specifier::new);

	@Override
	public int hashCode() {
		return hashCode;
	}
}