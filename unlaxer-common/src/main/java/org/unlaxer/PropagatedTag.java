package org.unlaxer;

import java.util.HashSet;
import java.util.Set;

public class PropagatedTag extends Tag{
	
	private static final long serialVersionUID = 2704725149428866550L;
	
	Tag tag;
	public final Set<PropagatedMode> propagatedModes = new HashSet<>();
	
	public PropagatedTag(Tag tag , PropagatedMode... propagatedModes) {
		super(tag.getName());
		this.tag = tag;
		for (PropagatedMode propagatedMode : propagatedModes) {
			this.propagatedModes.add(propagatedMode);
		}
	}

	public boolean equals(Object other) {
		return tag.equals(other);
	}

	public String toString() {
		return tag.toString();
	}

	public String getSimpleName() {
		return tag.getSimpleName();
	}

	public String getName() {
		return tag.getName();
	}

	public int hashCode() {
		return tag.hashCode();
	}
}