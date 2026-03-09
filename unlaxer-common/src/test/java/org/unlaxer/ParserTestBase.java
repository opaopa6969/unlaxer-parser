package org.unlaxer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

import org.junit.AfterClass;
import org.unlaxer.context.CombinedDebugSpecifier;
import org.unlaxer.context.CreateMetaTokenSpecifier;
import org.unlaxer.context.ParseContext;
import org.unlaxer.listener.CombinedDebugListener;
import org.unlaxer.listener.DebugParserListener;
import org.unlaxer.listener.DebugTransactionListener;
import org.unlaxer.listener.LogOutputCountListener;
import org.unlaxer.listener.OutputLevel;
import org.unlaxer.parser.Parser;
import org.unlaxer.util.BlackHole;
import org.unlaxer.util.MultipleOutputStream;

import net.arnx.jsonic.JSON;

public class ParserTestBase {
	
	public static String testFolderName ="parserTest";
	
	static {
		File root = new File("build/"+testFolderName);
		root.mkdirs();
	}
	
	public static void setLevel(OutputLevel outputLevel) {
		ParserTestBase.outputLevel.set(outputLevel);
	}
	public enum DoAssert{
		yes,no
	}
	public final LogListenerContainer transactionLogger = new LogListenerContainer();
	public final LogListenerContainer parseLogger = new LogListenerContainer();
	public final LogListenerContainer combinedLogger = new LogListenerContainer();
	
	public LogListenerContainer getTransactionLogger() {
		return transactionLogger;
	}

	public LogListenerContainer getParseLogger() {
		return parseLogger;
	}

	public LogListenerContainer getCombinedLogger() {
		return combinedLogger;
	}

	public static void setLogOutputCountListener(LogOutputCountListener listener){
		ParserTestBase.logOutputCountListener.set(listener);
	}
	
	public static void clearLogOutputCountListener(){
		ParserTestBase.logOutputCountListener.set(LogOutputCountListener.BlackHole);
	}
	
	public TestResult testPartialMatch(Parser parser, String sourceString, String matchedString,
			boolean createMeta)  {
		return testPartialMatch(parser, sourceString, matchedString, createMeta , DoAssert.yes);
	}
	
	public TestResult testPartialMatch(Parser parser, String sourceString, String matchedString,
			boolean createMeta  , DoAssert doAssert)  {
		return testMatch(parser, sourceString, matchedString, createMeta , doAssert);
	}
	
	public TestResult testPartialMatch(Parser parser, String sourceString, String matchedString) {
		return testPartialMatch(parser, sourceString, matchedString, true , DoAssert.yes);
	}
	
	public TestResult testPartialMatch(Parser parser, String sourceString, String matchedString  , DoAssert doAssert) {
		return testMatch(parser, sourceString, matchedString, true , doAssert);
	}

	TestResult testMatch(Parser parser, String sourceString, String matchedString, boolean createMeta , DoAssert doAssert){
		
		return test(parser, sourceString, createMeta,
			(parseContext , parsed)->{

				resultParsed.set(parsed);
				Source lastToken = parseContext.getCurrent().source();
				resultTokenString.set(lastToken);
				
				TestResult testResult = new TestResult(parsed, parseContext, lastToken);
				
				testResult.add(
						checkAssertEquals(true, parsed.isSucceeded() , doAssert)
				);
				
				if (matchedString == null) {
					
					testResult.add(
							checkAssertEquals(false, lastToken.isPresent() , doAssert)
					);

				} else {
//					if("".equals(sourceString)){
//						
//						testResult.add(
//								checkAssertFalse(parsed.getConsumed().source.isEmpty() , doAssert)
//						);
//
//						
//					}else{
						
						testResult.add(
								checkAssertEquals(matchedString, parsed.getConsumed().source.toString() , doAssert)
						);
//					}
				}
				return testResult;
			}
		);
	}
	
	static boolean  checkAssertEquals(Object expected , Object actual , DoAssert doAssert) {
		
		try {
			assertEquals(expected, actual);
			
			return true;
		} catch (Throwable e) {
			if(doAssert == DoAssert.yes) {
				throw e;
			}
			return false;
		}
	}
	
	static boolean  checkAssertFalse(boolean checkValue , DoAssert doAssert) {
		
		try {
			assertFalse(checkValue);
			return true;
		} catch (Throwable e) {
			if(doAssert == DoAssert.yes) {
				throw e;
			}
			return false;
		}
	}
	
	static boolean  checkAsserttrue(boolean checkValue , DoAssert doAssert) {
		
		try {
			assertTrue(checkValue);
			return true;
		} catch (Throwable e) {
			if(doAssert == DoAssert.yes) {
				throw e;
			}
			return false;
		}
	}
	
	public Parsed parse(Parser parser , String source) {
		
		StringSource stringSource = StringSource.createRootSource(source);
		ParseContext parseContext = new ParseContext(stringSource,CreateMetaTokenSpecifier.createMetaOn);
		Parsed parsed = parser.parse(parseContext);
		return parsed;
	}

	private ParseContext createParseContext(
			boolean createMeta, StringSource source, 
			PrintStream transactionOut, PrintStream parseOut) {
		
		return new ParseContext(
			source, //
			
			new CombinedDebugSpecifier(
				new CombinedDebugListener(
					new DebugParserListener(
							parseOut, //
							outputLevel.get(),//
							logOutputCountListener.get(),//
							parseLogger.breakPoints.get()
					),
					new DebugTransactionListener(
							transactionOut , //
							outputLevel.get(),//
							logOutputCountListener.get(),//
							transactionLogger.breakPoints.get()//
					),
					combinedLogger.breakPoints.get()
				)
					
			),
			CreateMetaTokenSpecifier.of(createMeta)
		);
	}
	
	public TestResult testAllMatch(Parser parser, String sourceString ) {
		return testAllMatch(parser, sourceString, true , DoAssert.yes);
	}

	public TestResult testAllMatch(Parser parser, String sourceString  , DoAssert doAssert) {

		return testPartialMatch(parser, sourceString, sourceString , doAssert);
	}
	public TestResult testAllMatch(Parser parser, String sourceString, boolean createMeta) {

		return testAllMatch(parser, sourceString, createMeta , DoAssert.yes);
	}
	
	public TestResult testAllMatch(Parser parser, String sourceString, boolean createMeta , DoAssert doAssert) {

		return testMatch(parser, sourceString, sourceString, createMeta , doAssert);
	}
	
	public TestResult testUnMatch(Parser parser, String sourceString ) {
		return testUnMatch(parser, sourceString, true , DoAssert.yes);
	}

	public TestResult testUnMatch(Parser parser, String sourceString  , DoAssert doAssert) {
		return testUnMatch(parser, sourceString, true , doAssert);
	}

	public TestResult testUnMatch(Parser parser, String sourceString, boolean createMeta) {
		return testUnMatch(parser , sourceString , createMeta , DoAssert.yes);
	}
	public TestResult testUnMatch(Parser parser, String sourceString, boolean createMeta , DoAssert doAssert) {

		return test(parser, sourceString, createMeta,
			(parseContext , parsed)->{
				resultParsed.set(parsed);
				Source lastToken = parseContext.getCurrent().source();
				TestResult testResult = new TestResult(parsed, parseContext, lastToken);
				
				testResult.add(
						checkAssertEquals(false, parsed.isSucceeded() , doAssert)
				);
				return testResult;
			}
		);
	}
	
	public TestResult testSucceededOnly(Parser parser, String sourceString) {
		return testSucceededOnly(parser, sourceString, DoAssert.yes);
	}
	public TestResult testSucceededOnly(Parser parser, String sourceString , DoAssert doAssert) {
		return testSucceededOnly(parser, sourceString, false , doAssert);
	}
	
	public TestResult testSucceededOnly(Parser parser, String sourceString, boolean createMeta , DoAssert doAssert) {

		return test(parser, sourceString, createMeta, 
			(parseContext , parsed)->{
				resultParsed.set(parsed);
				Source lastToken = parseContext.getCurrent().source();
				TestResult testResult = new TestResult(parsed, parseContext, lastToken);
				testResult.add(
						checkAssertEquals(true, parsed.isSucceeded()  ,doAssert)
				);
				return testResult;
			}
		);
	}
	
	public TestResult test(Parser parser, String sourceString, boolean createMeta , ParseFunction parseFunction) {

		int count = counts.get();
		counts.set(count+1);

		StringSource source = StringSource.createRootSource(sourceString);
		try (OutputStream transactionFile = createFileOutputSream("transaction" , count);
			OutputStream parseFile = createFileOutputSream("parse" , count);
			OutputStream bothFile = createFileOutputSream("combined" , count);
				
			PrintStream transactionPrintStream = 
					new PrintStream(new MultipleOutputStream(transactionFile,bothFile),false,"UTF-8");
				
			PrintStream parsePrintStream = 
					new PrintStream(new MultipleOutputStream(parseFile,bothFile),false,"UTF-8");
			PrintStream tokenPrintStream = new PrintStream(createFileOutputSream("token" , count),false,"UTF-8");
				
			ParseContext parseContext = 
				createParseContext(createMeta, source, transactionPrintStream , parsePrintStream);
			){
			Parsed parsed = parser.parse(parseContext);
			TestResult testResult = parseFunction.apply(parseContext, parsed);
			
			tokenPrintStream.println(
				TokenPrinter.get(
					testResult.parsed.getRootToken(false == createMeta), OutputLevel.detail)
			);
			
			return testResult;
		} catch (IOException e) {
			throw new RuntimeException(e);
		}finally {
		  outputLevel.set(OutputLevel.none);
		}
	}

	
	interface ParseFunction extends BiFunction<ParseContext, Parsed, TestResult>{}
	
	OutputStream createFileOutputSream(String logName , int count) {
		
		StackTraceElement[] stackTraces = Thread.currentThread().getStackTrace();
		LastAndFirst callerIndex = getCallerIndex(getTestClass(),stackTraces);
		try {
			String kind = stackTraces[callerIndex.first-1].getMethodName();
			String callerMethod = stackTraces[callerIndex.last].getMethodName();
			String callerClass = stackTraces[callerIndex.last].getClassName();
			
			callerMethodName.set(callerMethod);
			callerClassName.set(callerClass);
			int callerLine = stackTraces[callerIndex.last].getLineNumber();
			OutputStream out;
			try {
				out = outputLevel.get().isNone() ? 
						BlackHole.getOutputStream(): 
							createOutputStream(logName, kind, callerMethod, callerLine, count);
						return out;
			} catch (FileNotFoundException e) {
				throw new RuntimeException(e);
			}
			
		}catch (IndexOutOfBoundsException e) {
			System.err.println(JSON.encode(callerIndex));
			String thisClassName = getClass().getName();
			System.err.format("this class name %s\n",thisClassName);

			for(int i = 0 ; i < stackTraces.length ; i++){
				StackTraceElement stackTraceElement = stackTraces[i];
				String currentClassName = stackTraceElement.getClassName();
				System.err.format("\tstackTrace element class name %s\n",currentClassName);
			}
			throw e;
		}
	}

	private OutputStream createOutputStream(String logName, String kind, String callerMethod, int callerLine,
			int count) throws FileNotFoundException{
		File root = new File("build/"+testFolderName);
		root.mkdirs();
		File testClassFolder = new File(root,getClass().getName());
		testClassFolder.delete();
		testClassFolder.mkdirs();
		return new FileOutputStream(
			new File(testClassFolder, 
				callerMethod+//
				"_"+kind+//
				"_("+count+",L"+callerLine+")."+//
				logName+//
				".log"
			)
		);
	}
	
	static boolean outputStackTraceElements = false;
	
	public static LastAndFirst getCallerIndex(Class<?> thisClass , StackTraceElement[] stackTraces){
		String thisClassName = thisClass.getName();
		LastAndFirst lastAndFirst = new LastAndFirst();
//		System.out.format("this class name %s\n",thisClassName);

		  
	  for(int i = 0 ; i < stackTraces.length ; i++){
	    StackTraceElement stackTraceElement = stackTraces[i];
	    String currentClassName = stackTraceElement.getClassName();
	    if(outputStackTraceElements) {
	      System.out.format("\tstackTrace element class name %s\n",currentClassName);
	    }
	    if(currentClassName.equals(thisClassName)){
	      lastAndFirst.apply(i);
	    }
	  }
		return lastAndFirst;
	}
	
	static class LastAndFirst{
		public int last=0;
		public int first=Integer.MAX_VALUE;
		public void apply(int value){
			first = Math.min(first, value);
			last = Math.max(last, value);
		}
	}
	
	
	ThreadLocal<Integer> counts = new ThreadLocal<Integer>(){

		@Override
		protected Integer initialValue() {
			return 1;
		}
	};
	
	@AfterClass
	public static void checkTokenAndTransaction(){
		
		if(isCI()) {
			return;
		}
		
		try {
			List<CompareResult> results = 
				Files.walk(Paths.get("build/"+testFolderName , callerClassName.get()))
					.filter(path->path.toFile().isFile())
					.map(actualLog->{
						ResultKind compareKind = compare(actualLog);
						String name = actualLog.toString();
						String message;
						switch (compareKind) {
						case ioError:
							message = String.format("read error : %s\n" , name);break;
						case notMatch:
							message = String.format("not match : %s\n" , name);break;
						case noExpected:
							message = String.format("expected file not exists. see test/resources/parserTest : %s\n" , name);
							break;
						case match:
						default:
							message = String.format("match : %s\n" , name);break;
						}
						return new CompareResult(compareKind, actualLog, message);
					})
					.filter(result->result.compareKind == ResultKind.notMatch)
					.collect(Collectors.toList());
			if(results.size()>0){
				System.err.println("build/reports/tests/classes/"+
					callerClassName.get()+".html");
				fail(JSON.encode(results));
			}
		} catch (NoSuchFileException e){
			System.err.format(
					"%s#%s() is not execute setDebugLevel(DebugLevel) , then do not execute check Token and Transaction",
					callerClassName.get(),callerMethodName.get());
		
		} catch (IOException e) {
			e.printStackTrace();
		}			

	}
	
	
	public static class CompareResult{
		public ResultKind compareKind;
		public Path path;
		public String message;
		public CompareResult(ResultKind compareKind, Path path, String message) {
			super();
			this.compareKind = compareKind;
			this.path = path;
			this.message = message;
		}
	}
	
	
	public enum ResultKind{
		match,//
		notMatch,//
		noExpected,//
		ioError,//
	}
	
	static ResultKind compare(Path actualLog){
		Path expectedFile = getExpectedFile(actualLog);
		if(false == expectedFile.toFile().exists()){
			return ResultKind.noExpected;
		}
		try(BufferedReader actualReader = Files.newBufferedReader(actualLog, StandardCharsets.UTF_8);
			BufferedReader expectedReader = Files.newBufferedReader(expectedFile, StandardCharsets.UTF_8)){
			while(true){
				String actual = actualReader.readLine();
				String expected = expectedReader.readLine();
				if(actual == null && expected == null){
					return ResultKind.match;
				}
				if(actual == null || expected == null || false == actual.trim().equals(expected.trim())){
					print("expected", expected);
					print("actual", actual);
					return ResultKind.notMatch;
				}
			}
		} catch (IOException e) {
			e.printStackTrace();
			return ResultKind.ioError;
		}
	}
	
	static void print(String header,String data) {
		System.err.print(header+":");
		if(data == null) {
			System.err.println("null");
			return;
		}
		byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
		for (byte b : bytes) {
			System.err.format("%x ",b);
		}
		
	}
	
	static Path getExpectedFile(Path actual){
		Path path = Paths.get(actual.toFile().getAbsolutePath());
		int nameCount = path.getNameCount();
		Path name = path.subpath(nameCount-2,nameCount);

		Path expectedFile = Paths.get("src/test/resources/",
		testFolderName ,name.toString());
		return expectedFile;
	}
	
	static ThreadLocal<String> callerClassName = new ThreadLocal<>();
	static ThreadLocal<String> callerMethodName = new ThreadLocal<>();
	
	ThreadLocal<Source> resultTokenString = new ThreadLocal<>();
	ThreadLocal<Parsed> resultParsed = new ThreadLocal<>();

	static ThreadLocal<OutputLevel> outputLevel = new ThreadLocal<OutputLevel>() {

		@Override
		protected OutputLevel initialValue() {
			return OutputLevel.none;
		}
	};
	
	static ThreadLocal<LogOutputCountListener> logOutputCountListener =
		new ThreadLocal<LogOutputCountListener>(){

		@Override
		protected LogOutputCountListener initialValue() {
			return LogOutputCountListener.BlackHole;
		}
	};
	public static boolean isCI(){
		return "true".equals(System.getenv("CI"));
	}
	
	public static boolean isNotCI(){
		return false == isCI();
	}
	
	public Class<?> getTestClass(){
		return getClass();
	}
}
