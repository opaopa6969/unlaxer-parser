import { ChildProcess, spawn } from "node:child_process";

/** Executes only explicit argument arrays; owns every process group it terminates. */
export class CommandRunner {
  private readonly children = new Set<ChildProcess>();
  private disposed = false;

  private terminate(child: ChildProcess): void {
    if (!child.pid || child.exitCode !== null) return;
    try {
      if (process.platform === "win32") {
        spawn("taskkill", ["/pid", String(child.pid), "/T", "/F"], {shell: false, windowsHide: true, stdio: "ignore"});
      } else {
        process.kill(-child.pid, "SIGKILL");
      }
    } catch { /* The task-owned process may have already exited. */ }
  }

  dispose(): void {
    this.disposed = true;
    for (const child of this.children) this.terminate(child);
  }

  run(executable: string, args: string[], cwd: string, append: (text: string) => void,
      timeoutMs = 180000, environment: NodeJS.ProcessEnv = process.env): Promise<void> {
    if (this.disposed) return Promise.reject(new Error("生成処理は終了しています。"));
    return new Promise((resolve, reject) => {
      const child = spawn(executable, args, {cwd, env: environment, shell: false, windowsHide: true,
        detached: process.platform !== "win32", stdio: ["ignore", "pipe", "pipe"]});
      this.children.add(child);
      let tail = "", finished = false;
      const finish = (error?: Error) => {
        if (finished) return; finished = true; clearTimeout(timer); this.children.delete(child);
        if (error) reject(error); else resolve();
      };
      const onData = (data: Buffer) => { const text = data.toString("utf8"); tail = (tail + text).slice(-8000); append(text); };
      child.stdout?.on("data", onData); child.stderr?.on("data", onData);
      const timer = setTimeout(() => {
        this.terminate(child);
        finish(new Error(`生成処理が ${timeoutMs / 1000} 秒を超えたため停止しました。\n${tail}`));
      }, timeoutMs);
      child.once("error", error => finish(new Error(`コマンドを起動できません: ${executable}\n${error.message}`)));
      child.once("close", code => finish(code === 0 ? undefined : new Error(`生成処理が失敗しました (exit ${code})。\n${tail}`)));
    });
  }
}
