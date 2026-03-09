package org.unlaxer;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.unlaxer.Source.SourceKind;
import org.unlaxer.listener.OutputLevel;
import org.unlaxer.parser.ErrorMessageParser;
import org.unlaxer.parser.Parser;

public class TokenPrinter{
	
//	public enum DebugLevel{simple,detail}

	
	public static void output(Token token , PrintStream out){
		output(token , out, 0 , OutputLevel.simple,true);
	}

	
	public static void output(Token token , PrintStream out, int level){
		output(token , out, level ,OutputLevel.simple,true);
	}
	
	public static void output(Token token , PrintStream out, OutputLevel detailLevel){
		output(token, out, 0 ,detailLevel , true);
	}

	static String EMPTY="<EMPTY>";
//	static String EMPTY="'' ";
	
	public static void output(Token token , PrintStream out, int level, 
			OutputLevel detailLevel,boolean outputChildren){
		
		Source tokenString = token.getSource();
		
		for(int i = 0 ; i < level ; i++){
			out.print(" ");
		}
		CursorRange tokenRange = tokenString.cursorRange();
		Parser parser = token.parser;
		if(detailLevel == OutputLevel.detail){
			
			out.format("%s (%d - %d): %s%s%s", 
				tokenString.isPresent() ? quote(tokenString):EMPTY ,//
				tokenRange.startIndexInclusive.position().value(),//
				tokenRange.endIndexExclusive.position().value(), //
				parser.getName(),
				getInverted(token),//
				outputChildren ? "\n":"");
			
		}else if(detailLevel == OutputLevel.withTag){
			
			out.format("%s (%d - %d): %s%s%s%S", 
			    tokenString.isPresent() ? quote(tokenString):EMPTY ,//
			    tokenRange.startIndexInclusive.position().value(),//
					tokenRange.endIndexExclusive.position().value(), //
					parser.getName(),
					getInverted(token),//
					getTag(token),
					outputChildren ? "\n":"");
			
		}else if(detailLevel == OutputLevel.simple){
			
			out.format("%s : %s%s%s" , //
		    tokenString.isPresent() ? quote(tokenString):EMPTY ,//
				parser.getName(),
				getInverted(token),//
				outputChildren ? "\n":"");
		}
		if(false == outputChildren){
			return ;
		}
		level++;
		for(Token original : token.getAstNodeChildren()){
			output(original,out,level,detailLevel,true);
		}
	}
		
	private static String getTag(Token token) {
		StringBuilder builder = new StringBuilder();
		builder.append("{");
		Set<Tag> tags = token.parser.getTags();
		Iterator<Tag> iterator = tags.iterator();
		while (iterator.hasNext()) {
			Tag tag = (Tag) iterator.next();
			builder.append(tag.toString());
			if(iterator.hasNext()) {
				builder.append(",");
			}
		}
		builder.append("}");
		return builder.toString();
	}


	static String quote(Source word){
		return "'" +word.toString() + "'";
	}
	
	static String getInverted(Token token){
		return token.parser.getInvertMatchFromParent()?"(inverted)":"";
	}
	
	public static String get(Token token){
		return get(token , 0 ,OutputLevel.simple,true);
	}
	
	public static String get(Token token , int level){
		return get(token , level ,OutputLevel.simple,true);
	}
	
	public static String get(Token token , OutputLevel detailLevel){
		return get(token , 0 ,detailLevel ,true);
	}
	
	public static String get(Token token , int level, 
			OutputLevel detailLevel, boolean outputChildren) {
		
		try(ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
			PrintStream printStream = new PrintStream(byteArrayOutputStream,false,"UTF-8");){
			
			output(token, printStream , level , detailLevel ,outputChildren);
			return byteArrayOutputStream.toString("UTF-8");
			
		}catch (Exception e) {
			throw new RuntimeException(e);
		}
	}
	
	public static List<ErrorMessage> getErrorMessages(Token targetToken){
		return targetToken.flatten().stream()
			.filter(token-> token.parser instanceof ErrorMessageParser)
			.map(token->new ErrorMessage(token.source.cursorRange(), ((ErrorMessageParser)token.parser).get()))
			.collect(Collectors.toList());
	}
	
	public static String get(String header , TokenList tokens){
	  String string = tokens.toSource(SourceKind.detached).toString();
		return String.format("%s:%s ", header , string);
	}

}