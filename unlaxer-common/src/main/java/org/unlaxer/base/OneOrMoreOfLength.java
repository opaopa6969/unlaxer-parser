package org.unlaxer.base;

public interface OneOrMoreOfLength extends MinLength , MaxLength{

	@Override
	default int minLength() {
		return 1;
	}
}