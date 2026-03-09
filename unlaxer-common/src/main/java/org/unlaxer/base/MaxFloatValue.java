package org.unlaxer.base;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

public interface MaxFloatValue {
	
	public default float maxFloatValue(){
		MaxFloatValue._MaxFloatValue annotation = getClass().getAnnotation(MaxFloatValue._MaxFloatValue.class);
		return annotation == null ? 
				Float.MAX_VALUE : 
				annotation.value();
	}
	
	@Retention(RetentionPolicy.RUNTIME)
	@Target(ElementType.TYPE)
	public @interface _MaxFloatValue{
		float value();
	}

}