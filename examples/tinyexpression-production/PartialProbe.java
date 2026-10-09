import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.unlaxer.source.*;
import org.unlaxer.source.LanguageRegions.*;
public class PartialProbe {
  public static void main(String[] args) throws Exception {
    Path fixture=Path.of(args[0]);
    for(String name:Files.readAllLines(fixture.resolve("partial/cases.tsv"))) {
      if(name.startsWith("#")||name.isBlank())continue;
      var host=new DocumentSnapshot("file:///partial.formula",7,Files.readString(fixture.resolve("partial/"+name+".txt")));
      var strict=TinyProductionBridge.parse(host);var binding=TinyProductionBridge.parseEditor(host);
      System.out.println(name+"\tstrict\t"+strict.javaFiles().size()+"\t"+strict.regions().stream().filter(r->r.parseState()==State.FAILED).count());
      for(var region:binding.regions())if(region.language().equals(TinyProductionBridge.JAVA)) {
        if(!host.slice(region.body()).equals(region.sourceMap().output().text()))throw new AssertionError("synthetic text escaped");
        System.out.println(name+"\tjava\t"+region.id()+"\t"+region.parseState()+"\t"+region.full().start()+"\t"+region.full().end()+"\t"+region.body().start()+"\t"+region.body().end()+"\t"+binding.openEnds().contains(region.id()));
      }
      if(name.equals("eof")||name.equals("multiple")||name.equals("comments-crlf")) {
        var project=new LanguageQueries.Project("partial",1,Map.of(host.uri(),host),Map.of("classpath",args[2]));
        var provider=new ProviderProcess(List.of(args[1],"-cp",args[2],"org.unlaxer.dsl.provider.JavacProvider"),new ProviderProtocol.Identity("javac","21.0.9"),Set.of(Operation.COMPLETION),Duration.ofSeconds(30));
        var result=binding.queries(project,provider).query(host,project,host.length(),Operation.COMPLETION,Map.of("prefix","tar"));
        var edit=result.items().stream().filter(i->i.label().equals("target")).findFirst().orElseThrow().edits().get(0);
        var changed=binding.tree().apply(host,8,List.of(edit));
        if(!changed.text().equals(host.text()+"get"))throw new AssertionError("raw edit mismatch");
        if(TinyProductionBridge.parse(changed).regions().stream().noneMatch(r->r.parseState()==State.FAILED))throw new AssertionError("editor changed strict acceptance");
        System.out.println(name+"\tcompletion\t"+result.state()+"\t"+edit.span().start()+"\t"+edit.span().end()+"\t"+edit.replacement());
      }
      var owner=binding.tree().at(host.length());System.out.println(name+"\teof\t"+(owner==null?"none":owner.id()));
    }
  }
}
