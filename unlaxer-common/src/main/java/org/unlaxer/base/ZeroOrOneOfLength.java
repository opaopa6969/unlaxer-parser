package org.unlaxer.base;

public interface ZeroOrOneOfLength extends MinLength , MaxLength{

	@Override
	default int maxLength() {
		return 1;
	}
}