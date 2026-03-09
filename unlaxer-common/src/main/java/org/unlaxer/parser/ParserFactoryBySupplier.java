package org.unlaxer.parser;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

import org.unlaxer.util.FactoryBoundCache;

public class ParserFactoryBySupplier{
	
	static FactoryBoundCache<Supplier<? extends Parser>, Parser>//
		singletonsBySupplier = new FactoryBoundCache<>((supplier) -> {
			try {
				Parser parser = supplier.get();
				return parser;
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		});
	
	@SuppressWarnings("unchecked")
	public static <T extends Parser> T get(Supplier<? extends Parser> supplier) {
		T parser = (T) singletonsBySupplier.get(supplier);
		if(false == initialized(supplier)) {
			initilizedBySupplier.put(supplier, true);
//			parser.initialize();
		}
		return parser;
	}
	
	static Map<Supplier<? extends Parser> , Boolean> 
		initilizedBySupplier = new HashMap<>();
	
	static boolean initialized(Supplier<? extends Parser> supplier) {
		return initilizedBySupplier.getOrDefault(supplier, false);
	}
}