package org.unlaxer.util.cache;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.function.Function;

import org.unlaxer.util.NullSafetyConcurrentHashMap;

public class FactoryBoundCache<K,V> extends BoundCache implements Function<K, V>{
	
	Map<K,V> valueByKey = new NullSafetyConcurrentHashMap<>();
	
	Function<K,V> factory;
	
	Map<K,Long> lastTimeByKey = new NullSafetyConcurrentHashMap<>();
	
	final Long evictionTimeInMilli;
	
	boolean acceptClear = true;
	
	public FactoryBoundCache(Function<K, V> factory) {
		this(NameAndTags.of(factory),factory);
	}
	
	public FactoryBoundCache(NameAndTags nameAndTags , Function<K, V> factory) {
		super(nameAndTags);
		this.factory = factory;
		evictionTimeInMilli = null;
	}
	
	public FactoryBoundCache(Function<K, V> factory , int evictionTimeInMilli) {
		this(NameAndTags.of(factory) , factory , evictionTimeInMilli);
	}
	
	public FactoryBoundCache(NameAndTags nameAndTags ,Function<K, V> factory , int evictionTimeInMilli) {
		super(nameAndTags);
		this.factory = factory;
		this.evictionTimeInMilli = (long) evictionTimeInMilli;
	}
	
	
	public static <K,V> FactoryBoundCache<K,V> of(Function<K, V> factory){
		return of(NameAndTags.of(factory) , factory);
	}
	
	public static <K,V> FactoryBoundCache<K,V> of(NameAndTags nameAndTags ,Function<K, V> factory){
		return new FactoryBoundCache<>(nameAndTags,factory);
	}
	
	public synchronized V get(K key){
		
		if(evictionTimeInMilli == null){
			return valueByKey.computeIfAbsent(key, factory::apply);
		}
		
		long now = System.currentTimeMillis();
		Long last = lastTimeByKey.computeIfAbsent(key,k->0L);
		
		if(last + evictionTimeInMilli < now || false == valueByKey.containsKey(key)){

			lastTimeByKey.put(key, now);
			V value = factory.apply(key);
			valueByKey.put(key, value);
			return value;
		}
		return valueByKey.get(key);
	}
	
	public synchronized boolean clear(K key){
		
		if(false == acceptClear){
			return false;
		}
		V remove = valueByKey.remove(key);
		return remove != null;
	}
	
	public synchronized void clear(){
		if(false == acceptClear){
			return;
		}
		Map<K,V> old = valueByKey;
		valueByKey = new HashMap<>();
		old.clear();
	}
	
	public Set<Entry<K, V>> entrySet(){
		return valueByKey.entrySet();
	}

	@Override
	public V apply(K key) {
		return get(key);
	}
}