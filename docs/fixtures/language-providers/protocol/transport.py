import sys
import time
from pathlib import Path

mode = sys.argv[1]
if mode == 'marker':
    Path(sys.argv[2]).write_text('executed')
    sys.exit(0)

# 子プロセスの起動後、入力を読み始めるまでの遅延。
time.sleep(int(sys.argv[2]) / 1000)
sys.stdin.buffer.read()
time.sleep(int(sys.argv[3]) / 1000)
if mode == 'nonzero':
    sys.exit(42)
if mode == 'timeout':
    time.sleep(30)
if mode == 'oversized':
    sys.stdout.write('x' * (4 * 1024 * 1024 + 1))
if mode == 'oversized-stderr':
    sys.stderr.write('x' * (4 * 1024 * 1024 + 1))
