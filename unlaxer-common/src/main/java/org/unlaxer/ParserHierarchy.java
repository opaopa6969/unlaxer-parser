package org.unlaxer;

import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.unlaxer.parser.ChildOccurs;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.Parsers;
import org.unlaxer.parser.RootParserIndicator;
import org.unlaxer.parser.combinator.ConstructedCombinatorParser;
import org.unlaxer.parser.combinator.ConstructedSingleChildParser;

public interface ParserHierarchy{
	
	public static Predicate<Parser> isMeta = parser->
		parser instanceof ConstructedSingleChildParser ||
		parser instanceof ConstructedCombinatorParser;

	public static Predicate<Parser> isNotMeta = isMeta.negate();
	
	public static Predicate<Parser> isRoot = parser -> 
		parser instanceof RootParserIndicator ? 
				true :
				false == parser.getParent().isPresent();
	

	
	public enum NameKind{
		specifiedName,
		computedName,
		;
		public boolean isSpecifiedName(){
			return this == NameKind.specifiedName;
		}
		public boolean isComputedName(){
			return this == NameKind.computedName;
		}
	}
	
	public Name getName(NameKind nameKind);
	
	public default Name getName(){
		return getName(NameKind.specifiedName);
	}
	
	public default Name getComputedName(){
		return getName(NameKind.computedName);
	}
	
	public Optional<Parser> getParent();
	
	public Parsers getChildren();
	
	public void prepareChildren(Parsers childrenContainer);
	
	public void setParent(Parser parent);
	
	public Parser getRoot();
	
	public ChildOccurs getChildOccurs();
	
	
	public default Parsers getSiblings(boolean containsMe){
		Optional<Parser> parent = getParent();
		if(false == parent.isPresent()){
			return new Parsers();
		}
		return  Parsers.of(
			parent.get().getChildren().stream()
				.filter(parser-> containsMe ? (false == parser.equals(this)) :true)
				.collect(Collectors.toList())		
		);
	}
	
	public Optional<Parser> getParser(Name name);
	
	public Parser getThisParser();
}