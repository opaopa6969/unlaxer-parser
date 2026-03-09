package org.unlaxer.reducer;

import org.unlaxer.parser.LazyInstance;
import org.unlaxer.parser.MetaFunctionParser;
import org.unlaxer.parser.Parser;
import org.unlaxer.reducer.TagBasedReducer.NodeKind;

public class MetaFunctionTokenReducer extends AbstractTokenReducer  {

	@Override
	public boolean doReduce(Parser parser) {
		return //
				parser.hasTag(NodeKind.notNode.getTag()) ||
				(parser instanceof MetaFunctionParser && //
				false == parser instanceof LazyInstance);
	}

}