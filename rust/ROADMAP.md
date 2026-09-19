# Native parserからtinyexpression-rsへ

ユーザーの長期目標は、parserをネイティブバイナリとして配布し、最終的にtinyexpressionの式を実行する`tinyexpression-rs`バイナリと`rustcodeblock`を提供すること。この文書は段階的な設計方針であり、未実装の機能が使えるという宣言ではない。

## バイナリの区別

- **文法専用parser/evaluatorバイナリ**: 生成済みRustをビルドした実行ファイル。実行先にJVMやrustcは不要。現在の`unlaxer-evolution-example`がこの最小例で、tinyexpression互換ではない。
- **tinyexpression-rsバイナリ**: tinyexpressionの互換範囲を明示した評価CLI。まだ存在しない。CLI・埋込み用library・手書きsemanticsを分離する。
- **UBNF生成器のネイティブバイナリ**: 現在のJava frontendもRustへ移す別段階。Javaを必要とせず新しいUBNFを読み込んで生成する機能は、生成済みparserバイナリとは別物。

単体実行可能と全OS共通・完全static linkは同義ではない。現時点のCI artifactはUbuntu runnerでビルドしたLinux x86_64向けの縮小文法example。OS/ABIごとの検証なしに他環境での互換性を宣言しない。

## 段階と受け入れ条件

| 段階 | 内容 | 完了と判断する条件 |
|---|---|---|
| 0: 基礎（実装済み） | 限定UBNF、Rust runtime/AST/mapper/semantics、診断API | Javaとの共通corpus比較、compiler failure、native release buildのCI検証 |
| 1: DSL機能の拡張 | 文字列・boolean・変数・演算子優先順位・関数呼出し | 各機能を小PRで追加し、型・AST・値・span・失敗例をJavaと比較。数値型、null、欠損値、短絡評価を先に仕様化 |
| 2: tinyexpression-rs MVP | 実tinyexpression文法の対象範囲、環境/外部関数API、CLI | 同じ式と変数JSONから同じ値/失敗分類を得る。未対応構文を明示拒否し、ファイル/stdin/終了コード/入力制限をテスト。新repo分離・独立releaseはこの境界で再評価 |
| 3: rustcodeblock構文 | fence、識別子、本文、元のsource span | Rust本文を改変せず保持、未閉鎖/重複/未知schemeを診断。解析・LSPだけでコンパイラを起動しない |
| 4: 信頼されたAOT rustcodeblock | 明示opt-inのビルド、typed host呼出し、バイナリ出力 | 許可時だけ実行、無効時はブロックを黙って無視せず拒否。型不一致/コンパイル失敗を元の式位置へ写像。依存・toolchain・許可設定を固定して再現可能にする |
| 5: 配布・IDE/デバッガー | OS別成果物、LSP/DAP、必要ならRust frontend | 実protocolとeditorでの検証、source位置変換、短絡評価とstepの整合、各配布環境のsmoke。動的コード実行は別ADRで判断 |

現状の数値captureに末尾コメントが残る挙動はJavaとの比較のため記録しているが、tinyexpression-rsの仕様として固定するという意味ではない。次段階では言語仕様上の字句値とtrivia/spanを分離し、必要なら両backendの互換性テストを改訂する。

## javacodeblockから何を引き継ぐか

確認基点はtinyexpressionの`6c8196b7879abe58d6b739d3e63a16e853745ced`。以下はそのrevisionの実装/文書であり、Rust側の実装済み機能ではない。

- [`CodeParser`](https://github.com/opaopa6969/tinyexpression/blob/6c8196b7879abe58d6b739d3e63a16e853745ced/src/main/java/org/unlaxer/tinyexpression/parser/javalang/CodeParser.java): `scheme:identifier`と本文を抽出する。
- [`JavaCodeCalculatorV3`](https://github.com/opaopa6969/tinyexpression/blob/6c8196b7879abe58d6b739d3e63a16e853745ced/src/main/java/org/unlaxer/tinyexpression/evaluator/javacode/JavaCodeCalculatorV3.java): Javaブロックをコンパイルしてクラスとして呼ぶ。
- [`JavaCodeBlockPolicy`](https://github.com/opaopa6969/tinyexpression/blob/6c8196b7879abe58d6b739d3e63a16e853745ced/src/main/java/org/unlaxer/tinyexpression/evaluator/javacode/JavaCodeBlockPolicy.java)と[ADR-003](https://github.com/opaopa6969/tinyexpression/blob/6c8196b7879abe58d6b739d3e63a16e853745ced/docs/decisions/ADR-003-java-codeblock-safety.md): 既定無効、信頼したホストによる明示許可。

**警告:** JavaコードブロックはJVM上で任意コードをコンパイル・実行する。完全に信頼できる式作成者にのみ許可し、信頼できないユーザーにこの機能を公開しない。Rustブロックでもメモリ安全性はファイル・ネットワーク・プロセスへのアクセス制限にはならない。

Rust側の候補構文は` ```rust:module_name `で開始するfenceとするが、識別子の文法・呼出構文・型は今後のADRで確定する。JavaコードをRustへ自動翻訳する計画ではない。対応する拡張コードは作者がRustで実装する。

## 初期rustcodeblockはAOTを候補にする

```text
式文書 → fenceをASTとして保持 → 明示的なビルド許可
       → 検証済みmodule名と生成host interface → Cargoでビルド
       → tinyexpression-rs用の実行バイナリ
```

最初は、同一バイナリへstaticに組み込むmodule/typed trait方式を候補にする。値境界はDSLの明示的な`Value`/`Result`とhost interfaceで定義し、JVMの任意オブジェクトやreflectionを持ち込まない。Rust ABIの動的library読込みや各評価時のrustc起動は初期範囲に含めない。ビルド後は実行先にtoolchainを要求せず、ブロック変更時は再ビルドする。この点はJava版の動的コンパイルとは異なる。

通常の`parse`/`diagnose`/LSP hoverや文書を開く操作は副作用のない経路とし、Rustコンパイル・Cargo依存取得・コード実行を行わない。生成先pathはmodule名から無検証で組み立てず、allowlist化する。コードブロックから`Cargo.toml`や依存を自由に追加させない。ビルド許可は式内容から推測せずホスト設定で明示する。

ビルド自体も安全なsandboxではない。依存のbuild script/proc macroはビルド時にコードを実行し得るため、当初はホスト管理の固定依存/lockfileと管理されたビルド環境を使う。任意コードを有効にした後の実行権限・資源制限・panic/終了/ハングの隔離は別途設計が必要。信頼できない拡張を扱うならprocess/WASM等の隔離と能力制限を検討するが、今のruntimeにその保証はない。

## 次に着手する単位

full-specへ向けた順序と対応状況は[FULL-SPEC.md](FULL-SPEC.md)で追跡する。まず公開ParseContextと手書き/生成parser共通combinatorを基盤にし、optional・repeatとOption/Vec AST、token/annotationへ拡げる。先にtinyexpression全体やrustcodeblockを受理だけする実装を作らず、構文・意味処理・位置・失敗の契約を一機能ずつ揃える。
