package org.unlaxer.base;

import java.io.Serializable;
import java.util.Objects;
import java.util.Optional;

public class Triplet<T> implements Comparable<Triplet<T>> , Serializable{
	
	private static final long serialVersionUID = -4269236485816513769L;
	
	final T left;
	final T center;
	final T right;
	final int hashCode;
	
	public Triplet(T left, T center, T right) {
		super();
		this.left = left;
		this.center = center;
		this.right = right;
        int result = 1;
        result = 31 * result + Objects.hash(left);
        result = 31 * result + Objects.hash(center);
        result = 31 * result + Objects.hash(right);
        hashCode = result;
	}
	
	public Optional<T> left(){
		return Optional.ofNullable(left);
	}
	
	public Optional<T> center(){
		return Optional.ofNullable(center);
	}

	public Optional<T> right(){
		return Optional.ofNullable(right);
	}

	
	@SuppressWarnings("unchecked")
    private static <U1 extends Comparable<? super U1>> int compareTo(Triplet<?> o1, Triplet<?> o2) {
        final Triplet<U1> t1 = (Triplet<U1>) o1;
        final Triplet<U1> t2 = (Triplet<U1>) o2;

        final int check1 = t1.left.compareTo(t2.left);
        if (check1 != 0) {
            return check1;
        }

        final int check2 = t1.center.compareTo(t2.center);
        if (check2 != 0) {
            return check2;
        }

        final int check3 = t1.right.compareTo(t2.right);
        if (check3 != 0) {
            return check3;
        }
        return 0;
    }

	@Override
	public int compareTo(Triplet<T> other) {
		return Triplet.compareTo(this, other);
	}

	@Override
	public int hashCode() {
		return hashCode;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		@SuppressWarnings("unchecked")
		Triplet<T> other = (Triplet<T>) obj;
		if (center == null) {
			if (other.center != null)
				return false;
		} else if (!center.equals(other.center))
			return false;
		if (left == null) {
			if (other.left != null)
				return false;
		} else if (!left.equals(other.left))
			return false;
		if (right == null) {
			if (other.right != null)
				return false;
		} else if (!right.equals(other.right))
			return false;
		return true;
	}
}
