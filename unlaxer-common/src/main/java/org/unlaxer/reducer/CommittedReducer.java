package org.unlaxer.reducer;

import org.unlaxer.Committed;
import org.unlaxer.Token;

public interface CommittedReducer {

	public Token reduce(Committed committed);
}