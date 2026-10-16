import java.nio.file.*;
import java.util.*;
import jdk.jfr.consumer.*;

public class SummarizeJfr {
  static final Map<String,Long> metrics = new TreeMap<>();
  static void add(String group,String name,long weight) { metrics.merge(group+"\t"+name,weight,Long::sum); }
  static String category(List<String> names) {
    if (names.stream().anyMatch(x->x.startsWith("JavaConsumer.canonical") || x.startsWith("com.google.gson."))) return "outside_serialization";
    if (names.contains("org.unlaxer.context.ParseContext.close")) return "outside_context_close";
    if (names.stream().anyMatch(x->x.contains("TinyExpressionP4Mapper"))) return "mapper";
    if (names.contains("org.unlaxer.Token$MemoSnapshot.copies") || names.contains("org.unlaxer.Token$MemoSnapshot.copy")) return "memo_cst_copy";
    if (names.contains("org.unlaxer.context.PackratMemoTable.replay")) return "memo_replay_other";
    if (names.contains("org.unlaxer.context.PackratMemoTable.commitSuccess") || names.contains("org.unlaxer.Token.prepareMemoSnapshot")) return "memo_success_store_or_commit";
    if (names.contains("org.unlaxer.context.PackratMemoTable.memoizeFailure")) return "memo_failure_store";
    if (names.stream().anyMatch(x->x.startsWith("org.unlaxer.context.PackratMemoTable."))) return "memo_other";
    if (names.stream().anyMatch(x->x.startsWith("org.unlaxer.parser.") || x.startsWith("org.unlaxer.context.") || x.startsWith("org.unlaxer.TransactionElement."))) return "parse_other";
    return "startup_or_other";
  }
  public static void main(String[] args) throws Exception {
    try(var file = new RecordingFile(Path.of(args[0]))) {
      while(file.hasMoreEvents()) {
        var e=file.readEvent(); String type=e.getEventType().getName();
        add("events",type,1);
        if (type.equals("jdk.GarbageCollection")) { add("gc","pause_ns",e.getDuration("sumOfPauses").toNanos()); continue; }
        if (!type.equals("jdk.ExecutionSample") && !type.equals("jdk.ObjectAllocationSample")) continue;
        boolean allocation=type.equals("jdk.ObjectAllocationSample"); String group=allocation?"alloc_weight":"cpu_samples";
        long weight=allocation?e.getLong("weight"):1;
        var trace=e.getStackTrace(); List<String> names = trace==null?List.of():trace.getFrames().stream().map(f->f.getMethod().getType().getName()+"."+f.getMethod().getName()).toList();
        add(group,"total",weight); add(group+"_category",category(names),weight);
        if(trace!=null&&trace.isTruncated()) add(group,"truncated_stack_events",1);
        if(!names.isEmpty()) add(group+"_top",names.getFirst(),weight);
        for(String name:new HashSet<>(names)) if(name.startsWith("org.unlaxer")) add(group+"_inclusive",name,weight);
        if(allocation) add("alloc_class",e.getClass("objectClass").getName(),weight);
      }
    }
    metrics.forEach((name,value)->System.out.println(name+"\t"+value));
  }
}
