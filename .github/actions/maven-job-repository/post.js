// #718: remove this job's Maven repository and its tail view when the job ends, also after a
// failure or a cancellation. Only the directories select.sh created for this run attempt are
// removed; the tail view holds symbolic links, which are removed without following them, so
// the shared ~/.m2, the runner's repository and other jobs' files are never touched.
const { spawnSync } = require('child_process');
const fs = require('fs');
const path = require('path');

function remove(label, directory) {
  if (!directory) {
    console.log(`No job-local Maven ${label} was created; nothing to remove.`);
    return;
  }
  const temp = process.env.RUNNER_TEMP || '';
  const parent = path.join(temp, 'maven-repository');
  const prefix = `${process.env.GITHUB_RUN_ID}-${process.env.GITHUB_RUN_ATTEMPT}-`;
  // Directly below $RUNNER_TEMP/maven-repository (a relative or nested path has another dirname),
  // and named after this run attempt.
  if (!path.isAbsolute(temp) || path.dirname(directory) !== parent
      || !path.basename(directory).startsWith(prefix)) {
    console.log(`::warning::not removing '${directory}': it is not this run attempt's ${label} under ${parent}`);
    return;
  }
  let stat;
  try {
    stat = fs.lstatSync(directory);
  } catch (error) {
    console.log(`Job-local Maven ${label} already gone: ${directory}`);
    return;
  }
  if (!stat.isDirectory()) {
    console.log(`::warning::not removing '${directory}': it is not a directory`);
    return;
  }
  // du does not follow symbolic links, so the tail view reports only its own size.
  const size = spawnSync('du', ['-sh', directory], { encoding: 'utf8' });
  if (size.status === 0) console.log(`Job-local Maven ${label} size: ${size.stdout.trim()}`);
  try {
    // Recursive removal unlinks symbolic links and never descends into their targets.
    fs.rmSync(directory, { recursive: true, force: true });
  } catch (error) {
    console.log(`::warning::could not remove ${directory}: ${error.message}`);
    return;
  }
  console.log(`Removed job-local Maven ${label}: ${directory}`);
}

function main() {
  remove('repository', process.env.STATE_repository || '');
  if (process.env.STATE_tail) remove('tail view', process.env.STATE_tail);
  try {
    fs.rmdirSync(path.join(process.env.RUNNER_TEMP || '', 'maven-repository')); // only succeeds when empty
  } catch (error) {
    // not empty or already removed
  }
  return 0;
}

process.exit(main());
