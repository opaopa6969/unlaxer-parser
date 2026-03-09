package org.unlaxer.util;

import java.util.Map;
import java.util.function.Function;

public class FactoryBoundCache<K,V>{
	
	Map<K,V> valueByKey= new NullSafetyConcurrentHashMap<K,V>();
	
	Function<K,V> factory;
	
	public FactoryBoundCache(Function<K, V> factory) {
		super();
		this.factory = factory;
	}
	
	public synchronized V get(K key){
//		return valueByKey.computeIfAbsent(key, factory::apply);
		V v = valueByKey.get(key);
		if(v == null) {
			v = factory.apply(key);
			valueByKey.put(key, v);
		}
		return v;
	}
}