# 値渡しの引数の共通 corpus（#704）

`cases.json` は Java と native Rust の UBNF frontend、Java の生成器、Java host と native の
Rust 生成器に共通の独立期待値を置く。契約は [docs/call-by-value-arguments.md](../../docs/call-by-value-arguments.md)。

- `status: accept` の 8 文法（36 入力）は、CAPTURE_SCOPE / CAPTURE / SAME_AS で手で書いた等価文法
  `expanded`、生成される binding 名（`Body(=tag)` など。UBNF では書けない）から等価文法の識別子への
  対応 `captures`、instance の正規形の鍵と名前 `instances`、instance 規則の定義 span `spans`、
  入力ごとの結果 `expected`（受理なら AST、拒否なら最も遠い失敗位置）と parse 後の context の状態
  `state`（成功・消費位置・一致位置）を持つ。
- `error.stage: frontend` の 12 文法は `E-PARAM-*` の code、診断本文、CP 区間、行・列を持つ。値渡しの
  引数でも #701 と同じ検査・本文になることと、CAPTURE_SCOPE / CAPTURE を含めた要素数・入れ子の上限の
  数え方を確かめる。

期待値は実装の出力から作っていない。`generate_cases.py` が手書きの文法・期待値を JSON にし、
instance 名は手書きの鍵から独立に実装した FNV-1a 64bit で、拒否位置は文法文字列の部分文字列検索で
求める。上限の負例は docs に書いた数え方を script が手で追う。期待値を変えるときはこの script を直して
再生成する（`python3 -I generate_cases.py cases.json`）。

再現（JDK 21 と Rust 1.85 が必要）:

```sh
RUSTUP_TOOLCHAIN=1.85.0 cargo test --locked --manifest-path rust/Cargo.toml -p unlaxer-ubnf --test call_by_value
RUSTUP_TOOLCHAIN=1.85.0 mvn -pl unlaxer-common,unlaxer-dsl -am test \
  -Dtest=CallByValueConformanceTest -DrustConformance=true \
  -Dsurefire.failIfNoSpecifiedTests=false
```
