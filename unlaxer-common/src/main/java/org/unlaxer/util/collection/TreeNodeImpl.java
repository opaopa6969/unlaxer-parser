package org.unlaxer.util.collection;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;


public class TreeNodeImpl<T> implements TreeNode<T> {
	
	private static final long serialVersionUID = -621543628695213362L;
	
	TreeNode<T> parent;

	public final ID id;
	
	public T object;
	
	public final TreeNodeList<T> children;
	
	public TreeNodeImpl(ID id, T object) {
		this(id,object,null);
	}

	public TreeNodeImpl(ID id, T object , TreeNode<T> parent ) {
		super();
		this.id = id;
		this.parent = parent;
		this.object = object;
		children = new TreeNodeListImpl<>();
	}

	@Override
	public ID id() {
		return id;
	}

	@Override
	public T get() {
		return object;
	}

	@Override
	public boolean isRoot() {
		
		return parent == null;
	}

	@Override
	public boolean isLeaf() {
		return children.isEmpty();
	}

	@Override
	public Optional<TreeNode<T>> parent() {
		return Optional.ofNullable(parent);
	}

	@Override
	public TreeNodeList<T> children() {
		return children;
	}


	@Override
	public void setParent(TreeNode<T> parent) {
		this.parent = parent;
	}

	@Override
	public void addChild(TreeNode<T> child) {
		child.setParent(this);
		this.children.add(child);
	}

	@Override
	public TreeNode<T> copy(TreeNode<T> rootNode) {
		
		TreeNode<T> clone = addChildrenWithClone(
				rootNode , 
				rootNode.parent().orElse(null) , 
				children);

		return clone;
	}

	TreeNode<T> addChildrenWithClone(
			TreeNode<T> target ,
			TreeNode<T> parent , 
			List<TreeNode<T>> children) {
		
		TreeNode<T> clone = new TreeNodeImpl<>(target.id(), target.get() , parent );
		
		for (TreeNode<T> child : children) {
			
			TreeNode<T> copyOfChild = child.copy();
			
			TreeNode<T> newChild = addChildrenWithClone(
					copyOfChild , 
					clone ,
					child.children());
			
			clone.addChild(newChild);
		}
		return clone;
	}

	@Override
	public TreeNodeList<T> leafs() {
		return leafs(new TreeNodeListImpl<>() , this);
	}
	
	TreeNodeList<T> leafs(TreeNodeList<T> leafs,TreeNode<T> target) {
		
		if(target.isLeaf()) {
			leafs.add(target);
			return leafs;
		}
		
		for (TreeNode<T> treeNode : target.children()) {
			leafs = leafs(leafs, treeNode);
		}
		return leafs;
	}

	@Override
	public void addChildren(TreeNodeList<T> children) {
		
		for (TreeNode<T> child : children) {
			
			addChild(child);
		}
	}
	
	@Override
	public Optional<TreeNode<T>> find(ID targetId) {
		Predicate<TreeNode<T>> predicate = treeNode-> treeNode.id().equals(targetId);
		return find(this , predicate);
	}

	@Override
	public Optional<TreeNode<T>> find(Predicate<TreeNode<T>> predicate) {
		return find(this , predicate);
	}
	
	public Optional<TreeNode<T>> find(TreeNode<T> targetTreeNode , Predicate<TreeNode<T>> predicate) {
		
		if(predicate.test(targetTreeNode)) {
			return Optional.of(targetTreeNode);
		}
		
		for (TreeNode<T> childTreeNode : targetTreeNode.children()) {
			
			Optional<TreeNode<T>> find = find(childTreeNode , predicate);
			if(find.isPresent()) {
				return find;
			}
		}
		return Optional.empty();
	}
	
	@Override
	public void resetObject(T object) {
		this.object = object;
	}

	@Override
	public Optional<TreeNode<T>> findWithContent(Predicate<T> predicate) {
		return findWithContent(this , predicate);
	}
	
	public Optional<TreeNode<T>> findWithContent(TreeNode<T> targetTreeNode , Predicate<T> predicate) {
		
		if(predicate.test(targetTreeNode.get())) {
			return Optional.of(targetTreeNode);
		}
		
		for (TreeNode<T> childTreeNode : targetTreeNode.children()) {
			
			Optional<TreeNode<T>> find = findWithContent(childTreeNode , predicate);
			if(find.isPresent()) {
				return find;
			}
		}
		return Optional.empty();
	}

	@Override
	public void addChild(int index, TreeNode<T> child) {
		child.setParent(this);
		this.children.add(index,child);
	}

	@Override
	public <X> TreeNode<X> transform(Function<T, X> transfrmer) {
		
		TreeNode<X> clone = addChildrenWithClone(this , null , children , transfrmer);
		return clone;
	}
	
	<X> TreeNode<X> addChildrenWithClone(
			TreeNode<T> target ,
			TreeNode<X> parent , 

			List<TreeNode<T>> children,
			Function<T,X> transformer) {
		
		TreeNode<X> clone = new TreeNodeImpl<>(
				target.id(),
				transformer.apply(target.get()),
				parent );
		
		for (TreeNode<T> child : children) {
			
			
			TreeNode<X> newChild = addChildrenWithClone(
					child , 
					clone ,
					child.children(),
					transformer);
			
			clone.addChild(newChild);
		}
		return clone;
	}

	@Override
	public TreeNode<T> root() {
		
		TreeNode<T> current = this;
		while (true) {
			if(current.parent().isEmpty()) {
				return current;
			}
			current = current.parent().get();
		}
	}
	
	@Override
	public Stream<TreeNode<T>> stream(){
		return list().stream();
	}
	
	@Override
	public List<TreeNode<T>> list(){
		
		List<TreeNode<T>> list = new ArrayList<>();
		list(this,list);
		return list;
	}
	
	void list(TreeNode<T> node  , List<TreeNode<T>> list){
		list.add(node);
		for(TreeNode<T> child:node.children()) {
			list(child , list);
		}
	}

	@Override
	public boolean removeChild(ID targetId) {
		
		for(int i = 0 ; i < children.size() ; i++) {
			TreeNode<T> treeNode = children.get(i);
			if(treeNode.id().equals(targetId)) {
				children.remove(i);
				return true;
			}
		}
		return false;
	}

	@Override
	public boolean resetChild(ID targetId, TreeNode<T> newChild) {
		
		for(int i = 0 ; i < children.size() ; i++) {
			TreeNode<T> treeNode = children.get(i);
			if(treeNode.id().equals(targetId)) {
				children.set(i, newChild);
				return true;
			}
		}
		return false;
	}
}