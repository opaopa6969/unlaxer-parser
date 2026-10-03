import * as vscode from "vscode";
import { readFile } from "node:fs/promises";

/** Bundled content only: never render workspace text as HTML or execute a command from the webview. */
export function registerHelp(context: vscode.ExtensionContext): void {
  let panel: vscode.WebviewPanel | undefined;
  context.subscriptions.push(vscode.commands.registerCommand("ubnfLsp.openHelp", async () => {
    if (panel) { panel.reveal(); return; }
    const root = vscode.Uri.joinPath(context.extensionUri, "help-dist");
    const created = vscode.window.createWebviewPanel("ubnfHelp", "UBNF · はじめの一歩", vscode.ViewColumn.Beside, {
      enableScripts: true, localResourceRoots: [root]
    });
    panel = created;
    context.subscriptions.push(created);
    created.onDidDispose(() => { panel = undefined; });
    try {
      const html = await readFile(vscode.Uri.joinPath(root, "index.html").fsPath, "utf8");
      const uri = (file: string) => created.webview.asWebviewUri(vscode.Uri.joinPath(root, file)).toString();
      const catalog = JSON.parse(await readFile(vscode.Uri.joinPath(root, "catalog.json").fsPath, "utf8")) as {
        examples: { id: string; source: string }[];
      };
      created.webview.html = html
        .replaceAll("'self'", created.webview.cspSource)
        .replace('href="help.css"', `href="${uri("help.css")}"`)
        .replace('src="help.js"', `src="${uri("help.js")}"`)
        .replace("<body>", `<body data-catalog="${uri("catalog.json")}">`);
      created.webview.onDidReceiveMessage(async (message: unknown) => {
        if (typeof message !== "object" || message === null) return;
        const { type, id } = message as {type?: unknown; id?: unknown};
        if (type !== "openExample" || typeof id !== "string") return;
        const example = catalog.examples.find(item => item.id === id);
        if (!example) return;
        const document = await vscode.workspace.openTextDocument({language: "ubnf", content: example.source});
        await vscode.window.showTextDocument(document, vscode.ViewColumn.One);
      }, undefined, context.subscriptions);
    } catch (error) {
      created.dispose();
      void vscode.window.showErrorMessage(`UBNF catalog を開けませんでした: ${String(error)}`);
    }
  }));
}
