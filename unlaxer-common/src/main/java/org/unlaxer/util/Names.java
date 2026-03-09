package org.unlaxer.util;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.unlaxer.Name;

public class Names{
	
	List<Name> names;
	
	String name;

	public Names(List<Name> names) {
		super();
		this.names = names;
		name = names.stream().map(Name::toString).collect(Collectors.joining("/"));
	}
	
	public Names(Name... names) {
		this(Arrays.asList(names));
	}
	
	public String toString(){
		return name;
	}
	
	/** 
	 * check equivalent
	 */
	@Override
	public boolean equals(Object other) {
		
		if(other instanceof Names){
			return ((Names)other).toString().equals(toString());
		}
		return false;
	}

}