package org.unlaxer.dsl.semantic;

import java.util.*;
import org.unlaxer.editor.EditorCst;
import org.unlaxer.dsl.semantic.SemanticModel.*;

/** Portable semantic rule IR. JSON loading and grammar inventory validation are separate from execution. */
public final class SemanticRules {
    private SemanticRules() {}
    public enum Emit { SCOPE, TYPE, FIELD, SYMBOL, SIGNATURE, EXPRESSION, REFERENCE, CALL }
    public enum Source { CAPTURE, FIELD, LITERAL }
    public record Selector(Source source,String name) { public Selector { Objects.requireNonNull(source); require(name); } }
    public record Shape(Set<String> captures,Set<String> fields) {
        public Shape { captures=Set.copyOf(captures);fields=Set.copyOf(fields);captures.forEach(SemanticRules::require);fields.forEach(SemanticRules::require); }
    }
    public record Inventory(String grammar,Map<String,Shape> nodes) {
        public Inventory { require(grammar);nodes=Map.copyOf(nodes);nodes.keySet().forEach(SemanticRules::require);if(nodes.size()>4096)throw schema("LIMIT","inventory"); }
    }
    public record Rule(String id,String node,Emit emit,List<String> dependsOn,Map<String,Selector> selectors,String owner,String visibility) {
        public Rule { require(id);require(node);Objects.requireNonNull(emit);dependsOn=List.copyOf(dependsOn);selectors=Map.copyOf(selectors);scalar(owner);scalar(visibility);dependsOn.forEach(SemanticRules::require);selectors.keySet().forEach(SemanticRules::require); }
    }
    public static final class SchemaException extends IllegalArgumentException {
        private final String code,path;
        public SchemaException(String code,String path) {super(code+": "+path);this.code=code;this.path=path;}
        public String code() {return code;} public String path() {return path;}
    }
    public static final class Program {
        private final Inventory inventory;
        private final List<Rule> rules;
        private final Set<String> unknownLiterals;
        public Program(int schemaVersion,String grammar,String profile,List<String> unknownLiterals,List<Rule> rules,Inventory inventory) {
            if(schemaVersion!=1)throw schema("SCHEMA_VERSION","schemaVersion");
            if(!profile.equals("portableNominal/1"))throw schema("UNSUPPORTED_PROFILE","profile");
            if(!grammar.equals(inventory.grammar()))throw schema("GRAMMAR_MISMATCH","grammar");
            if(rules.size()>256)throw schema("LIMIT","rules");
            TextBudget budget=new TextBudget();budget.add(grammar);budget.add(profile);int slots=0;
            for(var entry:inventory.nodes().entrySet()) {
                budget.add(entry.getKey());slots+=entry.getValue().captures().size()+entry.getValue().fields().size();
                if(slots>16384)throw schema("LIMIT","inventory");
                entry.getValue().captures().forEach(budget::add);entry.getValue().fields().forEach(budget::add);
            }
            unknownLiterals.forEach(budget::add);
            for(Rule rule:rules) {
                budget.add(rule.id());budget.add(rule.node());budget.add(rule.owner());budget.add(rule.visibility());rule.dependsOn().forEach(budget::add);
                for(var selector:rule.selectors().entrySet()){budget.add(selector.getKey());budget.add(selector.getValue().name());}
            }
            this.inventory=inventory;this.rules=List.copyOf(rules);this.unknownLiterals=Set.copyOf(unknownLiterals);
            Map<String,Rule> byId=new LinkedHashMap<>();Set<String> bindings=new HashSet<>();
            for(Rule rule:rules) {
                if(byId.putIfAbsent(rule.id(),rule)!=null)throw schema("DUPLICATE_RULE",rule.id());
                if(!bindings.add(rule.node()+":"+rule.emit().name()))throw schema("AMBIGUOUS_BINDING",rule.id());
                Shape shape=inventory.nodes().get(rule.node());if(shape==null)throw schema("UNDEFINED_NODE",rule.id()+".node");
                Set<String> required=switch(rule.emit()) {
                    case SCOPE -> Set.of();case TYPE -> Set.of("name","kind");case FIELD,SYMBOL -> Set.of("name","type");
                    case SIGNATURE -> Set.of("name","parameters","result");case EXPRESSION -> Set.of("type");case REFERENCE -> Set.of("name");case CALL -> Set.of("name","arguments");
                };
                Set<String> allowed=new HashSet<>(required);if(rule.emit()==Emit.TYPE)allowed.add("parents");
                if(!rule.selectors().keySet().containsAll(required) || !allowed.containsAll(rule.selectors().keySet()))throw schema("INVALID_SELECTOR_SET",rule.id());
                for(var selected:rule.selectors().entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
                    Selector selector=selected.getValue();String path=rule.id()+"."+selected.getKey();
                    if(selector.source()==Source.FIELD && !shape.fields().contains(selector.name()))throw schema("UNDEFINED_FIELD",path);
                    if(selector.source()!=Source.LITERAL && !shape.captures().contains(selector.name()))throw schema(selector.source()==Source.FIELD?"UNMAPPED_FIELD":"UNDEFINED_CAPTURE",path);
                }
                if(rule.emit()==Emit.SYMBOL && !Set.of("after","scopeStart").contains(rule.visibility()))throw schema("INVALID_VISIBILITY",rule.id());
                if(rule.emit()!=Emit.SYMBOL && !rule.visibility().isEmpty())throw schema("INVALID_VISIBILITY",rule.id());
                if(rule.emit()!=Emit.FIELD && !rule.owner().isEmpty())throw schema("INVALID_OWNER",rule.id());
                if(new HashSet<>(rule.dependsOn()).size()!=rule.dependsOn().size())throw schema("DUPLICATE_DEPENDENCY",rule.id());
            }
            for(Rule rule:rules) {
                if(rule.emit()==Emit.FIELD && (!byId.containsKey(rule.owner()) || byId.get(rule.owner()).emit()!=Emit.TYPE))throw schema("INVALID_OWNER",rule.id());
                for(String dependency:rule.dependsOn())if(!byId.containsKey(dependency))throw schema("UNDEFINED_DEPENDENCY",rule.id());
            }
            Set<String> remaining=new LinkedHashSet<>(byId.keySet());
            while(!remaining.isEmpty()) {
                List<String> ready=remaining.stream().filter(id->byId.get(id).dependsOn().stream().noneMatch(remaining::contains)).toList();
                if(ready.isEmpty())throw schema("CYCLIC_RULES","rules");remaining.removeAll(ready);
            }
            for(Rule rule:rules)for(String dependency:rule.dependsOn())if(phase(byId.get(dependency).emit())>phase(rule.emit()))throw schema("RULE_PHASE",rule.id());
        }
        public Inventory inventory() {return inventory;}
        public List<Rule> rules() {return rules;}
        public Set<String> unknownLiterals() {return unknownLiterals;}
    }
    private static int phase(Emit emit) {return switch(emit){case SCOPE->0;case TYPE,FIELD->1;case SYMBOL,SIGNATURE,EXPRESSION,REFERENCE->2;case CALL->3;};}
    private static void require(String value) {if(value==null || value.isEmpty())throw schema("EMPTY_NAME","");scalar(value);}
    private static void scalar(String value) {
        Objects.requireNonNull(value);if(value.codePoints().anyMatch(cp->cp>=0xD800&&cp<=0xDFFF))throw schema("INVALID_SCALAR","");
        if(value.codePointCount(0,value.length())>1024)throw schema("LIMIT","");
    }
    private static final class TextBudget {
        private int bytes;
        void add(String value) {scalar(value);bytes+=value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;if(bytes>1048576)throw schema("LIMIT","");}
    }
    private static SchemaException schema(String code,String path) {return new SchemaException(code,path);}
    public record Diagnostic(String code,String uri,long version,Span span,String rule) {}
    public record Site(String call,int argument,Span span) {}
    public record Analysis(EditorCst.Status status,SemanticModel model,List<Diagnostic> diagnostics,List<Site> sites) {
        public Analysis {
            Objects.requireNonNull(status);diagnostics=List.copyOf(diagnostics);sites=List.copyOf(sites);
            if((model==null)!=(status==EditorCst.Status.FAILED))throw new IllegalArgumentException("invalid semantic status");
            if(model==null && !sites.isEmpty())throw new IllegalArgumentException("sites require a model");
            Set<Site> unique=new HashSet<>();
            for(Site site:sites) {
                Call call=model.calls().get(site.call());
                if(call==null||site.argument()<0||site.argument()>=call.arguments().size()||!call.arguments().get(site.argument()).span().equals(site.span())||!unique.add(site))throw new IllegalArgumentException("invalid semantic call site");
            }
        }
    }
}
