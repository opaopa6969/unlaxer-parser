package org.unlaxer.util.annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.unlaxer.parser.Parser;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
/**
 * annotate method for token extractor in Parser
 **/
public @interface RelatedParser{
	
	Class<? extends Parser>[] value();
	
}
