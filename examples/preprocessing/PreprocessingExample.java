package example.pipeline;

import com.google.gson.*;
import java.util.*;
import java.util.function.Function;
import org.unlaxer.editor.EditorCst;
import org.unlaxer.pipeline.AnalysisPipeline;
import org.unlaxer.pipeline.AnalysisPipeline.*;
import org.unlaxer.source.*;
import org.unlaxer.source.DocumentSnapshot.Span;
import org.unlaxer.source.SegmentSourceMap.*;
import org.unlaxer.dsl.semantic.*;

/** Executable example adapter. This small payload schema belongs to the example, not UBNF. */
public final class PreprocessingExample {
    private static final Gson JSON = new Gson();
    private PreprocessingExample() {}
    private static Phase phase(String id,List<String> dependencies,List<String> inputs,List<String> keys) {
        return new Phase(id,dependencies,inputs,keys,false);
    }
    private static Artifact source(DocumentSnapshot value) {
        return new Artifact(State.COMPLETE,value.text(),List.of(new Location(value,new Span(0,value.length()))),List.of());
    }
    public static AnalysisPipeline pipeline(SemanticRules.Program program,SemanticRules.Inventory inventory,Function<String,EditorCst> parser) {
        return new AnalysisPipeline(List.of(
            phase("semantic",List.of("expand"),List.of(),List.of("cursor")),
            phase("expand",List.of("include","optional"),List.of("main"),List.of("generation")),
            phase("optional",List.of(),List.of("optional"),List.of()),
            phase("include",List.of(),List.of("included"),List.of())),Map.of(
            "include",request->source(request.inputs().get("included")),
            "optional",request->source(request.inputs().get("optional")),
            "expand",PreprocessingExample::expand,
            "semantic",request->analyze(request,program,inventory,parser)),
            Map.of("optional",new Condition("extra","true")));
    }
    private static Artifact expand(Request request) {
        DocumentSnapshot main=request.inputs().get("main");
        Artifact included=request.dependencies().get("include"),optional=request.dependencies().get("optional");
        // Original include/call-site anchor, separate from the included file's origin.
        Location anchor=new Location(main,new Span(0,Math.min(6,main.length())));
        List<Location> origins=new ArrayList<>();origins.add(anchor);
        StringBuilder output=new StringBuilder();JsonArray segments=new JsonArray();
        append(output,segments,origins,"/*generated😀*/",Kind.GENERATED,anchor);
        append(output,segments,origins,included.payload(),Kind.COPY,included.origins().get(0));
        if(optional.state()==State.COMPLETE)append(output,segments,origins,optional.payload(),Kind.COPY,optional.origins().get(0));
        append(output,segments,origins,"\n",Kind.GENERATED,anchor);
        int mainStart=output.codePointCount(0,output.length());
        append(output,segments,origins,main.text(),Kind.COPY,new Location(main,new Span(0,main.length())));
        JsonObject payload=new JsonObject();payload.addProperty("text",output.toString());payload.add("segments",segments);
        payload.addProperty("version",Long.parseLong(request.configuration().get("generation")));
        payload.addProperty("mainStart",mainStart);payload.addProperty("mainLength",main.length());payload.addProperty("optional",optional.state().name());
        return new Artifact(State.COMPLETE,payload.toString(),origins,List.of());
    }
    private static void append(StringBuilder output,JsonArray segments,List<Location> origins,String text,Kind kind,Location origin) {
        if(text.isEmpty())return;
        int start=output.codePointCount(0,output.length());output.append(text);int index=origins.size();origins.add(origin);
        segments.add(JSON.toJsonTree(List.of(start,output.codePointCount(0,output.length()),kind.name(),index)));
    }
    private static SegmentSourceMap map(Artifact artifact) {
        JsonObject data=JsonParser.parseString(artifact.payload()).getAsJsonObject();
        DocumentSnapshot output=new DocumentSnapshot("memory:expanded",data.get("version").getAsLong(),data.get("text").getAsString());
        List<Segment> segments=new ArrayList<>();
        for(JsonElement item:data.getAsJsonArray("segments")) {
            JsonArray row=item.getAsJsonArray();segments.add(new Segment(new Span(row.get(0).getAsInt(),row.get(1).getAsInt()),Kind.valueOf(row.get(2).getAsString()),artifact.origins().get(row.get(3).getAsInt())));
        }
        return new SegmentSourceMap(output,segments);
    }
    private static Artifact analyze(Request request,SemanticRules.Program program,SemanticRules.Inventory inventory,Function<String,EditorCst> parser) {
        Artifact expanded=request.dependencies().get("expand");JsonObject data=JsonParser.parseString(expanded.payload()).getAsJsonObject();
        SegmentSourceMap map=map(expanded);DocumentSnapshot source=map.output();
        String cursorText=request.configuration().get("cursor");
        if(cursorText==null || cursorText.length()>10 || !cursorText.matches("[0-9]+"))throw new IllegalArgumentException("invalid cursor");
        int relative;
        try {relative=Integer.parseInt(cursorText);}catch(NumberFormatException error){throw new IllegalArgumentException("invalid cursor");}
        if(relative>data.get("mainLength").getAsInt())throw new IllegalArgumentException("invalid cursor");
        int cursor=Math.addExact(data.get("mainStart").getAsInt(),relative);
        var analysis=SemanticRuleEngine.analyze(program,inventory,source.uri(),source.version(),parser.apply(source.text()));
        JsonObject report=new JsonObject();report.addProperty("optional",data.get("optional").getAsString());
        report.add("types",JSON.toJsonTree(analysis.model()==null?List.of():analysis.model().types().keySet()));
        report.add("expected",new JsonArray());report.add("completion",new JsonArray());report.add("edit",new JsonArray());
        var diagnostics=new ArrayList<List<?>>();var mappings=new ArrayList<Mapping>();
        for(var diagnostic:analysis.diagnostics())for(Mapping location:map.diagnostics(new Span(diagnostic.span().start(),diagnostic.span().end()))) {
            var at=location.location();mappings.add(location);diagnostics.add(List.of(diagnostic.code(),at.snapshot().uri(),at.snapshot().version(),at.span().start(),at.span().end(),location.exact()));
        }
        report.add("diagnostics",JSON.toJsonTree(diagnostics));
        SemanticRuleEngine.query(analysis,source.uri(),source.version(),cursor,"").ifPresent(query->{
            report.add("expected",JSON.toJsonTree(query.expected().stream().map(TypeSystem.TypeRef::name).toList()));
            report.add("completion",JSON.toJsonTree(query.completions().stream().map(value->value.value().name()).toList()));
            var edit=map.edit(new Span(query.edit().start(),query.edit().end()));
            report.add("edit",JSON.toJsonTree(List.of(edit.snapshot().uri(),edit.snapshot().version(),edit.span().start(),edit.span().end())));
        });
        try {map.edit(new Span(0,1));throw new AssertionError("generated wrapper edit accepted");}catch(IllegalArgumentException expected){report.addProperty("generatedEdit","REJECTED");}
        report.addProperty("expandedLength",source.length());
        return new Artifact(State.valueOf(analysis.status().name()),report.toString(),expanded.origins(),mappings);
    }
}
