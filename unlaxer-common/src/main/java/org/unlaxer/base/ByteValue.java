package org.unlaxer.base;

import java.io.Serializable;

public class ByteValue implements Comparable<ByteValue> , Serializable , MinByteValue , MaxByteValue , Nullable , MinLength , MaxLength{
	
	private static final long serialVersionUID = 3439530857418827607L;
	
	public final byte value;

	public ByteValue(byte value) {
		super();
		this.value = value;
		if(minByteValue() > value || maxByteValue() <value) {
			throw new IllegalArgumentException(
				"value is out of range(" + minByteValue() + " - " + maxByteValue() + "):" + value);
		}
		
		int numberOfDigits = (int) ((value == 0 ? 0 :Math.log10(value))+1);
		if(minLength() > numberOfDigits|| maxLength() < numberOfDigits) {
			throw new IllegalArgumentException(
				"number of value's digits is out of range(" + minLength() + " - " + maxLength() + "):" + value);
		}
		
	}

	@Override
	public int compareTo(ByteValue other) {
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
		ByteValue other = (ByteValue) obj;
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