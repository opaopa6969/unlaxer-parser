package org.unlaxer.ast;

import org.unlaxer.parser.Parser;
import org.unlaxer.util.collection.ID;

public class NodeKindAndParser{
		public final ASTNodeKind nodeKind;
		public final Parser parser;
		final int hashCode;
		public NodeKindAndParser(ASTNodeKind nodeKind, Parser parser) {
			super();
			this.nodeKind = nodeKind;
			this.parser = parser;
//			hashCode = Objects.hash(nodeKind, parser);
			hashCode = super.hashCode();
		}
		@Override
		public int hashCode() {
			return hashCode;
		}
		@Override
		public boolean equals(Object obj) {
			return super.equals(obj);
//			if (this == obj)
//				return true;
//			if (obj == null)
//				return false;
//			if (getClass() != obj.getClass())
//				return false;
//			NodeKindAndParser other = (NodeKindAndParser) obj;
//			return nodeKind == other.nodeKind && Objects.equals(parser, other.parser);
		}
		
		public ID id() {
			return ID.of(hashCode());
		}
	}