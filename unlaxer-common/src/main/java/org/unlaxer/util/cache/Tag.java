package org.unlaxer.util.cache;
import java.util.function.Function;

import org.unlaxer.util.Either;

public class Tag extends Either<Enum<?>, String>{
	
	final String nameAsString;
	
	final int hashCode;

	Tag(Enum<?> left, String right) {
		super(left, right);
		nameAsString = apply(Enum::name, Function.identity());
		hashCode = nameAsString.hashCode();
	}
	
	public static Tag rightOf(String right){
		if(right == null){
			throw new IllegalArgumentException("must be not null");
		}
		return new Tag(null, right);
	}
	
	public static Tag rightOfNullable(String right){
		return new Tag(null, right);
	}

	
	public static Tag leftOf(Enum<?> left){
		if(left == null){
			throw new IllegalArgumentException("must be not null");
		}
		return new Tag(left,null);
	}
	
	public static Tag leftOfNullable(Enum<?> left){
		return new Tag(left,null);
	}

	@Override
	public String toString() {
		return nameAsString;
	}

	@Override
	public int hashCode() {
		return hashCode;
	}

	@Override
	public boolean equals(Object obj) {
		if(obj == null) {
			return false;
		}
		if(obj instanceof Tag) {
			return nameAsString.equals(((Tag)obj).toString());
		}
		return false;
	}
}