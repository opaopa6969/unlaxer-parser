package org.unlaxer.base;

import java.io.Serializable;

public class LongValue implements Comparable<LongValue> , Serializable , MinLongValue , MaxLongValue , Nullable , MinLength , MaxLength{
	
	private static final long serialVersionUID = -6867387784673301172L;
	
	public final long value;

	public LongValue(long value) {
		super();
		this.value = value;
		if(minLongValue() > value || maxLongValue() <value) {
			throw new IllegalArgumentException(
				"value is out of range(" + minLongValue() + " - " + maxLongValue() + "):" + value);
		}
		
		int numberOfDigits = (int) ((value == 0 ? 0 :Math.log10(value))+1);
		if(minLength() > numberOfDigits|| maxLength() < numberOfDigits) {
			throw new IllegalArgumentException(
				"number of value's digits is out of range(" + minLength() + " - " + maxLength() + "):" + value);
		}
		
	}

	@Override
	public int compareTo(LongValue other) {
		return Long.compare(value ,other.value );
	}


	@Override
	public int hashCode() {
		return Long.hashCode(value);
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		LongValue other = (LongValue) obj;
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