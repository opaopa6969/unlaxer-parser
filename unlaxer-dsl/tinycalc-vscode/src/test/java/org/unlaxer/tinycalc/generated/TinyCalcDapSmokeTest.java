package org.unlaxer.tinycalc.generated;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.eclipse.lsp4j.debug.ConfigurationDoneArguments;
import org.eclipse.lsp4j.debug.StackTraceArguments;
import org.eclipse.lsp4j.debug.Variable;
import org.eclipse.lsp4j.debug.VariablesArguments;
import org.eclipse.lsp4j.debug.services.IDebugProtocolClient;
import org.junit.Test;

public class TinyCalcDapSmokeTest {

  @Test
  public void generatedAdapterStopsAndExposesAstState() {
    List<String> events = new ArrayList<>();
    IDebugProtocolClient client = (IDebugProtocolClient) Proxy.newProxyInstance(
        IDebugProtocolClient.class.getClassLoader(),
        new Class<?>[] {IDebugProtocolClient.class},
        (proxy, method, args) -> {
          if (method.getDeclaringClass() == Object.class) {
            return null;
          }
          events.add(method.getName());
          return null;
        });

    TinyCalcDebugAdapter adapter = new TinyCalcDebugAdapter() {};
    adapter.connect(client);
    Path program = Path.of("examples", "basic.tcalc").toAbsolutePath();
    adapter.launch(Map.of(
        "program", program.toString(),
        "stopOnEntry", true,
        "steppingMode", "ast")).join();
    adapter.configurationDone(new ConfigurationDoneArguments()).join();

    assertTrue(events.contains("initialized"));
    assertTrue(events.contains("stopped"));

    StackTraceArguments stackArgs = new StackTraceArguments();
    stackArgs.setThreadId(1);
    var stack = adapter.stackTrace(stackArgs).join();
    assertEquals(1, stack.getStackFrames().length);
    assertEquals(program.toString(), stack.getStackFrames()[0].getSource().getPath());

    VariablesArguments variablesArgs = new VariablesArguments();
    variablesArgs.setVariablesReference(1);
    Variable[] variables = adapter.variables(variablesArgs).join().getVariables();
    List<String> names = Arrays.stream(variables).map(Variable::getName).toList();
    assertTrue(names.contains("runtimeMode"));
    assertTrue(names.contains("steppingMode"));
    assertTrue(names.contains("astNodeCount"));
    assertTrue(names.contains("astCurrentNode"));
  }
}
