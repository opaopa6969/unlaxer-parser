package org.unlaxer.dsl.codegen;

/**
 * DAP プロトコルメソッド（initialize, launch, configurationDone, setBreakpoints,
 * next, continue_, threads, stackTrace, scopes, variables, disconnect）の
 * コード生成を担当する。
 */
class DAPProtocolEmitter {

    private DAPProtocolEmitter() {}

    /** フィールド宣言と connect() を出力する。 */
    static void emitFieldsAndConnect(StringBuilder sb) {
        // fields
        sb.append("    protected IDebugProtocolClient client;\n");
        sb.append("    protected String pendingProgram;\n");
        sb.append("    protected String runtimeMode = \"token\";\n");
        sb.append("    protected boolean stopOnEntry;\n");
        sb.append("    protected String sourceContent = \"\";\n");
        sb.append("    protected List<Token> stepPoints = new ArrayList<>();\n");
        sb.append("    protected int stepIndex = 0;\n");
        sb.append("    private int astNodeCount = 0;\n");
        sb.append("    private List<String> astNodeTypes = new ArrayList<>();\n");
        sb.append("    private List<int[]> astNodeSpans = new ArrayList<>();\n");
        sb.append("    private Map<String, String> runtimeProbeVariables = new java.util.LinkedHashMap<>();\n");
        sb.append("    private Set<Integer> breakpointLines = new HashSet<>();\n\n");

        // connect()
        sb.append("    public void connect(IDebugProtocolClient client) {\n");
        sb.append("        this.client = client;\n");
        sb.append("    }\n\n");
    }

    /** initialize() を出力する。 */
    static void emitInitialize(StringBuilder sb) {
        sb.append("    @Override\n");
        sb.append("    public CompletableFuture<Capabilities> initialize(InitializeRequestArguments args) {\n");
        sb.append("        Capabilities cap = new Capabilities();\n");
        sb.append("        cap.setSupportsConfigurationDoneRequest(true);\n");
        sb.append("        return CompletableFuture.completedFuture(cap);\n");
        sb.append("    }\n\n");
    }

    /** launch() を出力する。 */
    static void emitLaunch(StringBuilder sb) {
        sb.append("    @Override\n");
        sb.append("    public CompletableFuture<Void> launch(Map<String, Object> args) {\n");
        sb.append("        pendingProgram = (String) args.getOrDefault(\"program\", \"\");\n");
        sb.append("        runtimeMode = String.valueOf(args.getOrDefault(\"runtimeMode\", \"token\"));\n");
        sb.append("        stopOnEntry = Boolean.TRUE.equals(args.get(\"stopOnEntry\"));\n");
        sb.append("        client.initialized();\n");
        sb.append("        return CompletableFuture.completedFuture(null);\n");
        sb.append("    }\n\n");
    }

    /** configurationDone() を出力する。 */
    static void emitConfigurationDone(StringBuilder sb) {
        sb.append("    @Override\n");
        sb.append("    public CompletableFuture<Void> configurationDone(ConfigurationDoneArguments args) {\n");
        sb.append("        if (!parseAndCollectSteps()) {\n");
        sb.append("            return CompletableFuture.completedFuture(null); // error already sent\n");
        sb.append("        }\n");
        sb.append("        if (stopOnEntry && !stepPoints.isEmpty()) {\n");
        sb.append("            StoppedEventArguments stopped = new StoppedEventArguments();\n");
        sb.append("            stopped.setReason(\"entry\");\n");
        sb.append("            stopped.setThreadId(1);\n");
        sb.append("            stopped.setAllThreadsStopped(true);\n");
        sb.append("            client.stopped(stopped);\n");
        sb.append("        } else if (!breakpointLines.isEmpty()) {\n");
        sb.append("            // run to first breakpoint\n");
        sb.append("            int bp = findBreakpointIndex(-1);\n");
        sb.append("            if (bp >= 0) {\n");
        sb.append("                stepIndex = bp;\n");
        sb.append("                StoppedEventArguments stopped = new StoppedEventArguments();\n");
        sb.append("                stopped.setReason(\"breakpoint\");\n");
        sb.append("                stopped.setThreadId(1);\n");
        sb.append("                stopped.setAllThreadsStopped(true);\n");
        sb.append("                client.stopped(stopped);\n");
        sb.append("            } else {\n");
        sb.append("                sendOutput(\"stdout\", \"Parsed successfully: \" + pendingProgram + \"\\n\");\n");
        sb.append("                sendTerminated();\n");
        sb.append("            }\n");
        sb.append("        } else {\n");
        sb.append("            sendOutput(\"stdout\", \"Parsed successfully: \" + pendingProgram + \"\\n\");\n");
        sb.append("            sendTerminated();\n");
        sb.append("        }\n");
        sb.append("        return CompletableFuture.completedFuture(null);\n");
        sb.append("    }\n\n");
    }

    /** setBreakpoints() を出力する。 */
    static void emitSetBreakpoints(StringBuilder sb) {
        sb.append("    @Override\n");
        sb.append("    public CompletableFuture<SetBreakpointsResponse> setBreakpoints(SetBreakpointsArguments args) {\n");
        sb.append("        breakpointLines.clear();\n");
        sb.append("        SetBreakpointsResponse response = new SetBreakpointsResponse();\n");
        sb.append("        SourceBreakpoint[] requested = args.getBreakpoints();\n");
        sb.append("        int count = requested == null ? 0 : requested.length;\n");
        sb.append("        Breakpoint[] breakpoints = new Breakpoint[count];\n");
        sb.append("        for (int i = 0; i < count; i++) {\n");
        sb.append("            int line = requested[i].getLine();\n");
        sb.append("            breakpointLines.add(line);\n");
        sb.append("            Breakpoint bp = new Breakpoint();\n");
        sb.append("            bp.setVerified(true);\n");
        sb.append("            bp.setLine(line);\n");
        sb.append("            breakpoints[i] = bp;\n");
        sb.append("        }\n");
        sb.append("        response.setBreakpoints(breakpoints);\n");
        sb.append("        return CompletableFuture.completedFuture(response);\n");
        sb.append("    }\n\n");
    }

    /** next() を出力する。 */
    static void emitNext(StringBuilder sb) {
        sb.append("    @Override\n");
        sb.append("    public CompletableFuture<Void> next(NextArguments args) {\n");
        sb.append("        onBeforeStep(stepIndex + 1, currentStepLabel());\n");
        sb.append("        stepIndex++;\n");
        sb.append("        onAfterStep(stepIndex, currentStepLabel());\n");
        sb.append("        if (stepIndex >= stepLimit()) {\n");
        sb.append("            sendOutput(\"stdout\", \"Completed: \" + pendingProgram + \"\\n\");\n");
        sb.append("            sendTerminated();\n");
        sb.append("        } else {\n");
        sb.append("            StoppedEventArguments stopped = new StoppedEventArguments();\n");
        sb.append("            stopped.setReason(\"step\");\n");
        sb.append("            stopped.setThreadId(1);\n");
        sb.append("            stopped.setAllThreadsStopped(true);\n");
        sb.append("            client.stopped(stopped);\n");
        sb.append("        }\n");
        sb.append("        return CompletableFuture.completedFuture(null);\n");
        sb.append("    }\n\n");
    }

    /** continue_() を出力する。 */
    static void emitContinue(StringBuilder sb) {
        sb.append("    @Override\n");
        sb.append("    public CompletableFuture<ContinueResponse> continue_(ContinueArguments args) {\n");
        sb.append("        ContinueResponse response = new ContinueResponse();\n");
        sb.append("        response.setAllThreadsContinued(true);\n");
        sb.append("        int bp = findBreakpointIndex(stepIndex);\n");
        sb.append("        if (bp >= 0) {\n");
        sb.append("            stepIndex = bp;\n");
        sb.append("            StoppedEventArguments stopped = new StoppedEventArguments();\n");
        sb.append("            stopped.setReason(\"breakpoint\");\n");
        sb.append("            stopped.setThreadId(1);\n");
        sb.append("            stopped.setAllThreadsStopped(true);\n");
        sb.append("            client.stopped(stopped);\n");
        sb.append("        } else {\n");
        sb.append("            sendOutput(\"stdout\", \"Completed: \" + pendingProgram + \"\\n\");\n");
        sb.append("            sendTerminated();\n");
        sb.append("        }\n");
        sb.append("        return CompletableFuture.completedFuture(response);\n");
        sb.append("    }\n\n");
    }

    /** threads() を出力する。 */
    static void emitThreads(StringBuilder sb) {
        sb.append("    @Override\n");
        sb.append("    public CompletableFuture<ThreadsResponse> threads() {\n");
        sb.append("        ThreadsResponse response = new ThreadsResponse();\n");
        sb.append("        org.eclipse.lsp4j.debug.Thread thread = new org.eclipse.lsp4j.debug.Thread();\n");
        sb.append("        thread.setId(1);\n");
        sb.append("        thread.setName(\"main\");\n");
        sb.append("        response.setThreads(new org.eclipse.lsp4j.debug.Thread[]{thread});\n");
        sb.append("        return CompletableFuture.completedFuture(response);\n");
        sb.append("    }\n\n");
    }

    /** stackTrace() を出力する。 */
    static void emitStackTrace(StringBuilder sb) {
        sb.append("    @Override\n");
        sb.append("    public CompletableFuture<StackTraceResponse> stackTrace(StackTraceArguments args) {\n");
        sb.append("        StackTraceResponse response = new StackTraceResponse();\n");
        sb.append("        if (hasCurrentStep()) {\n");
        sb.append("            Token current = currentStepToken();\n");
        sb.append("            if (current == null) {\n");
        sb.append("                response.setStackFrames(new StackFrame[0]);\n");
        sb.append("                return CompletableFuture.completedFuture(response);\n");
        sb.append("            }\n");
        sb.append("            int charOffset = current.source.offsetFromRoot().value();\n");
        sb.append("            if (isAstRuntimeMode()) {\n");
        sb.append("                int[] span = currentAstSpan();\n");
        sb.append("                if (span != null) {\n");
        sb.append("                    charOffset = span[0];\n");
        sb.append("                }\n");
        sb.append("            }\n");
        sb.append("            int line = 0, col = 0;\n");
        sb.append("            for (int i = 0; i < charOffset && i < sourceContent.length(); i++) {\n");
        sb.append("                if (sourceContent.charAt(i) == '\\n') { line++; col = 0; }\n");
        sb.append("                else { col++; }\n");
        sb.append("            }\n");
        sb.append("            StackFrame frame = new StackFrame();\n");
        sb.append("            frame.setId(0);\n");
        sb.append("            frame.setName(currentStepLabel() + \" (\" + (stepIndex + 1) + \"/\" + stepLimit() + \")\");\n");
        sb.append("            frame.setLine(line + 1);   // DAP は 1-based\n");
        sb.append("            frame.setColumn(col + 1);\n");
        sb.append("            Source source = new Source();\n");
        sb.append("            source.setPath(pendingProgram);\n");
        sb.append("            source.setName(Path.of(pendingProgram).getFileName().toString());\n");
        sb.append("            frame.setSource(source);\n");
        sb.append("            response.setStackFrames(new StackFrame[]{frame});\n");
        sb.append("        } else if (pendingProgram != null && !pendingProgram.isEmpty()) {\n");
        sb.append("            StackFrame frame = new StackFrame();\n");
        sb.append("            frame.setId(0);\n");
        sb.append("            frame.setName(\"<program>\");\n");
        sb.append("            frame.setLine(1);\n");
        sb.append("            frame.setColumn(0);\n");
        sb.append("            Source source = new Source();\n");
        sb.append("            source.setPath(pendingProgram);\n");
        sb.append("            frame.setSource(source);\n");
        sb.append("            response.setStackFrames(new StackFrame[]{frame});\n");
        sb.append("        } else {\n");
        sb.append("            response.setStackFrames(new StackFrame[0]);\n");
        sb.append("        }\n");
        sb.append("        return CompletableFuture.completedFuture(response);\n");
        sb.append("    }\n\n");
    }

    /** scopes() を出力する。 */
    static void emitScopes(StringBuilder sb) {
        sb.append("    @Override\n");
        sb.append("    public CompletableFuture<ScopesResponse> scopes(ScopesArguments args) {\n");
        sb.append("        ScopesResponse response = new ScopesResponse();\n");
        sb.append("        if (hasCurrentStep()) {\n");
        sb.append("            Scope scope = new Scope();\n");
        sb.append("            scope.setName(isAstRuntimeMode() ? \"Current AST Node\" : \"Current Token\");\n");
        sb.append("            scope.setVariablesReference(1);\n");
        sb.append("            scope.setExpensive(false);\n");
        sb.append("            response.setScopes(new Scope[]{scope});\n");
        sb.append("        } else {\n");
        sb.append("            response.setScopes(new Scope[0]);\n");
        sb.append("        }\n");
        sb.append("        return CompletableFuture.completedFuture(response);\n");
        sb.append("    }\n\n");
    }

    /** variables() を出力する。 */
    static void emitVariables(StringBuilder sb) {
        sb.append("    @Override\n");
        sb.append("    public CompletableFuture<VariablesResponse> variables(VariablesArguments args) {\n");
        sb.append("        VariablesResponse response = new VariablesResponse();\n");
        sb.append("        if (hasCurrentStep()) {\n");
        sb.append("            Token current = currentStepToken();\n");
        sb.append("            if (current == null) {\n");
        sb.append("                response.setVariables(new Variable[0]);\n");
        sb.append("                return CompletableFuture.completedFuture(response);\n");
        sb.append("            }\n");
        sb.append("            String text = current.source.sourceAsString().strip();\n");
        sb.append("            String parserName = current.getParser().getClass().getSimpleName();\n");
        sb.append("            if (isAstRuntimeMode() && !astNodeTypes.isEmpty()) {\n");
        sb.append("                parserName = currentStepLabel();\n");
        sb.append("                text = currentAstSourceText();\n");
        sb.append("            }\n");
        sb.append("            Variable var = new Variable();\n");
        sb.append("            var.setName(parserName);\n");
        sb.append("            var.setValue(\"\\\"\" + text.replace(\"\\\"\", \"\\\\\\\"\") + \"\\\"\");\n");
        sb.append("            var.setType(isAstRuntimeMode() ? \"ASTNode\" : \"Token\");\n");
        sb.append("            var.setVariablesReference(0);\n");
        sb.append("            Variable mode = new Variable();\n");
        sb.append("            mode.setName(\"runtimeMode\");\n");
        sb.append("            mode.setValue(runtimeMode);\n");
        sb.append("            mode.setType(\"String\");\n");
        sb.append("            mode.setVariablesReference(0);\n");
        sb.append("            Variable astNodes = new Variable();\n");
        sb.append("            astNodes.setName(\"astNodeCount\");\n");
        sb.append("            astNodes.setValue(String.valueOf(astNodeCount));\n");
        sb.append("            astNodes.setType(\"int\");\n");
        sb.append("            astNodes.setVariablesReference(0);\n");
        sb.append("            Variable astCurrent = new Variable();\n");
        sb.append("            astCurrent.setName(\"astCurrentNode\");\n");
        sb.append("            String currentAst = astNodeTypes.isEmpty()\n");
        sb.append("                ? \"\"\n");
        sb.append("                : astNodeTypes.get(Math.min(stepIndex, astNodeTypes.size() - 1));\n");
        sb.append("            astCurrent.setValue(currentAst);\n");
        sb.append("            astCurrent.setType(\"String\");\n");
        sb.append("            astCurrent.setVariablesReference(0);\n");
        sb.append("            List<Variable> vars = new ArrayList<>();\n");
        sb.append("            vars.add(var);\n");
        sb.append("            vars.add(mode);\n");
        sb.append("            vars.add(astNodes);\n");
        sb.append("            vars.add(astCurrent);\n");
        sb.append("            for (Map.Entry<String, String> entry : runtimeProbeVariables.entrySet()) {\n");
        sb.append("                if (\"runtimeMode\".equals(entry.getKey())) {\n");
        sb.append("                    continue;\n");
        sb.append("                }\n");
        sb.append("                Variable runtimeVar = new Variable();\n");
        sb.append("                runtimeVar.setName(entry.getKey());\n");
        sb.append("                runtimeVar.setValue(entry.getValue());\n");
        sb.append("                runtimeVar.setType(\"String\");\n");
        sb.append("                runtimeVar.setVariablesReference(0);\n");
        sb.append("                vars.add(runtimeVar);\n");
        sb.append("            }\n");
        sb.append("            java.util.List<Variable> extraVars = additionalVariables();\n");
        sb.append("            if (extraVars != null) { vars.addAll(extraVars); }\n");
        sb.append("            response.setVariables(vars.toArray(new Variable[0]));\n");
        sb.append("        } else {\n");
        sb.append("            List<Variable> vars = new ArrayList<>();\n");
        sb.append("            for (Map.Entry<String, String> entry : runtimeProbeVariables.entrySet()) {\n");
        sb.append("                Variable runtimeVar = new Variable();\n");
        sb.append("                runtimeVar.setName(entry.getKey());\n");
        sb.append("                runtimeVar.setValue(entry.getValue());\n");
        sb.append("                runtimeVar.setType(\"String\");\n");
        sb.append("                runtimeVar.setVariablesReference(0);\n");
        sb.append("                vars.add(runtimeVar);\n");
        sb.append("            }\n");
        sb.append("            java.util.List<Variable> extraVars = additionalVariables();\n");
        sb.append("            if (extraVars != null) { vars.addAll(extraVars); }\n");
        sb.append("            response.setVariables(vars.toArray(new Variable[0]));\n");
        sb.append("        }\n");
        sb.append("        return CompletableFuture.completedFuture(response);\n");
        sb.append("    }\n\n");
    }

    /** disconnect() を出力する。 */
    static void emitDisconnect(StringBuilder sb) {
        sb.append("    @Override\n");
        sb.append("    public CompletableFuture<Void> disconnect(DisconnectArguments args) {\n");
        sb.append("        System.exit(0);\n");
        sb.append("        return CompletableFuture.completedFuture(null);\n");
        sb.append("    }\n\n");
    }
}
