package org.unlaxer.base;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

public interface MaxLength {
	
	public default int maxLength() {
		_MaxLength annotation = getClass().getAnnotation(_MaxLength.class);
		return annotation == null ?
				Integer.MAX_VALUE : 
				annotation.value();
	}

	@Retention(RetentionPolicy.RUNTIME)
	@Target(ElementType.TYPE)
	public @interface _MaxLength {
		int value();
	}
}