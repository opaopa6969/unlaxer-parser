package org.unlaxer.util.cache;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public abstract class BoundCache{
	
	private static Collection<BoundCache> caches =  new ArrayList<BoundCache>();
	
	private static Map<String,Collection<BoundCache>> cachesByNamme = new HashMap<>();
	
	NameAndTags nameAndTags;
	
	public BoundCache(NameAndTags nameAndTags) {
		super();
		this.nameAndTags = nameAndTags;
		add(nameAndTags , this);
		
	}
	
	public abstract void clear();

	public static void add(NameAndTags nameAndTags, BoundCache boundCache) {
		caches.add(boundCache);

		nameAndTags.tags.get().stream().map(Tag::toString).forEach(nameAsString->{
			cachesByNamme
			.computeIfAbsent(nameAsString, x-> new ArrayList<>())
			.add(boundCache);
		});
	}

	public static synchronized int clearAllCaches(){
		int size = caches.size();
		caches.stream().parallel().forEach(BoundCache::clear);
		return size;
	}
	
	public static synchronized int clearCaches(Tag name){
		
		AtomicInteger counts = new AtomicInteger();
		String nameAString = name.toString();
		Set<BoundCache> collectByTag = cachesByNamme.entrySet().stream()
			.filter(entry->{
				return entry.getKey().equals(nameAString);
			})
			.map(Entry::getValue)
			.flatMap(Collection::stream)
			.distinct()
			.peek(x->counts.incrementAndGet())
			.collect(Collectors.toSet());
//			.forEach(BoundCache::clear);
		
		Set<BoundCache> collectByName = caches.stream()
			.filter(cache->cache.toString().equals(nameAString))
			.distinct()
			.peek(x->counts.incrementAndGet())
			.collect(Collectors.toSet());
		
		collectByName.addAll(collectByTag);
		
		collectByName.stream()
			.forEach(BoundCache::clear);
		
		return counts.get();
	}
	
	public static Map<String,Collection<BoundCache>> getCachesByNamme(){
		return cachesByNamme;
	}

	
	public NameAndTags nameAndTags() {
		return nameAndTags;
	}
	
	public enum ReturningStrategy{
		returnOptionalWhenNotPrepared,
		blockingWhenNotPrepared,
	}
	
	public enum updatingStrategy{
		updateWithDoubleBuffer,
		updateWithSinglebuffer,
	}

	@Override
	public String toString() {
		return nameAndTags.name;
	}
}