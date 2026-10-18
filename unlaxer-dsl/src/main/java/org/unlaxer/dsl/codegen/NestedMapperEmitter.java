package org.unlaxer.dsl.codegen;

import org.unlaxer.dsl.codegen.rust.GrammarIR.*;

final class NestedMapperEmitter {
    private NestedMapperEmitter() {}
    static String mapped(NestedShape shape,String value,int depth,String sources,String projection) {
        if(depth==shape.containers().size() && projection!=null) return "nestedProject("+value+", "+sources+", "+shape.javaLeafType()+".class, "+projection+")";
        if(depth==shape.containers().size()) return "nested"+(shape.leaf()==Kind.INTEGER?"Integer":"Text")+"("+value+")";
        String child="nested_"+depth;
        return "nested"+(shape.containers().get(depth)==Cardinality.OPTIONAL?"Optional":"List")+"("+value+", "+child+" -> "+mapped(shape,child,depth+1,sources,projection)+")";
    }
    static final String HELPERS = """
        private static org.unlaxer.capture.CaptureSnapshot.Element nestedSnapshot(Token token) {
            var pending = new java.util.ArrayDeque<java.util.Map.Entry<Token,Integer>>();
            pending.push(java.util.Map.entry(token,0));
            Token boundary = null;
            int visits = 0;
            while (!pending.isEmpty()) {
                var current = pending.pop();
                guardVisit(current.getKey());
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
            var snapshot = org.unlaxer.capture.CaptureSnapshot.from(boundary);
            var elements = new java.util.ArrayDeque<org.unlaxer.capture.CaptureSnapshot.Element>();
            elements.push(snapshot);
            while (!elements.isEmpty()) {
                var element = elements.pop();
                if (MAPPING_GUARD != null) MAPPING_GUARD.chargeWork(1L + element.children().size());
                if (MAPPING_GUARD != null) MAPPING_GUARD.chargeText(element.text());
                elements.addAll(element.children());
            }
            return snapshot;
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

    static final String PROJECT_HELPERS = """
        // Pair snapshots with original leaf tokens in CST order, including equal/empty spans.
        private static List<Token> nestedSourceNodes(Token root) {
            var pending = new java.util.ArrayDeque<java.util.Map.Entry<Token,Integer>>();
            pending.push(java.util.Map.entry(root,0));
            var values = new ArrayList<Token>();
            int visits = 0;
            while (!pending.isEmpty()) {
                var current = pending.pop();
                guardVisit(current.getKey());
                if (++visits > 65536 || current.getValue() >= 256) throw new IllegalArgumentException("nested projection traversal limit");
                Token token = current.getKey();
                if (token.parser instanceof org.unlaxer.parser.combinator.CaptureBoundaryParser boundary && boundary.boundaryKind() == org.unlaxer.parser.combinator.CaptureBoundaryParser.Kind.SEPARATOR) continue;
                if (token.parser instanceof org.unlaxer.parser.combinator.CaptureBoundaryParser boundary && boundary.boundaryKind() == org.unlaxer.parser.combinator.CaptureBoundaryParser.Kind.VALUE) values.add(token);
                else {
                    var children = token.getOriginalChildren();
                    for (int i=children.size()-1; i>=0; i--) {
                        if (visits + pending.size() >= 65536) throw new IllegalArgumentException("nested projection traversal limit");
                        pending.push(java.util.Map.entry(children.get(i),current.getValue()+1));
                    }
                }
            }
            return List.copyOf(values);
        }
        private static <T> T nestedProject(org.unlaxer.capture.CaptureSnapshot.Element value, java.util.Iterator<Token> sources, Class<T> wanted, java.util.function.Function<Token,?> map) {
            if (value.kind() != org.unlaxer.parser.combinator.CaptureBoundaryParser.Kind.VALUE || !sources.hasNext()) throw new IllegalArgumentException("missing original nested leaf");
            Token root = sources.next();
            var pending = new java.util.ArrayDeque<java.util.Map.Entry<Token,Integer>>();
            pending.push(java.util.Map.entry(root,0));
            var values = new ArrayList<T>();
            int visits = 0;
            while (!pending.isEmpty()) {
                var current = pending.pop();
                guardVisit(current.getKey());
                if (++visits > 65536 || current.getValue() >= 256) throw new IllegalArgumentException("nested projection traversal limit");
                Object projected = map.apply(current.getKey());
                if (projected != null) {
                    if (!wanted.isInstance(projected)) throw new IllegalArgumentException("unexpected nested mapped type at " + value.startCP() + ".." + value.endCP());
                    values.add(wanted.cast(projected));
                } else {
                    var children = current.getKey().getOriginalChildren();
                    for (int i=children.size()-1; i>=0; i--) {
                        if (visits + pending.size() >= 65536) throw new IllegalArgumentException("nested projection traversal limit");
                        pending.push(java.util.Map.entry(children.get(i),current.getValue()+1));
                    }
                }
            }
            if (values.size()!=1) throw new IllegalArgumentException("expected one nested mapped value at " + value.startCP() + ".." + value.endCP() + ", got " + values.size());
            return values.get(0);
        }
        """;
}
