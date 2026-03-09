package org.unlaxer.util;


public class Singletons {

	static FactoryBoundCache<Class<?>, Object>//
	singletons = new FactoryBoundCache<>((clazz) -> {
		try {
			return clazz.getDeclaredConstructor().newInstance();
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	});

	@SuppressWarnings("unchecked")
	public static <T> T get(Class<T> clazz) {
		return (T) singletons.get(clazz);
	}
	
}
