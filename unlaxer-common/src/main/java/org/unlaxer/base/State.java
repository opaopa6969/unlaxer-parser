package org.unlaxer.base;

import java.util.Set;

public interface State<S> {

	Set<S> transitions();
	boolean canTransition(S toState);
	String explain();
}
