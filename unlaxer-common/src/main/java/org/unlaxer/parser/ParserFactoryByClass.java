package org.unlaxer.parser;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.unlaxer.ast.ASTNodeKind;
import org.unlaxer.util.FactoryBoundCache;

public class ParserFactoryByClass{
	
	static FactoryBoundCache<Class<? extends Parser>, Parser>//
		singletonsByClass = new FactoryBoundCache<>((clazz) -> {
			try {
				Parser parser = clazz.getDeclaredConstructor().newInstance();
				return parser;
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		});
	
	@SuppressWarnings("unchecked")
	public static <T extends Parser> T get(Class<T> clazz) {
		T parser = (T) singletonsByClass.get(clazz);
		if(false == initialized(clazz)) {
			initilizedByClass.put(clazz, true);
//			parser.initialize();
		}
		return parser;
	}
	
	@SuppressWarnings("unchecked")
	public static <T extends Parser> T get(ASTNodeKind nodeKind , Class<T> clazz ) {
		ClassAndKind key = new ClassAndKind(nodeKind , clazz);
		T parser = (T) singletonsByClassAndKind.get(key);
		if(false == initialized(key)) {
			initilizedByClassAndKind.put(key, true);
//			parser.initialize();
		}
		return parser;
	}

	
	static Map<Class<? extends Parser> , Boolean> 
		initilizedByClass = new HashMap<>();
	
	static Map<ClassAndKind , Boolean> 
		initilizedByClassAndKind = new HashMap<>();

	
	static boolean initialized(Class<? extends Parser> clazz) {
		return initilizedByClass.getOrDefault(clazz, false);
	}
	
	static boolean initialized(ClassAndKind clazzAndKind) {
		return initilizedByClassAndKind.getOrDefault(clazzAndKind, false);
	}
	
	public static <T extends Parser> T newInstance(Class<T> clazz) {
		try {
			T parser = clazz.getDeclaredConstructor().newInstance();
			return parser;
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	static FactoryBoundCache<ClassAndKind,Parser>//
	singletonsByClassAndKind = new FactoryBoundCache<>((classAndKind) -> {
		try {
			Parser parser = classAndKind.parserClass.getDeclaredConstructor().newInstance();
			classAndKind.kind().map(ASTNodeKind::tag).ifPresent(parser::addTag);
			return parser;
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	});
	
	public static class ClassAndKind{
		
		public final Class<? extends Parser> parserClass;
		ASTNodeKind kind;
		public ClassAndKind(ASTNodeKind kind , Class<? extends Parser> parserClass) {
			super();
			this.parserClass = parserClass;
			this.kind = kind;
			hashCode = (parserClass.getCanonicalName()+kind.name()).hashCode();
		}
		
		public ClassAndKind(Class<? extends Parser> parserClass) {
			super();
			this.parserClass = parserClass;
			this.kind = null;
			hashCode = (parserClass.getCanonicalName()).hashCode();
		}
		
		int hashCode;
		
		@Override
		public int hashCode() {
			return hashCode;
		}
		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (obj == null)
				return false;
			if (getClass() != obj.getClass())
				return false;
			ClassAndKind other = (ClassAndKind) obj;
			return kind == other.kind && Objects.equals(parserClass, other.parserClass);
		}

		public Optional<ASTNodeKind> kind(){
			return Optional.of(kind);
		}
	}
	
}