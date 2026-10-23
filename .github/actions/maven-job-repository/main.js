// #718: a JavaScript action only so that post.js can remove the repository when the job ends
// (composite actions have no post step). The work itself is select.sh.
const { spawnSync } = require('child_process');
const path = require('path');

const result = spawnSync('bash', [path.join(__dirname, 'select.sh')], { stdio: 'inherit' });
if (result.error) {
  console.log(`::error::cannot run select.sh: ${result.error.message}`);
  process.exit(1);
}
process.exit(result.status === null ? 1 : result.status);
