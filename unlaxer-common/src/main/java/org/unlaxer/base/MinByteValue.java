package org.unlaxer.base;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

public interface MinByteValue {
	
	public default byte minByteValue(){
		_MinByteValue annotation = getClass().getAnnotation(_MinByteValue.class);
		return annotation == null ? 
				Byte.MIN_VALUE : 
				annotation.value();
	}
	
	@Retention(RetentionPolicy.RUNTIME)
	@Target(ElementType.TYPE)
	public @interface _MinByteValue{
		byte value();
	}
}