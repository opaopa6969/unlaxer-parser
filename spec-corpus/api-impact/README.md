# Language-evolution API corpus

`cases.json` は独立に固定した変更一覧と、旧評価器・旧AST利用者のコンパイル成否を持つ。
Java / Rust の差を期待値から削って一致させない。各ペアのコメント中の非BMP文字は
source offset が UTF-16 / UTF-8 の単位にずれないことを検証するために残す。

`ApiImpactConformanceTest` は両 host の Rust target レポートを JSON 全体で照合し、
Java target は実 AST / evaluator 宣言と照合する。LF / CRLF の双方で位置を検証する。
レポートの message は host 固有なので、失敗ケースは side / code / surviving snapshot を比較する。

コンパイルでは変更前 schema の利用者コードを凍結し、変更後の生成コードに組み合わせる。
失敗が生成器自体の不正出力によるものではないと確認するため、更新した利用者もコンパイルする。
利用者は固定テンプレートで生成されるテスト用コードで、人間の作業時間や修正行数の測定ではない。

2026-09-30 の局所検証では52レポート、52組のcompile probe（旧API＋旧利用者、新API＋旧利用者、
新API＋更新利用者の計156コンパイル）が期待値と一致した。旧利用者＋新APIは各言語で
14件が失敗、12件が成功。旧APIと更新利用者のpositive controlは全件成功した。
これは13個の小さな変更に対する検証であり、任意の文法・利用者や意味的互換性の証明ではない。

各 CI 実行の TSV は `rust-conformance` artifact に保存される。
列の意味・再現コマンド・適用範囲は [契約文書](../../docs/api-impact-report.md) を参照。
