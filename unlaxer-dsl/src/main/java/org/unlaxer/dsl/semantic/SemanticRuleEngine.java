package org.unlaxer.dsl.semantic;

import java.util.*;
import static org.unlaxer.dsl.semantic.SemanticModel.UNKNOWN;
import org.unlaxer.editor.EditorCst;
import org.unlaxer.dsl.semantic.SemanticRules.*;
import org.unlaxer.dsl.semantic.SemanticModel.*;

/** Executes validated portable rules against source-preserving, generated editor CST nodes. */
public final class SemanticRuleEngine {
    private SemanticRuleEngine() {}
    private record Binding(Rule rule,EditorCst.Node node,String id) {}
    private record Value(String text,Span span,boolean synthetic) {}
    private static final class Failure extends RuntimeException {
        final String code,rule;final Span span;
        Failure(String code,Span span,String rule) {super(code);this.code=code;this.span=span;this.rule=rule;}
    }
    private static Failure fail(String code,Span span,String rule) {return new Failure(code,span,rule);}
    private static Span span(EditorCst.Span span) {return new Span(span.start(),span.end());}
    private static int width(Span span) {return span.end()-span.start();}
    private static boolean overlaps(Span a,Span b) {return Math.max(a.start(),b.start())<Math.min(a.end(),b.end());}
    private static String slice(String text,Span span) {return text.substring(text.offsetByCodePoints(0,span.start()),text.offsetByCodePoints(0,span.end()));}
    private static boolean healthy(EditorCst cst,EditorCst.Node node) {
        return !node.synthetic() && node.captures().stream().noneMatch(EditorCst.Capture::synthetic)
            && cst.defects().stream().noneMatch(d->d.kind()==EditorCst.DefectKind.ERROR && overlaps(span(node.span()),span(d.span())));
    }
    private static List<Value> values(Binding b,String key) {
        Selector selector=b.rule().selectors().get(key);if(selector==null)return List.of();
        if(selector.source()==Source.LITERAL)return List.of(new Value(selector.name(),span(b.node().span()),false));
        return b.node().captures().stream().filter(c->c.name().equals(selector.name())).map(c->new Value(c.text(),span(c.span()),c.synthetic())).toList();
    }
    private static Value one(Binding b,String key) {
        List<Value> selected=values(b,key);
        if(selected.size()!=1)throw fail("SELECTOR_CARDINALITY",span(b.node().span()),b.rule().id()+"."+key);
        Value value=selected.get(0);String text=value.text().trim();
        if(text.codePointCount(0,text.length())>1024)throw fail("LIMIT",value.span(),b.rule().id()+"."+key);
        if(value.synthetic() || text.isEmpty())throw fail("INCOMPLETE_BINDING",value.span(),b.rule().id()+"."+key);
        return new Value(text,value.span(),false);
    }
    private static List<String> names(Binding b,String key) {
        List<String> result=new ArrayList<>();for(Value value:values(b,key)) {
            if(value.synthetic() || value.text().trim().isEmpty())throw fail("INCOMPLETE_BINDING",value.span(),b.rule().id()+"."+key);
            if(value.text().trim().codePointCount(0,value.text().trim().length())>1024)throw fail("LIMIT",value.span(),b.rule().id()+"."+key);
            result.add(value.text().trim());
        }return List.copyOf(result);
    }
    private static Scope containing(List<Scope> scopes,Span target,String excluded) {
        List<Scope> candidates=scopes.stream().filter(s->!s.id().equals(excluded)&&s.span().contains(target)).sorted(Comparator.comparingInt(s->width(s.span()))).toList();
        if(candidates.size()>1 && width(candidates.get(0).span())==width(candidates.get(1).span()) && !candidates.get(1).id().equals("$root") && !candidates.get(0).id().equals("$root"))throw fail("AMBIGUOUS_SCOPE",target,"");
        return candidates.stream().filter(s->!s.id().equals("$root")).findFirst().orElse(candidates.get(candidates.size()-1));
    }
    public static Analysis analyze(Program program,Inventory current,String uri,long version,EditorCst cst) {
        Objects.requireNonNull(program);Objects.requireNonNull(current);Objects.requireNonNull(cst);
        Span document=new Span(0,cst.source().codePointCount(0,cst.source().length()));
        if(uri==null || uri.isEmpty() || version<0 || uri.codePointCount(0,uri.length())>4096 || uri.codePoints().anyMatch(cp->cp>=0xD800&&cp<=0xDFFF))throw new IllegalArgumentException("invalid semantic snapshot");
        List<Diagnostic> diagnostics=new ArrayList<>();
        try {
            if(!program.inventory().equals(current))throw fail("INVENTORY_MISMATCH",document,"");
            if(cst.status()==EditorCst.Status.FAILED)throw fail("PARSE_"+cst.reason().name(),document,"");
            if(cst.nodes().size()>16384 || cst.nodes().stream().mapToLong(n->n.captures().size()).sum()>65536)throw fail("LIMIT",document,"");
            long captureBytes=0;for(var node:cst.nodes())for(var capture:node.captures()){captureBytes+=capture.text().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;if(captureBytes>4194304)throw fail("LIMIT",document,"");}
            List<EditorCst.Node> nodes=new ArrayList<>(cst.nodes());
            nodes.sort(Comparator.comparingInt((EditorCst.Node n)->n.span().start()).thenComparingInt(n->n.span().end()).thenComparing(EditorCst.Node::rule,(a,b)->Arrays.compare(a.codePoints().toArray(),b.codePoints().toArray())));
            List<Binding> bindings=new ArrayList<>();
            for(EditorCst.Node node:nodes) {
                Shape shape=current.nodes().get(node.rule());Span nodeSpan=span(node.span());
                if(shape==null || !document.contains(nodeSpan))throw fail("CST_INVENTORY",nodeSpan,node.rule());
                for(EditorCst.Capture capture:node.captures())if(!shape.captures().contains(capture.name()) || !nodeSpan.contains(span(capture.span())) || !slice(cst.source(),span(capture.span())).equals(capture.text()))throw fail("CST_CAPTURE",nodeSpan,node.rule());
                for(Rule rule:program.rules())if(rule.node().equals(node.rule())) {
                    if(bindings.size()>=4096)throw fail("LIMIT",nodeSpan,rule.id());
                    bindings.add(new Binding(rule,node,rule.id()+"@"+nodeSpan.start()+":"+nodeSpan.end()));
                }
            }
            Set<String> identities=new HashSet<>();for(Binding b:bindings)if(!identities.add(b.id()))throw fail("AMBIGUOUS_BINDING",span(b.node().span()),b.rule().id());
            List<Scope> scopes=new ArrayList<>();scopes.add(new Scope("$root",null,document));
            List<Binding> scopeBindings=bindings.stream().filter(b->b.rule().emit()==Emit.SCOPE).sorted(Comparator.comparingInt((Binding b)->width(span(b.node().span()))).reversed()).toList();
            for(Binding b:scopeBindings) {
                Span s=span(b.node().span());if(width(s)==0)continue;
                if(scopes.size()>=512)throw fail("LIMIT",s,b.rule().id());
                if(scopes.stream().anyMatch(existing->!existing.id().equals("$root")&&existing.span().equals(s)))throw fail("AMBIGUOUS_SCOPE",s,b.rule().id());
                scopes.add(new Scope(b.id(),containing(scopes,s,"").id(),s));
            }
            List<Binding> typeBindings=bindings.stream().filter(b->b.rule().emit()==Emit.TYPE && healthy(cst,b.node())).toList();
            if(typeBindings.size()>512)throw fail("LIMIT",document,"");
            Map<String,List<Field>> fields=new LinkedHashMap<>();
            for(Binding b:bindings)if(b.rule().emit()==Emit.FIELD && healthy(cst,b.node())) {
                List<Binding> owners=typeBindings.stream().filter(t->t.rule().id().equals(b.rule().owner())&&span(t.node().span()).contains(span(b.node().span()))).sorted(Comparator.comparingInt(t->width(span(t.node().span())))).toList();
                if(owners.isEmpty()) {diagnostics.add(new Diagnostic("INCOMPLETE_OWNER",uri,version,span(b.node().span()),b.rule().id()));continue;}
                if(owners.size()>1 && owners.get(0).node().span().equals(owners.get(1).node().span()))throw fail("AMBIGUOUS_OWNER",span(b.node().span()),b.rule().id());
                fields.computeIfAbsent(owners.get(0).id(),ignored->new ArrayList<>()).add(new Field(one(b,"name").text(),one(b,"type").text(),span(b.node().span())));
            }
            List<Type> types=new ArrayList<>();
            for(Binding b:typeBindings) {
                if(!containing(scopes,span(b.node().span()),"").id().equals("$root"))throw fail("TYPE_SCOPE_UNSUPPORTED",span(b.node().span()),b.rule().id());
                String kind=one(b,"kind").text();TypeKind parsed;
                try {if(!kind.matches("[a-zA-Z]+"))throw new IllegalArgumentException();parsed=TypeKind.valueOf(kind.toUpperCase(Locale.ROOT));}catch(IllegalArgumentException e){throw fail("INVALID_TYPE_KIND",one(b,"kind").span(),b.rule().id());}
                types.add(new Type(one(b,"name").text(),parsed,names(b,"parents"),fields.getOrDefault(b.id(),List.of()),span(b.node().span())));
            }
            List<Symbol> symbols=new ArrayList<>();List<Signature> signatures=new ArrayList<>();Map<String,String> signatureScopes=new HashMap<>();
            Map<Span,String> expressions=new TreeMap<>(Comparator.comparingInt(Span::start).thenComparingInt(Span::end));
            Map<Span,String> references=new HashMap<>();
            for(Binding b:bindings)if(healthy(cst,b.node())) {
                Span s=span(b.node().span());Scope scope=containing(scopes,s,"");
                switch(b.rule().emit()) {
                    case SYMBOL -> symbols.add(new Symbol(b.id(),one(b,"name").text(),one(b,"type").text(),scope.id(),s,b.rule().visibility().equals("after")?s.end():scope.span().start()));
                    case SIGNATURE -> {signatures.add(new Signature(b.id(),one(b,"name").text(),names(b,"parameters"),one(b,"result").text(),s));signatureScopes.put(b.id(),scope.id());}
                    case EXPRESSION -> {if(references.containsKey(s)||expressions.putIfAbsent(s,one(b,"type").text())!=null)throw fail("AMBIGUOUS_EXPRESSION",s,b.rule().id());}
                    case REFERENCE -> {if(expressions.containsKey(s)||references.putIfAbsent(s,one(b,"name").text())!=null)throw fail("AMBIGUOUS_EXPRESSION",s,b.rule().id());}
                    default -> {}
                }
            }
            SemanticModel preliminary=new SemanticModel(uri,version,cst.source(),types,scopes,symbols,signatures,List.of());
            for(var entry:expressions.entrySet())if(!entry.getValue().equals(UNKNOWN)&&!preliminary.types().containsKey(entry.getValue()))throw fail("UNDEFINED_TYPE",entry.getKey(),"");
            List<Call> calls=new ArrayList<>();List<Site> sites=new ArrayList<>();
            for(Binding b:bindings)if(b.rule().emit()==Emit.CALL) {
                Value name;
                try{name=one(b,"name");}catch(Failure e){if(e.code.equals("INCOMPLETE_BINDING")){diagnostics.add(new Diagnostic(e.code,uri,version,e.span,e.rule));continue;}throw e;}
                if(b.rule().selectors().get("name").source()!=Source.LITERAL&&cst.defects().stream().anyMatch(d->d.kind()==EditorCst.DefectKind.ERROR&&overlaps(name.span(),span(d.span())))) {
                    diagnostics.add(new Diagnostic("INCOMPLETE_BINDING",uri,version,name.span(),b.rule().id()+".name"));continue;
                }
                Span s=span(b.node().span());List<Argument> arguments=new ArrayList<>();
                for(Value value:values(b,"arguments")) {
                    String type=UNKNOWN;
                    boolean damaged=value.synthetic()||cst.defects().stream().anyMatch(d->d.kind()==EditorCst.DefectKind.ERROR&&overlaps(value.span(),span(d.span())));
                    String text=value.text().trim();
                    if(!damaged&&!program.unknownLiterals().contains(text)) {
                        type=expressions.get(value.span());
                        if(type==null) {
                            String reference=references.getOrDefault(value.span(),text);
                            type=preliminary.visibleSymbolsAt(value.span().start()).stream().filter(sym->sym.name().equals(reference)).map(Symbol::type).findFirst().orElse(UNKNOWN);
                            if(type.equals(UNKNOWN))diagnostics.add(new Diagnostic("UNRESOLVED_REFERENCE",uri,version,value.span(),b.rule().id()));
                        }
                    }
                    arguments.add(new Argument(type,value.span()));sites.add(new Site(b.id(),arguments.size()-1,value.span()));
                }
                Scope scope=containing(scopes,s,"");List<String> candidates=List.of();
                while(scope!=null) {
                    String scopeId=scope.id();candidates=signatures.stream().filter(sig->sig.name().equals(name.text())&&signatureScopes.get(sig.id()).equals(scopeId)).map(Signature::id).toList();
                    if(!candidates.isEmpty())break;
                    scope=scope.parent()==null?null:preliminary.scopes().get(scope.parent());
                }
                if(candidates.isEmpty())diagnostics.add(new Diagnostic("UNRESOLVED_CALL",uri,version,name.span(),b.rule().id()));
                calls.add(new Call(b.id(),candidates,arguments,s));
            }
            SemanticModel model=new SemanticModel(uri,version,cst.source(),types,scopes,symbols,signatures,calls);
            boolean incomplete=false;
            for(Call call:calls) {
                CallInference.Resolution resolution=inference(model).infer(signatures(model,call),call(model,call));
                if(resolution.state()==CallInference.State.INCOMPATIBLE)diagnostics.add(new Diagnostic("INCOMPATIBLE_CALL",uri,version,call.span(),call.id()));
                else if(resolution.state()==CallInference.State.AMBIGUOUS)diagnostics.add(new Diagnostic("AMBIGUOUS_CALL",uri,version,call.span(),call.id()));
                else if(Set.of(CallInference.State.LIMIT,CallInference.State.UNSUPPORTED,CallInference.State.CYCLE,CallInference.State.INVALID).contains(resolution.state())) {
                    incomplete=true;diagnostics.add(new Diagnostic("INFERENCE_"+resolution.state(),uri,version,call.span(),call.id()));
                }
            }
            return new Analysis(incomplete?EditorCst.Status.PARTIAL:cst.status(),model,diagnostics,sites);
        }catch(Failure failure) {diagnostics.add(new Diagnostic(failure.code,uri,version,failure.span,failure.rule));}
        catch(ModelException failure) {diagnostics.add(new Diagnostic(failure.code(),uri,version,new Span(failure.start(),failure.end()),""));}
        return new Analysis(EditorCst.Status.FAILED,null,diagnostics,List.of());
    }
    private static TypeSystem.TypeRef ref(String name) {return name.equals(UNKNOWN)?TypeSystem.TypeRef.unknown():TypeSystem.TypeRef.named(name);}
    private static CallInference inference(SemanticModel model) {
        List<TypeSystem.Definition> definitions=model.types().values().stream().map(type->new TypeSystem.Definition(type.id(),List.of(),List.of(),type.supertypes().stream().map(SemanticRuleEngine::ref).toList(),Map.of(),false)).toList();
        return new CallInference(new TypeSystem(new TypeSystem.DeclaredProvider(TypeSystem.Policy.NOMINAL,definitions,Set.of(TypeSystem.Capability.NAMED)),List.of(),4096),4096);
    }
    private static List<CallInference.Signature> signatures(SemanticModel model,Call call) {
        return call.signatures().stream().map(model.signatures()::get).map(sig->new CallInference.Signature(sig.id(),List.of(),sig.parameters().stream().map(SemanticRuleEngine::ref).toList(),ref(sig.result()),false,model.uri(),model.version(),sig.span())).toList();
    }
    private static CallInference.Call call(SemanticModel model,Call call) {return new CallInference.Call(model.uri(),model.version(),call.span(),call.arguments().stream().map(a->new CallInference.Argument(ref(a.type()),a.span())).toList(),TypeSystem.TypeRef.unknown());}
    public record Query(CallInference.Resolution resolution,List<TypeSystem.TypeRef> expected,List<CallInference.Completion> completions,Span edit) {
        public Query {expected=List.copyOf(expected);completions=List.copyOf(completions);}
    }
    public static Optional<Query> query(Analysis analysis,String uri,long version,int cursor,String prefix) {
        SemanticModel model=analysis.model();if(model==null)return Optional.empty();
        if(!model.uri().equals(uri)||model.version()!=version)throw new IllegalArgumentException("STALE_SNAPSHOT");
        if(cursor<0||cursor>model.source().codePointCount(0,model.source().length()))throw new IllegalArgumentException("INVALID_CURSOR");
        List<Site> sites=analysis.sites().stream().filter(s->s.span().start()<=cursor&&cursor<=s.span().end()).toList();
        if(sites.isEmpty())return Optional.empty();
        if(sites.size()!=1)throw new IllegalArgumentException("AMBIGUOUS_CALL_SITE");
        Site site=sites.get(0);int prefixLength=prefix.codePointCount(0,prefix.length());if(prefixLength>cursor)throw new IllegalArgumentException("INVALID_PREFIX");Span edit=new Span(cursor-prefixLength,cursor);
        if(edit.start()<site.span().start() || !slice(model.source(),edit).equals(prefix))throw new IllegalArgumentException("INVALID_PREFIX");
        Call call=model.calls().get(site.call());CallInference inference=inference(model);
        var resolution=inference.expectedArgument(signatures(model,call),call(model,call),site.argument());
        var values=model.visibleSymbolsAt(cursor).stream().filter(s->s.name().startsWith(prefix)).map(s->new CallInference.Value(s.id(),s.name(),ref(s.type()),uri,version,s.declaration())).toList();
        return Optional.of(new Query(resolution,inference.expectedTypes(resolution,site.argument()),inference.complete(values,resolution,site.argument()),edit));
    }
}
