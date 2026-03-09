package org.unlaxer.util.collection;

import java.util.ArrayList;
import java.util.Collection;

public class TreeNodeListImpl<T> extends ArrayList<TreeNode<T>> implements TreeNodeList<T>{

	private static final long serialVersionUID = 7331840463435053001L;

	public TreeNodeListImpl() {
		super();
	}

	public TreeNodeListImpl(Collection<? extends TreeNode<T>> c) {
		super(c);
	}

	public TreeNodeListImpl(int initialCapacity) {
		super(initialCapacity);
	}
}