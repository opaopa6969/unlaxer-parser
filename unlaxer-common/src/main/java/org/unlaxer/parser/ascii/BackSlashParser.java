package org.unlaxer.parser.ascii;

import org.unlaxer.parser.StaticParser;
import org.unlaxer.parser.elementary.SingleStringParser;

public class BackSlashParser extends SingleStringParser implements StaticParser{

	private static final long serialVersionUID = 8372721532360299942L;

	@Override
	public boolean isMatch(String target) {
		return "\\".equals(target);
	}
}