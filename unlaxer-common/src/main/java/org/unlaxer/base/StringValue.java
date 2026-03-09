package org.unlaxer.base;

import java.io.Serializable;

public class StringValue implements Comparable<StringValue> , Serializable , MinLength , MaxLength , Nullable{

	private static final long serialVersionUID = 5145706711655155935L;
	
	public final String value;
	
	int hashCode;

	public StringValue(String value) {
		super();
		if(value == null) {
			throw new IllegalArgumentException(messageIfNull());
		}
		this.value = value;
		hashCode = value.hashCode();
		
		int length = value.length();
		if(minLength() > length || maxLength() <length) {
			throw new IllegalArgumentException(
				"value length is out of range(" + minLength() + " - " + maxLength() + "):" + length + "/ values is " + value);
		}
	}

	@Override
	public int compareTo(StringValue other) {
		return value.compareTo(other.value);
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
		StringValue other = (StringValue) obj;
		if (value == null) {
			if (other.value != null)
				return false;
		} else if (!value.equals(other.value))
			return false;
		return true;
	}

	@Override
	public String toString() {
		return value;
	}

	@Override
	public final boolean nullable() {
		return false;
	}

	public String getValue() {
		return value;
	}
}
	
