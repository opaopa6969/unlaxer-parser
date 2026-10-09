import * as vscode from "vscode";
import { mkdir, mkdtemp, readFile } from "node:fs/promises";
import * as path from "node:path";
import { CommandRunner } from "./command-runner";

interface Tools { javaPath: string; jarPath: string; jvmArgs: string[]; output: vscode.OutputChannel; }
const attribute = (value: string) => value.replaceAll("&", "&amp;").replaceAll('"', "&quot;").replaceAll("<", "&lt;").replaceAll(">", "&gt;");

export function playgroundHtml(source: string, cspSource: string, uri: (file: string) => string): string {
  return source.replaceAll("'self'", cspSource)
    .replace('href="help/help.css"', `href="${attribute(uri("help/help.css"))}"`)
    .replace('href="playground.css"', `href="${attribute(uri("playground.css"))}"`)
    .replace('src="playground.js"', `src="${attribute(uri("playground.js"))}"`)
    .replace('href="help/index.html"', 'href="#"')
    .replace("<body>", `<body data-wasm="${attribute(uri("language.wasm"))}" data-worker="${attribute(uri("worker.js"))}" data-grammar="${attribute(uri("grammar.ubnf"))}" data-vocabulary="${attribute(uri("vocabulary.json"))}">`);
}

export function registerPlayground(context: vscode.ExtensionContext, tools: Tools): void {
  const runner = new CommandRunner(); context.subscriptions.push(runner);
  let previous = vscode.window.activeTextEditor?.document;
  let running = false;
  context.subscriptions.push(vscode.window.onDidChangeActiveTextEditor(editor => {
    if (editor?.document.languageId === "ubnf") previous = editor.document;
  }));
  context.subscriptions.push(vscode.commands.registerCommand("ubnfLsp.openPlayground", async () => {
    if (running) { void vscode.window.showInformationMessage("UBNF playground を生成中です。完了までお待ちください。"); return; }
    running = true;
    let stage = "文法の確認";
    try {
      if (!vscode.workspace.isTrusted) throw new Error("生成には信頼したワークスペースが必要です。信頼できる文法だけを開いてください。");
      const active = vscode.window.activeTextEditor?.document;
      const document = active?.languageId === "ubnf" ? active : previous;
      if (!document || document.isClosed || document.languageId !== "ubnf") throw new Error("生成する .ubnf 文書を開いてください。『はじめの一歩』からサンプルを作れます。");
      if (document.isUntitled || document.isDirty) {
        if (!await document.save()) throw new Error("文法を .ubnf ファイルとして保存してから実行してください。");
      }
      // Save As may replace the untitled TextDocument with a new file document.
      const saved = document.isUntitled ? vscode.window.activeTextEditor?.document : document;
      if (!saved || saved.uri.scheme !== "file" || saved.isDirty) throw new Error("ローカルの .ubnf ファイルへ保存してください。");
      const otherDirty = vscode.workspace.textDocuments.find(item => item.uri.scheme === "file" && item.languageId === "ubnf" && item.isDirty && item.uri.toString() !== saved.uri.toString());
      if (otherDirty) throw new Error(`未保存の UBNF ファイルがあります: ${otherDirty.fileName}\nimport も保存済みファイルから読みます。保存してから再実行してください。`);
      const config = vscode.workspace.getConfiguration("ubnfLsp");
      const nodePath = config.get<string>("playground.nodePath", "node");
      const cargoPath = config.get<string>("playground.cargoPath", "cargo");
      await mkdir(context.globalStorageUri.fsPath, {recursive: true});
      const job = await mkdtemp(path.join(context.globalStorageUri.fsPath, "playground-"));
      const project = path.join(job, "project");
      tools.output.appendLine(`[playground] source: ${saved.fileName}\n[playground] output: ${project}`);
      await vscode.window.withProgress({location: vscode.ProgressLocation.Notification, title: "UBNF playground を生成", cancellable: false}, async progress => {
        stage = "UBNF → parser の生成"; progress.report({message: stage});
        await runner.run(tools.javaPath, [...tools.jvmArgs, "--enable-preview", "-cp", tools.jarPath,
          "org.unlaxer.dsl.CodegenMain", "playground", "--grammar", saved.fileName, "--output", project], job, text => tools.output.append(text));
        stage = "Rust parser → WebAssembly のビルド"; progress.report({message: stage});
        await runner.run(nodePath, [path.join(project, "build.mjs")], project, text => tools.output.append(text), 180000,
          {...process.env, UBNF_CARGO: cargoPath});
      });
      stage = "WASM playground の起動";
      const root = vscode.Uri.file(path.join(project, "public"));
      const panel = vscode.window.createWebviewPanel("ubnfPlayground", `UBNF Playground · ${path.basename(saved.fileName)}`,
        vscode.ViewColumn.Beside, {enableScripts: true, localResourceRoots: [root]});
      context.subscriptions.push(panel);
      const html = await readFile(path.join(root.fsPath, "index.html"), "utf8");
      await new Promise<void>((resolve, reject) => {
        let settled = false;
        const finish = (error?: Error) => {
          if (settled) return; settled = true; clearTimeout(timer);
          if (error) reject(error); else resolve();
        };
        const timer = setTimeout(() => finish(new Error("WASM 画面が起動しませんでした。UBNF LSP のログを確認してください。")), 20000);
        panel.onDidDispose(() => finish(new Error("playground の起動前に画面が閉じられました。")), undefined, context.subscriptions);
        panel.webview.onDidReceiveMessage((message: unknown) => {
          if (typeof message !== "object" || message === null) return;
          const {type, error} = message as {type?: unknown; error?: unknown};
          if (type === "ready") { tools.output.appendLine("[playground] generated WASM is ready in the webview"); finish(); }
          else if (type === "error" && typeof error === "string") finish(new Error(error));
          else if (type === "openHelp") void vscode.commands.executeCommand("ubnfLsp.openHelp");
        }, undefined, context.subscriptions);
        panel.webview.html = playgroundHtml(html, panel.webview.cspSource,
          file => panel.webview.asWebviewUri(vscode.Uri.joinPath(root, file)).toString());
      });
      return {projectPath: project};
    } catch (error) {
      const message = `${stage}に失敗: ${String(error)}`;
      tools.output.appendLine(message);
      void vscode.window.showErrorMessage(message.slice(0, 1800), "環境準備・Help", "ログを開く").then(choice => {
        if (choice === "環境準備・Help") void vscode.commands.executeCommand("ubnfLsp.openHelp");
        if (choice === "ログを開く") tools.output.show(true);
      });
      return undefined;
    } finally { running = false; }
  }));
}
