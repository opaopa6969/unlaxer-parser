package org.unlaxer.util;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Function;

public class NullSafetyConcurrentHashMap<K, V> extends ConcurrentHashMap<K, V> {

  private static final long serialVersionUID = 3071525148285814641L;

  @SuppressWarnings("unchecked")
  final V NULL_VALUE = (V) new Object();
  @SuppressWarnings("unchecked")
  final K NULL_KEY = (K) new Object();

  public NullSafetyConcurrentHashMap() {
    super();
  }

  public NullSafetyConcurrentHashMap(int initialCapacity, float loadFactor, int concurrencyLevel) {
    super(initialCapacity, loadFactor, concurrencyLevel);
  }

  public NullSafetyConcurrentHashMap(int initialCapacity, float loadFactor) {
    super(initialCapacity, loadFactor);
  }

  public NullSafetyConcurrentHashMap(int initialCapacity) {
    super(initialCapacity);
  }

  public NullSafetyConcurrentHashMap(Map<? extends K, ? extends V> m) {
    super(m);
  }

  @Override
  public V put(K key, V value) {
    if (key == null || value == null) {
      return value;
    }
    key = safeKey(key);
    value = safeValue(value);
    return getValue(super.put(key, value));
  }

  @Override
  public V putIfAbsent(K key, V value) {
    if (key == null || value == null) {
      return value;
    }
    key = safeKey(key);
    value = safeValue(value);
    return super.putIfAbsent(key, value);
  }



  @Override
  public V computeIfAbsent(K key, Function<? super K, ? extends V> mappingFunction) {
    if (key == null) {
      return null;
    }
    key = safeKey(key);
    return getValue(super.computeIfAbsent(key, mappingFunction));
  }

  @Override
  public V computeIfPresent(K key,
      BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
    if (key == null) {
      return null;
    }
    key = safeKey(key);
    return getValue(super.computeIfPresent(key, remappingFunction));
  }

  @Override
  public V compute(K key, BiFunction<? super K, ? super V, ? extends V> remappingFunction) {
    if (key == null) {
      return null;
    }
    key = safeKey(key);
    return getValue(super.compute(key, remappingFunction));
  }

  @Override
  public V remove(Object key) {
    if (key == null) {
      return null;
    }
    key = key == null ? NULL_KEY : key;
    return getValue(super.remove(key));
  }

  @Override
  public boolean remove(Object key, Object value) {
    if (key == null || value == null) {
      return false;
    }
    key = key == null ? NULL_KEY : key;
    value = value == null ? NULL_VALUE : value;
    return super.remove(key, value);
  }

  @Override
  public boolean replace(K key, V oldValue, V newValue) {
    if (key == null || oldValue == null || newValue == null) {
      return false;
    }
    key = safeKey(key);
    oldValue = safeValue(oldValue);
    newValue = safeValue(newValue);
    return super.replace(key, oldValue, newValue);
  }

  @Override
  public V replace(K key, V value) {
    if (key == null || value == null) {
      return null;
    }
    key = safeKey(key);
    value = safeValue(value);
    return getValue(super.replace(key, value));
  }

  @Override
  public V getOrDefault(Object key, V defaultValue) {
    if (key == null) {
      return defaultValue;
    }
    key = key == null ? NULL_KEY : key;
    defaultValue = safeValue(defaultValue);
    return getValue(super.getOrDefault(key, defaultValue));
  }


  @Override
  public V merge(K key, V value, BiFunction<? super V, ? super V, ? extends V> remappingFunction) {
    if (key == null || value == null) {
      return value;
    }
    key = safeKey(key);
    value = safeValue(value);
    return getValue(super.merge(key, value, remappingFunction));
  }

  @Override
  public boolean containsKey(Object key) {
    if (key == null) {
      return false;
    }
    key = key == null ? NULL_KEY : key;
    return super.containsKey(key);
  }

  @Override
  public boolean containsValue(Object value) {
    if (value == null) {
      return false;
    }
    value = value == null ? NULL_VALUE : value;
    return super.containsValue(value);
  }

  @Override
  public V get(Object key) {
    if (key == null) {
      return null;
    }
    key = key == null ? NULL_KEY : key;
    return getValue(super.get(key));
  }

  K safeKey(K key) {
    return key == null ? NULL_KEY : key;
  }

  V safeValue(V value) {
    return value == null ? NULL_VALUE : value;
  }

  V getValue(V value) {
    return value == NULL_VALUE ? null : value;
  }

  K getKey(K key) {
    return key == NULL_KEY ? null : key;
  }
}
