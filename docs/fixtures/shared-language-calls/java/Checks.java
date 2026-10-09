package example.shared;
import java.util.*;
import org.unlaxer.*;
import org.unlaxer.context.*;
import org.unlaxer.source.*;
import org.unlaxer.source.LanguageRegions.*;
import org.unlaxer.parser.Parser;
public final class Checks {
 static Memoization memo;
 static ParseContext context(Source source) { return ParseContext.withOptions(source,ParseOptions.withMemoization(memo)); }
 static void check(boolean value) { if (!value) throw new AssertionError(); }
 static Language language(String entry) { return new Language("boundary","example/boundary","1","Boundary",entry); }
 static SharedGrammarCalls.Registry boundary(String entry) { return new SharedGrammarCalls.Registry(Map.of(language(entry),BoundaryParsers.embeddedGrammar())); }
 static SharedGrammarCalls.Adapter adapter(String entry) { return new SharedGrammarCalls.Adapter() {
  protected SharedGrammarCalls.Registry registry() { return boundary(entry); }
  protected Language language() { return Checks.language(entry); }
 }; }
 static String failure(String source,String entry) {
  try(var context=context(StringSource.createRootSource(source))) {
   var result=boundary(entry).probe(context,language(entry),new CallA(),TokenKind.consumed);
   check(context.getPosition(TokenKind.consumed).value()==0);
   return result.failure().kind()+"\t"+result.failure().offset();
  }
 }
 public static List<String> lexicalSessions() {
  var rows=new ArrayList<String>();
  for(var mode:org.unlaxer.dsl.runtime.Lexing.Mode.values()) for(String source:List.of("ab!","ax!")) {
   var session=new org.unlaxer.dsl.runtime.Lexing.Session(source,new org.unlaxer.dsl.runtime.Lexing.Options(mode,true),List.of(new org.unlaxer.dsl.runtime.Lexing.Terminal("!",true,org.unlaxer.dsl.runtime.LexicalExpression.leaf(org.unlaxer.dsl.runtime.LexicalExpression.Op.LITERAL,"!"))),false);
   try(var context=new ParseContext(StringSource.createRootSource(source))) {
    context.getGlobalScopeTreeMap().put(Name.of(org.unlaxer.dsl.runtime.Lexing.class),session);
    var before=session.metrics(); boolean accepted=new CallA().parse(context).isSucceeded();
    check(context.getGlobalScopeTreeMap().get(Name.of(org.unlaxer.dsl.runtime.Lexing.class))==session);
    var after=session.metrics();int consumed=context.getPosition(TokenKind.consumed).value();
    if(accepted) check(new org.unlaxer.dsl.runtime.Lexing.LiteralParser("!").parse(context).isSucceeded());
    rows.add(mode+"\t"+source+"\t"+accepted+"\t"+consumed+"\t"+context.getPosition(TokenKind.consumed).value()+"\t"+(after.terminalEvaluations()-before.terminalEvaluations())+"\t"+(after.inventoryEvaluations()-before.inventoryEvaluations()));
   }
  }
  return rows;
 }
 public static List<String> run(Memoization selected) {
  memo=selected;
  var result=new ArrayList<String>();
  try(var context=context(StringSource.createRootSource("😀D:aX;"))) {
   context.getCurrent().getParserCursor().getCursor(TokenKind.consumed).setPosition(new CodePointIndex(3));
   var value=CallA.REGISTRY.probe(context,CallA.A,new CallA(),TokenKind.consumed);
   check(context.getPosition(TokenKind.consumed).value()==3);
   result.add("direct-failure\t"+value.failure().kind()+"\t"+value.failure().offset());
  }
  try(var context=context(StringSource.createRootSource("😀D:aX;"))) {
   new org.unlaxer.parser.combinator.Chain(new org.unlaxer.parser.elementary.WordParser("😀D:aX;"),new org.unlaxer.parser.elementary.WordParser("z")).parse(context);
   context.getCurrent().getParserCursor().getCursor(TokenKind.consumed).setPosition(new CodePointIndex(3));
   var value=CallA.REGISTRY.probe(context,CallA.A,new CallA(),TokenKind.consumed);
   result.add("local-failure\t"+value.failure().offset()+"\t"+context.getParseFailureDiagnostics().getFarthestOffset());
  }
  try(var context=context(StringSource.createRootSource("ab"+"x".repeat(1_048_575)))) {
   var value=CallA.REGISTRY.probe(context,CallA.A,new CallA(),TokenKind.consumed);
   result.add("input-limit\t"+value.failure().kind()+"\t"+value.failure().offset());
  }
  var literal=new org.unlaxer.parser.elementary.WordParser("ab");
  var optional=new org.unlaxer.parser.combinator.Chain(new org.unlaxer.parser.elementary.WordParser("a"),new org.unlaxer.parser.elementary.WordParser("b"),new org.unlaxer.parser.combinator.Optional(new org.unlaxer.parser.elementary.WordParser("!")));
  var failedChild=new org.unlaxer.parser.combinator.Chain(new org.unlaxer.parser.elementary.WordParser("a"),new org.unlaxer.parser.elementary.WordParser("X"));
  var cases=List.of(new org.unlaxer.parser.combinator.Not(literal),new org.unlaxer.parser.combinator.Not(optional),new org.unlaxer.parser.combinator.Chain(new org.unlaxer.parser.combinator.Not(failedChild),new org.unlaxer.parser.elementary.WordParser("Z")));
  for(int i=0;i<cases.size();i++) {
   try(var context=context(StringSource.createRootSource("😀N:ab;"))) {
    var parser=new org.unlaxer.parser.combinator.Chain(new org.unlaxer.parser.elementary.WordParser("😀N:"),cases.get(i));
    check(parser.parse(context).isFailed()); check(context.getPosition(TokenKind.consumed).value()==0);
    result.add("not-policy-"+i+"\t"+context.getParseFailureDiagnostics().getFarthestOffset());
   }
  }
  result.add("nullable\t"+failure("x","Empty"));
  result.add("empty-host\t"+failure("","Empty"));
  result.add("recovered\t"+failure("bad;","Recover"));
  try { boundary("Missing"); throw new AssertionError(); } catch(IllegalArgumentException expected) { result.add("missing-entry\tREJECTED"); }
  try(var context=ParseContext.withOptions(StringSource.createRootSource("ab"),ParseOptions.withMemoization(memo).withDiagnostics(ParseOptions.Diagnostics.DETAILED_ON_FAILURE))) {
   var value=CallA.REGISTRY.probe(context,CallA.A,new CallA(),TokenKind.consumed);
   result.add("deferred\t"+value.failure().kind()+"\t"+value.failure().offset());
  }
  String large="abababababab"+"x".repeat(1_048_576-12);
  try(var context=context(StringSource.createRootSource(large))) {
   for(int i=0;i<4;i++) check(new CallA().parse(context).isSucceeded());
   var value=CallA.REGISTRY.probe(context,CallA.A,new CallA(),TokenKind.consumed);
   result.add("retention\t"+value.failure().kind()+"\t"+value.failure().offset());
  }
  try(var context=context(StringSource.createRootSource(large))) {
   var call=new CallA(); context.begin(call);
   for(int i=0;i<4;i++) check(call.parse(context).isSucceeded());
   context.rollback(call);
   check(new AheadA().parse(context).isSucceeded());
   for(int i=0;i<4;i++) check(call.parse(context).isSucceeded());
   result.add("budget-rollback\t"+context.getPosition(TokenKind.consumed).value());
  }
  try(var context=context(StringSource.createRootSource(large))) {
   var call=adapter("Outer"); for(int i=0;i<2;i++) check(call.parse(context).isSucceeded());
   var value=boundary("Outer").probe(context,language("Outer"),call,TokenKind.consumed);
   result.add("nested-retention\t"+value.failure().kind()+"\t"+value.failure().offset());
  }
  for(int length : List.of(32,33)) {
   try(var context=context(StringSource.createRootSource("a".repeat(length)+"z"))) {
    var value=boundary("Recur").probe(context,language("Recur"),new RecurCall(),TokenKind.consumed);
    result.add("depth-"+length+"\t"+(value.succeeded()?"COMPLETE\t"+value.call().span().end():value.failure().kind()+"\t"+value.failure().offset()));
   }
  }
  SharedGrammarCalls.Call retained;
  try(var context=context(StringSource.createRootSource("ab"))) {
   retained=CallA.REGISTRY.probe(context,CallA.A,new CallA(),TokenKind.consumed).call();
  }
  check(((ChildAAST.AValue)ChildAMapper.mapParsedTokenWithSourceMap(retained.tree()).ast()).value().equals("a"));
  result.add("retained-tree\tAValue\ta");
  DocumentSnapshot host=new DocumentSnapshot("file:///shared",7,"😀D:ab;");
  Language parent=new Language("parent","example/parent","1","Parent","Root");
  var tree=EmbeddedLanguages.parse(host,parent,Map.of(parent,ParentParsers.embeddedGrammar(),CallA.A,ChildAParsers.embeddedGrammar()),8,32).tree();
  check(tree.regions().size()==2); var child=tree.regions().get(0).language().equals(CallA.A)?tree.regions().get(0):tree.regions().get(1);
  check(child.language().equals(CallA.A)); check(child.body().equals(new DocumentSnapshot.Span(3,5)));
  var project=new LanguageQueries.Project("p",1,Map.of(host.uri(),host),Map.of());
  var provider=new LanguageQueries.Provider() {
   public Set<Operation> capabilities(){return Set.of(Operation.COMPLETION);}
   public LanguageQueries.Response query(LanguageQueries.Request request){
    check(request.cursor()==1);
    var location=new SegmentSourceMap.Location(request.region().sourceMap().output(),new DocumentSnapshot.Span(0,1));
    return new LanguageQueries.Response(request.region().sourceMap().output(),"p",1,State.COMPLETE,List.of(new LanguageQueries.Item("a","owned",List.of(location),List.of())));
   }
  };
  var queries=new LanguageQueries(tree,project,Map.of(CallA.A,provider));
  var response=queries.query(host,project,4,Operation.COMPLETION,Map.of());
  check(response.items().get(0).locations().get(0).location().span().equals(new DocumentSnapshot.Span(3,4)));
  result.add("query\tCOMPLETE\t3\t4");
  try { queries.query(new DocumentSnapshot(host.uri(),8,host.text()),project,4,Operation.COMPLETION,Map.of()); throw new AssertionError(); }
  catch(IllegalArgumentException expected){result.add("stale\tREJECTED");}
  return result;
 }
}
