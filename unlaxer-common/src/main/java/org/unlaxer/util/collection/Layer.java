package org.unlaxer.util.collection;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Layer<K,V> {//implements List<T>{
	
	final List<V> layers;
	
	final Map<K,V> layersByKey;

	public Layer() {
		super();
		layers = new ArrayList<>();
		layersByKey = new HashMap<>();
	}

	public Layer<K,V> addLayer(K key ,V value){
		layersByKey.put(key, value);
		layers.add(value);
		return this;
	}
	
	public V get(K key) {
		return layersByKey.get(key);
	}

	public List<V> values(){
		return layers;
	}
	
	public int size() {
		return layers.size();
	}
	
	public V getByIndex(int index) {
		return layers.get(index);
	}
	
}