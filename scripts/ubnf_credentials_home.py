"""Child-only Linux passwd isolation for HTTPS credentials fixtures (#958)."""
from contextlib import contextmanager
import json
import os
from pathlib import Path
import pwd
import queue
import subprocess
import sys
import threading

ROOT = Path(__file__).resolve().parents[1]


@contextmanager
def batch_cli(command, env, cwd, diagnostic):
    """Use CodegenBatchHost only within one isolated passwd/HOME/cwd environment."""
    process = subprocess.Popen(command, env=env, cwd=cwd, stdin=subprocess.PIPE,
                               stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    lines = queue.Queue()

    def read_lines():
        for line in process.stdout:
            lines.put(line)
        lines.put(None)

    reader = threading.Thread(target=read_lines, daemon=True)
    stray = []
    drain = threading.Thread(target=lambda: stray.append(process.stderr.read()), daemon=True)
    reader.start()
    drain.start()

    def call(options):
        process.stdin.write((json.dumps(options) + '\n').encode())
        process.stdin.flush()
        try:
            line = lines.get(timeout=30)
        except queue.Empty:
            raise AssertionError('credentials batch call timed out') from None
        assert line is not None, 'credentials batch host stopped'
        answer = json.loads(line)
        return diagnostic(answer['stdout'].encode(), answer['stderr'].encode(), answer['exit'])

    try:
        yield call
    finally:
        process.stdin.close()
        try:
            code = process.wait(timeout=30)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=5)
            raise AssertionError('credentials batch host did not stop') from None
        reader.join(timeout=5)
        drain.join(timeout=5)
        diagnostic(b'', b''.join(stray), code)
        assert code == 0 and not b''.join(stray), 'credentials batch host failed'
        process.stdout.close()
        process.stderr.close()


def compile_passwd_shim(directory):
    assert sys.platform == 'linux', 'credentials home fixtures require Linux LD_PRELOAD'
    library = directory / 'passwd-home.so'
    result = subprocess.run(['cc', '-shared', '-fPIC', '-Wall', '-Wextra', '-Werror',
                             '-o', str(library), str(ROOT / 'scripts/fixtures/credentials-passwd.c')],
                            capture_output=True, timeout=30)
    assert result.returncode == 0, 'test passwd shim compilation failed'
    return library


def isolated_env(library, home, passwd_home):
    env = dict(os.environ)
    for key in ['HOME', 'UBNF_CREDENTIALS_FILE', 'JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS',
                'JDK_JAVA_OPTIONS', 'UBNF_TEST_PASSWD_RETRY']:
        env.pop(key, None)
    if home is not None:
        env['HOME'] = str(home)
    env.update(LD_PRELOAD=str(library), UBNF_TEST_PASSWD_HOME=str(passwd_home))
    return env


def metadata(path):
    # Do not open, hash or parse real credentials. Inode/size/mtime/ctime detect
    # content or existence changes without reading any secret bytes (also symlinks).
    try:
        value = path.stat()
        target = (value.st_dev, value.st_ino, value.st_size, value.st_mtime_ns, value.st_ctime_ns)
    except FileNotFoundError:
        target = None
    try:
        value = path.lstat()
        link = (value.st_dev, value.st_ino, value.st_size, value.st_mtime_ns, value.st_ctime_ns)
    except FileNotFoundError:
        link = None
    return link, target


@contextmanager
def protect_real_credentials():
    home = Path(pwd.getpwuid(os.getuid()).pw_dir)
    path = home / '.config/unlaxer/credentials.json'
    before = metadata(path)
    try:
        yield
    finally:
        assert metadata(path) == before, 'real credentials metadata changed during fixture execution'
