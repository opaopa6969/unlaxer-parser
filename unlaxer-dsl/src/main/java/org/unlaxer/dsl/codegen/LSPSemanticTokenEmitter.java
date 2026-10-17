package org.unlaxer.dsl.codegen;

/** Validation of the existing UTF-16 encoded semantic-token extension hook. */
final class LSPSemanticTokenEmitter {
    private LSPSemanticTokenEmitter() {}

    static void emitValidation(IndentedWriter w) {
        String source = """
            private static List<Integer> validateSemanticTokenData(String content, int[] data, SemanticTokensLegend legend,
                    boolean multiline, boolean overlapping) {
                if (data == null) return List.of();
                if (data.length % 5 != 0 || legend == null) throw new IllegalArgumentException("invalid semantic token encoding");
                if (data.length == 0) return List.of();
                int modifiers = legend.getTokenModifiers().size();
                int allowedModifiers = modifiers >= 31 ? Integer.MAX_VALUE : (1 << modifiers) - 1;
                List<Integer> starts = new ArrayList<>(), ends = new ArrayList<>();
                BitSet boundaries = new BitSet(content.length() + 1);
                starts.add(0); boundaries.set(0);
                for (int index = 0; index < content.length();) {
                    int scalar = content.codePointAt(index);
                    if (scalar == '\\r' || scalar == '\\n') {
                        ends.add(index++);
                        if (scalar == '\\r' && index < content.length() && content.charAt(index) == '\\n') index++;
                        starts.add(index);
                    } else index += Character.charCount(scalar);
                    boundaries.set(index);
                }
                ends.add(content.length());
                long tokenLine = 0, tokenStart = 0, previousEnd = 0;
                List<Integer> result = new ArrayList<>(data.length);
                for (int offset = 0; offset < data.length; offset += 5) {
                    for (int part = 0; part < 5; part++) {
                        if (data[offset + part] < 0) throw new IllegalArgumentException("invalid semantic token encoding");
                        result.add(data[offset + part]);
                    }
                    tokenLine += data[offset];
                    tokenStart = data[offset] == 0 ? tokenStart + data[offset + 1] : data[offset + 1];
                    if (data[offset + 3] >= 65536 || data[offset + 3] >= legend.getTokenTypes().size()
                            || (data[offset + 4] & ~allowedModifiers) != 0)
                        throw new IllegalArgumentException("invalid semantic token length or legend index");
                    if (tokenLine >= starts.size()) throw new IllegalArgumentException("semantic token outside document");
                    long absolute = starts.get((int) tokenLine) + tokenStart;
                    int lineEnd = ends.get((int) tokenLine);
                    if (absolute > lineEnd) throw new IllegalArgumentException("semantic token outside line");
                    long rawEnd = absolute + data[offset + 2];
                    // LSP 3.17: excess length is interpreted at line end without multiline support.
                    long end = multiline ? rawEnd : Math.min(rawEnd, lineEnd);
                    if (end > content.length() || !boundaries.get((int) absolute) || !boundaries.get((int) end)
                            || !overlapping && end > absolute && absolute < previousEnd)
                        throw new IllegalArgumentException("invalid semantic token position or overlap");
                    previousEnd = Math.max(previousEnd, end);
                }
                return result;
            }
            """;
        source.lines().forEach(w::line);
        w.blankLine();
    }
}
