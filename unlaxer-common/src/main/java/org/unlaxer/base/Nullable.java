package org.unlaxer.base;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

public interface Nullable{
	
	public default boolean nullable(){
		
		_Nullable annotation = getClass().getAnnotation(_Nullable.class);
		return annotation == null ?
				false  : 
				annotation.value();
	}
	

	@Retention(RetentionPolicy.RUNTIME)
	@Target(ElementType.TYPE)
	public @interface _Nullable {
		boolean value();
	}
	
	default String messageIfNull() {
		return "値を指定する必要があります";
	}

}