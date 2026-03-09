package org.unlaxer.base;

import java.io.Serializable;

public class FloatValue implements Comparable<FloatValue> , Serializable , MinFloatValue , MaxFloatValue , Nullable , MinLength , MaxLength{
	
	private static final long serialVersionUID = -8975634929994393082L;
	
	public final float value;

	public FloatValue(float value) {
		super();
		this.value = value;
		if(minFloatValue() > value || maxFloatValue() <value) {
			throw new IllegalArgumentException(
				"value is out of range(" + minFloatValue() + " - " + maxFloatValue() + "):" + value);
		}
		
		int numberOfDigits = (int) ((value == 0 ? 0 :Math.log10(value))+1);
		if(minLength() > numberOfDigits|| maxLength() < numberOfDigits) {
			throw new IllegalArgumentException(
				"number of value's digits is out of range(" + minLength() + " - " + maxLength() + "):" + value);
		}
		
	}

	@Override
	public int compareTo(FloatValue other) {
		return Float.compare(value, other.value);
	}


	@Override
	public int hashCode() {
		return Float.hashCode(value);
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		FloatValue other = (FloatValue) obj;
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