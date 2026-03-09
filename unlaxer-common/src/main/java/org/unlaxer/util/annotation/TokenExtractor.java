package org.unlaxer.util.annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.METHOD , ElementType.LOCAL_VARIABLE})
@Retention(RetentionPolicy.RUNTIME)
/**
 * annotate method for token extractor in Parser
 **/
public @interface TokenExtractor{
	
	public enum Timing{
		CreateOperatorOperandTree,
		UseOperatorOperandTree
	}
	
	boolean specifiedTokenIsThisParser() default true;
	
	boolean isExtactedList() default false;
	
	Timing[] timings() default {Timing.CreateOperatorOperandTree};
}
