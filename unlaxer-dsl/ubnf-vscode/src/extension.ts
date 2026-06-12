import * as path from "path";
import * as vscode from "vscode";
import { LanguageClient, LanguageClientOptions, ServerOptions } from "vscode-languageclient/node";

let client: LanguageClient | undefined;
let outputChannel: vscode.OutputChannel | undefined;

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

  outputChannel = vscode.window.createOutputChannel("UBNF LSP");
  outputChannel.appendLine(`[ubnf-lsp] java: ${javaPath}`);
  outputChannel.appendLine(`[ubnf-lsp] jar:  ${jarPath}`);

  const serverOptions: ServerOptions = {
    command: javaPath,
    args: [...jvmArgs, "--enable-preview", "-jar", jarPath],
    options: {}
  };

  const clientOptions: LanguageClientOptions = {
    documentSelector: [{ scheme: "file", language: "ubnf" }],
    outputChannel
  };

  client = new LanguageClient(
    "ubnfLanguageServer",
    "UBNF Language Server",
    serverOptions,
    clientOptions
  );

  context.subscriptions.push(
    vscode.commands.registerCommand("ubnfLsp.showServerOutput", () => {
      outputChannel?.show(true);
    })
  );

  client.start().then(
    () => outputChannel?.appendLine("[ubnf-lsp] language server started"),
    (error: unknown) => {
      outputChannel?.appendLine(`[ubnf-lsp] failed to start: ${String(error)}`);
      void vscode.window.showErrorMessage(
        "UBNF language server failed to start. Java 21+ is required. " +
        "See output channel 'UBNF LSP' for details."
      );
    }
  );

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
