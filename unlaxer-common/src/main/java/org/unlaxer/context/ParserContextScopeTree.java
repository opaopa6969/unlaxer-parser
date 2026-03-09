package org.unlaxer.context;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.unlaxer.Name;
import org.unlaxer.parser.Parser;

public interface ParserContextScopeTree{
	
	public static final Name nameLess = Name.of(ParserContextScopeTree.class);
	
	public Map<Parser,Map<Name,Object>> getParserContextScopeTreeMap();
	
	public default Optional<Object> get(Parser parser){
		return get(parser,nameLess);
	}
	
	default Map<Name, Object> getParserContextScopeTreeMap(Parser parser){
		
		return getParserContextScopeTreeMap()
			.computeIfAbsent(parser, parser_->new HashMap<>());
	}
	
	public default Optional<Object> get(Parser parser , Name name){
		Map<Name, Object> map = getParserContextScopeTreeMap(parser);
		return Optional.of(map.get(name));
	}
	
	public default <T> Optional<T> get(Parser parser , Class<T> specifiedClass){
		return get(parser, nameLess , specifiedClass);
	}

	public default <T> Optional<T> get(Parser parser , Name name , Class<T> specifiedClass){
		Map<Name, Object> map = getParserContextScopeTreeMap(parser);
		Object object = map.get(name);
		if(object == null){
			return Optional.empty();
		}
		return Optional.of(specifiedClass.cast(object));
	}
	
	@SuppressWarnings("unchecked")
	public default <T> List<T> getList(Parser parser , Name name , Class<T> specifiedClass){
		Map<Name, Object> map = getParserContextScopeTreeMap(parser);
		List<T> list = (List<T>) map.get(name);
		return list == null? Collections.emptyList() : list;
	}

	
	public default void put(Parser parser , Object object){
		put(parser, nameLess, object);
	}
	
	public default void put(Parser parser , Name name , Object object){
		Map<Name, Object> map = getParserContextScopeTreeMap(parser);
		map.put(name, object);
	}
	
	public default Optional<Object> remove(Parser parser){
		return remove(parser,nameLess);
		
	}
	
	public default Optional<Object> remove(Parser parser , Name name){
		Map<Name, Object> map = getParserContextScopeTreeMap(parser);
		return Optional.of(map.remove(name));
	}
	
	public default void removeAll(Parser parser){
		Map<Name, Object> map = getParserContextScopeTreeMap(parser);
		map.clear();
		getParserContextScopeTreeMap().remove(parser);
	}
	
	public default boolean containsKey(Parser parser , Name name){
		Map<Name, Object> map = getParserContextScopeTreeMap(parser);
		return map.containsKey(name);
	}
	
	public default boolean containsValue(Parser parser , Object object){
		Map<Name, Object> map = getParserContextScopeTreeMap(parser);
		return map.containsValue(object);
	}
	
	public default boolean containsParser(Parser parser){
		return getParserContextScopeTreeMap().containsKey(parser);
	}
}