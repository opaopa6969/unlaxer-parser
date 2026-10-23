// #718: remove this job's Maven repository when the job ends, also after a failure or a
// cancellation. Only the directory select.sh created for this run attempt is removed;
// the shared ~/.m2 and other jobs' files are never touched.
const { spawnSync } = require('child_process');
const fs = require('fs');
const path = require('path');

function main() {
  const repository = process.env.STATE_repository || '';
  if (!repository) {
    console.log('No job-local Maven repository was created; nothing to remove.');
    return 0;
  }
  const temp = process.env.RUNNER_TEMP || '';
  const parent = path.join(temp, 'maven-repository');
  const prefix = `${process.env.GITHUB_RUN_ID}-${process.env.GITHUB_RUN_ATTEMPT}-`;
  // Directly below $RUNNER_TEMP/maven-repository (a relative or nested path has another dirname),
  // and named after this run attempt.
  if (!path.isAbsolute(temp) || path.dirname(repository) !== parent
      || !path.basename(repository).startsWith(prefix)) {
    console.log(`::warning::not removing '${repository}': it is not this run attempt's repository under ${parent}`);
    return 0;
  }
  if (!fs.existsSync(repository)) {
    console.log(`Job-local Maven repository already gone: ${repository}`);
    return 0;
  }
  const size = spawnSync('du', ['-sh', repository], { encoding: 'utf8' });
  if (size.status === 0) console.log(`Job-local Maven repository size: ${size.stdout.trim()}`);
  try {
    fs.rmSync(repository, { recursive: true, force: true });
  } catch (error) {
    console.log(`::warning::could not remove ${repository}: ${error.message}`);
    return 0;
  }
  try {
    fs.rmdirSync(parent); // only succeeds when no other repository is left there
  } catch (error) {
    // not empty or already removed
  }
  console.log(`Removed job-local Maven repository: ${repository}`);
  return 0;
}

process.exit(main());
