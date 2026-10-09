import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.unlaxer.source.*;
import org.unlaxer.source.LanguageRegions.*;
import org.unlaxer.source.DocumentSnapshot.Span;
public class Probe {
    public static void main(String[] args) throws Exception {
        Path fixture=Path.of(args[0]);
        if(!LanguageProfile.parse(Files.readString(fixture.resolve("../../../language-profiles/java/profile.tsv"))).identity("CompilationUnit").equals(TinyProductionBridge.JAVA))throw new AssertionError("profile identity drift");
        for(String row:Files.readAllLines(fixture.resolve("cases.tsv"))) {
            if(row.startsWith("#")||row.isBlank())continue;
            String[] fields=row.split("\t",-1); String name=fields[0];
            var host=new DocumentSnapshot("file:///production.formula",7,Files.readString(fixture.resolve(name+".txt")));
            TinyProductionBridge.Binding binding;
            try { binding=TinyProductionBridge.parse(host); }
            catch(IllegalArgumentException e) { System.out.println(name+"\trejected");continue; }
            for(var r:binding.regions())System.out.println(name+"\tregion\t"+r.id()+"\t"+r.parseState()+"\t"+r.full().start()+"\t"+r.full().end()+"\t"+r.body().start()+"\t"+r.body().end()+"\t"+binding.javaFiles().getOrDefault(r.id(),"-"));
            var project=new LanguageQueries.Project("tiny-production",12,Map.of(host.uri(),host),Map.of("classpath",args[2]));
            var process=new ProviderProcess(List.of(args[1],"-cp",args[2],"org.unlaxer.dsl.provider.JavacProvider"),new ProviderProtocol.Identity("javac","21.0.9"),Set.of(Operation.VALIDATE,Operation.COMPLETION,Operation.HOVER,Operation.DEFINITION),Duration.ofSeconds(30));
            var queries=binding.queries(project,process);
            if(fields[1].equals("validate")) for(var result:queries.diagnosticsAll(host,project,Map.of())) {
                if(!binding.javaFiles().containsKey(result.region()))continue;
                System.out.println(name+"\tvalidate\t"+result.region()+"\t"+result.state()+"\t"+result.diagnostics().size());
                for(var diagnostic:result.diagnostics())for(var mapped:diagnostic.locations()) {
                    var location=mapped.location(); if(!location.snapshot().equals(host)||!mapped.exact()||diagnostic.message().isEmpty())throw new AssertionError("diagnostic ownership");
                    var position=host.lsp(location.span().start());
                    System.out.println(name+"\tdiagnostic\t"+result.region()+"\t"+diagnostic.code()+"\t"+diagnostic.severity()+"\t"+location.span().start()+"\t"+location.span().end()+"\t"+position.line()+"\t"+position.character());
                }
            }
            for(String query:Files.readAllLines(fixture.resolve("queries.tsv"))) {
                String[] q=query.split("\t"); if(!q[0].equals(name))continue;
                var result=queries.query(host,project,Integer.parseInt(q[1]),Operation.valueOf(q[2]),Map.of());
                if(result.items().size()!=1)throw new AssertionError("query item count");
                var item=result.items().get(0);var location=item.locations().get(0).location();
                if(!location.snapshot().equals(host)||!item.locations().get(0).exact())throw new AssertionError("query ownership");
                System.out.println(name+"\tquery\t"+q[2]+"\t"+result.state()+"\t"+item.label()+"\t"+item.detail()+"\t"+location.span().start()+"\t"+location.span().end());
            }
            if(fields[1].equals("completion")) {
                int cursor=Integer.parseInt(fields[2]); var result=queries.query(host,project,cursor,Operation.COMPLETION,Map.of("prefix","tar"));
                var item=result.items().stream().filter(i->i.label().equals("target")).findFirst().orElseThrow(); var edit=item.edits().get(0);
                var changed=binding.tree().apply(host,8,List.of(edit));
                if(!changed.text().equals(Files.readString(fixture.resolve("completion-edited.txt"))))throw new AssertionError("full source edit mismatch");
                System.out.println(name+"\tcompletion\t"+result.state()+"\t"+edit.span().start()+"\t"+edit.span().end()+"\t"+edit.replacement());
                TinyProductionBridge.parse(changed);
            }
            // Exact source identity, project config/version and closed delimiter ownership.
            try {queries.query(new DocumentSnapshot(host.uri(),8,host.text()),project,0,Operation.COMPLETION,Map.of());throw new AssertionError("stale accepted");}catch(IllegalArgumentException expected){}
            try {queries.query(host,new LanguageQueries.Project(project.id(),13,project.documents(),project.configuration()),0,Operation.COMPLETION,Map.of());throw new AssertionError("stale project accepted");}catch(IllegalArgumentException expected){}
            try {queries.query(new DocumentSnapshot(host.uri(),7,host.text()+" "),project,0,Operation.COMPLETION,Map.of());throw new AssertionError("changed source accepted");}catch(IllegalArgumentException expected){}
            try {queries.query(host,new LanguageQueries.Project(project.id(),12,project.documents(),Map.of()),0,Operation.COMPLETION,Map.of());throw new AssertionError("changed config accepted");}catch(IllegalArgumentException expected){}
            for(var r:binding.regions())if(r.language().equals(TinyProductionBridge.JAVA)) {
                if(binding.tree().at(r.body().end()).language().equals(TinyProductionBridge.JAVA))throw new AssertionError("closing fence owned by Java");
                if(queries.query(host,project,r.body().start(),Operation.RENAME,Map.of()).state()!=State.UNSUPPORTED)throw new AssertionError("rename advertised");
            }
            System.out.println(name+"\tguards\tPASS");
        }
    }
}
