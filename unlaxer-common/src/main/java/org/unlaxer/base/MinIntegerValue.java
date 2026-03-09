package org.unlaxer.base;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

public interface MinIntegerValue {
	
	public default int minIntegerValue(){
		_MinIntegerValue annotation = getClass().getAnnotation(_MinIntegerValue.class);
		return annotation == null ? 
				Integer.MIN_VALUE : 
				annotation.value();
	}
	
	@Retention(RetentionPolicy.RUNTIME)
	@Target(ElementType.TYPE)
	public @interface _MinIntegerValue{
		int value();
	}
}