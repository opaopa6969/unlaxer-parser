package org.unlaxer.util.annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.function.UnaryOperator;

import org.unlaxer.Token;

@Target({ElementType.METHOD , ElementType.LOCAL_VARIABLE , ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
/**
 * annotate method or class for token re-constructor
 **/
public @interface TokenReConstructor{
	
	public interface TokenReConstructorInterface extends UnaryOperator<Token>{

		Token apply(Token token);
	}

}
