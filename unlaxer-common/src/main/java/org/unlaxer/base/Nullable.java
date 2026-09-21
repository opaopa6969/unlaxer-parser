package org.unlaxer.base;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

public interface Nullable{
	
	/* Read once per class; the annotation proxy lookup was a per-call cost (#270). */
	ClassValue<Boolean> NULLABLE_BY_CLASS = new ClassValue<>() {
		@Override
		protected Boolean computeValue(Class<?> type) {
			_Nullable annotation = type.getAnnotation(_Nullable.class);
			return annotation == null ? Boolean.FALSE : Boolean.valueOf(annotation.value());
		}
	};

	public default boolean nullable(){
		return NULLABLE_BY_CLASS.get(getClass());
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