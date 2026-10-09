import sys,time
from pathlib import Path
mode=sys.argv[1]
if mode=='marker': Path(sys.argv[2]).write_text('executed')
sys.stdin.buffer.read()
if mode=='nonzero': sys.exit(42)
if mode=='timeout': time.sleep(5)
if mode=='oversized': sys.stdout.write('x'*(4*1024*1024+1))
