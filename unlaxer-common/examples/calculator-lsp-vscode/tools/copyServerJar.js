const fs = require("fs");
const path = require("path");

function fail(message) {
  process.stderr.write(message + "\n");
  process.exit(1);
}

const jarFrom = path.join(__dirname, "..", "server", "target", "calculator-lsp-server.jar");
const jarTo = path.join(__dirname, "..", "server-dist", "calculator-lsp-server.jar");

if (!fs.existsSync(jarFrom)) {
  fail(`Server jar not found: ${jarFrom}\nRun: npm run build:server`);
}

fs.mkdirSync(path.dirname(jarTo), { recursive: true });
fs.copyFileSync(jarFrom, jarTo);
process.stdout.write(`Copied server jar to: ${jarTo}\n`);
