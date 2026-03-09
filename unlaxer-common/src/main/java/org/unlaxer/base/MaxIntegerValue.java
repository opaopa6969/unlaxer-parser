package org.unlaxer.base;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

public interface MaxIntegerValue {
	
	public default int maxIntegerValue(){
		_MaxIntegerValue annotation = getClass().getAnnotation(_MaxIntegerValue.class);
		return annotation == null ? 
				Integer.MAX_VALUE : 
				annotation.value();
	}
	
	@Retention(RetentionPolicy.RUNTIME)
	@Target(ElementType.TYPE)
	public @interface _MaxIntegerValue{
		int value();
	}

}