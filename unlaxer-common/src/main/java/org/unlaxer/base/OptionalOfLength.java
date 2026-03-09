package org.unlaxer.base;

public interface OptionalOfLength extends MinLength , MaxLength{

	@Override
	default int maxLength() {
		return 1;
	}
}