import java.nio.file.*;
import java.util.*;

/** Experiment-only proof algebra. It does not infer safety for arbitrary UBNF. */
public final class Policy {
    record Fact(String purity, boolean nullable, Set<String> first, String memo) {}
    static Fact leaf(String purity, boolean nullable, Set<String> first, String memo) {
        return new Fact(purity, nullable, first, memo);
    }
    static List<String> parts(String body) {
        var result = new ArrayList<String>(); int start=0, depth=0;
        for (int i=0;i<body.length();i++) {
            char c=body.charAt(i);
            if(c=='(') depth++; if(c==')') depth--;
            if(depth<0) throw new IllegalArgumentException("unbalanced proof");
            if(c==';' && depth==0) { result.add(body.substring(start,i)); start=i+1; }
        }
        if(depth!=0) throw new IllegalArgumentException("unbalanced proof");
        result.add(body.substring(start)); return result;
    }
    static Fact proof(String text, int depth) {
        if(depth>32 || text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>4096) return leaf("UNKNOWN",true,Set.of("*"),"LIMIT");
        if(text.startsWith("lit:")) {
            String value=text.substring(4);
            return leaf("PURE",value.isEmpty(),value.isEmpty()?Set.of():Set.of(new String(Character.toChars(value.codePointAt(0)))),"SAFE");
        }
        switch(text) {
            case "empty": return leaf("PURE",true,Set.of(),"SAFE");
            case "one": return leaf("PURE",false,Set.of("*"),"SAFE");
            case "cycle": return leaf("PURE",true,Set.of("*"),"NULLABLE_CYCLE");
            case "variable": return leaf("PURE",false,Set.of("*"),"VARIABLE_LEXING");
            case "custom": return leaf("UNKNOWN",true,Set.of("*"),"UNKNOWN_CUSTOM");
            case "state": return leaf("STATEFUL",true,Set.of("*"),"STATE_DEPENDENCE");
            default: break;
        }
        int open=text.indexOf('(');
        if(open<0 || !text.endsWith(")")) return leaf("UNKNOWN",true,Set.of("*"),"UNKNOWN_PROOF");
        String op=text.substring(0,open);
        if(!Set.of("seq","alt","star","look").contains(op)) return leaf("UNKNOWN",true,Set.of("*"),"UNKNOWN_PROOF");
        var children=parts(text.substring(open+1,text.length()-1)).stream().map(s->proof(s,depth+1)).toList();
        if((op.equals("star")||op.equals("look"))&&children.size()!=1) return leaf("UNKNOWN",true,Set.of("*"),"UNKNOWN_PROOF");
        String purity="PURE", reason="SAFE"; var first=new HashSet<String>(); boolean nullable=op.equals("seq");
        for(var child:children) {
            if(!child.purity.equals("PURE")) purity=child.purity;
            if(reason.equals("SAFE")&&!child.memo.equals("SAFE")) reason=child.memo;
            if(op.equals("alt")) {
                if(reason.equals("SAFE")&&(child.nullable || (!first.isEmpty()&&(first.contains("*")||child.first.contains("*")||!Collections.disjoint(first,child.first))))) reason="OVERLAPPING_FIRST";
                first.addAll(child.first); nullable|=child.nullable;
            } else if(op.equals("seq")) {
                if(nullable) first.addAll(child.first); nullable&=child.nullable;
            }
        }
        if(op.equals("star")||op.equals("look")) {
            var child=children.get(0); first.addAll(child.first); nullable=true;
            if(op.equals("look")) reason="LOOKAHEAD_RESCAN";
            else if(reason.equals("SAFE")&&child.nullable) reason="NULLABLE_REPEAT";
        }
        return leaf(purity,nullable,first,reason);
    }
    static String select(String target,String requirements,String mode,String purity) {
        var needs=new LinkedHashSet<>(List.of(requirements.split(",")));
        var known=Set.of("ast","full-cst","user-state","scope-replay","trace","incremental","custom-native","custom-foreign","provider","callback");
        if(!Set.of("java","rust").contains(target)||!Set.of("auto","classic","ubnfc").contains(mode)||!known.containsAll(needs)) return "REJECT\tUNKNOWN_REQUEST";
        if(needs.contains("custom-foreign")) return "REJECT\tUNKNOWN_CUSTOM";
        boolean rich=needs.stream().anyMatch(n->!n.equals("ast"))||!purity.equals("PURE");
        if(mode.equals("ubnfc")&&rich) return "REJECT\tUBNFC_CAPABILITY_OR_REPLAY";
        if(target.equals("rust")&&needs.stream().anyMatch(Set.of("full-cst","user-state","scope-replay","trace","incremental")::contains)) return "REJECT\tRUST_RICH_PROFILE_UNPROVEN";
        if(rich||mode.equals("classic")) return "CLASSIC\tRICH_ONCE";
        return "UBNFC_MEMO\tFIXED_AST_ORACLE";
    }
    public static void main(String[] args) throws Exception {
        for(String line:Files.readAllLines(Path.of(args[1]))) {
            if(line.isBlank()||line.startsWith("#")) continue;
            String[] p=line.split("\t",-1);
            if(args[0].equals("proof")) {var f=proof(p[1],0);System.out.println(p[0]+"\t"+f.purity+"\t"+f.memo);}
            else System.out.println(p[0]+"\t"+select(p[1],p[2],p[3],p[4]));
        }
    }
}
