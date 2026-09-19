# 実行記録

2026-09-19、基点`21dead3`に本改訂を適用したツリーで実行。Oracle JDK 21.0.9、Maven 3.9.9、Linux x86_64 / WSL2。Git revisionの厳密な再現にはこのファイルを含むPRのmerge revisionを使用する。時刻や実行速度は環境に依存する。

`mvn -B clean test`は成功。common 625、dsl 778、ubnf-vscode 26、tinycalc-vscode 1、合計1,430件のJUnitテストで失敗・error・skipは0。外部tinyexpressionのテストはこの数に含まない。

同じ全体テスト実行が出力した言語進化のTSV:

```tsv
stage	handwritten_files_changed	lines_added	lines_removed	generated_files_changed	generated_files	machine_ms
0	2	25	0	8	8	270
1	2	2	1	3	8	319
2	2	6	1	5	8	420
3	2	6	1	5	8	404
```

`machine_ms`は1回の実行で生成・コンパイル・service APIの動作確認に費やした経過時間。JVMのwarmupやテスト順序の影響を含み、開発工数・parser throughput・他ツールとの速度比較に用いない。再実行ではこの列の一致を要求しない。

代数は8元の閉包、64積、512結合則を検査。コピー言語は全3,280短入力と追加境界例を検査。言語進化の負例はコンパイル診断コードと欠落メソッド名を検証し、演算子追加の負例はコンパイル成功後の実行時例外を確認した。これらの失敗はテスト内で期待される結果であり、上のテスト失敗件数にはならない。

原稿の数値はこの実験に限定する。再現方法と計測範囲は[artifact](artifact.md)を参照。

CIと同じ`-Pstatic-analysis`でcommon/dslを`clean test-compile`し、SpotBugs 4.10.2.0のcheckも成功した。Error Proneの有効な検査とSpotBugsにerrorはなかった。
