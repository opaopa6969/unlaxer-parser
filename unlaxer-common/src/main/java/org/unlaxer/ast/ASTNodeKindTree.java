package org.unlaxer.ast;

import org.unlaxer.util.collection.ID;
import org.unlaxer.util.collection.TreeNode;
import org.unlaxer.util.collection.TreeNodeImpl;

public class ASTNodeKindTree extends TreeNodeImpl<NodeKindAndParser>{

	private static final long serialVersionUID = 4326214225315480410L;
	
	public ASTNodeKindTree(ID id, NodeKindAndParser object) {
		super(id, object);
	}
	
	public ASTNodeKindTree(NodeKindAndParser object) {
		super(object.id(), object);
	}
	
	public ASTNodeKindTree(ID id, NodeKindAndParser object , TreeNode<NodeKindAndParser> parent) {
		super(id, object,parent);
	}
	
	public ASTNodeKindTree(NodeKindAndParser object , TreeNode<NodeKindAndParser> parent) {
		super(object.id(), object,parent);
	}
}