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
            // Checked before @mapping: a skipped rule publishes no mapping on either host.
            if (rule.annotations().stream().anyMatch(a -> a instanceof SkipAnnotation || a instanceof EnumAnnotation))
                throw invalid(rule,"@skip/@enum rules cannot own nested captures");
            var mapping = MapperElementUtil.getMappingAnnotation(rule).orElseThrow(() -> invalid(rule,"@mapping required"));
            // Each unsupported combination names its tracking issue; native Rust uses the same order and text.
            if (rule.annotations().stream().anyMatch(a -> a instanceof LeftAssocAnnotation || a instanceof RightAssocAnnotation))
                throw invalid(rule,"@leftAssoc/@rightAssoc projection of nested captures is not supported yet (#695)");
            if (rule.annotations().stream().anyMatch(a -> a instanceof EvalAnnotation))
                throw invalid(rule,"@eval on a nested capture owner is not supported yet (#694)");
            if (rule.annotations().stream().anyMatch(a -> a instanceof CommonFieldAnnotation) && !mapping.paramNames().isEmpty())
                throw invalid(rule,"@commonField on a nested owner with fields is not supported yet (#697)");
            if (TokenStreamGrammar.enabled(grammar))
                throw invalid(rule,"token stream with nested captures is not supported yet (#696)");
            for (var problem : TypeofConstraints.problems(rule))
                throw new IllegalArgumentException(problem.code() + ": " + problem.message());
            var sites = new CaptureBindingPlan(rule);
            if (sites.allSites().size() != captureNames(rule).size()) throw invalid(rule,"mapping must name each unique outer capture");
            boolean localTrivia=rule.annotations().stream().anyMatch(a -> a instanceof WhitespaceAnnotation || a instanceof InterleaveAnnotation);
            for (String field : captureNames(rule)) {
                if (sites.sites(field).size() != 1) throw invalid(rule,"one capture site required for " + field);
                var element=sites.sites(field).get(0).element();
                shape(grammar, element, 0);
                // Inlined alias helpers keep the grammar trivia; a rule-local override would differ per host.
                if (localTrivia && !expandAliases(grammar,element).equals(element))
                    throw invalid(rule,"nested capture through a helper under rule-local trivia is not supported");
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
        return shape(grammar,element,depth,new Expansion());
    }
    /**
     * Alias expansion state of one schema query: the active alias chain (cycle detection) and the
     * successful alias shapes by rule and depth. A shape is a pure function of (rule, depth) once its
     * expansion is acyclic, so the memo keeps validation linear in the number of helper rules
     * instead of exponential in shared multi-element helpers. Native Rust uses the same scheme.
     */
    private static final class Expansion {
        final List<String> stack=new ArrayList<>();
        final java.util.Map<String,GrammarIR.NestedShape> memo=new java.util.HashMap<>();
    }
    private static GrammarIR.NestedShape shape(GrammarDecl grammar, AtomicElement element, int depth, Expansion helpers) {
        if (depth > 32) throw new IllegalArgumentException("E-NESTED-SHAPE: schema depth exceeds 32");
        if (element instanceof OptionalElement e) return wrap(Cardinality.OPTIONAL,body(grammar,e.body(),depth+1,helpers));
        if (element instanceof RepeatElement e) return wrap(Cardinality.MANY,body(grammar,e.body(),depth+1,helpers));
        if (element instanceof SeparatedElement e) return wrap(Cardinality.MANY,shape(grammar,e.element(),depth+1,helpers));
        if (element instanceof OneOrMoreElement e) return wrap(Cardinality.MANY,shape(grammar,e.body(),depth+1,helpers));
        if (element instanceof BoundedRepeatElement e) return wrap(Cardinality.MANY,shape(grammar,e.body(),depth+1,helpers));
        if (element instanceof TransparentElement e) return body(grammar,e.body(),depth+1,helpers);
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
            return aliasShape(grammar,target,depth,helpers);
        }
        throw new IllegalArgumentException("E-NESTED-SHAPE: unsupported element " + element.getClass().getSimpleName());
    }
    /**
     * An unmapped, unannotated, non-recursive helper is a transparent alias: its body's shape is the
     * shape of the reference. Native Rust (`nested_captures::alias_shape`) checks the same conditions
     * in the same order and reports the same text.
     */
    private static GrammarIR.NestedShape aliasShape(GrammarDecl grammar, RuleDecl target, int depth, Expansion helpers) {
        String name=target.name();
        if (helpers.stack.contains(name)) throw new IllegalArgumentException("E-NESTED-SHAPE: nested capture through recursive helper "+name+" is not supported");
        var cached=helpers.memo.get(name+"@"+depth);
        if (cached!=null) return cached;
        if (MapperElementUtil.isSkipped(target)) throw new IllegalArgumentException("E-NESTED-SHAPE: nested capture through @skip rule "+name+" is not supported");
        if (target.annotations().stream().anyMatch(a -> !(a instanceof DocAnnotation)))
            throw new IllegalArgumentException("E-NESTED-SHAPE: nested capture through annotated helper "+name+" is not supported");
        if (hasBindings(target.body())) throw new IllegalArgumentException("E-NESTED-SHAPE: nested capture through helper "+name+" with captures is not supported");
        helpers.stack.add(name);
        var result=body(grammar,target.body(),depth+1,helpers);
        helpers.stack.remove(helpers.stack.size()-1);
        if (result.leaf()==Kind.VALUE) throw new IllegalArgumentException("E-NESTED-SHAPE: nested capture through mixed-choice helper "+name+" is not supported");
        helpers.memo.put(name+"@"+depth,result);
        return result;
    }
    // AST captures, typeof constraints and general bindings stay owned by the rule that declares them.
    private static boolean hasBindings(RuleBody body) {
        var sequences=body instanceof ChoiceBody c?c.alternatives():List.of((SequenceBody)body);
        for (var sequence:sequences) for (var e:sequence.elements()) {
            if (e.captureName().isPresent() || e.typeofConstraint().isPresent() || hasBindings(e.element())) return true;
        }
        return false;
    }
    private static boolean hasBindings(AtomicElement element) {
        if (element instanceof OwnedCaptureElement || element instanceof CaptureScopeElement || element instanceof ReplayElement) return true;
        if (element instanceof TransparentElement e) return hasBindings(e.body());
        if (element instanceof OptionalElement e) return hasBindings(e.body());
        if (element instanceof RepeatElement e) return hasBindings(e.body());
        if (element instanceof OneOrMoreElement e) return hasBindings(e.body());
        if (element instanceof BoundedRepeatElement e) return hasBindings(e.body());
        if (element instanceof SeparatedElement e) return hasBindings(e.element()) || hasBindings(e.separator());
        return false;
    }
    /**
     * The transparent alias whose single body element stands for {@code element} inside a nested
     * capture, or empty when the reference stays an ordinary leaf (token, mapped node, enum, or a
     * helper whose body is a choice / multi-element sequence and therefore one text or i32 value).
     * Callers only use this after {@link #requireValid} accepted the grammar, so no cycle remains.
     */
    public static java.util.Optional<RuleDecl> inlinedAlias(GrammarDecl grammar, AtomicElement element) {
        if (!(element instanceof RuleRefElement reference) || grammar.tokens().stream().anyMatch(t->t.name().equals(reference.name())))
            return java.util.Optional.empty();
        return grammar.rules().stream().filter(r->r.name().equals(reference.name())).findFirst()
            .filter(r->!EnumSchema.isEnum(r) && !MapperElementUtil.isSkipped(r) && MapperElementUtil.getMappingAnnotation(r).isEmpty())
            .filter(r->r.body() instanceof SequenceBody s && s.elements().size()==1
                || r.body() instanceof ChoiceBody c && c.alternatives().size()==1 && c.alternatives().get(0).elements().size()==1);
    }
    // First helper reference (source order) anywhere in a body, without entering referenced rules.
    private static String helperIn(GrammarDecl grammar, RuleBody body) {
        var sequences=body instanceof ChoiceBody c?c.alternatives():List.of((SequenceBody)body);
        for (var sequence:sequences) for (var e:sequence.elements()) {
            var found=helperIn(grammar,e.element());
            if (found!=null) return found;
        }
        return null;
    }
    private static String helperIn(GrammarDecl grammar, AtomicElement element) {
        if (element instanceof RuleRefElement r) return isHelper(grammar,r.name())?r.name():null;
        if (element instanceof TransparentElement e) return helperIn(grammar,e.body());
        if (element instanceof OptionalElement e) return helperIn(grammar,e.body());
        if (element instanceof RepeatElement e) return helperIn(grammar,e.body());
        if (element instanceof OneOrMoreElement e) return helperIn(grammar,e.body());
        if (element instanceof BoundedRepeatElement e) return helperIn(grammar,e.body());
        if (element instanceof SeparatedElement e) { var found=helperIn(grammar,e.element()); return found!=null?found:helperIn(grammar,e.separator()); }
        return null;
    }
    private static boolean isHelper(GrammarDecl grammar, String name) {
        return grammar.tokens().stream().noneMatch(t->t.name().equals(name)) && grammar.rules().stream().anyMatch(r->r.name().equals(name)
            && !EnumSchema.isEnum(r) && (MapperElementUtil.isSkipped(r) || MapperElementUtil.getMappingAnnotation(r).isEmpty()));
    }
    /** The single body element of an {@link #inlinedAlias}. */
    public static AtomicElement aliasElement(RuleDecl alias) {
        var sequence=alias.body() instanceof ChoiceBody c?c.alternatives().get(0):(SequenceBody)alias.body();
        return sequence.elements().get(0).element();
    }
    /**
     * Replaces inlined alias references by their single body element along the nested path, exactly
     * where {@code ParserRuleEmitter.resolveNested} descends: single-element bodies, repeated items and
     * separated items. Multi-element and choice bodies (and separators) stay ordinary flat parsers.
     */
    public static AtomicElement expandAliases(GrammarDecl grammar, AtomicElement element) {
        var alias=inlinedAlias(grammar,element);
        if (alias.isPresent()) return expandAliases(grammar,aliasElement(alias.get()));
        if (element instanceof GroupElement e) return new GroupElement(expandSingle(grammar,e.body()));
        if (element instanceof OwnedCaptureElement e) return new OwnedCaptureElement(e.name(),expandSingle(grammar,e.body()));
        if (element instanceof CaptureScopeElement e) return new CaptureScopeElement(expandSingle(grammar,e.body()));
        if (element instanceof OptionalElement e) return new OptionalElement(expandSingle(grammar,e.body()));
        if (element instanceof RepeatElement e) return new RepeatElement(expandSingle(grammar,e.body()));
        if (element instanceof OneOrMoreElement e) return new OneOrMoreElement(expandAliases(grammar,e.body()));
        if (element instanceof BoundedRepeatElement e) return org.unlaxer.dsl.bootstrap.BoundedQuantifiers.copy(new BoundedRepeatElement(expandAliases(grammar,e.body()),e.min(),e.max()),e);
        if (element instanceof SeparatedElement e) return new SeparatedElement(expandAliases(grammar,e.element()),e.separator());
        return element;
    }
    private static RuleBody expandSingle(GrammarDecl grammar, RuleBody body) {
        var sequence=body instanceof ChoiceBody c && c.alternatives().size()==1?c.alternatives().get(0):body;
        if (!(sequence instanceof SequenceBody s) || s.elements().size()!=1) return body;
        var only=s.elements().get(0);
        var replaced=new SequenceBody(List.of(new AnnotatedElement(expandAliases(grammar,only.element()),only.captureName(),only.typeofConstraint())));
        return body instanceof ChoiceBody?new ChoiceBody(List.of(replaced)):replaced;
    }
    public static GrammarIR.NestedShape body(GrammarDecl grammar, RuleBody body, int depth) {
        return body(grammar,body,depth,new Expansion());
    }
    private static GrammarIR.NestedShape body(GrammarDecl grammar, RuleBody body, int depth, Expansion helpers) {
        var sequences=body instanceof ChoiceBody c?c.alternatives():List.of((SequenceBody)body);
        GrammarIR.NestedShape result=null;
        for (var sequence:sequences) {
            GrammarIR.NestedShape current;
            if (sequence.elements().size()==1) current=shape(grammar,sequence.elements().get(0).element(),depth+1,helpers);
            else {
                for (var e:sequence.elements()) {
                    var item=shape(grammar,e.element(),depth+1,helpers);
                    if (!item.containers().isEmpty() || item.javaLeafType()!=null) throw new IllegalArgumentException("E-NESTED-SHAPE: multi-element container/mapped group needs an explicit mapped node");
                }
                current=new GrammarIR.NestedShape(Kind.TEXT,null,List.of());
            }
            if (result != null) {
                if (!result.containers().isEmpty() || !current.containers().isEmpty())
                    throw new IllegalArgumentException("E-NESTED-SHAPE: choice with container alternatives is not supported yet (#693)");
                if (!result.equals(current) || current.javaLeafType() != null)
                    current = new GrammarIR.NestedShape(Kind.VALUE,null,List.of(),grammar.name()+"AST.NestedValue");
            }
            result=current;
        }
        if (result==null) throw new IllegalArgumentException("E-NESTED-SHAPE: empty body");
        // Mixed sums classify each alternative by its own CST token; a helper anywhere inside an
        // alternative would change that classification and its preferred depth. This also keeps
        // mixed detection of choices outside a capture (markNestedMixed, Rust lowering) unchanged:
        // such choices were never mixed before helpers had a nested shape.
        if (result.leaf()==Kind.VALUE) for (var sequence:sequences) {
            var helper=helperIn(grammar,sequence);
            if (helper!=null) throw new IllegalArgumentException("E-NESTED-SHAPE: mixed choice alternative through helper "+helper+" is not supported");
        }
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
