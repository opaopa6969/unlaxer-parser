package org.unlaxer.base;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

public interface MinLength{
	
	public default int minLength(){
		_MinLength annotation = getClass().getAnnotation(_MinLength.class);
		return annotation == null ? 
				0 : 
				annotation.value();
	}
	
	@Retention(RetentionPolicy.RUNTIME)
	@Target(ElementType.TYPE)
	public @interface _MinLength{
		int value();
	}
}