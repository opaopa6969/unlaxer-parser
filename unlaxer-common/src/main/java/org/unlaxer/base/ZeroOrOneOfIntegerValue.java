package org.unlaxer.base;

public interface ZeroOrOneOfIntegerValue extends MinIntegerValue , MaxIntegerValue{

	@Override
	default int maxIntegerValue() {
		return 1;
	}
}