package org.unlaxer.parser;

import java.util.Map;
import java.util.Optional;

import org.unlaxer.Name;

public interface GlobalScopeTree{
	
	public Map<Name,Object> getGlobalScopeTreeMap();
	
	
	public default Optional<Object> get(Name name){
		return Optional.of(getGlobalScopeTreeMap().get(name));
	}
	
	public default <T> Optional<T> get(Name name , Class<T> specifiedClass){
		return Optional.of(specifiedClass.cast(getGlobalScopeTreeMap().get(name)));
	}
	
	public default void put(Name name , Object object){
		getGlobalScopeTreeMap().put(name, object);
	}
	
	public default Optional<Object> remove(Name name){
		return Optional.of(getGlobalScopeTreeMap().remove(name));
	}
	
	public default boolean containsKey(Name name){
		return getGlobalScopeTreeMap().containsKey(name);
	}
	
	public default boolean containsValue(Object object){
		return getGlobalScopeTreeMap().containsValue(object);
	}
	
}