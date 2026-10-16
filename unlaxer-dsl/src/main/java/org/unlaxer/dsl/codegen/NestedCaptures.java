package org.unlaxer.dsl.codegen;

import java.util.ArrayList;
import java.util.List;
import org.unlaxer.dsl.bootstrap.UBNFAST.*;
import org.unlaxer.dsl.codegen.rust.GrammarIR;
import org.unlaxer.dsl.codegen.rust.GrammarIR.Cardinality;
import org.unlaxer.dsl.codegen.rust.GrammarIR.Kind;

/** Opt-in recursive container schema. The legacy flat capture contract remains the default. */
public final class NestedCaptures {
    private NestedCaptures() {}
    public static boolean enabled(RuleDecl rule) {
        return rule.annotations().stream().anyMatch(a -> a instanceof SimpleAnnotation s && s.name().equals("nestedCaptures"));
    }
    public static void requireValid(GrammarDecl grammar) {
        var issues=problems(grammar);
        if (!issues.isEmpty()) throw new IllegalArgumentException(issues.get(0).message());
    }
    public static List<GrammarValidator.ValidationIssue> problems(GrammarDecl grammar) {
        var result=new ArrayList<GrammarValidator.ValidationIssue>();
        var checkedMappings=new java.util.HashSet<String>();
        for (RuleDecl rule:grammar.rules()) if(enabled(rule)) {
            try { validateRule(grammar,rule); }
            catch(IllegalArgumentException error) { result.add(new GrammarValidator.ValidationIssue("E-NESTED-SHAPE",error.getMessage(),"Use supported explicit nested scalar containers.",rule.name())); }
        }
        // Validate each contributor before cross-rule traversal and shape comparison.
        if (result.isEmpty()) for (RuleDecl rule:grammar.rules()) if(enabled(rule)) {
            var mapping=MapperElementUtil.getMappingAnnotation(rule).orElseThrow();
            if(checkedMappings.add(mapping.className())) try { requireSharedShape(grammar,rule,mapping); }
            catch(IllegalArgumentException error) { result.add(new GrammarValidator.ValidationIssue("E-NESTED-SHAPE",error.getMessage(),"Use identical recursive field schemas.",rule.name())); }
        }
        return List.copyOf(result);
    }
    private static void validateRule(GrammarDecl grammar,RuleDecl rule) {
            checkTraversal(rule.body(), 0);
            if (rule.annotations().stream().filter(a -> a instanceof SimpleAnnotation s && s.name().equals("nestedCaptures")).count() != 1)
                throw invalid(rule, "duplicate annotation");
            var mapping = MapperElementUtil.getMappingAnnotation(rule).orElseThrow(() -> invalid(rule,"@mapping required"));
            if (rule.annotations().stream().anyMatch(a -> a instanceof LeftAssocAnnotation || a instanceof RightAssocAnnotation || a instanceof SkipAnnotation || a instanceof EnumAnnotation || a instanceof EvalAnnotation || a instanceof CommonFieldAnnotation && !mapping.paramNames().isEmpty())
                    || TokenStreamGrammar.enabled(grammar))
                throw invalid(rule,"combination requires the remaining #583 implementation");
            for (var problem : TypeofConstraints.problems(rule))
                throw new IllegalArgumentException(problem.code() + ": " + problem.message());
            var sites = new CaptureBindingPlan(rule);
            if (sites.allSites().size() != captureNames(rule).size()) throw invalid(rule,"mapping must name each unique outer capture");
            for (String field : captureNames(rule)) {
                if (sites.sites(field).size() != 1) throw invalid(rule,"one capture site required for " + field);
                shape(grammar, sites.sites(field).get(0).element(), 0);
            }
            if (captureNames(rule).stream().anyMatch(name -> field(grammar,rule,name).leaf()==Kind.VALUE)) {
                for (var candidate : grammar.rules()) {
                    String name=MapperElementUtil.getMappingAnnotation(candidate).map(MappingAnnotation::className).orElse("");
                    if (name.equals("NestedValue") || name.startsWith("NestedValue.") || EnumSchema.isEnum(candidate) && candidate.name().equals("NestedValue"))
                        throw invalid(rule,"NestedValue is reserved for the nested mixed sum type");
                }
            }
            // Captures hidden by an enclosing quantifier would need an implicit container.
            requireOuter(rule, rule.body(), false);
    }
    // Recursive containers share their explicit leaf type and complete container shape.
    private static void requireSharedShape(GrammarDecl grammar,RuleDecl owner,MappingAnnotation mapping) {
        for(var candidate:grammar.rules()) {
            var other=MapperElementUtil.getMappingAnnotation(candidate).orElse(null);
            if(other==null || !other.className().equals(mapping.className()) || candidate==owner) continue;
            if(!enabled(candidate) || !mapping.paramNames().equals(other.paramNames()))
                throw invalid(owner,"shared nested mapping requires identical opt-in and parameters: "+candidate.name());
            var sites=new CaptureBindingPlan(candidate);
            for(String name:mapping.paramNames()) {
                if(sites.sites(name).size()!=1 || !field(grammar,owner,name).equals(shape(grammar,sites.sites(name).get(0).element(),0)))
                    throw invalid(owner,"shared nested mapping requires identical shape for "+name+" on "+candidate.name());
            }
        }
    }
    private static void requireOuter(RuleDecl rule, RuleBody body, boolean inside) {
        if (body instanceof ChoiceBody c) { for (var s : c.alternatives()) requireOuter(rule,s,inside); }
        else for (var e : ((SequenceBody)body).elements()) {
            if (e.captureName().isPresent()) {
                if (inside) throw invalid(rule,"capture must own its complete container");
            }
            visitOuter(rule,e.element(),inside || e.captureName().isPresent());
        }
    }
    private static void visitOuter(RuleDecl rule, AtomicElement e, boolean inside) {
        if (e instanceof TransparentElement g) requireOuter(rule,g.body(),inside);
        else if (e instanceof OptionalElement o) requireOuter(rule,o.body(),true);
        else if (e instanceof RepeatElement r) requireOuter(rule,r.body(),true);
        else if (e instanceof OneOrMoreElement r) visitOuter(rule,r.body(),true);
        else if (e instanceof BoundedRepeatElement r) visitOuter(rule,r.body(),true);
        else if (e instanceof SeparatedElement r) { visitOuter(rule,r.element(),true);visitOuter(rule,r.separator(),true); }
    }
    public static GrammarIR.NestedShape field(GrammarDecl grammar, RuleDecl rule, String name) {
        checkTraversal(rule.body(), 0);
        return shape(grammar,new CaptureBindingPlan(rule).sites(name).get(0).element(),0);
    }
    // Bound preparatory capture/typeof visitors before constructing their recursive plans.
    private static void checkTraversal(Object value, int depth) {
        if (depth > 64) throw new IllegalArgumentException("E-NESTED-SHAPE: capture traversal depth exceeds 64");
        // ChoiceBody and its SequenceBody alternatives represent one logical RuleBody.
        // Native RuleBody stores alternatives directly, without an extra AST wrapper.
        if (value instanceof ChoiceBody c) for (var alternative : c.alternatives()) checkTraversal(alternative, depth);
        else if (value instanceof SequenceBody s) for (var element : s.elements()) checkTraversal(element.element(), depth + 1);
        else if (value instanceof TransparentElement e) checkTraversal(e.body(), depth + 1);
        else if (value instanceof OptionalElement e) checkTraversal(e.body(), depth + 1);
        else if (value instanceof RepeatElement e) checkTraversal(e.body(), depth + 1);
        else if (value instanceof OneOrMoreElement e) checkTraversal(e.body(), depth + 1);
        else if (value instanceof BoundedRepeatElement e) checkTraversal(e.body(), depth + 1);
        else if (value instanceof SeparatedElement e) { checkTraversal(e.element(), depth + 1); checkTraversal(e.separator(), depth + 1); }
    }
    public static GrammarIR.NestedShape shape(GrammarDecl grammar, AtomicElement element, int depth) {
        if (depth > 32) throw new IllegalArgumentException("E-NESTED-SHAPE: schema depth exceeds 32");
        if (element instanceof OptionalElement e) return wrap(Cardinality.OPTIONAL,body(grammar,e.body(),depth+1));
        if (element instanceof RepeatElement e) return wrap(Cardinality.MANY,body(grammar,e.body(),depth+1));
        if (element instanceof SeparatedElement e) return wrap(Cardinality.MANY,shape(grammar,e.element(),depth+1));
        if (element instanceof OneOrMoreElement e) return wrap(Cardinality.MANY,shape(grammar,e.body(),depth+1));
        if (element instanceof BoundedRepeatElement e) return wrap(Cardinality.MANY,shape(grammar,e.body(),depth+1));
        if (element instanceof TransparentElement e) return body(grammar,e.body(),depth+1);
        if (element instanceof TerminalElement || element instanceof ReplayElement) return new GrammarIR.NestedShape(Kind.TEXT,null,List.of());
        if (element instanceof RuleRefElement e) {
            var token=grammar.tokens().stream().filter(t->t.name().equals(e.name())).findFirst();
            if(token.isPresent()) {
                boolean integer=token.get() instanceof TokenDecl.Simple t && List.of("NumberParser","DigitParser").contains(t.parserClass().substring(t.parserClass().lastIndexOf('.')+1));
                return new GrammarIR.NestedShape(integer?Kind.INTEGER:Kind.TEXT,null,List.of());
            }
            var target=grammar.rules().stream().filter(r->r.name().equals(e.name())).findFirst().orElseThrow(()->new IllegalArgumentException("E-NESTED-SHAPE: unknown rule "+e.name()));
            if(EnumSchema.isEnum(target)) return new GrammarIR.NestedShape(Kind.ENUM,target.name(),List.of(),grammar.name()+"AST."+target.name());
            if(!MapperElementUtil.isSkipped(target)) {
                var mapping=MapperElementUtil.getMappingAnnotation(target);
                if(mapping.isPresent()) return new GrammarIR.NestedShape(Kind.NODE,null,List.of(),grammar.name()+"AST."+mapping.get().className());
            }
            throw new IllegalArgumentException("E-NESTED-SHAPE: unmapped/skip aliases require the remaining #583 implementation");
        }
        throw new IllegalArgumentException("E-NESTED-SHAPE: unsupported element " + element.getClass().getSimpleName());
    }
    public static GrammarIR.NestedShape body(GrammarDecl grammar, RuleBody body, int depth) {
        var sequences=body instanceof ChoiceBody c?c.alternatives():List.of((SequenceBody)body);
        GrammarIR.NestedShape result=null;
        for (var sequence:sequences) {
            GrammarIR.NestedShape current;
            if (sequence.elements().size()==1) current=shape(grammar,sequence.elements().get(0).element(),depth+1);
            else {
                for (var e:sequence.elements()) {
                    var item=shape(grammar,e.element(),depth+1);
                    if (!item.containers().isEmpty() || item.javaLeafType()!=null) throw new IllegalArgumentException("E-NESTED-SHAPE: multi-element container/mapped group needs an explicit mapped node");
                }
                current=new GrammarIR.NestedShape(Kind.TEXT,null,List.of());
            }
            if (result != null) {
                if (!result.containers().isEmpty() || !current.containers().isEmpty())
                    throw new IllegalArgumentException("E-NESTED-SHAPE: container choice requires the remaining #583 implementation");
                if (!result.equals(current) || current.javaLeafType() != null)
                    current = new GrammarIR.NestedShape(Kind.VALUE,null,List.of(),grammar.name()+"AST.NestedValue");
            }
            result=current;
        }
        if (result==null) throw new IllegalArgumentException("E-NESTED-SHAPE: empty body");
        return result;
    }
    static java.util.Set<String> captureNames(RuleDecl rule) {
        var names = new java.util.LinkedHashSet<>(MapperElementUtil.getMappingAnnotation(rule).orElseThrow().paramNames());
        names.addAll(TypeofConstraints.captures(rule));
        return names;
    }
    public static boolean hasMixed(GrammarDecl grammar) {
        return grammar.rules().stream().filter(NestedCaptures::enabled).anyMatch(rule ->
            captureNames(rule).stream()
                .anyMatch(name -> field(grammar,rule,name).leaf() == Kind.VALUE));
    }
    private static GrammarIR.NestedShape wrap(Cardinality container,GrammarIR.NestedShape inner) {
        var containers=new ArrayList<Cardinality>();containers.add(container);containers.addAll(inner.containers());
        return new GrammarIR.NestedShape(inner.leaf(),inner.enumName(),containers,inner.javaLeafType());
    }
    public static String javaType(GrammarIR.NestedShape shape) {
        String type=shape.javaLeafType()!=null?shape.javaLeafType():shape.leaf()==Kind.INTEGER?"Integer":"String";
        for(int i=shape.containers().size()-1;i>=0;i--) type=(shape.containers().get(i)==Cardinality.OPTIONAL?"Optional":"List")+"<"+type+">";
        return type;
    }
    private static IllegalArgumentException invalid(RuleDecl rule,String detail) { return new IllegalArgumentException("E-NESTED-SHAPE: " + rule.name() + ": " + detail); }
}
