package org.unlaxer.dsl.semantic;

import java.util.*;
import org.unlaxer.dsl.semantic.SemanticModel.Span;
import org.unlaxer.dsl.semantic.TypeSystem.*;

/** Bounded constraint inference, preserving every viable overload instead of choosing by order. */
public final class CallInference {
    public enum State { RESOLVED, AMBIGUOUS, UNKNOWN, INCOMPATIBLE, UNSUPPORTED, CYCLE, LIMIT, INVALID }
    public record Parameter(String id,TypeRef bound) {}
    public record Signature(String id,List<Parameter> variables,List<TypeRef> parameters,TypeRef result,boolean varargs,String uri,long version,Span span) {
        public Signature {
            variables=List.copyOf(variables); parameters=List.copyOf(parameters); Objects.requireNonNull(result); Objects.requireNonNull(span);
            if(id==null || id.isEmpty() || uri==null || uri.isEmpty() || version<0 || varargs && parameters.isEmpty()
                    || variables.stream().map(Parameter::id).anyMatch(s->s==null || s.isEmpty())
                    || variables.stream().map(Parameter::id).distinct().count()!=variables.size()) throw new IllegalArgumentException("invalid signature");
            variables.forEach(v->Objects.requireNonNull(v.bound()));
        }
    }
    public record Argument(TypeRef type,Span span) { public Argument { Objects.requireNonNull(type); Objects.requireNonNull(span); } }
    public record Call(String uri,long version,Span span,List<Argument> arguments,TypeRef expectedReturn) {
        public Call {
            Objects.requireNonNull(uri); Objects.requireNonNull(span); Objects.requireNonNull(expectedReturn); arguments=List.copyOf(arguments);
            if(uri.isEmpty() || version<0) throw new IllegalArgumentException("invalid call snapshot");
            int end=span.start();
            for(Argument argument:arguments) {
                if(!span.contains(argument.span()) || argument.span().start()<end) throw new IllegalArgumentException("argument outside call");
                end=argument.span().end();
            }
        }
    }
    public record Constraint(String role,TypeRef actual,TypeRef expected,String uri,long version,Span span,Decision decision) {}
    public record Candidate(String signature,Status status,Map<String,TypeRef> substitution,List<TypeRef> parameters,TypeRef result,boolean varargs,String uri,long version,Span span,List<Constraint> constraints) {
        public Candidate { substitution=Map.copyOf(substitution); parameters=List.copyOf(parameters); constraints=List.copyOf(constraints); }
    }
    public record Resolution(State state,String uri,long version,List<Candidate> candidates) { public Resolution { candidates=List.copyOf(candidates); } }
    public record Value(String id,String name,TypeRef type,String uri,long version,Span span) {}
    public record Completion(Value value,Decision decision) {}
    private final TypeSystem types;
    private final int maximumSteps;
    private static final class Budget {
        private int remaining;
        private Budget(int remaining) { this.remaining=remaining; }
        private void use(int depth) { if(remaining--<=0 || depth>=128) throw new TypeException(Status.LIMIT,"INFERENCE_LIMIT"); }
    }
    public CallInference(TypeSystem types,int maximumSteps) {
        this.types=Objects.requireNonNull(types);
        if(maximumSteps<1 || maximumSteps>4096) throw new IllegalArgumentException("maximumSteps must be 1..4096");
        this.maximumSteps=maximumSteps;
    }
    public Resolution infer(List<Signature> signatures,Call call) {
        if(signatures.stream().map(Signature::id).distinct().count()!=signatures.size()) throw new IllegalArgumentException("duplicate signature");
        Budget budget=new Budget(maximumSteps); List<Candidate> candidates=new ArrayList<>();
        for(Signature signature:signatures) {
            Candidate candidate;
            try { candidate=infer(signature,call,budget); }
            catch(TypeException exception) {
                Decision decision=Decision.of(exception.status(),exception.getMessage());
                candidate=new Candidate(signature.id(),exception.status(),Map.of(),signature.parameters(),signature.result(),signature.varargs(),signature.uri(),signature.version(),signature.span(),
                    List.of(new Constraint("solver",TypeRef.unknown(),TypeRef.unknown(),call.uri(),call.version(),call.span(),decision)));
            }
            candidates.add(candidate); if(candidate.status()==Status.LIMIT) break;
        }
        State state;
        Optional<Status> failure=List.of(Status.LIMIT,Status.CYCLE,Status.INVALID,Status.UNSUPPORTED).stream().filter(s->candidates.stream().anyMatch(c->c.status()==s)).findFirst();
        List<Candidate> viable=candidates.stream().filter(c->c.status()==Status.YES || c.status()==Status.UNKNOWN).toList();
        if(failure.isPresent()) state=State.valueOf(failure.get().name());
        else if(viable.size()>1) state=State.AMBIGUOUS;
        else if(viable.size()==1) state=viable.get(0).status()==Status.YES?State.RESOLVED:State.UNKNOWN;
        else state=signatures.isEmpty()?State.UNKNOWN:State.INCOMPATIBLE;
        return new Resolution(state,call.uri(),call.version(),candidates);
    }
    /** Ignore the expression being edited when deriving its expected type. */
    public Resolution expectedArgument(List<Signature> signatures,Call call,int index) {
        if(index<0 || index>call.arguments().size()) throw new IllegalArgumentException("invalid argument index");
        List<Argument> arguments=new ArrayList<>(call.arguments());
        if(index==arguments.size()) arguments.add(new Argument(TypeRef.unknown(),new Span(call.span().end(),call.span().end())));
        else arguments.set(index,new Argument(TypeRef.unknown(),arguments.get(index).span()));
        return infer(signatures,new Call(call.uri(),call.version(),call.span(),arguments,call.expectedReturn()));
    }
    private Candidate infer(Signature signature,Call call,Budget budget) {
        budget.use(0);
        List<Constraint> checks=new ArrayList<>();
        int count=signature.parameters().size();
        if(!signature.varargs() && call.arguments().size()!=count || signature.varargs() && call.arguments().size()<count-1) {
            checks.add(new Constraint("arity",TypeRef.unknown(),TypeRef.unknown(),call.uri(),call.version(),call.span(),Decision.of(Status.NO,"CALL_ARITY")));
            return candidate(signature,Map.of(),signature.parameters(),signature.result(),checks);
        }
        Map<String,List<TypeRef>> lower=new LinkedHashMap<>(),upper=new LinkedHashMap<>();
        signature.variables().forEach(p->{lower.put(p.id(),new ArrayList<>());upper.put(p.id(),new ArrayList<>());});
        for(int i=0;i<call.arguments().size();i++) collect(parameter(signature.parameters(),signature.varargs(),i),call.arguments().get(i).type(),true,lower,upper,budget,0);
        if(call.expectedReturn().kind()!=Kind.UNKNOWN) collect(signature.result(),call.expectedReturn(),false,lower,upper,budget,0);
        Map<String,TypeRef> bindings=new LinkedHashMap<>();
        for(Parameter variable:signature.variables()) {
            budget.use(0); List<TypeRef> lows=lower.get(variable.id()),ups=upper.get(variable.id());
            TypeRef selected=lows.isEmpty()?join(ups,false,budget):join(lows,true,budget);
            if(selected!=null) bindings.put(variable.id(),selected);
        }
        List<TypeRef> parameters=signature.parameters().stream().map(t->TypeSystem.substitute(t,bindings,maximumSteps)).toList();
        TypeRef result=TypeSystem.substitute(signature.result(),bindings,maximumSteps);
        for(int i=0;i<call.arguments().size();i++) {
            Argument argument=call.arguments().get(i); TypeRef expected=parameter(parameters,signature.varargs(),i);
            check(checks,"argument:"+i,argument.type(),expected,call.uri(),call.version(),argument.span(),budget);
        }
        for(Parameter variable:signature.variables()) {
            if(variable.bound().kind()==Kind.UNKNOWN) continue;
            check(checks,"bound:"+variable.id(),bindings.getOrDefault(variable.id(),TypeRef.variable(variable.id())),TypeSystem.substitute(variable.bound(),bindings,maximumSteps),signature.uri(),signature.version(),signature.span(),budget);
        }
        if(call.expectedReturn().kind()!=Kind.UNKNOWN) check(checks,"return",result,call.expectedReturn(),call.uri(),call.version(),call.span(),budget);
        check(checks,"result",result,result,call.uri(),call.version(),call.span(),budget);
        // An unbound variable in a result must not masquerade as a fully inferred callable.
        if(hasVariable(result) || parameters.stream().anyMatch(CallInference::hasVariable))
            checks.add(new Constraint("unbound",result,result,call.uri(),call.version(),call.span(),Decision.of(Status.UNKNOWN,"UNBOUND_VARIABLE")));
        return candidate(signature,bindings,parameters,result,checks);
    }
    private void check(List<Constraint> checks,String role,TypeRef actual,TypeRef expected,String uri,long version,Span span,Budget budget) {
        budget.use(0); checks.add(new Constraint(role,actual,expected,uri,version,span,types.compare(actual,expected)));
    }
    private static Candidate candidate(Signature s,Map<String,TypeRef> bindings,List<TypeRef> parameters,TypeRef result,List<Constraint> checks) {
        Status status=TypeSystem.combine(true,"CALL_CONSTRAINTS",checks.stream().map(Constraint::decision).toList()).status();
        return new Candidate(s.id(),status,bindings,parameters,result,s.varargs(),s.uri(),s.version(),s.span(),checks);
    }
    private static boolean hasVariable(TypeRef t) { return t.kind()==Kind.VARIABLE || t.arguments().stream().anyMatch(CallInference::hasVariable); }
    private TypeRef join(List<TypeRef> values,boolean lower,Budget budget) {
        if(values.isEmpty()) return null;
        TypeRef selected=values.get(0);
        for(int i=1;i<values.size();i++) {
            budget.use(0); TypeRef next=values.get(i);
            Decision forward=types.compare(next,selected),reverse=types.compare(selected,next);
            for(Decision decision:List.of(forward,reverse)) if(decision.status()!=Status.YES && decision.status()!=Status.NO && decision.status()!=Status.UNKNOWN) throw new TypeException(decision.status(),decision.rule());
            if((lower?forward:reverse).status()==Status.YES) continue;
            if((lower?reverse:forward).status()==Status.YES) selected=next;
            else {
                Capability cap=lower?Capability.UNION:Capability.INTERSECTION;
                if(!types.provider().capabilities().contains(cap)) throw new TypeException(Status.UNSUPPORTED,cap.name());
                selected=new TypeRef(lower?Kind.UNION:Kind.INTERSECTION,"",List.of(selected,next));
            }
        }
        return selected;
    }
    private void collect(TypeRef pattern,TypeRef actual,boolean lower,Map<String,List<TypeRef>> lows,Map<String,List<TypeRef>> ups,Budget budget,int depth) {
        budget.use(depth);
        if(actual.kind()==Kind.UNKNOWN || actual.kind()==Kind.VARIABLE) return;
        if(pattern.kind()==Kind.VARIABLE && lows.containsKey(pattern.name())) { (lower?lows:ups).get(pattern.name()).add(actual); return; }
        if(pattern.kind()!=actual.kind() || pattern.arguments().size()!=actual.arguments().size()) return;
        if(pattern.kind()==Kind.NAMED) {
            if(!pattern.name().equals(actual.name())) return;
            if(pattern.arguments().isEmpty()) return;
            List<Variance> variance=types.provider().variance(pattern.name()).orElseThrow(()->new TypeException(Status.UNSUPPORTED,"INFERENCE_VARIANCE"));
            if(variance.size()!=pattern.arguments().size()) throw new TypeException(Status.INVALID,"TYPE_ARITY");
            for(int i=0;i<variance.size();i++) {
                TypeRef p=pattern.arguments().get(i),a=actual.arguments().get(i);
                if(variance.get(i)!=Variance.CONTRAVARIANT) collect(p,a,lower,lows,ups,budget,depth+1);
                if(variance.get(i)!=Variance.COVARIANT) collect(p,a,!lower,lows,ups,budget,depth+1);
            }
        } else if(pattern.kind()==Kind.FUNCTION) {
            for(int i=0;i<pattern.arguments().size();i++) collect(pattern.arguments().get(i),actual.arguments().get(i),i==pattern.arguments().size()-1?lower:!lower,lows,ups,budget,depth+1);
        } else if(pattern.kind()==Kind.NULLABLE) collect(pattern.arguments().get(0),actual.arguments().get(0),lower,lows,ups,budget,depth+1);
    }
    private static TypeRef parameter(List<TypeRef> parameters,boolean varargs,int index) { return parameters.get(varargs?Math.min(index,parameters.size()-1):index); }
    public List<TypeRef> expectedTypes(Resolution resolution,int index) {
        if(index<0) throw new IllegalArgumentException("invalid argument index");
        if(!Set.of(State.RESOLVED,State.AMBIGUOUS,State.UNKNOWN).contains(resolution.state())) return List.of();
        List<TypeRef> result=new ArrayList<>();
        for(Candidate c:resolution.candidates()) if((c.status()==Status.YES || c.status()==Status.UNKNOWN) && (index<c.parameters().size() || c.varargs())) {
            TypeRef type=parameter(c.parameters(),c.varargs(),index); if(!result.contains(type)) result.add(type);
        }
        return List.copyOf(result);
    }
    /** Diagnostics and completion consume this exact decision tree. */
    public Decision assess(Resolution resolution,int index,TypeRef actual) {
        if(index<0) throw new IllegalArgumentException("invalid argument index");
        if(Set.of(State.UNSUPPORTED,State.CYCLE,State.LIMIT,State.INVALID).contains(resolution.state()))
            return new Decision(Status.valueOf(resolution.state().name()),"INFERENCE_FAILURE",resolution.candidates().stream().flatMap(c->c.constraints().stream()).map(Constraint::decision).toList());
        return TypeSystem.combine(false,"EXPECTED_ARGUMENT",expectedTypes(resolution,index).stream().map(t->types.compare(actual,t)).toList());
    }
    public List<Completion> complete(List<Value> visible,Resolution resolution,int index) {
        List<Completion> result=new ArrayList<>();
        for(Value value:visible) { Decision decision=assess(resolution,index,value.type()); if(decision.status()==Status.YES || decision.status()==Status.UNKNOWN) result.add(new Completion(value,decision)); }
        result.sort(Comparator.comparing((Completion c)->c.decision().status()==Status.YES?0:1).thenComparing(c->c.value().name(),(a,b)->Arrays.compare(a.codePoints().toArray(),b.codePoints().toArray())));
        return List.copyOf(result);
    }
}
