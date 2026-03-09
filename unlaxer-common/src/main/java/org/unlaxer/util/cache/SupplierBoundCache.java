package org.unlaxer.util.cache;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class SupplierBoundCache<V> extends BoundCache implements Supplier<V>{
	
	V value;
	
	Supplier<V> supplier;
	
	long lastTime = 0L;
	
	final Long evictionTimeInMilli;
	
	Optional<Consumer<V>> consumerWhenClearing;
	
	boolean clear;

	
	public SupplierBoundCache(Supplier<V> supplier) {
		this(NameAndTags.of(supplier) , supplier);
	}
	
	public SupplierBoundCache(NameAndTags nameAndTags , Supplier<V> supplier) {
		this(nameAndTags , supplier , null);
	}
	
	public SupplierBoundCache(Supplier<V> supplier , Consumer<V> consumerWhenClearing) {
		this(NameAndTags.of(supplier) , supplier  , consumerWhenClearing);
	}
	
	public SupplierBoundCache(NameAndTags nameAndTags , Supplier<V> supplier , Consumer<V> consumerWhenClearing) {
		super(nameAndTags);
		this.supplier = supplier;
		evictionTimeInMilli = null;
		this.consumerWhenClearing = Optional.ofNullable(consumerWhenClearing);
	}
	
	public SupplierBoundCache(Supplier<V> supplier, int evictionTimeInMilli) {
		this(NameAndTags.of(supplier) , supplier , evictionTimeInMilli);
	}
	
	public SupplierBoundCache(NameAndTags nameAndTags , Supplier<V> supplier, int evictionTimeInMilli) {
		this(nameAndTags ,supplier , evictionTimeInMilli , null);
	}
	
	public SupplierBoundCache(Supplier<V> supplier, int evictionTimeInMilli , Consumer<V> consumerWhenClearing) {
		this(NameAndTags.of(supplier) , supplier , evictionTimeInMilli , consumerWhenClearing);
	}
	
	public SupplierBoundCache(NameAndTags nameAndTags ,Supplier<V> supplier, int evictionTimeInMilli , Consumer<V> consumerWhenClearing) {
		super(nameAndTags);
		this.supplier = supplier;
		this.evictionTimeInMilli = (long) evictionTimeInMilli;
		this.consumerWhenClearing = Optional.ofNullable(consumerWhenClearing);
	}
	
	public static <V>SupplierBoundCache<V> of(Supplier<V> supplier){
		return new SupplierBoundCache<>(NameAndTags.of(supplier) , supplier);
	}
	
	public static <V>SupplierBoundCache<V> of(NameAndTags nameAndTags ,Supplier<V> supplier){
		return new SupplierBoundCache<>(nameAndTags , supplier);
	}
	
	public synchronized V get(){
		
		if(evictionTimeInMilli == null){
			if(value != null){
				return value;
			}
			value = supplier.get();
			return value;
		}
		
		long now = System.currentTimeMillis();
		
		if(lastTime + evictionTimeInMilli < now || value == null){
			lastTime = now;
			value = supplier.get();
		}
		return value;
	}
	
	public synchronized void clear(){
		consumerWhenClearing.ifPresent(consumer->consumer.accept(value));
		value = null;
	}
	
}