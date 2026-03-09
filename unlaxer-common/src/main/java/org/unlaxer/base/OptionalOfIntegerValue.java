package org.unlaxer.base;

public interface OptionalOfIntegerValue extends MinIntegerValue , MaxIntegerValue{

	@Override
	default int maxIntegerValue() {
		return 1;
	}
}