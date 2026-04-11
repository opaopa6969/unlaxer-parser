package org.unlaxer.dsl.codegen;

/**
 * DAP ランタイム/ステッピング関連メソッド（parseAndCollectSteps, AST ウォーク,
 * ステップヘルパー, ブレークポイント, フック, 出力ユーティリティ）のコード生成を担当する。
 */
class DAPRuntimeEmitter {

    private DAPRuntimeEmitter() {}

    /** parseAndCollectSteps() を出力する。 */
    static void emitParseAndCollectSteps(StringBuilder sb, String parsersClass) {
        sb.append("    private boolean parseAndCollectSteps() {\n");
        sb.append("        if (pendingProgram == null || pendingProgram.isEmpty()) {\n");
        sb.append("            sendOutput(\"stderr\", \"No program specified\\n\");\n");
        sb.append("            sendTerminated();\n");
        sb.append("            return false;\n");
        sb.append("        }\n");
        sb.append("        try {\n");
        sb.append("            sourceContent = Files.readString(Path.of(pendingProgram));\n");
        sb.append("        } catch (IOException e) {\n");
        sb.append("            sendOutput(\"stderr\", \"Cannot read file: \" + pendingProgram + \"\\n\");\n");
        sb.append("            sendTerminated();\n");
        sb.append("            return false;\n");
        sb.append("        }\n");
        sb.append("        Parser parser = ").append(parsersClass).append(".getRootParser();\n");
        sb.append("        ParseContext context = new ParseContext(createRootSourceCompat(sourceContent));\n");
        sb.append("        Parsed result = parser.parse(context);\n");
        sb.append("        context.close();\n");
        sb.append("        boolean fullParse = result.isSucceeded() &&\n");
        sb.append("            result.getConsumed().source.sourceAsString().length() == sourceContent.length();\n");
        sb.append("        if (!fullParse) {\n");
        sb.append("            int offset = result.isSucceeded()\n");
        sb.append("                ? result.getConsumed().source.sourceAsString().length() : 0;\n");
        sb.append("            sendOutput(\"stderr\", \"Parse error at offset \" + offset + \"\\n\");\n");
        sb.append("            sendTerminated();\n");
        sb.append("            return false;\n");
        sb.append("        }\n");
        sb.append("        stepPoints = new ArrayList<>();\n");
        sb.append("        collectStepPoints(result.getConsumed(), stepPoints);\n");
        sb.append("        if (stepPoints.isEmpty()) {\n");
        sb.append("            stepPoints.add(result.getConsumed()); // fallback: root token\n");
        sb.append("        }\n");
        sb.append("        if (isAstRuntimeMode()) {\n");
        sb.append("            collectAstSteps();\n");
        sb.append("            astNodeCount = astNodeTypes.size();\n");
        sb.append("        } else {\n");
        sb.append("            astNodeTypes = List.of();\n");
        sb.append("            astNodeSpans = List.of();\n");
        sb.append("            astNodeCount = 0;\n");
        sb.append("        }\n");
        sb.append("        stepIndex = 0;\n");
        sb.append("        collectRuntimeProbeVariables();\n");
        sb.append("        return true;\n");
        sb.append("    }\n\n");
    }

    /** collectRuntimeProbeVariables() を出力する。 */
    static void emitCollectRuntimeProbeVariables(StringBuilder sb) {
        sb.append("    private void collectRuntimeProbeVariables() {\n");
        sb.append("        runtimeProbeVariables = new java.util.LinkedHashMap<>();\n");
        sb.append("        runtimeProbeVariables.put(\"runtimeMode\", runtimeMode == null ? \"\" : runtimeMode);\n");
        sb.append("        if (sourceContent == null) {\n");
        sb.append("            return;\n");
        sb.append("        }\n");
        sb.append("        try {\n");
        sb.append("            Class<?> bridgeClass = Class.forName(\"org.unlaxer.tinyexpression.dap.TinyExpressionDapRuntimeBridge\");\n");
        sb.append("            java.lang.reflect.Method method = bridgeClass.getMethod(\"debugVariables\", String.class, String.class);\n");
        sb.append("            Object raw = method.invoke(null, sourceContent, runtimeMode);\n");
        sb.append("            if (!(raw instanceof Map<?, ?> map)) {\n");
        sb.append("                return;\n");
        sb.append("            }\n");
        sb.append("            for (Map.Entry<?, ?> entry : map.entrySet()) {\n");
        sb.append("                Object key = entry.getKey();\n");
        sb.append("                if (key == null) {\n");
        sb.append("                    continue;\n");
        sb.append("                }\n");
        sb.append("                String value = entry.getValue() == null ? \"\" : String.valueOf(entry.getValue());\n");
        sb.append("                runtimeProbeVariables.put(String.valueOf(key), value);\n");
        sb.append("            }\n");
        sb.append("        } catch (Throwable ignored) {\n");
        sb.append("            // bridge is optional\n");
        sb.append("        }\n");
        sb.append("    }\n\n");
    }

    /** createRootSourceCompat() を出力する。 */
    static void emitCreateRootSourceCompat(StringBuilder sb) {
        sb.append("    private static StringSource createRootSourceCompat(String source) {\n");
        sb.append("        try {\n");
        sb.append("            java.lang.reflect.Method m = StringSource.class.getMethod(\"createRootSource\", String.class);\n");
        sb.append("            Object v = m.invoke(null, source);\n");
        sb.append("            if (v instanceof StringSource s) {\n");
        sb.append("                return s;\n");
        sb.append("            }\n");
        sb.append("        } catch (Throwable ignored) {}\n");
        sb.append("        try {\n");
        sb.append("            for (java.lang.reflect.Constructor<?> c : StringSource.class.getDeclaredConstructors()) {\n");
        sb.append("                Class<?>[] types = c.getParameterTypes();\n");
        sb.append("                if (types.length == 0 || types[0] != String.class) {\n");
        sb.append("                    continue;\n");
        sb.append("                }\n");
        sb.append("                Object[] args = new Object[types.length];\n");
        sb.append("                args[0] = source;\n");
        sb.append("                c.setAccessible(true);\n");
        sb.append("                Object v = c.newInstance(args);\n");
        sb.append("                if (v instanceof StringSource s) {\n");
        sb.append("                    return s;\n");
        sb.append("                }\n");
        sb.append("            }\n");
        sb.append("        } catch (Throwable ignored) {}\n");
        sb.append("        throw new IllegalStateException(\"No compatible StringSource initializer found\");\n");
        sb.append("    }\n\n");
    }

    /** collectAstSteps() および関連の AST ウォークメソッドを出力する。 */
    static void emitAstMethods(StringBuilder sb, String packageName, String grammarName, String mapperClass) {
        sb.append("    private void collectAstSteps() {\n");
        sb.append("        try {\n");
        sb.append("            Object ast = ").append(mapperClass).append(".parse(sourceContent);\n");
        sb.append("            List<String> types = new ArrayList<>();\n");
        sb.append("            List<int[]> spans = new ArrayList<>();\n");
        sb.append("            collectAstNodeMeta(ast, types, spans);\n");
        sb.append("            astNodeTypes = types;\n");
        sb.append("            astNodeSpans = spans;\n");
        sb.append("        } catch (Throwable ignored) {\n");
        sb.append("            astNodeTypes = List.of();\n");
        sb.append("            astNodeSpans = List.of();\n");
        sb.append("        }\n");
        sb.append("    }\n\n");

        sb.append("    private void collectAstNodeMeta(Object node, List<String> types, List<int[]> spans) {\n");
        sb.append("        if (node == null) {\n");
        sb.append("            return;\n");
        sb.append("        }\n");
        sb.append("        types.add(node.getClass().getSimpleName());\n");
        sb.append("        spans.add(sourceSpanOfAstNode(node));\n");
        sb.append("        java.lang.reflect.Method[] methods = node.getClass().getMethods();\n");
        sb.append("        for (java.lang.reflect.Method method : methods) {\n");
        sb.append("            if (method.getParameterCount() != 0) {\n");
        sb.append("                continue;\n");
        sb.append("            }\n");
        sb.append("            String name = method.getName();\n");
        sb.append("            if (\"getClass\".equals(name) || \"hashCode\".equals(name) || \"toString\".equals(name)) {\n");
        sb.append("                continue;\n");
        sb.append("            }\n");
        sb.append("            try {\n");
        sb.append("                Object value = method.invoke(node);\n");
        sb.append("                if (value == null) {\n");
        sb.append("                    continue;\n");
        sb.append("                }\n");
        sb.append("                if (value instanceof List<?> list) {\n");
        sb.append("                    for (Object element : list) {\n");
        sb.append("                        if (isAstNodeCandidate(element)) {\n");
        sb.append("                            collectAstNodeMeta(element, types, spans);\n");
        sb.append("                        }\n");
        sb.append("                    }\n");
        sb.append("                    continue;\n");
        sb.append("                }\n");
        sb.append("                if (isAstNodeCandidate(value)) {\n");
        sb.append("                    collectAstNodeMeta(value, types, spans);\n");
        sb.append("                }\n");
        sb.append("            } catch (Throwable ignored) {}\n");
        sb.append("        }\n");
        sb.append("    }\n\n");

        sb.append("    private int[] sourceSpanOfAstNode(Object node) {\n");
        sb.append("        try {\n");
        sb.append("            Class<?> mapperClass = Class.forName(\"").append(packageName).append(".").append(mapperClass).append("\");\n");
        sb.append("            java.lang.reflect.Method method = mapperClass.getMethod(\"sourceSpanOf\", Object.class);\n");
        sb.append("            Object raw = method.invoke(null, node);\n");
        sb.append("            if (raw instanceof java.util.Optional<?> optional && optional.isPresent()) {\n");
        sb.append("                Object rawSpan = optional.get();\n");
        sb.append("                if (!(rawSpan instanceof int[] value)) {\n");
        sb.append("                    return null;\n");
        sb.append("                }\n");
        sb.append("                if (value != null && value.length >= 2) {\n");
        sb.append("                    int start = Math.max(0, Math.min(value[0], sourceContent.length()));\n");
        sb.append("                    int end = Math.max(start, Math.min(value[1], sourceContent.length()));\n");
        sb.append("                    return new int[]{start, end};\n");
        sb.append("                }\n");
        sb.append("            }\n");
        sb.append("        } catch (Throwable ignored) {}\n");
        sb.append("        return null;\n");
        sb.append("    }\n\n");

        sb.append("    private boolean isAstNodeCandidate(Object value) {\n");
        sb.append("        if (value == null) {\n");
        sb.append("            return false;\n");
        sb.append("        }\n");
        sb.append("        String className = value.getClass().getName();\n");
        sb.append("        return className.startsWith(\"").append(packageName).append(".").append(grammarName).append("AST\");\n");
        sb.append("    }\n\n");
    }

    /** ステップ関連ヘルパーメソッドを出力する。 */
    static void emitStepHelpers(StringBuilder sb) {
        sb.append("    private int stepLimit() {\n");
        sb.append("        if (isAstRuntimeMode() && !astNodeTypes.isEmpty()) {\n");
        sb.append("            return astNodeTypes.size();\n");
        sb.append("        }\n");
        sb.append("        return stepPoints.size();\n");
        sb.append("    }\n\n");

        sb.append("    private boolean hasCurrentStep() {\n");
        sb.append("        return stepLimit() > 0 && stepIndex < stepLimit();\n");
        sb.append("    }\n\n");

        sb.append("    private Token currentStepToken() {\n");
        sb.append("        if (stepPoints.isEmpty()) {\n");
        sb.append("            return null;\n");
        sb.append("        }\n");
        sb.append("        if (isAstRuntimeMode() && !astNodeTypes.isEmpty()) {\n");
        sb.append("            int limit = Math.max(1, stepLimit());\n");
        sb.append("            int capped = Math.min(stepIndex, limit - 1);\n");
        sb.append("            int mapped = (int) Math.floor((double) capped * (stepPoints.size() - 1) / Math.max(1, limit - 1));\n");
        sb.append("            return stepPoints.get(Math.max(0, Math.min(mapped, stepPoints.size() - 1)));\n");
        sb.append("        }\n");
        sb.append("        return stepPoints.get(Math.max(0, Math.min(stepIndex, stepPoints.size() - 1)));\n");
        sb.append("    }\n\n");

        sb.append("    private String currentStepLabel() {\n");
        sb.append("        if (isAstRuntimeMode() && !astNodeTypes.isEmpty()) {\n");
        sb.append("            return astNodeTypes.get(Math.min(stepIndex, astNodeTypes.size() - 1));\n");
        sb.append("        }\n");
        sb.append("        Token current = currentStepToken();\n");
        sb.append("        return current == null ? \"step\" : current.getParser().getClass().getSimpleName();\n");
        sb.append("    }\n\n");

        sb.append("    private int[] currentAstSpan() {\n");
        sb.append("        if (!isAstRuntimeMode() || astNodeSpans.isEmpty()) {\n");
        sb.append("            return null;\n");
        sb.append("        }\n");
        sb.append("        int index = Math.min(stepIndex, astNodeSpans.size() - 1);\n");
        sb.append("        int[] span = astNodeSpans.get(index);\n");
        sb.append("        if (span == null || span.length < 2) {\n");
        sb.append("            return null;\n");
        sb.append("        }\n");
        sb.append("        return span;\n");
        sb.append("    }\n\n");

        sb.append("    private String currentAstSourceText() {\n");
        sb.append("        int[] span = currentAstSpan();\n");
        sb.append("        if (span == null) {\n");
        sb.append("            Token current = currentStepToken();\n");
        sb.append("            return current == null ? \"\" : current.source.sourceAsString().strip();\n");
        sb.append("        }\n");
        sb.append("        int start = Math.max(0, Math.min(span[0], sourceContent.length()));\n");
        sb.append("        int end = Math.max(start, Math.min(span[1], sourceContent.length()));\n");
        sb.append("        return sourceContent.substring(start, end).strip();\n");
        sb.append("    }\n\n");
    }

    /** collectStepPoints() とトークン/AST ステップ収集メソッドを出力する。 */
    static void emitCollectStepPoints(StringBuilder sb) {
        sb.append("    private void collectStepPoints(Token token, List<Token> out) {\n");
        sb.append("        if (isAstRuntimeMode()) {\n");
        sb.append("            collectAstStepPoints(token, out);\n");
        sb.append("            return;\n");
        sb.append("        }\n");
        sb.append("        collectTokenStepPoints(token, out);\n");
        sb.append("    }\n\n");

        sb.append("    private boolean isAstRuntimeMode() {\n");
        sb.append("        return \"ast\".equalsIgnoreCase(runtimeMode) || \"ast_evaluator\".equalsIgnoreCase(runtimeMode);\n");
        sb.append("    }\n\n");

        sb.append("    private void collectTokenStepPoints(Token token, List<Token> out) {\n");
        sb.append("        if (token == null) return;\n");
        sb.append("        if (token.filteredChildren == null || token.filteredChildren.isEmpty()) {\n");
        sb.append("            out.add(token);\n");
        sb.append("            return;\n");
        sb.append("        }\n");
        sb.append("        for (Token child : token.filteredChildren) {\n");
        sb.append("            collectTokenStepPoints(child, out);\n");
        sb.append("        }\n");
        sb.append("    }\n\n");

        sb.append("    private void collectAstStepPoints(Token token, List<Token> out) {\n");
        sb.append("        // Current fallback keeps token-level stepping; replace with AST-node stepping when mapper/evaluator runtime is wired.\n");
        sb.append("        collectTokenStepPoints(token, out);\n");
        sb.append("    }\n\n");
    }

    /** ブレークポイント関連ヘルパーメソッドを出力する。 */
    static void emitBreakpointHelpers(StringBuilder sb) {
        sb.append("    private int getLineForToken(Token t) {\n");
        sb.append("        int charOffset = t.source.offsetFromRoot().value();\n");
        sb.append("        int line = 1;\n");
        sb.append("        for (int i = 0; i < charOffset && i < sourceContent.length(); i++) {\n");
        sb.append("            if (sourceContent.charAt(i) == '\\n') { line++; }\n");
        sb.append("        }\n");
        sb.append("        return line;\n");
        sb.append("    }\n\n");

        sb.append("    private int findBreakpointIndex(int fromIndex) {\n");
        sb.append("        int limit = stepLimit();\n");
        sb.append("        for (int i = fromIndex + 1; i < limit; i++) {\n");
        sb.append("            if (breakpointLines.contains(getLineForStep(i))) {\n");
        sb.append("                return i;\n");
        sb.append("            }\n");
        sb.append("        }\n");
        sb.append("        return -1;\n");
        sb.append("    }\n\n");

        sb.append("    private int getLineForStep(int index) {\n");
        sb.append("        if (isAstRuntimeMode() && !astNodeSpans.isEmpty()) {\n");
        sb.append("            int[] span = astNodeSpans.get(Math.max(0, Math.min(index, astNodeSpans.size() - 1)));\n");
        sb.append("            if (span != null && span.length >= 2) {\n");
        sb.append("                return getLineForOffset(span[0]);\n");
        sb.append("            }\n");
        sb.append("        }\n");
        sb.append("        if (stepPoints.isEmpty()) {\n");
        sb.append("            return 1;\n");
        sb.append("        }\n");
        sb.append("        Token token = stepPoints.get(Math.max(0, Math.min(index, stepPoints.size() - 1)));\n");
        sb.append("        return getLineForToken(token);\n");
        sb.append("    }\n\n");

        sb.append("    private int getLineForOffset(int charOffset) {\n");
        sb.append("        int line = 1;\n");
        sb.append("        for (int i = 0; i < charOffset && i < sourceContent.length(); i++) {\n");
        sb.append("            if (sourceContent.charAt(i) == '\\n') {\n");
        sb.append("                line++;\n");
        sb.append("            }\n");
        sb.append("        }\n");
        sb.append("        return line;\n");
        sb.append("    }\n\n");
    }

    /** GGP フックメソッドを出力する。 */
    static void emitHookMethods(StringBuilder sb) {
        sb.append("    // Hook: called before each step execution\n");
        sb.append("    protected void onBeforeStep(int stepIndex, String stepLabel) {\n");
        sb.append("        // Override to add pre-step logic\n");
        sb.append("    }\n\n");

        sb.append("    // Hook: called after each step execution\n");
        sb.append("    protected void onAfterStep(int stepIndex, String stepLabel) {\n");
        sb.append("        // Override to add post-step logic\n");
        sb.append("    }\n\n");

        sb.append("    // Hook: additional variables to display in debugger\n");
        sb.append("    protected java.util.List<Variable> additionalVariables() {\n");
        sb.append("        return java.util.Collections.emptyList();\n");
        sb.append("    }\n\n");
    }

    /** 出力ユーティリティ (sendOutput, sendTerminated) を出力する。 */
    static void emitOutputUtilities(StringBuilder sb) {
        sb.append("    private void sendOutput(String category, String output) {\n");
        sb.append("        if (client == null) return;\n");
        sb.append("        OutputEventArguments event = new OutputEventArguments();\n");
        sb.append("        event.setCategory(category);\n");
        sb.append("        event.setOutput(output);\n");
        sb.append("        client.output(event);\n");
        sb.append("    }\n\n");

        sb.append("    private void sendTerminated() {\n");
        sb.append("        if (client == null) return;\n");
        sb.append("        client.terminated(new TerminatedEventArguments());\n");
        sb.append("        ExitedEventArguments exited = new ExitedEventArguments();\n");
        sb.append("        exited.setExitCode(0);\n");
        sb.append("        client.exited(exited);\n");
        sb.append("    }\n");
    }
}
