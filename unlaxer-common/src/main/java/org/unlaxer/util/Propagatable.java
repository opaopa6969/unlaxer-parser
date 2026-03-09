package org.unlaxer.util;

import java.util.Optional;


public interface Propagatable<T> {
	
//	public T getDefaultValue();
	
	public Optional<? extends Propagatable<T>> getParentNode();
	
//	public List<Propagatable<T>> getChildNodes();
	
	public boolean doPropagateToChild();
	
	public T getThisNodeOrignalValue();
	
	public default T getValue(){
		Optional<? extends Propagatable<T>> parentNodeOptional = getParentNode();
		if(false == parentNodeOptional.isPresent()){
			return getThisNodeOrignalValue();
		}
		Propagatable<T> parent = parentNodeOptional.get();
		boolean doPropagateToChild = parent.doPropagateToChild();
		if(false == doPropagateToChild){
			return getThisNodeOrignalValue(); 
		}
		return merge(parent.getValue());
	}
	
	public T merge(T fromParentValue , T fromThisNodeValue);
	
	public default T merge(T fromParentValue){
		return merge(fromParentValue , getThisNodeOrignalValue());
	}
}
