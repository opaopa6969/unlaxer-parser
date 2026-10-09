package org.unlaxer.dsl.semantic;

import com.google.gson.*;
import com.google.gson.stream.*;
import java.io.*;
import java.util.*;
import org.unlaxer.dsl.bootstrap.UBNFAST.*;
import org.unlaxer.dsl.semantic.SemanticRules.*;

/** Compile versioned JSON against the current UBNF inventory, before any source is analyzed. */
public final class SemanticRulesLoader {
    private SemanticRulesLoader() {}
    public static Program load(String json,GrammarDecl grammar) {
        JsonObject root=object(parse(json),"",Set.of("schemaVersion","grammar","profile","unknownLiterals","rules"));
        if(!root.has("schemaVersion") || !root.get("schemaVersion").isJsonPrimitive() || !root.getAsJsonPrimitive("schemaVersion").isNumber() || !root.get("schemaVersion").getAsString().equals("1"))throw error("SCHEMA_VERSION","schemaVersion");
        List<Rule> rules=new ArrayList<>();int index=0;
        for(JsonElement element:array(root,"rules","rules")) {
            String path="rules["+index+++"]";JsonObject value=object(element,path,Set.of("id","node","emit","dependsOn","name","kind","parents","owner","type","visibility","parameters","result","arguments"));
            String id=text(value,"id",path+".id"),node=text(value,"node",path+".node");Emit emit;
            String emitName=text(value,"emit",path+".emit");
            try {if(!emitName.matches("[a-zA-Z]+"))throw new IllegalArgumentException();emit=Emit.valueOf(emitName.toUpperCase(Locale.ROOT));} catch(IllegalArgumentException e) {throw error("INVALID_EMIT",id);}
            Map<String,Selector> selectors=new LinkedHashMap<>();
            for(String key:List.of("name","kind","parents","type","parameters","result","arguments"))if(value.has(key)) {
                JsonObject selector=object(value.get(key),id+"."+key,Set.of("capture","field","literal"));
                if(selector.size()!=1)throw error("INVALID_SELECTOR",id+"."+key);
                String source=selector.keySet().iterator().next();selectors.put(key,new Selector(Source.valueOf(source.toUpperCase(Locale.ROOT)),text(selector,source,id+"."+key)));
            }
            rules.add(new Rule(id,node,emit,strings(value,"dependsOn",id+".dependsOn",true),selectors,
                optional(value,"owner",id+".owner"),optional(value,"visibility",id+".visibility")));
        }
        return new Program(1,text(root,"grammar","grammar"),text(root,"profile","profile"),strings(root,"unknownLiterals","unknownLiterals",false),rules,inventory(grammar));
    }
    public static Inventory inventory(GrammarDecl grammar) {
        Map<String,Shape> shapes=new LinkedHashMap<>();
        for(RuleDecl rule:grammar.rules()) {
            Set<String> captures=new LinkedHashSet<>(),fields=new LinkedHashSet<>();body(rule.body(),captures,new int[]{0},0);
            for(Annotation annotation:rule.annotations())if(annotation instanceof MappingAnnotation mapping)fields.addAll(mapping.paramNames());
            if(shapes.putIfAbsent(rule.name(),new Shape(captures,fields))!=null)throw error("DUPLICATE_NODE",rule.name());
        }
        return new Inventory(grammar.name(),shapes);
    }
    private static void body(RuleBody body,Set<String> captures,int[] count,int depth) {
        if(depth>64 || ++count[0]>16384)throw error("LIMIT","inventory");
        if(body instanceof ChoiceBody choice)for(SequenceBody alternative:choice.alternatives())body(alternative,captures,count,depth+1);
        else for(AnnotatedElement element:((SequenceBody)body).elements()) {element.captureName().ifPresent(captures::add);atomic(element.element(),captures,count,depth+1);}
    }
    private static void atomic(AtomicElement element,Set<String> captures,int[] count,int depth) {
        if(depth>64 || ++count[0]>16384)throw error("LIMIT","inventory");
        if(element instanceof GroupElement group)body(group.body(),captures,count,depth+1);
        else if(element instanceof OptionalElement optional)body(optional.body(),captures,count,depth+1);
        else if(element instanceof RepeatElement repeat)body(repeat.body(),captures,count,depth+1);
        else if(element instanceof OneOrMoreElement repeat)atomic(repeat.body(),captures,count,depth+1);
        else if(element instanceof BoundedRepeatElement repeat)atomic(repeat.body(),captures,count,depth+1);
        else if(element instanceof SeparatedElement separated) {atomic(separated.element(),captures,count,depth+1);atomic(separated.separator(),captures,count,depth+1);}
    }
    private static String optional(JsonObject value,String key,String path) {return value.has(key)?text(value,key,path):"";}
    private static List<String> strings(JsonObject value,String key,String path,boolean optional) {
        if(optional && !value.has(key))return List.of();List<String> result=new ArrayList<>();
        for(JsonElement item:array(value,key,path)) {if(!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString())throw error("SCHEMA_TYPE",path);result.add(item.getAsString());}return List.copyOf(result);
    }
    private static String text(JsonObject value,String key,String path) {
        if(!value.has(key) || !value.get(key).isJsonPrimitive() || !value.getAsJsonPrimitive(key).isString())throw error("SCHEMA_TYPE",path);return value.get(key).getAsString();
    }
    private static JsonArray array(JsonObject value,String key,String path) {if(!value.has(key) || !value.get(key).isJsonArray())throw error("SCHEMA_TYPE",path);return value.getAsJsonArray(key);}
    private static JsonObject object(JsonElement value,String path,Set<String> allowed) {
        if(value==null || !value.isJsonObject())throw error("SCHEMA_TYPE",path);JsonObject object=value.getAsJsonObject();
        if(!allowed.containsAll(object.keySet()))throw error("UNKNOWN_PROPERTY",path);return object;
    }
    private static JsonElement parse(String json) {
        if(json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>1048576)throw error("LIMIT","");
        if(json.startsWith("\uFEFF"))throw error("INVALID_JSON","");
        try(JsonReader reader=new JsonReader(new StringReader(json))) {
            reader.setStrictness(Strictness.STRICT);JsonElement value=read(reader,0,new int[]{0});if(reader.peek()!=JsonToken.END_DOCUMENT)throw error("INVALID_JSON","");return value;
        } catch(IOException | IllegalStateException | NumberFormatException e) {throw error("INVALID_JSON","");}
    }
    private static JsonElement read(JsonReader reader,int depth,int[] count) throws IOException {
        if(depth>64 || ++count[0]>16384)throw error("LIMIT","");
        return switch(reader.peek()) {
            case BEGIN_OBJECT -> {JsonObject value=new JsonObject();reader.beginObject();while(reader.hasNext()) {String key=reader.nextName();scalar(key);if(value.has(key))throw error("INVALID_JSON","");value.add(key,read(reader,depth+1,count));}reader.endObject();yield value;}
            case BEGIN_ARRAY -> {JsonArray value=new JsonArray();reader.beginArray();while(reader.hasNext())value.add(read(reader,depth+1,count));reader.endArray();yield value;}
            case STRING -> {String value=reader.nextString();scalar(value);yield new JsonPrimitive(value);}
            case NUMBER -> {String number=reader.nextString();if(!Double.isFinite(Double.parseDouble(number)))throw error("INVALID_JSON","");yield JsonParser.parseString(number);}
            case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
            case NULL -> {reader.nextNull();yield JsonNull.INSTANCE;}
            default -> throw error("INVALID_JSON","");
        };
    }
    private static void scalar(String value) {if(value.codePoints().anyMatch(cp->cp>=0xD800 && cp<=0xDFFF))throw error("INVALID_JSON","");}
    private static SchemaException error(String code,String path) {return new SchemaException(code,path);}
}
