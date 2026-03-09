package org.unlaxer.base;

import java.io.Serializable;

public class ShortValue implements Comparable<ShortValue> , Serializable , MinShortValue , MaxShortValue , Nullable , MinLength , MaxLength{
	
	private static final long serialVersionUID = 3439530857418827607L;
	
	public final short value;

	public ShortValue(short value) {
		super();
		this.value = value;
		if(minShortValue() > value || maxShortValue() <value) {
			throw new IllegalArgumentException(
				"value is out of range(" + minShortValue() + " - " + maxShortValue() + "):" + value);
		}
		
		int  numberOfDigits = (int) ((value == 0 ? 0 :Math.log10(value))+1);
		if(minLength() > numberOfDigits|| maxLength() < numberOfDigits) {
			throw new IllegalArgumentException(
				"number of value's digits is out of range(" + minLength() + " - " + maxLength() + "):" + value);
		}
		
	}

	@Override
	public int compareTo(ShortValue other) {
		return value - other.value;
	}


	@Override
	public int hashCode() {
		return value;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		ShortValue other = (ShortValue) obj;
		if (value != other.value)
			return false;
		return true;
	}

	@Override
	public String toString() {
		return String.valueOf(value);
	}

	@Override
	public final boolean nullable() {
		return false;
	}
}