import * as path from "path";
import * as vscode from "vscode";
import { LanguageClient, LanguageClientOptions, ServerOptions } from "vscode-languageclient/node";

let client: LanguageClient | undefined;

function getBundledJarPath(context: vscode.ExtensionContext): string {
  return context.asAbsolutePath(path.join("server-dist", "tinycalc-lsp-server.jar"));
}

export async function activate(context: vscode.ExtensionContext): Promise<void> {
  const config: vscode.WorkspaceConfiguration =
    vscode.workspace.getConfiguration("tinycalcLsp");

  const javaPath: string  = config.get<string>("server.javaPath", "java");
  const configuredJar: string = config.get<string>("server.jarPath", "");
  const jvmArgs: string[] = config.get<string[]>("server.jvmArgs", []) ?? [];

  const jarPath: string = configuredJar.trim().length > 0
    ? configuredJar
    : getBundledJarPath(context);

  // --- LSP server ---
  const serverOptions: ServerOptions = {
    command: javaPath,
    args: [...jvmArgs, "--enable-preview", "-jar", jarPath],
    options: {}
  };

  const clientOptions: LanguageClientOptions = {
    documentSelector: [{ scheme: "file", language: "tinycalc" }],
    outputChannel: vscode.window.createOutputChannel("TinyCalc LSP")
  };

  client = new LanguageClient(
    "tinycalcLanguageServer",
    "TinyCalc Language Server",
    serverOptions,
    clientOptions
  );

  client.start();

  context.subscriptions.push({
    dispose: () => {
      void client?.stop();
    }
  });

  // --- DAP adapter ---
  // Uses -cp (not -jar) so we can specify TinyCalcDapLauncher as the main class
  // while reusing the same fat jar that contains all classes.
  const dapFactory: vscode.DebugAdapterDescriptorFactory = {
    createDebugAdapterDescriptor(
      _session: vscode.DebugSession
    ): vscode.ProviderResult<vscode.DebugAdapterDescriptor> {
      return new vscode.DebugAdapterExecutable(
        javaPath,
        [
          ...jvmArgs,
          "--enable-preview",
          "-cp", jarPath,
          "org.unlaxer.tinycalc.generated.TinyCalcDapLauncher"
        ]
      );
    }
  };

  context.subscriptions.push(
    vscode.debug.registerDebugAdapterDescriptorFactory("tinycalc", dapFactory)
  );
}

export async function deactivate(): Promise<void> {
  if (client != null) {
    await client.stop();
  }
}
