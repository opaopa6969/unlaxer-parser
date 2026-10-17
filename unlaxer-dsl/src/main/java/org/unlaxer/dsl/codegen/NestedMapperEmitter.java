package org.unlaxer.dsl.codegen;

import org.unlaxer.dsl.codegen.rust.GrammarIR.*;

final class NestedMapperEmitter {
    private NestedMapperEmitter() {}
    static String mapped(NestedShape shape,String value,int depth) {
        if(depth==shape.containers().size()) return "nested"+(shape.leaf()==Kind.INTEGER?"Integer":"Text")+"("+value+")";
        String child="nested_"+depth;
        return "nested"+(shape.containers().get(depth)==Cardinality.OPTIONAL?"Optional":"List")+"("+value+", "+child+" -> "+mapped(shape,child,depth+1)+")";
    }
    static final String HELPERS = """
        private static org.unlaxer.capture.CaptureSnapshot.Element nestedSnapshot(Token token) {
            var pending = new java.util.ArrayDeque<java.util.Map.Entry<Token,Integer>>();
            pending.push(java.util.Map.entry(token,0));
            Token boundary = null;
            int visits = 0;
            while (!pending.isEmpty()) {
                var current = pending.pop();
                if (++visits > 65536 || current.getValue() >= 256) throw new IllegalArgumentException("nested capture traversal limit");
                if (current.getKey().parser instanceof org.unlaxer.parser.combinator.CaptureBoundaryParser) {
                    if (boundary != null) throw new IllegalArgumentException("multiple nested capture boundaries");
                    boundary = current.getKey();
                } else for (Token child : current.getKey().getOriginalChildren()) {
                    if (visits + pending.size() >= 65536) throw new IllegalArgumentException("nested capture traversal limit");
                    pending.push(java.util.Map.entry(child,current.getValue()+1));
                }
            }
            if (boundary == null) throw new IllegalArgumentException("missing nested capture boundary");
            return org.unlaxer.capture.CaptureSnapshot.from(boundary);
        }
        private static String nestedText(org.unlaxer.capture.CaptureSnapshot.Element value) {
            if (value.kind() != org.unlaxer.parser.combinator.CaptureBoundaryParser.Kind.VALUE) throw new IllegalArgumentException("expected nested value boundary");
            return value.text();
        }
        private static Integer nestedInteger(org.unlaxer.capture.CaptureSnapshot.Element value) {
            try { return Integer.valueOf(nestedText(value).trim()); }
            catch (NumberFormatException error) { throw new IllegalArgumentException("invalid i32 nested capture at " + value.startCP() + ".." + value.endCP(),error); }
        }
        private static <T> Optional<T> nestedOptional(org.unlaxer.capture.CaptureSnapshot.Element value, java.util.function.Function<org.unlaxer.capture.CaptureSnapshot.Element,T> map) {
            if (value.kind() != org.unlaxer.parser.combinator.CaptureBoundaryParser.Kind.OPTIONAL || value.children().size()>1) throw new IllegalArgumentException("expected nested optional boundary");
            return value.children().isEmpty()?Optional.empty():Optional.of(map.apply(value.children().get(0)));
        }
        private static <T> List<T> nestedList(org.unlaxer.capture.CaptureSnapshot.Element value, java.util.function.Function<org.unlaxer.capture.CaptureSnapshot.Element,T> map) {
            if (value.kind() != org.unlaxer.parser.combinator.CaptureBoundaryParser.Kind.LIST) throw new IllegalArgumentException("expected nested list boundary");
            return value.children().stream().map(map).toList();
        }
        """;
}
