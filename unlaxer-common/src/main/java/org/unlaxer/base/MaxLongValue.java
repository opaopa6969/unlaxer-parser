package org.unlaxer.base;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

public interface MaxLongValue {
	
	public default long  maxLongValue(){
		_MaxLongValue annotation = getClass().getAnnotation(_MaxLongValue.class);
		return annotation == null ? 
				Long.MAX_VALUE : 
				annotation.value();
	}
	
	@Retention(RetentionPolicy.RUNTIME)
	@Target(ElementType.TYPE)
	public @interface _MaxLongValue{
		long value();
	}

}