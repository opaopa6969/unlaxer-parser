package org.unlaxer.base;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

public interface MinIntegerValue {
	
	/*
	 * The annotation is a property of the class, so it is read once per class. Reading it on
	 * every call went through the annotation proxy (a LinkedHashMap lookup per call), which
	 * dominated cursor arithmetic in deep parses (#270).
	 */
	ClassValue<Integer> MININTEGERVALUE_BY_CLASS = new ClassValue<>() {
		@Override
		protected Integer computeValue(Class<?> type) {
			_MinIntegerValue annotation = type.getAnnotation(_MinIntegerValue.class);
			return annotation == null ? Integer.MIN_VALUE : annotation.value();
		}
	};

	public default int minIntegerValue(){
		return MININTEGERVALUE_BY_CLASS.get(getClass());
	}
	
	@Retention(RetentionPolicy.RUNTIME)
	@Target(ElementType.TYPE)
	public @interface _MinIntegerValue{
		int value();
	}
}