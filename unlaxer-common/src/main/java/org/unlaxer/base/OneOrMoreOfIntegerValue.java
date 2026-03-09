package org.unlaxer.base;

public interface OneOrMoreOfIntegerValue extends MinIntegerValue , MaxIntegerValue{

	@Override
	default int minIntegerValue() {
		return 1;
	}
}