package org.unlaxer.base;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

public interface MaxShortValue {
	
	public default short maxShortValue(){
		_MaxShortValue annotation = getClass().getAnnotation(_MaxShortValue.class);
		return annotation == null ? 
				Short.MAX_VALUE : 
				annotation.value();
	}
	
	@Retention(RetentionPolicy.RUNTIME)
	@Target(ElementType.TYPE)
	public @interface _MaxShortValue{
		short value();
	}

}