package org.unlaxer.base;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

public interface MaxByteValue {
	
	public default byte maxByteValue(){
		_MaxByteValue annotation = getClass().getAnnotation(_MaxByteValue.class);
		return annotation == null ? 
				Byte.MAX_VALUE : 
				annotation.value();
	}
	
	@Retention(RetentionPolicy.RUNTIME)
	@Target(ElementType.TYPE)
	public @interface _MaxByteValue{
		byte value();
	}

}