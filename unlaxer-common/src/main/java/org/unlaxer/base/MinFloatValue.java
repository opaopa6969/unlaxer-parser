package org.unlaxer.base;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

public interface MinFloatValue {
	
	public default float minFloatValue(){
		MinFloatValue._MinFloatValue annotation = getClass().getAnnotation(MinFloatValue._MinFloatValue.class);
		return annotation == null ? 
				Float.MIN_VALUE : 
				annotation.value();
	}
	
	@Retention(RetentionPolicy.RUNTIME)
	@Target(ElementType.TYPE)
	public @interface _MinFloatValue{
		float  value();
	}
}