package org.unlaxer.util.collection;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public interface TreeNodeList<T> extends List<TreeNode<T>>{
	
	public static <T> TreeNodeList<T> empty(){
		return new TreeNodeListImpl<T>();
	}
	
	public default Optional<T> findAsContent(Predicate<T> predicate){
		
		for (int i = 0 ; i < size() ; i ++) {
			TreeNode<T> treeNode = get(i);
			T content = treeNode.get();
			if(predicate.test(content)) {
				return Optional.of(content);
			}
		}
		return Optional.empty();
	}
	
	public default Optional<TreeNode<T>> find(Predicate<T> predicate){
		
		for (int i = 0 ; i < size() ; i ++) {
			TreeNode<T> treeNode = get(i);
			T content = treeNode.get();
			if(predicate.test(content)) {
				return Optional.of(treeNode);
			}
		}
		return Optional.empty();
	}
	
	public default List<T> unwrap() {
		return stream()
			.map(TreeNode::get)
			.collect(Collectors.toList());
	}
}