"""Explicit test/build JVM temp option; no launcher notices on stderr."""
import os
from pathlib import Path


def java_command(executable):
    command = [str(executable)]
    temporary = os.environ.get('TMPDIR')
    if temporary:
        prefix = '-J-D' if Path(executable).name in ('javac', 'javac.exe') else '-D'
        command.append(prefix + 'java.io.tmpdir=' + temporary)
    return command
