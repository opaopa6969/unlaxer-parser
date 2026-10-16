package org.unlaxer.dsl.codegen;

/** Validation of the existing UTF-16 encoded semantic-token extension hook. */
final class LSPSemanticTokenEmitter {
    private LSPSemanticTokenEmitter() {}

    static void emitValidation(IndentedWriter w) {
        String source = """
            private static List<Integer> validateSemanticTokenData(String content, int[] data, SemanticTokensLegend legend) {
                if (data == null) return List.of();
                if (data.length % 5 != 0 || legend == null) throw new IllegalArgumentException("invalid semantic token encoding");
                int modifiers = legend.getTokenModifiers().size();
                int allowedModifiers = modifiers >= 31 ? Integer.MAX_VALUE : (1 << modifiers) - 1;
                long tokenLine = 0, tokenStart = 0;
                int index = 0, line = 0, column = 0;
                List<Integer> result = new ArrayList<>(data.length);
                for (int offset = 0; offset < data.length; offset += 5) {
                    for (int part = 0; part < 5; part++) {
                        if (data[offset + part] < 0) throw new IllegalArgumentException("invalid semantic token encoding");
                        result.add(data[offset + part]);
                    }
                    tokenLine += data[offset];
                    tokenStart = data[offset] == 0 ? tokenStart + data[offset + 1] : data[offset + 1];
                    if (data[offset + 2] == 0 || data[offset + 3] >= legend.getTokenTypes().size()
                            || (data[offset + 4] & ~allowedModifiers) != 0)
                        throw new IllegalArgumentException("invalid semantic token length or legend index");
                    for (long target : new long[]{tokenStart, tokenStart + data[offset + 2]}) {
                        while (line != tokenLine || column != target) {
                            if (line > tokenLine || line == tokenLine && column > target || index == content.length())
                                throw new IllegalArgumentException("invalid semantic token position");
                            int scalar = content.codePointAt(index);
                            index += Character.charCount(scalar);
                            if (scalar == '\\r' || scalar == '\\n') {
                                if (line == tokenLine) throw new IllegalArgumentException("semantic token crosses line boundary");
                                if (scalar == '\\r' && index < content.length() && content.charAt(index) == '\\n') index++;
                                line++; column = 0;
                            } else column += Character.charCount(scalar);
                        }
                    }
                }
                return result;
            }
            """;
        source.lines().forEach(w::line);
        w.blankLine();
    }
}
