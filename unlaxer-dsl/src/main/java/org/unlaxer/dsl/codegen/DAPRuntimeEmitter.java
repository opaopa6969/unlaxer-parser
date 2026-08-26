package org.unlaxer.dsl.codegen;

/**
 * DAP ランタイム/ステッピング関連メソッド（parseAndCollectSteps, AST ウォーク,
 * ステップヘルパー, ブレークポイント, フック, 出力ユーティリティ）のコード生成を担当する。
 */
class DAPRuntimeEmitter {

    private DAPRuntimeEmitter() {}

    /** parseAndCollectSteps() を出力する。 */
    static void emitParseAndCollectSteps(IndentedWriter w, String parsersClass) {
        w.line("private boolean parseAndCollectSteps() {");
        w.indent();
        w.line("if (pendingProgram == null || pendingProgram.isEmpty()) {");
        w.indent();
        w.line("sendOutput(\"stderr\", \"No program specified\\n\");");
        w.line("sendTerminated();");
        w.line("return false;");
        w.dedent();
        w.line("}");
        w.line("try {");
        w.indent();
        w.line("sourceContent = Files.readString(Path.of(pendingProgram));");
        w.dedent();
        w.line("} catch (IOException e) {");
        w.indent();
        w.line("sendOutput(\"stderr\", \"Cannot read file: \" + pendingProgram + \"\\n\");");
        w.line("sendTerminated();");
        w.line("return false;");
        w.dedent();
        w.line("}");
        w.line("Parser parser = " + parsersClass + ".getRootParser();");
        w.line("ParseContext context = new ParseContext(createRootSourceCompat(sourceContent));");
        w.line("Parsed result = parser.parse(context);");
        w.line("context.close();");
        w.line("boolean fullParse = result.isSucceeded() &&");
        w.line("    result.getConsumed().source.sourceAsString().length() == sourceContent.length();");
        w.line("if (!fullParse) {");
        w.indent();
        w.line("int offset = result.isSucceeded()");
        w.line("    ? result.getConsumed().source.sourceAsString().length() : 0;");
        w.line("sendOutput(\"stderr\", \"Parse error at offset \" + offset + \"\\n\");");
        w.line("sendTerminated();");
        w.line("return false;");
        w.dedent();
        w.line("}");
        w.line("stepPoints = new ArrayList<>();");
        w.line("collectStepPoints(result.getConsumed(), stepPoints);");
        w.line("if (stepPoints.isEmpty()) {");
        w.indent();
        w.line("stepPoints.add(result.getConsumed()); // fallback: root token");
        w.dedent();
        w.line("}");
        w.line("if (isAstRuntimeMode()) {");
        w.indent();
        w.line("collectAstSteps();");
        w.line("astNodeCount = astNodeTypes.size();");
        w.line("if (astNodeCount == 0) {");
        w.indent();
        w.line("String detail = astStepError == null || astStepError.isBlank() ? \"no mapped AST nodes\" : astStepError;");
        w.line("sendOutput(\"stderr\", \"AST stepping unavailable: \" + detail + \"\\n\");");
        w.line("sendTerminated();");
        w.line("return false;");
        w.dedent();
        w.line("}");
        w.dedent();
        w.line("} else {");
        w.indent();
        w.line("astNodeTypes = List.of();");
        w.line("astNodeSpans = List.of();");
        w.line("astNodeCount = 0;");
        w.dedent();
        w.line("}");
        w.line("stepIndex = 0;");
        w.line("collectRuntimeProbeVariables();");
        w.line("return true;");
        w.dedent();
        w.line("}");
        w.blankLine();
    }

    /** collectRuntimeProbeVariables() を出力する。 */
    static void emitCollectRuntimeProbeVariables(IndentedWriter w) {
        w.line("protected void collectRuntimeProbeVariables() {");
        w.indent();
        w.line("runtimeProbeVariables = new java.util.LinkedHashMap<>();");
        w.line("runtimeProbeVariables.put(\"runtimeMode\", runtimeMode == null ? \"\" : runtimeMode);");
        w.line("runtimeProbeVariables.put(\"steppingMode\", steppingMode == null ? \"\" : steppingMode);");
        w.line("if (sourceContent == null) {");
        w.indent();
        w.line("return;");
        w.dedent();
        w.line("}");
        w.line("try {");
        w.indent();
        w.line("Map<String, String> values = runtimeVariables(sourceContent, runtimeMode, launchArguments);");
        w.line("if (values != null) { runtimeProbeVariables.putAll(values); }");
        w.dedent();
        w.line("} catch (Throwable error) {");
        w.indent();
        w.line("String message = error.getMessage() == null ? \"\" : error.getMessage();");
        w.line("runtimeProbeVariables.put(\"debugRuntimeError\", error.getClass().getSimpleName() + \":\" + message);");
        w.dedent();
        w.line("}");
        w.dedent();
        w.line("}");
        w.blankLine();
    }

    /** createRootSourceCompat() を出力する。 */
    static void emitCreateRootSourceCompat(IndentedWriter w) {
        w.line("private static StringSource createRootSourceCompat(String source) {");
        w.indent();
        w.line("try {");
        w.indent();
        w.line("java.lang.reflect.Method m = StringSource.class.getMethod(\"createRootSource\", String.class);");
        w.line("Object v = m.invoke(null, source);");
        w.line("if (v instanceof StringSource s) {");
        w.indent();
        w.line("return s;");
        w.dedent();
        w.line("}");
        w.dedent();
        w.line("} catch (Throwable ignored) {}");
        w.line("try {");
        w.indent();
        w.line("for (java.lang.reflect.Constructor<?> c : StringSource.class.getDeclaredConstructors()) {");
        w.indent();
        w.line("Class<?>[] types = c.getParameterTypes();");
        w.line("if (types.length == 0 || types[0] != String.class) {");
        w.indent();
        w.line("continue;");
        w.dedent();
        w.line("}");
        w.line("Object[] args = new Object[types.length];");
        w.line("args[0] = source;");
        w.line("c.setAccessible(true);");
        w.line("Object v = c.newInstance(args);");
        w.line("if (v instanceof StringSource s) {");
        w.indent();
        w.line("return s;");
        w.dedent();
        w.line("}");
        w.dedent();
        w.line("}");
        w.dedent();
        w.line("} catch (Throwable ignored) {}");
        w.line("throw new IllegalStateException(\"No compatible StringSource initializer found\");");
        w.dedent();
        w.line("}");
        w.blankLine();
    }

    /** collectAstSteps() および関連の AST ウォークメソッドを出力する。 */
    static void emitAstMethods(IndentedWriter w, String packageName, String grammarName, String mapperClass) {
        w.line("private void collectAstSteps() {");
        w.indent();
        w.line("astStepError = \"\";");
        w.line("try {");
        w.indent();
        w.line("Object ast = " + mapperClass + ".parse(sourceContent);");
        w.line("List<String> types = new ArrayList<>();");
        w.line("List<int[]> spans = new ArrayList<>();");
        w.line("collectAstNodeMeta(ast, types, spans);");
        w.line("astNodeTypes = types;");
        w.line("astNodeSpans = spans;");
        w.dedent();
        w.line("} catch (Throwable error) {");
        w.indent();
        w.line("astNodeTypes = List.of();");
        w.line("astNodeSpans = List.of();");
        w.line("String message = error.getMessage() == null ? \"\" : error.getMessage();");
        w.line("astStepError = error.getClass().getSimpleName() + \":\" + message;");
        w.dedent();
        w.line("}");
        w.dedent();
        w.line("}");
        w.blankLine();

        w.line("private void collectAstNodeMeta(Object node, List<String> types, List<int[]> spans) {");
        w.indent();
        w.line("if (node == null) {");
        w.indent();
        w.line("return;");
        w.dedent();
        w.line("}");
        w.line("types.add(node.getClass().getSimpleName());");
        w.line("spans.add(sourceSpanOfAstNode(node));");
        w.line("java.lang.reflect.Method[] methods = node.getClass().getMethods();");
        w.line("for (java.lang.reflect.Method method : methods) {");
        w.indent();
        w.line("if (method.getParameterCount() != 0) {");
        w.indent();
        w.line("continue;");
        w.dedent();
        w.line("}");
        w.line("String name = method.getName();");
        w.line("if (\"getClass\".equals(name) || \"hashCode\".equals(name) || \"toString\".equals(name)) {");
        w.indent();
        w.line("continue;");
        w.dedent();
        w.line("}");
        w.line("try {");
        w.indent();
        w.line("Object value = method.invoke(node);");
        w.line("if (value == null) {");
        w.indent();
        w.line("continue;");
        w.dedent();
        w.line("}");
        w.line("if (value instanceof List<?> list) {");
        w.indent();
        w.line("for (Object element : list) {");
        w.indent();
        w.line("if (isAstNodeCandidate(element)) {");
        w.indent();
        w.line("collectAstNodeMeta(element, types, spans);");
        w.dedent();
        w.line("}");
        w.dedent();
        w.line("}");
        w.line("continue;");
        w.dedent();
        w.line("}");
        w.line("if (isAstNodeCandidate(value)) {");
        w.indent();
        w.line("collectAstNodeMeta(value, types, spans);");
        w.dedent();
        w.line("}");
        w.dedent();
        w.line("} catch (Throwable ignored) {}");
        w.dedent();
        w.line("}");
        w.dedent();
        w.line("}");
        w.blankLine();

        w.line("private int[] sourceSpanOfAstNode(Object node) {");
        w.indent();
        w.line("try {");
        w.indent();
        w.line("Class<?> mapperClass = Class.forName(\"" + packageName + "." + mapperClass + "\");");
        w.line("java.lang.reflect.Method method = mapperClass.getMethod(\"sourceSpanOf\", Object.class);");
        w.line("Object raw = method.invoke(null, node);");
        w.line("if (raw instanceof java.util.Optional<?> optional && optional.isPresent()) {");
        w.indent();
        w.line("Object rawSpan = optional.get();");
        w.line("if (!(rawSpan instanceof int[] value)) {");
        w.indent();
        w.line("return null;");
        w.dedent();
        w.line("}");
        w.line("if (value != null && value.length >= 2) {");
        w.indent();
        w.line("int start = Math.max(0, Math.min(value[0], sourceContent.length()));");
        w.line("int end = Math.max(start, Math.min(value[1], sourceContent.length()));");
        w.line("return new int[]{start, end};");
        w.dedent();
        w.line("}");
        w.dedent();
        w.line("}");
        w.dedent();
        w.line("} catch (Throwable ignored) {}");
        w.line("return null;");
        w.dedent();
        w.line("}");
        w.blankLine();

        w.line("private boolean isAstNodeCandidate(Object value) {");
        w.indent();
        w.line("if (value == null) {");
        w.indent();
        w.line("return false;");
        w.dedent();
        w.line("}");
        w.line("String className = value.getClass().getName();");
        w.line("return className.startsWith(\"" + packageName + "." + grammarName + "AST\");");
        w.dedent();
        w.line("}");
        w.blankLine();
    }

    /** ステップ関連ヘルパーメソッドを出力する。 */
    static void emitStepHelpers(IndentedWriter w) {
        w.line("private int stepLimit() {");
        w.indent();
        w.line("if (isAstRuntimeMode() && !astNodeTypes.isEmpty()) {");
        w.indent();
        w.line("return astNodeTypes.size();");
        w.dedent();
        w.line("}");
        w.line("return stepPoints.size();");
        w.dedent();
        w.line("}");
        w.blankLine();

        w.line("private boolean hasCurrentStep() {");
        w.indent();
        w.line("return stepLimit() > 0 && stepIndex < stepLimit();");
        w.dedent();
        w.line("}");
        w.blankLine();

        w.line("private Token currentStepToken() {");
        w.indent();
        w.line("if (stepPoints.isEmpty()) {");
        w.indent();
        w.line("return null;");
        w.dedent();
        w.line("}");
        w.line("if (isAstRuntimeMode() && !astNodeTypes.isEmpty()) {");
        w.indent();
        w.line("int limit = Math.max(1, stepLimit());");
        w.line("int capped = Math.min(stepIndex, limit - 1);");
        w.line("int mapped = (int) Math.floor((double) capped * (stepPoints.size() - 1) / Math.max(1, limit - 1));");
        w.line("return stepPoints.get(Math.max(0, Math.min(mapped, stepPoints.size() - 1)));");
        w.dedent();
        w.line("}");
        w.line("return stepPoints.get(Math.max(0, Math.min(stepIndex, stepPoints.size() - 1)));");
        w.dedent();
        w.line("}");
        w.blankLine();

        w.line("private String currentStepLabel() {");
        w.indent();
        w.line("if (isAstRuntimeMode() && !astNodeTypes.isEmpty()) {");
        w.indent();
        w.line("return astNodeTypes.get(Math.min(stepIndex, astNodeTypes.size() - 1));");
        w.dedent();
        w.line("}");
        w.line("Token current = currentStepToken();");
        w.line("return current == null ? \"step\" : current.getParser().getClass().getSimpleName();");
        w.dedent();
        w.line("}");
        w.blankLine();

        w.line("private int[] currentAstSpan() {");
        w.indent();
        w.line("if (!isAstRuntimeMode() || astNodeSpans.isEmpty()) {");
        w.indent();
        w.line("return null;");
        w.dedent();
        w.line("}");
        w.line("int index = Math.min(stepIndex, astNodeSpans.size() - 1);");
        w.line("int[] span = astNodeSpans.get(index);");
        w.line("if (span == null || span.length < 2) {");
        w.indent();
        w.line("return null;");
        w.dedent();
        w.line("}");
        w.line("return span;");
        w.dedent();
        w.line("}");
        w.blankLine();

        w.line("private String currentAstSourceText() {");
        w.indent();
        w.line("int[] span = currentAstSpan();");
        w.line("if (span == null) {");
        w.indent();
        w.line("Token current = currentStepToken();");
        w.line("return current == null ? \"\" : current.source.sourceAsString().strip();");
        w.dedent();
        w.line("}");
        w.line("int start = Math.max(0, Math.min(span[0], sourceContent.length()));");
        w.line("int end = Math.max(start, Math.min(span[1], sourceContent.length()));");
        w.line("return sourceContent.substring(start, end).strip();");
        w.dedent();
        w.line("}");
        w.blankLine();
    }

    /** collectStepPoints() とトークン/AST ステップ収集メソッドを出力する。 */
    static void emitCollectStepPoints(IndentedWriter w) {
        w.line("private void collectStepPoints(Token token, List<Token> out) {");
        w.indent();
        w.line("if (isAstRuntimeMode()) {");
        w.indent();
        w.line("collectAstStepPoints(token, out);");
        w.line("return;");
        w.dedent();
        w.line("}");
        w.line("collectTokenStepPoints(token, out);");
        w.dedent();
        w.line("}");
        w.blankLine();

        w.line("private boolean isAstRuntimeMode() {");
        w.indent();
        w.line("return \"ast\".equalsIgnoreCase(steppingMode);");
        w.dedent();
        w.line("}");
        w.blankLine();

        w.line("private static String inferSteppingMode(String runtimeMode) {");
        w.indent();
        w.line("if (runtimeMode == null) return \"token\";");
        w.line("String normalized = runtimeMode.strip().toLowerCase(java.util.Locale.ROOT).replace('_', '-');");
        w.line("return normalized.equals(\"ast\") || normalized.endsWith(\"-ast\")");
        w.line("    || normalized.contains(\"ast-evaluator\") ? \"ast\" : \"token\";");
        w.dedent();
        w.line("}");
        w.blankLine();

        w.line("private static String firstNonBlankLaunchArgument(Map<String, Object> args, String... names) {");
        w.indent();
        w.line("for (String name : names) {");
        w.indent();
        w.line("Object value = args.get(name);");
        w.line("if (value != null && !String.valueOf(value).isBlank()) return String.valueOf(value);");
        w.dedent();
        w.line("}");
        w.line("return \"\";");
        w.dedent();
        w.line("}");
        w.blankLine();

        w.line("private void collectTokenStepPoints(Token token, List<Token> out) {");
        w.indent();
        w.line("if (token == null) return;");
        w.line("if (token.filteredChildren == null || token.filteredChildren.isEmpty()) {");
        w.indent();
        w.line("out.add(token);");
        w.line("return;");
        w.dedent();
        w.line("}");
        w.line("for (Token child : token.filteredChildren) {");
        w.indent();
        w.line("collectTokenStepPoints(child, out);");
        w.dedent();
        w.line("}");
        w.dedent();
        w.line("}");
        w.blankLine();

        w.line("private void collectAstStepPoints(Token token, List<Token> out) {");
        w.indent();
        w.line("// Tokens remain a source-location index; stepLimit() is driven exclusively by mapped AST nodes.");
        w.line("collectTokenStepPoints(token, out);");
        w.dedent();
        w.line("}");
        w.blankLine();
    }

    /** ブレークポイント関連ヘルパーメソッドを出力する。 */
    static void emitBreakpointHelpers(IndentedWriter w) {
        w.line("private int getLineForToken(Token t) {");
        w.indent();
        w.line("int charOffset = t.source.offsetFromRoot().value();");
        w.line("int line = 1;");
        w.line("for (int i = 0; i < charOffset && i < sourceContent.length(); i++) {");
        w.indent();
        w.line("if (sourceContent.charAt(i) == '\\n') { line++; }");
        w.dedent();
        w.line("}");
        w.line("return line;");
        w.dedent();
        w.line("}");
        w.blankLine();

        w.line("private int findBreakpointIndex(int fromIndex) {");
        w.indent();
        w.line("int limit = stepLimit();");
        w.line("for (int i = fromIndex + 1; i < limit; i++) {");
        w.indent();
        w.line("if (breakpointLines.contains(getLineForStep(i))) {");
        w.indent();
        w.line("return i;");
        w.dedent();
        w.line("}");
        w.dedent();
        w.line("}");
        w.line("return -1;");
        w.dedent();
        w.line("}");
        w.blankLine();

        w.line("private int getLineForStep(int index) {");
        w.indent();
        w.line("if (isAstRuntimeMode() && !astNodeSpans.isEmpty()) {");
        w.indent();
        w.line("int[] span = astNodeSpans.get(Math.max(0, Math.min(index, astNodeSpans.size() - 1)));");
        w.line("if (span != null && span.length >= 2) {");
        w.indent();
        w.line("return getLineForOffset(span[0]);");
        w.dedent();
        w.line("}");
        w.dedent();
        w.line("}");
        w.line("if (stepPoints.isEmpty()) {");
        w.indent();
        w.line("return 1;");
        w.dedent();
        w.line("}");
        w.line("Token token = stepPoints.get(Math.max(0, Math.min(index, stepPoints.size() - 1)));");
        w.line("return getLineForToken(token);");
        w.dedent();
        w.line("}");
        w.blankLine();

        w.line("private int getLineForOffset(int charOffset) {");
        w.indent();
        w.line("int line = 1;");
        w.line("for (int i = 0; i < charOffset && i < sourceContent.length(); i++) {");
        w.indent();
        w.line("if (sourceContent.charAt(i) == '\\n') {");
        w.indent();
        w.line("line++;");
        w.dedent();
        w.line("}");
        w.dedent();
        w.line("}");
        w.line("return line;");
        w.dedent();
        w.line("}");
        w.blankLine();
    }

    /** GGP フックメソッドを出力する。 */
    static void emitHookMethods(IndentedWriter w) {
        w.line("// Hook: application-specific runtime evaluation and variables");
        w.line("protected Map<String, String> runtimeVariables(String source, String runtimeMode,");
        w.line("        Map<String, Object> launchArguments) {");
        w.indent();
        w.line("return Map.of();");
        w.dedent();
        w.line("}");
        w.blankLine();

        w.line("// Hook: called before each step execution");
        w.line("protected void onBeforeStep(int stepIndex, String stepLabel) {");
        w.indent();
        w.line("// Override to add pre-step logic");
        w.dedent();
        w.line("}");
        w.blankLine();

        w.line("// Hook: called after each step execution");
        w.line("protected void onAfterStep(int stepIndex, String stepLabel) {");
        w.indent();
        w.line("// Override to add post-step logic");
        w.dedent();
        w.line("}");
        w.blankLine();

        w.line("// Hook: additional variables to display in debugger");
        w.line("protected java.util.List<Variable> additionalVariables() {");
        w.indent();
        w.line("return java.util.Collections.emptyList();");
        w.dedent();
        w.line("}");
        w.blankLine();
    }

    /** 出力ユーティリティ (sendOutput, sendTerminated) を出力する。 */
    static void emitOutputUtilities(IndentedWriter w) {
        w.line("private void sendOutput(String category, String output) {");
        w.indent();
        w.line("if (client == null) return;");
        w.line("OutputEventArguments event = new OutputEventArguments();");
        w.line("event.setCategory(category);");
        w.line("event.setOutput(output);");
        w.line("client.output(event);");
        w.dedent();
        w.line("}");
        w.blankLine();

        w.line("private void sendTerminated() {");
        w.indent();
        w.line("if (client == null) return;");
        w.line("client.terminated(new TerminatedEventArguments());");
        w.line("ExitedEventArguments exited = new ExitedEventArguments();");
        w.line("exited.setExitCode(0);");
        w.line("client.exited(exited);");
        w.dedent();
        w.line("}");
    }
}
