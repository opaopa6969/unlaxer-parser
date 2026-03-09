import * as path from "path";
import * as vscode from "vscode";
import { LanguageClient, LanguageClientOptions, ServerOptions } from "vscode-languageclient/node";

let client: LanguageClient | undefined;

function getBundledJarPath(context: vscode.ExtensionContext): string {
  // Jar will be copied here by `npm run build:server`
  return context.asAbsolutePath(path.join("server-dist", "calculator-lsp-server.jar"));
}

export async function activate(context: vscode.ExtensionContext): Promise<void> {
  const config: vscode.WorkspaceConfiguration = vscode.workspace.getConfiguration("calculatorLsp");

  const javaPath: string = config.get<string>("server.javaPath", "java");
  const configuredJarPath: string = config.get<string>("server.jarPath", "");
  const jvmArgs: string[] = config.get<string[]>("server.jvmArgs", []) ?? [];

  const jarPath: string = configuredJarPath.trim().length > 0
    ? configuredJarPath
    : getBundledJarPath(context);

  // Start LSP server via stdio:
  //   java [jvmArgs...] -jar <jarPath>
  const serverOptions: ServerOptions = {
    command: javaPath,
    args: [...jvmArgs, "-jar", jarPath],
    options: {}
  };

  const clientOptions: LanguageClientOptions = {
    documentSelector: [{ scheme: "file", language: "calculator" }],
    outputChannel: vscode.window.createOutputChannel("Calculator LSP"),
    // If you want per-workspace settings, you can add initializationOptions here.
  };

  client = new LanguageClient(
    "calculatorLanguageServer",
    "Calculator Language Server",
    serverOptions,
    clientOptions
  );

  client.start();
  context.subscriptions.push({ dispose: () => client?.stop() });

  context.subscriptions.push(
    vscode.commands.registerCommand("calculatorLsp.showServerOutput", async () => {
      clientOptions.outputChannel?.show(true);
    })
  );
}

export async function deactivate(): Promise<void> {
  if (client != null) {
    await client.stop();
  }
}
