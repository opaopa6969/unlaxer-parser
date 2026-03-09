package org.unlaxer.util;
import java.util.Optional;
import java.util.function.Function;

public class Either<L, R> {
	
	
	public final Optional<L> left;
	public final Optional<R> right;
	
	protected Either(L left, R right) {
		super();
		this.left = Optional.ofNullable(left);
		this.right = Optional.ofNullable(right);
		if(this.left.isPresent() ^ this.right.isPresent()) {
			
		}else {
			throw new IllegalArgumentException(
				"left is " + this.left.isPresent() + " / right is " + this.right.isPresent()
			);
		}
	}
	
	public static <L, R> Either<L, R> rightOf(R right){
		if(right == null){
			throw new IllegalArgumentException("must be not null");
		}
		return new Either<L, R>(null, right);
	}
	
	public static <L, R> Either<L, R> rightOfNullable(R right){
		return new Either<L, R>(null, right);
	}

	
	public static <L, R>  Either<L, R> leftOf(L left){
		if(left == null){
			throw new IllegalArgumentException("must be not null");
		}
		return new Either<L, R>(left,null);
	}
	
	public static <L, R>  Either<L, R> leftOfNullable(L left){
		return new Either<L, R>(left,null);
	}

	
	public <V> V apply(Function<L, V> leftFunction, Function<R,V> rightFunction){
		return left.map(leftFunction).orElseGet(()->rightFunction.apply(right.get()));
	}
}
