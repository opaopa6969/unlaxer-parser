package org.unlaxer.parser;

import java.io.Serializable;
import java.util.function.Supplier;

import org.unlaxer.Parsed;
import org.unlaxer.ParserPath;
import org.unlaxer.TaggableAccessor;
import org.unlaxer.Token;
import org.unlaxer.TokenKind;
import org.unlaxer.context.ParseContext;

public interface Parser extends //
    PropagatableDestination, //
    TaggableAccessor, //
//	Taggable , //
    ParserPath, //
//	ParserHierarchy , //
//	ParserFinder,//
//	Initializable,
    Serializable {

  // FIXME make Parsed parse(ParseContext parseContext) only. use to get tokenKind
  // and invertMatch
  public Parsed parse(ParseContext parseContext, TokenKind tokenKind, boolean invertMatch);
  
  public default Parsed parse(ParseContext parseContext) {
    return parse(parseContext, getTokenKind(), false);
  }

  public default TokenKind getTokenKind() {
    return TokenKind.consumed;
  }

  public default boolean forTerminalSymbol() {
    return this instanceof TerminalSymbol;
  }

  public default boolean equalsByClass(Parser other) {
    return getClass().equals(other.getClass());
  }

  public default Parser getChild() {
    return getChildren().get(0);
  }

  @Override
  default Parser getThisParser() {
    return this;
  }

  public NodeReduceMarker getNodeReduceMarker();

  public static <T extends Parser> T get(Class<T> clazz) {
    return ParserFactoryByClass.get(clazz);
  }
  
  public static <T extends Parser> Supplier<T> getSupplier(Class<T> clazz) {
    return ()->ParserFactoryByClass.get(clazz);
  }

//  public static <T extends Parser> T get(ASTNodeKind nodeKind, Class<T> clazz) {
//    return ParserFactoryByClass.get(nodeKind, clazz);
//  }

  public static <T extends Parser> T get(Supplier<T> supplier) {
    return ParserFactoryBySupplier.get(supplier);
  }
  
  public static <T extends Parser> T newInstance(Class<T> clazz) {
    return ParserFactoryByClass.newInstance(clazz);
  }
  
  public default boolean isTokenParsedByThisParser(Token token) {
	  return token.getParser().getClass() == getClass();
  }
  
  public default void checkTokenParsedByThisParser(Token token) {
	  if(false == isTokenParsedByThisParser(token)) {
		  throw new IllegalArgumentException("expected parse:" + getClass() + " actual parser:" + token.getParser().getClass());
	  }
  }
  
  public static  boolean isTokenParsedBySpecifiedParser(Token token , Class<? extends Parser> parserClass) {
	  return token.getParser().getClass() == parserClass;
  }

  
  public static void checkTokenParsedBySpecifiedParser(Token token, Class<? extends Parser> parserClass) {
	  if(false == isTokenParsedBySpecifiedParser(token , parserClass)) {
		  throw new IllegalArgumentException("expected parse:" + parserClass + " actual parser:" + token.getParser().getClass());
	  }
  }
}