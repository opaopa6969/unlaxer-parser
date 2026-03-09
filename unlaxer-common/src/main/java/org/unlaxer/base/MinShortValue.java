package org.unlaxer.base;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

public interface MinShortValue {
	
	public default short minShortValue(){
		_MinShortValue annotation = getClass().getAnnotation(_MinShortValue.class);
		return annotation == null ? 
				Short.MIN_VALUE : 
				annotation.value();
	}
	
	@Retention(RetentionPolicy.RUNTIME)
	@Target(ElementType.TYPE)
	public @interface _MinShortValue{
		short value();
	}
}