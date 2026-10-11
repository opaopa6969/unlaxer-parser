# Engine reuse correctness experiment

[日本語の結論・範囲・再現手順](../../docs/engine-reuse-experiment-ja.md)。

```sh
python3 examples/engine-reuse/run.py
python3 examples/engine-reuse/run.py --ubnfc /path/to/pinned/private/ubnfc
```

The second command requires the clean private checkout at the recorded revision.
Generated private code stays in ignored `target/engine-reuse`; do not publish it.
This example does not change an engine default or provide a production facade.
