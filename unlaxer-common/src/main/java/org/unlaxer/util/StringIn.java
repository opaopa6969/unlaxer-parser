package org.unlaxer.util;

public class StringIn {
	
	public static boolean match(String base , String...inClause) {
		for (String in : inClause) {
			if(base.equals(in)) {
				return true;
			}
		}
		return false;
	}
	
}
