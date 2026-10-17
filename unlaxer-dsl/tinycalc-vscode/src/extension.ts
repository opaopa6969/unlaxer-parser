import * as path from "path";
import { ChildProcess, spawn } from "node:child_process";
import * as vscode from "vscode";
import { LanguageClient, LanguageClientOptions, ServerOptions, CloseAction, ErrorAction } from "vscode-languageclient/node";

let client: LanguageClient | undefined;
let pending: Promise<void> = Promise.resolve();
let stopping = false;
let ownedProcess: ChildProcess | undefined;
let cancelStarting: (() => void) | undefined;
class StartupCancelled extends Error {}

class OwnedLanguageClient extends LanguageClient {
  override stop(timeout?: number): Promise<void> {
    // languageclient 9 invokes stop without handling its rejection when initialize fails.
    // A starting client cannot shut down over LSP; stopClient terminates our owned process.
    if (!this.isRunning()) return Promise.resolve();
    return super.stop(timeout);
  }
}

async function stopClient(): Promise<void> {
  const previous = client;
  client = undefined;
  try { if (previous) await previous.dispose(1000); }
  catch { /* A starting/failed client cannot complete LSP shutdown; terminate its owned process. */ }
  const child = ownedProcess;
  if (child && child.exitCode === null && child.signalCode === null && child.pid !== undefined) {
    await new Promise<void>((resolve, reject) => {
      const force = setTimeout(() => child.kill("SIGKILL"), 500);
      const deadline = setTimeout(() => { clearTimeout(force); reject(new Error("Server process did not terminate.")); }, 3000);
      child.once("exit", () => { clearTimeout(force); clearTimeout(deadline); resolve(); });
      child.kill("SIGTERM");
    });
  }
  ownedProcess = undefined;
}

type Command = { command: string; args: string[] };

function serverCommand(context: vscode.ExtensionContext, protocol: "lsp" | "dap"): Command {
  const config = vscode.workspace.getConfiguration("tinycalcLsp");
  function text(key: string, fallback: string): string {
    const value = config.get<unknown>(key, fallback);
    if (typeof value !== "string" || !value.trim()) throw new Error(`TinyCalc: ${key} must be a nonempty string.`);
    return value;
  }
  function args(key: string): string[] {
    const value = config.get<unknown>(key, []);
    if (!Array.isArray(value) || value.some(item => typeof item !== "string")) throw new Error(`TinyCalc: ${key} must be an array of strings.`);
    return value;
  }
  const backend = text("server.backend", "java");
  if (backend === "rust") {
    return { command: text(protocol === "lsp" ? "server.rustLspPath" : "server.rustDapPath", ""),
      args: args(protocol === "lsp" ? "server.rustLspArgs" : "server.rustDapArgs") };
  }
  if (backend !== "java") throw new Error(`TinyCalc: server.backend must be java or rust.`);
  const configuredJar = config.get<unknown>("server.jarPath", "");
  if (typeof configuredJar !== "string") throw new Error("TinyCalc: server.jarPath must be a string.");
  const jar = configuredJar.trim() ? configuredJar : context.asAbsolutePath(path.join("server-dist", "tinycalc-lsp-server.jar"));
  const javaArgs = [...args("server.jvmArgs"), "--enable-preview"];
  if (protocol === "lsp") javaArgs.push("-jar", jar);
  else javaArgs.push("-cp", jar, "org.unlaxer.tinycalc.generated.TinyCalcDapLauncher");
  return { command: text("server.javaPath", "java"), args: javaArgs };
}

export async function activate(context: vscode.ExtensionContext): Promise<void> {
  stopping = false;
  const output = vscode.window.createOutputChannel("TinyCalc LSP");
  context.subscriptions.push(output);
  let crashes: number[] = [];
  const restart = (): Promise<void> => {
    cancelStarting?.();
    const operation = pending.then(async () => {
      if (stopping) return;
      // Validate before replacing a working server. Commands and arguments never pass through a shell.
      const command = serverCommand(context, "lsp");
      const timeout = vscode.workspace.getConfiguration("tinycalcLsp").get<unknown>("server.startupTimeoutMs", 30000);
      if (typeof timeout !== "number" || !Number.isInteger(timeout) || timeout < 1000 || timeout > 300000) {
        throw new Error("server.startupTimeoutMs must be an integer from 1000 to 300000.");
      }
      await stopClient();
      if (stopping) return;
      let cancelled = false, ready = false;
      const options: ServerOptions = () => new Promise<ChildProcess>((resolve, reject) => {
        if (cancelled || stopping) { reject(new StartupCancelled()); return; }
        const child = spawn(command.command, command.args, { shell: false, stdio: "pipe", windowsHide: true });
        ownedProcess = child;
        child.once("error", reject);
        child.once("spawn", () => resolve(child));
      });
      const clientOptions: LanguageClientOptions = {
        documentSelector: [{ scheme: "file", language: "tinycalc" }], outputChannel: output,
        errorHandler: {
          error: () => ({ action: ErrorAction.Continue }),
          closed: () => {
            if (ready && !stopping && client === next) {
              const now = Date.now();
              crashes = crashes.filter(time => now - time < 180000);
              crashes.push(now);
              if (crashes.length <= 5) {
                // Finish the client's close transition before entering our serialized restart queue.
                setTimeout(() => { if (!stopping && client === next) void restart().catch(report); }, 0);
              } else report(new Error("Server exited repeatedly. Correct the configuration and restart manually."));
            }
            return { action: CloseAction.DoNotRestart, handled: true };
          }
        }
      };
      const next = new OwnedLanguageClient("tinycalcLanguageServer", "TinyCalc Language Server", options, clientOptions);
      client = next;
      let timer: ReturnType<typeof setTimeout> | undefined;
      const interrupted = new Promise<never>((_, reject) => {
        cancelStarting = () => { cancelled = true; reject(new StartupCancelled()); };
        timer = setTimeout(() => { cancelled = true; reject(new Error(`Server initialization exceeded ${timeout} ms.`)); }, timeout);
      });
      try {
        const started = next.start();
        // Repeated start observes the existing startup promise. Attach its rejection handler
        // now: languageclient 9 otherwise leaves that internal promise unhandled on failure.
        void next.start().catch(() => {});
        await Promise.race([started, interrupted]);
        ready = true;
      } catch (error) {
        await stopClient();
        if (!(error instanceof StartupCancelled)) throw error;
      } finally {
        clearTimeout(timer);
        cancelStarting = undefined;
      }
    });
    // A failed start must not poison subsequent explicit restart attempts.
    pending = operation.catch(() => {});
    return operation;
  };
  const report = (error: unknown): void => {
    const message = error instanceof Error ? error.message : String(error);
    output.appendLine(message);
    void vscode.window.showErrorMessage(`TinyCalc server: ${message}`);
  };
  context.subscriptions.push(vscode.commands.registerCommand("tinycalcLsp.restartServer", restart));
  context.subscriptions.push(vscode.workspace.onDidChangeConfiguration(event => {
    if (event.affectsConfiguration("tinycalcLsp.server")) void restart().catch(report);
  }));
  context.subscriptions.push(vscode.debug.registerDebugAdapterDescriptorFactory("tinycalc", {
    createDebugAdapterDescriptor(): vscode.DebugAdapterExecutable {
      const command = serverCommand(context, "dap");
      return new vscode.DebugAdapterExecutable(command.command, command.args);
    }
  }));
  await restart().catch(report);
}

export async function deactivate(): Promise<void> {
  stopping = true;
  cancelStarting?.();
  await pending;
  await stopClient();
}
