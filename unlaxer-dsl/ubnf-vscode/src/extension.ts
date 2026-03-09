import * as path from "path";
import * as vscode from "vscode";
import { LanguageClient, LanguageClientOptions, ServerOptions } from "vscode-languageclient/node";

let client: LanguageClient | undefined;

function getBundledJarPath(context: vscode.ExtensionContext): string {
  return context.asAbsolutePath(path.join("server-dist", "ubnf-lsp-server.jar"));
}

export async function activate(context: vscode.ExtensionContext): Promise<void> {
  const config: vscode.WorkspaceConfiguration =
    vscode.workspace.getConfiguration("ubnfLsp");

  const javaPath: string  = config.get<string>("server.javaPath", "java");
  const configuredJar: string = config.get<string>("server.jarPath", "");
  const jvmArgs: string[] = config.get<string[]>("server.jvmArgs", []) ?? [];

  const jarPath: string = configuredJar.trim().length > 0
    ? configuredJar
    : getBundledJarPath(context);

  const serverOptions: ServerOptions = {
    command: javaPath,
    args: [...jvmArgs, "--enable-preview", "-jar", jarPath],
    options: {}
  };

  const clientOptions: LanguageClientOptions = {
    documentSelector: [{ scheme: "file", language: "ubnf" }],
    outputChannel: vscode.window.createOutputChannel("UBNF LSP")
  };

  client = new LanguageClient(
    "ubnfLanguageServer",
    "UBNF Language Server",
    serverOptions,
    clientOptions
  );

  client.start();

  context.subscriptions.push({
    dispose: () => {
      void client?.stop();
    }
  });
}

export async function deactivate(): Promise<void> {
  if (client != null) {
    await client.stop();
  }
}
