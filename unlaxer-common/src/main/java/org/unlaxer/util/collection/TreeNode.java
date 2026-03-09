package org.unlaxer.util.collection;

import java.io.Serializable;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;

public interface TreeNode<T> extends Serializable , IDAccessor{
	
	public T get();
	
	public TreeNode<T> root();
	
	public boolean isRoot();
	
	public boolean isLeaf();
	
	public ID id();
	
	public Optional<TreeNode<T>> parent();
	
	public TreeNodeList<T> children();
	
	public void setParent(TreeNode<T> parent);
	
	public void addChild(TreeNode<T> child);
	
	public void addChild(int index , TreeNode<T> child);
	
	public void addChildren(TreeNodeList<T> children);
	
	public boolean removeChild(ID targetId);
	
	public boolean resetChild(ID targetId , TreeNode<T> newChild);
	
	public Optional<TreeNode<T>> find(ID targetId);
	
	public Optional<TreeNode<T>> find(Predicate<TreeNode<T>> predicate);
	
	public Optional<TreeNode<T>> findWithContent(Predicate<T> predicate);
	
	public default TreeNode<T> copy(){
		return copy(this);
	}
	
	public TreeNode<T> copy(TreeNode<T> rootNode);
	
	public <X> TreeNode<X> transform(Function<T,X> transfrmer);
	
	public TreeNodeList<T> leafs();
	
	public static <T> TreeNode<T> of(ID id, T object) {
		return new TreeNodeImpl<T>(id, object);
	}

	public static <T> TreeNode<T> of(ID id, T object , TreeNode<T> parent ) {
		return new TreeNodeImpl<T>(id, object , parent);
	}
	
	public void resetObject(T object);
	
	public Stream<TreeNode<T>> stream();

	public List<TreeNode<T>> list();
}