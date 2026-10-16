# 左結合の固定 delimiter oracle

`cases.json` は基準 Java で使えていた左結合の固定 delimiter を、Java / Rust 共通の値・CP範囲・失敗として記述する。生成コードから期待値を採取しない。NUMBERの整数値、literalの同一型/異capture、tokenStream、補助文字とCRLF、mapped operandと共有mapping、複数precedenceを含む。

`parseOptions: true` は OFF/SAFE_FAILURES × AUTO/DETAILED/DETAILED_ON_FAILURE の6設定を要求する。`parseCp` は構文失敗のfarthest、または完全な構文prefixの後の余剰入力位置。Javaの直接contextはdeferred診断で自動再試行しないので位置はDETAILEDで観測し、全設定で受理/拒否・正常値・保持snapshotを比較する。

`invalid-cases.json` は空literalの既存生成前拒否をJava-host/native-host Rustで比較する。Optional/別Repeatの旧拒否2例は、基準Javaで正常であると確認し、後続 `associative-nullable-delimiters` の成功oracleへ移した（#679）。右結合やnested-ownerの新形はこのcorpusの対象外。

実行:

```sh
RUSTUP_TOOLCHAIN=1.85.0 mvn -pl unlaxer-common,unlaxer-dsl -am test \
  -Dtest=AssociativeDelimiterConformanceTest -DrustConformance=true \
  -Dsurefire.failIfNoSpecifiedTests=false
```

Java21実生成compile/map/evalと、両hostのRust全生成ファイル一致・各出力の独立compile/runを行う。preferred候補の型/順序/深さ/原文CP範囲とordinary mapper値を照合し、後続失敗・再解析後にも保持snapshotを確認する。
