package org.unlaxer.util.collection;

import java.util.Comparator;

public class Comparators {
	
	public static final Comparator<String> longerIsFirst = (x,y)->{
		int diff = y.length() - x.length();
		if(diff !=0) {
			return diff;
		}
		return y.compareTo(x);
	};	

	public static final Comparator<String> shoterIsFirst = (y,x)->{
		int diff = y.length() - x.length();
		if(diff !=0) {
			return diff;
		}
		return y.compareTo(x);
	};

}
