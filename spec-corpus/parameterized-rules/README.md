# 一階の引数付き規則の共通 corpus（#701）

`cases.json` は Java と native Rust の UBNF frontend、Java の生成器、Java host と native の
Rust 生成器に共通の独立期待値を置く。契約と停止性の根拠は
[docs/parameterized-rules.md](../../docs/parameterized-rules.md)。

- `status: accept` の 10 文法（#699 の alias 透過と、展開の入れ子がちょうど 128 段の 2 例を含む）は、手で展開した等価文法 `expanded`、instance の正規形の鍵と名前
  `instances`、instance 規則の定義 span `spans`、入力ごとの受理・拒否、CP 位置、AST を持つ。
- `error.stage: validate` の 2 文法は展開後の文法を既存検査が `E-LEFT-RECURSION` で拒否する例
  （lean4-peg CE-002 と、展開後にだけ現れる nullable な接頭辞）。手で展開した文法も持つ。
- `error.stage: frontend` の 23 文法は `E-PARAM-*` の code、診断本文、CP 区間、行・列を持つ。

期待値は実装の出力から作っていない。`generate_cases.py` が手書きの文法・期待値を JSON にし、
instance 名は手書きの鍵から独立に実装した FNV-1a 64bit で、拒否位置は文法文字列の部分文字列検索で
求める。上限の負例の位置は、docs に書いた数え方を script が手で追って求める。期待値を変えるときはこの script を直して再生成する
（`python3 -I generate_cases.py cases.json`）。

再現（JDK 21 と Rust 1.85 が必要）:

```sh
RUSTUP_TOOLCHAIN=1.85.0 cargo test --locked --manifest-path rust/Cargo.toml -p unlaxer-ubnf --test parameterized_rules
RUSTUP_TOOLCHAIN=1.85.0 mvn -pl unlaxer-common,unlaxer-dsl -am test \
  -Dtest=ParameterizedRuleConformanceTest -DrustConformance=true \
  -Dsurefire.failIfNoSpecifiedTests=false
```
