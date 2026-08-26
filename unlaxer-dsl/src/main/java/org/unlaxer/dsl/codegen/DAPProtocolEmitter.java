package org.unlaxer.dsl.codegen;

/**
 * DAP プロトコルメソッド（initialize, launch, configurationDone, setBreakpoints,
 * next, continue_, threads, stackTrace, scopes, variables, disconnect）の
 * コード生成を担当する。
 */
class DAPProtocolEmitter {

    private DAPProtocolEmitter() {}

    /** フィールド宣言と connect() を出力する。 */
    static void emitFieldsAndConnect(IndentedWriter w) {
        // fields
        w.line("protected IDebugProtocolClient client;");
        w.line("protected String pendingProgram;");
        w.line("protected String runtimeMode = \"token\";");
        w.line("protected String steppingMode = \"token\";");
        w.line("protected boolean stopOnEntry;");
        w.line("protected Map<String, Object> launchArguments = Map.of();");
        w.line("protected String sourceContent = \"\";");
        w.line("protected int sourceLineOffset = 0;");
        w.line("protected List<Token> stepPoints = new ArrayList<>();");
        w.line("protected int stepIndex = 0;");
        w.line("private int astNodeCount = 0;");
        w.line("private List<String> astNodeTypes = new ArrayList<>();");
        w.line("private List<int[]> astNodeSpans = new ArrayList<>();");
        w.line("private String astStepError = \"\";");
        w.line("private Map<String, String> runtimeProbeVariables = new java.util.LinkedHashMap<>();");
        w.line("private Set<Integer> breakpointLines = new HashSet<>();");
        w.blankLine();

        // connect()
        w.line("public void connect(IDebugProtocolClient client) {");
        w.indent();
        w.line("this.client = client;");
        w.dedent();
        w.line("}");
        w.blankLine();
    }

    /** initialize() を出力する。 */
    static void emitInitialize(IndentedWriter w) {
        w.line("@Override");
        w.line("public CompletableFuture<Capabilities> initialize(InitializeRequestArguments args) {");
        w.indent();
        w.line("Capabilities cap = new Capabilities();");
        w.line("cap.setSupportsConfigurationDoneRequest(true);");
        w.line("return CompletableFuture.completedFuture(cap);");
        w.dedent();
        w.line("}");
        w.blankLine();
    }

    /** launch() を出力する。 */
    static void emitLaunch(IndentedWriter w) {
        w.line("@Override");
        w.line("public CompletableFuture<Void> launch(Map<String, Object> args) {");
        w.indent();
        w.line("launchArguments = args == null ? Map.of() : new java.util.LinkedHashMap<>(args);");
        w.line("pendingProgram = firstNonBlankLaunchArgument(launchArguments, \"program\", \"formulaSource\");");
        w.line("runtimeMode = String.valueOf(launchArguments.getOrDefault(\"runtimeMode\", \"token\"));");
        w.line("steppingMode = String.valueOf(launchArguments.getOrDefault(\"steppingMode\", inferSteppingMode(runtimeMode)));");
        w.line("stopOnEntry = Boolean.TRUE.equals(launchArguments.get(\"stopOnEntry\"));");
        w.line("client.initialized();");
        w.line("return CompletableFuture.completedFuture(null);");
        w.dedent();
        w.line("}");
        w.blankLine();
    }

    /** configurationDone() を出力する。 */
    static void emitConfigurationDone(IndentedWriter w) {
        w.line("@Override");
        w.line("public CompletableFuture<Void> configurationDone(ConfigurationDoneArguments args) {");
        w.indent();
        w.line("if (!parseAndCollectSteps()) {");
        w.indent();
        w.line("return CompletableFuture.completedFuture(null); // error already sent");
        w.dedent();
        w.line("}");
        w.line("if (stopOnEntry && !stepPoints.isEmpty()) {");
        w.indent();
        w.line("StoppedEventArguments stopped = new StoppedEventArguments();");
        w.line("stopped.setReason(\"entry\");");
        w.line("stopped.setThreadId(1);");
        w.line("stopped.setAllThreadsStopped(true);");
        w.line("client.stopped(stopped);");
        w.dedent();
        w.line("} else if (!breakpointLines.isEmpty()) {");
        w.indent();
        w.line("// run to first breakpoint");
        w.line("int bp = findBreakpointIndex(-1);");
        w.line("if (bp >= 0) {");
        w.indent();
        w.line("stepIndex = bp;");
        w.line("StoppedEventArguments stopped = new StoppedEventArguments();");
        w.line("stopped.setReason(\"breakpoint\");");
        w.line("stopped.setThreadId(1);");
        w.line("stopped.setAllThreadsStopped(true);");
        w.line("client.stopped(stopped);");
        w.dedent();
        w.line("} else {");
        w.indent();
        w.line("sendOutput(\"stdout\", \"Parsed successfully: \" + pendingProgram + \"\\n\");");
        w.line("sendTerminated();");
        w.dedent();
        w.line("}");
        w.dedent();
        w.line("} else {");
        w.indent();
        w.line("sendOutput(\"stdout\", \"Parsed successfully: \" + pendingProgram + \"\\n\");");
        w.line("sendTerminated();");
        w.dedent();
        w.line("}");
        w.line("return CompletableFuture.completedFuture(null);");
        w.dedent();
        w.line("}");
        w.blankLine();
    }

    /** setBreakpoints() を出力する。 */
    static void emitSetBreakpoints(IndentedWriter w) {
        w.line("@Override");
        w.line("public CompletableFuture<SetBreakpointsResponse> setBreakpoints(SetBreakpointsArguments args) {");
        w.indent();
        w.line("breakpointLines.clear();");
        w.line("SetBreakpointsResponse response = new SetBreakpointsResponse();");
        w.line("SourceBreakpoint[] requested = args.getBreakpoints();");
        w.line("int count = requested == null ? 0 : requested.length;");
        w.line("Breakpoint[] breakpoints = new Breakpoint[count];");
        w.line("for (int i = 0; i < count; i++) {");
        w.indent();
        w.line("int line = requested[i].getLine();");
        w.line("breakpointLines.add(line);");
        w.line("Breakpoint bp = new Breakpoint();");
        w.line("bp.setVerified(true);");
        w.line("bp.setLine(line);");
        w.line("breakpoints[i] = bp;");
        w.dedent();
        w.line("}");
        w.line("response.setBreakpoints(breakpoints);");
        w.line("return CompletableFuture.completedFuture(response);");
        w.dedent();
        w.line("}");
        w.blankLine();
    }

    /** next() を出力する。 */
    static void emitNext(IndentedWriter w) {
        w.line("@Override");
        w.line("public CompletableFuture<Void> next(NextArguments args) {");
        w.indent();
        w.line("onBeforeStep(stepIndex + 1, currentStepLabel());");
        w.line("stepIndex++;");
        w.line("onAfterStep(stepIndex, currentStepLabel());");
        w.line("if (stepIndex >= stepLimit()) {");
        w.indent();
        w.line("sendOutput(\"stdout\", \"Completed: \" + pendingProgram + \"\\n\");");
        w.line("sendTerminated();");
        w.dedent();
        w.line("} else {");
        w.indent();
        w.line("StoppedEventArguments stopped = new StoppedEventArguments();");
        w.line("stopped.setReason(\"step\");");
        w.line("stopped.setThreadId(1);");
        w.line("stopped.setAllThreadsStopped(true);");
        w.line("client.stopped(stopped);");
        w.dedent();
        w.line("}");
        w.line("return CompletableFuture.completedFuture(null);");
        w.dedent();
        w.line("}");
        w.blankLine();
    }

    /** continue_() を出力する。 */
    static void emitContinue(IndentedWriter w) {
        w.line("@Override");
        w.line("public CompletableFuture<ContinueResponse> continue_(ContinueArguments args) {");
        w.indent();
        w.line("ContinueResponse response = new ContinueResponse();");
        w.line("response.setAllThreadsContinued(true);");
        w.line("int bp = findBreakpointIndex(stepIndex);");
        w.line("if (bp >= 0) {");
        w.indent();
        w.line("stepIndex = bp;");
        w.line("StoppedEventArguments stopped = new StoppedEventArguments();");
        w.line("stopped.setReason(\"breakpoint\");");
        w.line("stopped.setThreadId(1);");
        w.line("stopped.setAllThreadsStopped(true);");
        w.line("client.stopped(stopped);");
        w.dedent();
        w.line("} else {");
        w.indent();
        w.line("sendOutput(\"stdout\", \"Completed: \" + pendingProgram + \"\\n\");");
        w.line("sendTerminated();");
        w.dedent();
        w.line("}");
        w.line("return CompletableFuture.completedFuture(response);");
        w.dedent();
        w.line("}");
        w.blankLine();
    }

    /** threads() を出力する。 */
    static void emitThreads(IndentedWriter w) {
        w.line("@Override");
        w.line("public CompletableFuture<ThreadsResponse> threads() {");
        w.indent();
        w.line("ThreadsResponse response = new ThreadsResponse();");
        w.line("org.eclipse.lsp4j.debug.Thread thread = new org.eclipse.lsp4j.debug.Thread();");
        w.line("thread.setId(1);");
        w.line("thread.setName(\"main\");");
        w.line("response.setThreads(new org.eclipse.lsp4j.debug.Thread[]{thread});");
        w.line("return CompletableFuture.completedFuture(response);");
        w.dedent();
        w.line("}");
        w.blankLine();
    }

    /** stackTrace() を出力する。 */
    static void emitStackTrace(IndentedWriter w) {
        w.line("@Override");
        w.line("public CompletableFuture<StackTraceResponse> stackTrace(StackTraceArguments args) {");
        w.indent();
        w.line("StackTraceResponse response = new StackTraceResponse();");
        w.line("if (hasCurrentStep()) {");
        w.indent();
        w.line("Token current = currentStepToken();");
        w.line("if (current == null) {");
        w.indent();
        w.line("response.setStackFrames(new StackFrame[0]);");
        w.line("return CompletableFuture.completedFuture(response);");
        w.dedent();
        w.line("}");
        w.line("int charOffset = current.source.offsetFromRoot().value();");
        w.line("if (isAstRuntimeMode()) {");
        w.indent();
        w.line("int[] span = currentAstSpan();");
        w.line("if (span != null) {");
        w.indent();
        w.line("charOffset = span[0];");
        w.dedent();
        w.line("}");
        w.dedent();
        w.line("}");
        w.line("int line = 0, col = 0;");
        w.line("for (int i = 0; i < charOffset && i < sourceContent.length(); i++) {");
        w.indent();
        w.line("if (sourceContent.charAt(i) == '\\n') { line++; col = 0; }");
        w.line("else { col++; }");
        w.dedent();
        w.line("}");
        w.line("StackFrame frame = new StackFrame();");
        w.line("frame.setId(0);");
        w.line("frame.setName(currentStepLabel() + \" (\" + (stepIndex + 1) + \"/\" + stepLimit() + \")\");");
        w.line("frame.setLine(line + sourceLineOffset + 1);   // DAP is 1-based");
        w.line("frame.setColumn(col + 1);");
        w.line("Source source = new Source();");
        w.line("source.setPath(pendingProgram);");
        w.line("source.setName(Path.of(pendingProgram).getFileName().toString());");
        w.line("frame.setSource(source);");
        w.line("response.setStackFrames(new StackFrame[]{frame});");
        w.dedent();
        w.line("} else if (pendingProgram != null && !pendingProgram.isEmpty()) {");
        w.indent();
        w.line("StackFrame frame = new StackFrame();");
        w.line("frame.setId(0);");
        w.line("frame.setName(\"<program>\");");
        w.line("frame.setLine(sourceLineOffset + 1);");
        w.line("frame.setColumn(0);");
        w.line("Source source = new Source();");
        w.line("source.setPath(pendingProgram);");
        w.line("frame.setSource(source);");
        w.line("response.setStackFrames(new StackFrame[]{frame});");
        w.dedent();
        w.line("} else {");
        w.indent();
        w.line("response.setStackFrames(new StackFrame[0]);");
        w.dedent();
        w.line("}");
        w.line("return CompletableFuture.completedFuture(response);");
        w.dedent();
        w.line("}");
        w.blankLine();
    }

    /** scopes() を出力する。 */
    static void emitScopes(IndentedWriter w) {
        w.line("@Override");
        w.line("public CompletableFuture<ScopesResponse> scopes(ScopesArguments args) {");
        w.indent();
        w.line("ScopesResponse response = new ScopesResponse();");
        w.line("if (hasCurrentStep()) {");
        w.indent();
        w.line("Scope scope = new Scope();");
        w.line("scope.setName(isAstRuntimeMode() ? \"Current AST Node\" : \"Current Token\");");
        w.line("scope.setVariablesReference(1);");
        w.line("scope.setExpensive(false);");
        w.line("response.setScopes(new Scope[]{scope});");
        w.dedent();
        w.line("} else {");
        w.indent();
        w.line("response.setScopes(new Scope[0]);");
        w.dedent();
        w.line("}");
        w.line("return CompletableFuture.completedFuture(response);");
        w.dedent();
        w.line("}");
        w.blankLine();
    }

    /** variables() を出力する。 */
    static void emitVariables(IndentedWriter w) {
        w.line("@Override");
        w.line("public CompletableFuture<VariablesResponse> variables(VariablesArguments args) {");
        w.indent();
        w.line("VariablesResponse response = new VariablesResponse();");
        w.line("if (hasCurrentStep()) {");
        w.indent();
        w.line("Token current = currentStepToken();");
        w.line("if (current == null) {");
        w.indent();
        w.line("response.setVariables(new Variable[0]);");
        w.line("return CompletableFuture.completedFuture(response);");
        w.dedent();
        w.line("}");
        w.line("String text = current.source.sourceAsString().strip();");
        w.line("String parserName = current.getParser().getClass().getSimpleName();");
        w.line("if (isAstRuntimeMode() && !astNodeTypes.isEmpty()) {");
        w.indent();
        w.line("parserName = currentStepLabel();");
        w.line("text = currentAstSourceText();");
        w.dedent();
        w.line("}");
        w.line("Variable var = new Variable();");
        w.line("var.setName(parserName);");
        w.line("var.setValue(\"\\\"\" + text.replace(\"\\\"\", \"\\\\\\\"\") + \"\\\"\");");
        w.line("var.setType(isAstRuntimeMode() ? \"ASTNode\" : \"Token\");");
        w.line("var.setVariablesReference(0);");
        w.line("Variable mode = new Variable();");
        w.line("mode.setName(\"runtimeMode\");");
        w.line("mode.setValue(runtimeMode);");
        w.line("mode.setType(\"String\");");
        w.line("mode.setVariablesReference(0);");
        w.line("Variable astNodes = new Variable();");
        w.line("astNodes.setName(\"astNodeCount\");");
        w.line("astNodes.setValue(String.valueOf(astNodeCount));");
        w.line("astNodes.setType(\"int\");");
        w.line("astNodes.setVariablesReference(0);");
        w.line("Variable astCurrent = new Variable();");
        w.line("astCurrent.setName(\"astCurrentNode\");");
        w.line("String currentAst = astNodeTypes.isEmpty()");
        w.line("    ? \"\"");
        w.line("    : astNodeTypes.get(Math.min(stepIndex, astNodeTypes.size() - 1));");
        w.line("astCurrent.setValue(currentAst);");
        w.line("astCurrent.setType(\"String\");");
        w.line("astCurrent.setVariablesReference(0);");
        w.line("List<Variable> vars = new ArrayList<>();");
        w.line("vars.add(var);");
        w.line("vars.add(mode);");
        w.line("vars.add(astNodes);");
        w.line("vars.add(astCurrent);");
        w.line("for (Map.Entry<String, String> entry : runtimeProbeVariables.entrySet()) {");
        w.indent();
        w.line("if (\"runtimeMode\".equals(entry.getKey())) {");
        w.indent();
        w.line("continue;");
        w.dedent();
        w.line("}");
        w.line("Variable runtimeVar = new Variable();");
        w.line("runtimeVar.setName(entry.getKey());");
        w.line("runtimeVar.setValue(entry.getValue());");
        w.line("runtimeVar.setType(\"String\");");
        w.line("runtimeVar.setVariablesReference(0);");
        w.line("vars.add(runtimeVar);");
        w.dedent();
        w.line("}");
        w.line("java.util.List<Variable> extraVars = additionalVariables();");
        w.line("if (extraVars != null) { vars.addAll(extraVars); }");
        w.line("response.setVariables(vars.toArray(new Variable[0]));");
        w.dedent();
        w.line("} else {");
        w.indent();
        w.line("List<Variable> vars = new ArrayList<>();");
        w.line("for (Map.Entry<String, String> entry : runtimeProbeVariables.entrySet()) {");
        w.indent();
        w.line("Variable runtimeVar = new Variable();");
        w.line("runtimeVar.setName(entry.getKey());");
        w.line("runtimeVar.setValue(entry.getValue());");
        w.line("runtimeVar.setType(\"String\");");
        w.line("runtimeVar.setVariablesReference(0);");
        w.line("vars.add(runtimeVar);");
        w.dedent();
        w.line("}");
        w.line("java.util.List<Variable> extraVars = additionalVariables();");
        w.line("if (extraVars != null) { vars.addAll(extraVars); }");
        w.line("response.setVariables(vars.toArray(new Variable[0]));");
        w.dedent();
        w.line("}");
        w.line("return CompletableFuture.completedFuture(response);");
        w.dedent();
        w.line("}");
        w.blankLine();
    }

    /** disconnect() を出力する。 */
    static void emitDisconnect(IndentedWriter w) {
        w.line("@Override");
        w.line("public CompletableFuture<Void> disconnect(DisconnectArguments args) {");
        w.indent();
        w.line("System.exit(0);");
        w.line("return CompletableFuture.completedFuture(null);");
        w.dedent();
        w.line("}");
        w.blankLine();
    }
}
