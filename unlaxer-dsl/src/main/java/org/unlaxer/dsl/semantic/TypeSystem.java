package org.unlaxer.dsl.semantic;

import java.util.*;

/** Bounded portable type relations. A language provider owns the meaning of named types. */
public final class TypeSystem {
    public enum Kind { NAMED, VARIABLE, UNKNOWN, UNION, INTERSECTION, NULLABLE, FUNCTION, ALIAS, NULL }
    public enum Capability { NAMED, GENERICS, UNION, INTERSECTION, NULLABLE, FUNCTION, ALIAS, STRUCTURAL, TRAIT }
    public enum Status { YES, NO, UNKNOWN, UNSUPPORTED, CYCLE, LIMIT, INVALID }
    public enum Variance { INVARIANT, COVARIANT, CONTRAVARIANT }
    public enum Policy { NOMINAL, STRUCTURAL, TRAIT }
    public record TypeRef(Kind kind, String name, List<TypeRef> arguments) {
        public TypeRef {
            Objects.requireNonNull(kind); Objects.requireNonNull(name); arguments=List.copyOf(arguments);
            boolean named=kind==Kind.NAMED || kind==Kind.VARIABLE || kind==Kind.ALIAS;
            if (named==name.isEmpty()) throw new IllegalArgumentException("invalid type name");
            int size=arguments.size();
            if ((kind==Kind.VARIABLE || kind==Kind.UNKNOWN || kind==Kind.NULL) && size!=0
                    || kind==Kind.NULLABLE && size!=1
                    || (kind==Kind.UNION || kind==Kind.INTERSECTION || kind==Kind.FUNCTION) && size==0)
                throw new IllegalArgumentException("invalid type arity");
        }
        public static TypeRef named(String name, TypeRef... arguments) { return new TypeRef(Kind.NAMED,name,List.of(arguments)); }
        public static TypeRef variable(String name) { return new TypeRef(Kind.VARIABLE,name,List.of()); }
        public static TypeRef unknown() { return new TypeRef(Kind.UNKNOWN,"",List.of()); }
    }
    public record Decision(Status status, String rule, List<Decision> evidence) {
        public Decision { Objects.requireNonNull(status); Objects.requireNonNull(rule); evidence=List.copyOf(evidence); }
        public static Decision of(Status status,String rule) { return new Decision(status,rule,List.of()); }
    }
    public record Alias(String name,List<String> parameters,TypeRef body) {
        public Alias { Objects.requireNonNull(name); parameters=List.copyOf(parameters); Objects.requireNonNull(body); validateParameters(parameters); }
    }
    public record Definition(String name,List<String> parameters,List<Variance> variance,List<TypeRef> parents,Map<String,TypeRef> fields,boolean structural) {
        public Definition {
            Objects.requireNonNull(name); parameters=List.copyOf(parameters); variance=List.copyOf(variance);
            parents=List.copyOf(parents); fields=Map.copyOf(fields); validateParameters(parameters);
            if(name.isEmpty() || parameters.size()!=variance.size()) throw new IllegalArgumentException("invalid named definition");
        }
    }
    @FunctionalInterface public interface Relation { Decision compare(TypeRef actual,TypeRef expected); }
    public interface Provider {
        Set<Capability> capabilities();
        default Optional<List<Variance>> variance(String name) { return Optional.empty(); }
        default Decision validate(TypeRef named) { return Decision.of(Status.YES,"PROVIDER_TYPE"); }
        Decision named(TypeRef actual,TypeRef expected,Relation relation);
    }
    /** Read-only structural fields and explicit nominal/trait edges; not a complete Java/TS/Rust checker. */
    public static final class DeclaredProvider implements Provider {
        private final Policy policy;
        private final Map<String,Definition> definitions;
        private final Set<Capability> capabilities;
        public DeclaredProvider(Policy policy,List<Definition> definitions,Set<Capability> capabilities) {
            this.policy=Objects.requireNonNull(policy); this.capabilities=Set.copyOf(capabilities);
            Map<String,Definition> collected=new LinkedHashMap<>();
            for(Definition definition:definitions) if(collected.putIfAbsent(definition.name(),definition)!=null) throw new IllegalArgumentException("duplicate type");
            this.definitions=Map.copyOf(collected);
        }
        @Override public Set<Capability> capabilities() { return capabilities; }
        @Override public Optional<List<Variance>> variance(String name) { return Optional.ofNullable(definitions.get(name)).map(Definition::variance); }
        @Override public Decision validate(TypeRef ref) {
            Definition definition=definitions.get(ref.name());
            if(definition==null) return Decision.of(Status.INVALID,"UNDEFINED_TYPE");
            return Decision.of(definition.parameters().size()==ref.arguments().size()?Status.YES:Status.INVALID,"TYPE_ARITY");
        }
        @Override public Decision named(TypeRef actual,TypeRef expected,Relation relation) {
            Definition a=definitions.get(actual.name()),b=definitions.get(expected.name());
            if(a==null || b==null) return Decision.of(Status.INVALID,"UNDEFINED_TYPE");
            if(a.parameters().size()!=actual.arguments().size() || b.parameters().size()!=expected.arguments().size()) return Decision.of(Status.INVALID,"TYPE_ARITY");
            if(actual.name().equals(expected.name())) {
                List<Decision> checks=new ArrayList<>();
                for(int i=0;i<a.variance().size();i++) {
                    TypeRef x=actual.arguments().get(i),y=expected.arguments().get(i);
                    switch(a.variance().get(i)) {
                        case COVARIANT -> checks.add(relation.compare(x,y));
                        case CONTRAVARIANT -> checks.add(relation.compare(y,x));
                        case INVARIANT -> { checks.add(relation.compare(x,y)); checks.add(relation.compare(y,x)); }
                    }
                }
                return combine(true,"NAMED_ARGUMENTS",checks);
            }
            Map<String,TypeRef> aBindings=bind(a.parameters(),actual.arguments()), bBindings=bind(b.parameters(),expected.arguments());
            if(policy==Policy.STRUCTURAL && b.structural()) {
                if(!capabilities.contains(Capability.STRUCTURAL)) return Decision.of(Status.UNSUPPORTED,"STRUCTURAL");
                List<Decision> checks=new ArrayList<>();
                for(String field:b.fields().keySet().stream().sorted(TypeSystem::compareText).toList()) {
                    if(!a.fields().containsKey(field)) checks.add(Decision.of(Status.NO,"MISSING_FIELD:"+field));
                    else {
                        TypeRef x=substitute(a.fields().get(field),aBindings,256),y=substitute(b.fields().get(field),bBindings,256);
                        Decision nested=relation.compare(x,y);
                        checks.add(new Decision(nested.status(),"FIELD:"+field,List.of(nested)));
                    }
                }
                return combine(true,"STRUCTURAL_FIELDS",checks);
            }
            if(policy==Policy.TRAIT && !capabilities.contains(Capability.TRAIT)) return Decision.of(Status.UNSUPPORTED,"TRAIT");
            List<Decision> parents=new ArrayList<>();
            for(TypeRef parent:a.parents()) parents.add(relation.compare(substitute(parent,aBindings,256),expected));
            return combine(false,policy==Policy.TRAIT?"DECLARED_TRAIT_EDGES":"NOMINAL_PARENTS",parents);
        }
    }
    public static final class TypeException extends IllegalArgumentException {
        private final Status status;
        public TypeException(Status status,String reason) { super(reason); this.status=status; }
        public Status status() { return status; }
    }
    private final Provider provider;
    private final Map<String,Alias> aliases;
    private final int maximumSteps;
    public TypeSystem(Provider provider,List<Alias> aliases,int maximumSteps) {
        this.provider=Objects.requireNonNull(provider);
        if(maximumSteps<1 || maximumSteps>4096) throw new IllegalArgumentException("maximumSteps must be 1..4096");
        this.maximumSteps=maximumSteps;
        Map<String,Alias> collected=new HashMap<>();
        for(Alias alias:aliases) if(alias.name().isEmpty() || collected.putIfAbsent(alias.name(),alias)!=null) throw new IllegalArgumentException("invalid alias");
        this.aliases=Map.copyOf(collected);
    }
    public Provider provider() { return provider; }
    public Decision compare(TypeRef actual,TypeRef expected) {
        try {
            Budget validation=new Budget(4096);
            validate(actual,validation,0); validate(expected,validation,0);
            return compare(actual,expected,new Budget(maximumSteps),new HashSet<>(),new HashSet<>(),0);
        } catch(TypeException exception) { return Decision.of(exception.status(),exception.getMessage()); }
    }
    private void validate(TypeRef ref,Budget budget,int depth) {
        budget.use(depth);
        Set<Capability> required=EnumSet.noneOf(Capability.class); required(ref,required);
        for(Capability capability:required) if(!provider.capabilities().contains(capability)) throw new TypeException(Status.UNSUPPORTED,capability.name());
        if(ref.kind()==Kind.NAMED) { Decision result=provider.validate(ref); if(result.status()!=Status.YES) throw new TypeException(result.status(),result.rule()); }
        if(ref.kind()==Kind.ALIAS) {
            Alias alias=aliases.get(ref.name());
            if(alias==null) throw new TypeException(Status.INVALID,"UNDEFINED_ALIAS:"+ref.name());
            if(alias.parameters().size()!=ref.arguments().size()) throw new TypeException(Status.INVALID,"ALIAS_ARITY:"+ref.name());
        }
        for(TypeRef arg:ref.arguments()) validate(arg,budget,depth+1);
    }
    private record Pair(TypeRef actual,TypeRef expected) {}
    private record AliasUse(boolean actual,TypeRef ref) {}
    private static final class Budget {
        private int remaining;
        private Budget(int remaining) { this.remaining=remaining; }
        private void use(int depth) { if(remaining--<=0 || depth>=128) throw new TypeException(Status.LIMIT,"TYPE_LIMIT"); }
    }
    private Decision compare(TypeRef actual,TypeRef expected,Budget budget,Set<Pair> active,Set<AliasUse> activeAliases,int depth) {
        try {
            budget.use(depth);
            Set<Capability> required=EnumSet.noneOf(Capability.class);
            required(actual,required); required(expected,required);
            for(Capability capability:required) if(!provider.capabilities().contains(capability)) return Decision.of(Status.UNSUPPORTED,capability.name());
            if(actual.kind()==Kind.ALIAS || expected.kind()==Kind.ALIAS) {
                boolean left=actual.kind()==Kind.ALIAS; TypeRef ref=left?actual:expected;
                AliasUse key=new AliasUse(left,ref);
                if(!activeAliases.add(key)) return Decision.of(Status.CYCLE,"ALIAS_CYCLE:"+ref.name());
                try {
                    Alias alias=aliases.get(ref.name());
                    if(alias==null) return Decision.of(Status.INVALID,"UNDEFINED_ALIAS:"+ref.name());
                    if(alias.parameters().size()!=ref.arguments().size()) return Decision.of(Status.INVALID,"ALIAS_ARITY:"+ref.name());
                    TypeRef expanded=substitute(alias.body(),bind(alias.parameters(),ref.arguments()),budget,new HashSet<>(),depth+1);
                    validate(expanded,budget,depth+1);
                    Decision result=compare(left?expanded:actual,left?expected:expanded,budget,active,activeAliases,depth+1);
                    return new Decision(result.status(),"ALIAS:"+ref.name(),List.of(result));
                } finally { activeAliases.remove(key); }
            }
            if(actual.kind()==Kind.UNKNOWN || expected.kind()==Kind.UNKNOWN || actual.kind()==Kind.VARIABLE || expected.kind()==Kind.VARIABLE)
                return Decision.of(Status.UNKNOWN,"UNRESOLVED_TYPE");
            Pair pair=new Pair(actual,expected);
            if(!active.add(pair)) return Decision.of(Status.CYCLE,"RELATION_CYCLE");
            try {
                Relation relation=(a,b)->compare(a,b,budget,active,activeAliases,depth+1);
                if(actual.kind()==Kind.NULLABLE) return combine(true,"ACTUAL_NULLABLE",List.of(relation.compare(actual.arguments().get(0),expected),relation.compare(nil(),expected)));
                if(expected.kind()==Kind.NULLABLE) return combine(false,"EXPECTED_NULLABLE",List.of(relation.compare(actual,expected.arguments().get(0)),relation.compare(actual,nil())));
                if(actual.kind()==Kind.UNION) return combine(true,"ACTUAL_UNION",actual.arguments().stream().map(t->relation.compare(t,expected)).toList());
                if(expected.kind()==Kind.INTERSECTION) return combine(true,"EXPECTED_INTERSECTION",expected.arguments().stream().map(t->relation.compare(actual,t)).toList());
                if(expected.kind()==Kind.UNION) return combine(false,"EXPECTED_UNION",expected.arguments().stream().map(t->relation.compare(actual,t)).toList());
                if(actual.kind()==Kind.INTERSECTION) return combine(false,"ACTUAL_INTERSECTION",actual.arguments().stream().map(t->relation.compare(t,expected)).toList());
                if(actual.kind()==Kind.NULL || expected.kind()==Kind.NULL) return Decision.of(actual.kind()==expected.kind()?Status.YES:Status.NO,"NULL");
                if(actual.kind()==Kind.FUNCTION && expected.kind()==Kind.FUNCTION) {
                    if(actual.arguments().size()!=expected.arguments().size()) return Decision.of(Status.NO,"FUNCTION_ARITY");
                    int result=actual.arguments().size()-1; List<Decision> checks=new ArrayList<>();
                    for(int i=0;i<result;i++) checks.add(relation.compare(expected.arguments().get(i),actual.arguments().get(i)));
                    checks.add(relation.compare(actual.arguments().get(result),expected.arguments().get(result)));
                    return combine(true,"FUNCTION",checks);
                }
                if(actual.kind()!=Kind.NAMED || expected.kind()!=Kind.NAMED) return Decision.of(Status.NO,"TYPE_KIND");
                return provider.named(actual,expected,relation);
            } finally { active.remove(pair); }
        } catch(TypeException exception) { return Decision.of(exception.status(),exception.getMessage()); }
    }
    private static TypeRef nil() { return new TypeRef(Kind.NULL,"",List.of()); }
    private static void required(TypeRef ref,Set<Capability> capabilities) {
        if(ref.kind()==Kind.NAMED) { capabilities.add(Capability.NAMED); if(!ref.arguments().isEmpty()) capabilities.add(Capability.GENERICS); }
        else if(ref.kind()!=Kind.VARIABLE && ref.kind()!=Kind.UNKNOWN && ref.kind()!=Kind.NULL) capabilities.add(Capability.valueOf(ref.kind().name()));
    }
    public static Decision combine(boolean all,String rule,List<Decision> checks) {
        for(Status status:List.of(Status.LIMIT,Status.CYCLE,Status.INVALID,Status.UNSUPPORTED))
            if(checks.stream().anyMatch(c->c.status()==status)) return new Decision(status,rule,checks);
        Status decisive=all?Status.NO:Status.YES;
        if(checks.stream().anyMatch(c->c.status()==decisive)) return new Decision(decisive,rule,checks);
        if(checks.stream().anyMatch(c->c.status()==Status.UNKNOWN)) return new Decision(Status.UNKNOWN,rule,checks);
        return new Decision(all?Status.YES:Status.NO,rule,checks);
    }
    private static int compareText(String a,String b) { return Arrays.compare(a.codePoints().toArray(),b.codePoints().toArray()); }
    private static void validateParameters(List<String> parameters) {
        if(parameters.stream().anyMatch(String::isEmpty) || new HashSet<>(parameters).size()!=parameters.size()) throw new IllegalArgumentException("invalid type parameters");
    }
    private static Map<String,TypeRef> bind(List<String> parameters,List<TypeRef> arguments) {
        Map<String,TypeRef> result=new HashMap<>(); for(int i=0;i<parameters.size();i++) result.put(parameters.get(i),arguments.get(i)); return result;
    }
    public static TypeRef substitute(TypeRef ref,Map<String,TypeRef> bindings,int maximumSteps) {
        if(maximumSteps<1 || maximumSteps>4096) throw new IllegalArgumentException("maximumSteps must be 1..4096");
        return substitute(ref,bindings,new Budget(maximumSteps),new HashSet<>(),0);
    }
    private static TypeRef substitute(TypeRef ref,Map<String,TypeRef> bindings,Budget budget,Set<String> active,int depth) {
        budget.use(depth);
        if(ref.kind()==Kind.VARIABLE && bindings.containsKey(ref.name())) {
            TypeRef replacement=bindings.get(ref.name()); if(replacement.equals(ref)) return ref;
            if(!active.add(ref.name())) throw new TypeException(Status.CYCLE,"SUBSTITUTION_CYCLE:"+ref.name());
            try { return substitute(replacement,bindings,budget,active,depth+1); } finally { active.remove(ref.name()); }
        }
        List<TypeRef> arguments=new ArrayList<>(); for(TypeRef arg:ref.arguments()) arguments.add(substitute(arg,bindings,budget,active,depth+1));
        return new TypeRef(ref.kind(),ref.name(),arguments);
    }
}
