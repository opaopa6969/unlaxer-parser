package org.unlaxer.parser;

import java.util.Optional;

import org.unlaxer.util.Propagatable;

public class NodeReduceMarker implements Propagatable<Boolean> {
	
	Optional<NodeReduceMarker> parent;
	
	public NodeReduceMarker(Optional<NodeReduceMarker> parent) {
		super();
		this.parent = parent;
	}

	public NodeReduceMarker() {
		parent = Optional.empty();
	}

	@Override
	public Optional<? extends Propagatable<Boolean>> getParentNode() {
		return parent;
	}

	@Override
	public boolean doPropagateToChild() {
		return false;
	}

	@Override
	public Boolean getThisNodeOrignalValue() {
		return false;
	}

	@Override
	public Boolean merge(Boolean fromParentValue, Boolean fromThisNodeValue) {
		return fromParentValue;
	}
	
}