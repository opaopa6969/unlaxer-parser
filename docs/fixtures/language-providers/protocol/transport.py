import sys
import time
from pathlib import Path

mode = sys.argv[1]
limit = 4 * 1024 * 1024
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
    sys.stdout.buffer.write(b'x' * (limit + 1))
if mode in ('limit-stdout', 'limit-stderr', 'oversized-stderr'):
    response = Path(__file__).with_name('response-ok.wire').read_bytes()
    if mode == 'limit-stdout':
        # 有効な item の detail を hex で埋め、frame 全体を正確に 4 MiB にする。
        padding = limit - len(response) - len(b'item\t78\t\n')
        assert padding >= 0 and padding % 2 == 0
        response = response[:-4] + b'item\t78\t' + b'78' * (padding // 2) + b'\nend\n'
        assert len(response) == limit
    sys.stdout.buffer.write(response)
    if mode in ('limit-stderr', 'oversized-stderr'):
        sys.stderr.buffer.write(b'x' * (limit + (mode == 'oversized-stderr')))
