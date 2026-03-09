package org.unlaxer.util;

import org.unlaxer.Range;

public class StringUtil {
	
	public static String delete(String word, int position){
		int length = word.length();
		if(position < 0 || position+1 > length){
			return word;
		}
		return word.substring(0,position).concat(word.substring(position+1));
	}
	
	public static String delete(String word, Range range){
		int length = word.length();
		int startIndexInclusive = range.startIndexInclusive;
		int endIndexExclusive = range.endIndexExclusive;
		StringBuilder builder = new StringBuilder();
		
		if(startIndexInclusive > 0){
			builder.append(word.substring(0,startIndexInclusive));
		}
		if(endIndexExclusive <=length){
			builder.append(word.substring(endIndexExclusive));
		}
		return builder.toString();
	}
	
	public static String deleteAndInsert(String word, int position ,String insertion){
		
		return deleteAndInsert(word, new Range(position , position + 1), insertion);
	}

	
	public static String deleteAndInsert(String word, Range range ,String insertion){
		int length = word.length();
		int startIndexInclusive = range.startIndexInclusive;
		int endIndexExclusive = range.endIndexExclusive;
		StringBuilder builder = new StringBuilder();
		
		if(startIndexInclusive > 0){
			builder.append(word.substring(0,startIndexInclusive));
		}
		
		builder.append(insertion);
		
		if(endIndexExclusive <=length){
			builder.append(word.substring(endIndexExclusive));
		}
		return builder.toString();
	}

	
	public static String insert(String base , String insertion , int position){
		
		if(base.isEmpty()){
			return insertion;
		}
		
		StringBuilder builder = new StringBuilder();
		if(position > 0){
			builder.append(base.substring(0,position));
		}
		builder.append(insertion);
		
		if(position < base.length()){
			
			builder.append(base.substring(position));
		}
		return builder.toString();
	}
}
