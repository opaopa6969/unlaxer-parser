# Java / Rust 共通 token adapter 契約

追跡: [#158](https://github.com/opaopa6969/unlaxer-parser/issues/158)、親 #111。
UBNF の token の意味を `(id, version)` で指定し、Java class と Rust function / builtin に
明示的に接続する。任意の Java 実装を Rust へ翻訳する仕組みではない。

## 既存文法からの移行

```ubnf
grammar Example {
  token STRING = ADAPTER('tinyexpression.string', version=1)
  @root @mapping(Value, params=[value]) Root ::= STRING @value;
}
```

従来の `token STRING = org.unlaxer.tinyexpression.parser.StringLiteralParser` も引き続き有効。
既存 FQN を一括置換する必要はない。生成 Java は従来と同様に parser class の subclass を
作るので、その class が compile/runtime classpath に必要であり、継承可能で無引数 constructor を
持つという既存の binding 条件も残る。Rust 側に JVM 依存は生じない。

組み込み登録は次の4件（version はすべて1）。これらも token alias を自由に選べる。

| ID | Java binding（`org.unlaxer.tinyexpression.parser.` 以下） | Rust binding |
|---|---|---|
| `tinyexpression.string` | `StringLiteralParser` | DoubleQuoted → SingleQuoted の ordered choice |
| `tinyexpression.code-start` | `javalang.CodeStartParser` | `Expr::CodeStart` |
| `tinyexpression.code-end` | `javalang.CodeEndParser` | `Expr::CodeEnd` |
| `tinyexpression.long-code-block` | `javalang.LongCodeBlockParser` | `Expr::LongCodeBlock` |

組み込みとは registry に定義済みという意味で、TinyExpression の Java class を unlaxer に
内包するという意味ではない。[文字列](tiny-string-token.md)と [code fence](tiny-code-fence.md) の
受理範囲・raw capture・mapper の変換・行境界契約は変更しない。

## 独自 adapter の登録

```ubnf
grammar Custom {
  @tokenAdapter: {
    id: 'example.word'
    version: '1'
    java: 'example.WordParser'
    rust: 'crate::word_token'
  }
  token WORD = ADAPTER('example.word', version=1)
  @root @mapping(Value, params=[value]) Root ::= WORD @value;
}
```

これは UBNF の data-only block setting。必須 key は `id`、`version`、`java`、`rust` の四つで、
各一回だけ指定する。UBNF format 2 では、外部実装の受理仕様を読めるよう、次の任意 key も使える。

```ubnf
@ubnf: v2
@tokenAdapter: {
  id: 'example.word'
  version: '1'
  java: 'example.WordParser'
  rust: 'crate::word_token'
  accepts: 'ASCII letter followed by ASCII letters, digits, or _'
  failure: 'no-consume'
  consumes: 'always'
  context: 'remaining,position'
}
```

- `accepts`: 人間とツール向けの簡潔な受理契約。空文字列は不可。
- `failure`: `no-consume`（失敗時に consumed cursor を進めない）または `may-consume`。
- `consumes`: 成功時の consumed cursor 契約。`always`、`maybe`、`never` のいずれか。
- `context`: 実装が読む read-only accessor をカンマ区切りで列挙する。空または省略は context 非依存。
  使用可能な値は `source`、`remaining`、`position`、`matchedPosition` のみ。

未知の key・重複 key・必須 key の欠損・不正な accessor は拒否する。外部 manifest や plugin を自動探索しない。

- ID は case-sensitive な `[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*`。
- version は ASCII 十進数字で、正規化後は `1..2147483647`。`01` と `1` は同じ version。
  syntax AST は raw 字句を保持し、0 / overflow は token / setting の位置付きエラーにする。
- Java class は2要素以上の dotted name、Rust function は2要素以上の `::` path。
  各要素は `[_A-Za-z][_A-Za-z0-9]*`、単独 `_` と予約語等は除外する。
  Rust の先頭 `crate` / `self` / `super` は例外的に許可する。
  raw identifier・型引数・マクロ・式・コード断片は指定できない。
- 同一 `(id, version)` の再登録は builtin を含め拒否する。別 version は共存可能。
  複数 token alias が同じ登録を参照することも可能。
- registry は grammar 単位。無効な登録を除外した上で、参照側の独立したエラーも収集する。

生成 class 名が衝突する token alias（`WORD` と `Word` など）、または adapter wrapper が
別 token の短名 class 参照を隠す定義は構造検証で拒否する。Java validator は
`E-TOKEN-ADAPTER-NAME-COLLISION`、両 host の Rust checker は `P-STRUCTURE` を返す。
低水準の Java generator API は従来どおり呼出し側で共通 validator を先に実行する。

Java は指定 class を継承する token wrapper を生成する。Rust は
`Expr::Custom(crate::word_token)` のような関数参照を生成する。
関数は生成 module から参照可能で、既存 runtime の次の型へ適合する必要がある。

```rust
fn word_token(context: &mut unlaxer_runtime::ParseContext<'_>)
    -> unlaxer_runtime::ParseResult
{
    context.parse(&unlaxer_runtime::Expr::Literal("😀"))?;
    context.parse(&unlaxer_runtime::Expr::Literal("x"))
}
```

class / function の実在、可視性、署名は target compiler が検証する。
登録だけでは言語間の意味的同値性を証明できない。provider 実装者が同じ入力・期待値で検証する。

## 公開 registry API と検査

Java の `TokenAdapterRegistry.build(grammar, sourceSnapshot)` は immutable な registry と
全 diagnostic を返す。`find(id, version)` で descriptor を参照する。
`requireValid(grammar)` は位置情報なしの AST 用で、最初の登録・参照エラーを例外にする。
snapshot を持たない Java API の diagnostic span は `null` になり得る。

Rust の `unlaxer_generator::adapters::AdapterRegistry` は `builtins()`、
`from_grammar(&grammar)`、`resolve(id, raw_version)`、`contains(id, version)` を提供する。
`from_grammar` は登録側の diagnostic、`resolve` は参照側の diagnostic code / subject を返す。
双方の [移植可能性 checker](portability-check.md) がこれらを統合して全件の位置付き JSON にする。

| Code | 意味 | Span |
|---|---|---|
| `P-ADAPTER-DEFINITION` | schema / ID / version / binding path 不正 | setting または token 全体 |
| `P-ADAPTER-DUPLICATE` | 同じ `(id, version)` の重複 | 後出の setting 全体 |
| `P-ADAPTER-UNKNOWN` | 未登録 ID | 参照 token 全体 |
| `P-ADAPTER-VERSION` | ID は既知だが version 未登録 | 参照 token 全体 |

subject は判明した ID、不明なら `tokenAdapter`。位置は元入力の Unicode code-point 半開区間。
LF / CRLF は元のまま数える。adapter 診断があれば `structure: blocked` となる。
`portable: true` は schema・対応機能・構造検証を通ったという意味であり、外部 provider の
リンク成功・挙動の一致・安全性まで保証しない。

## ParseContext と安全性の境界

### UBNF format 2 の context accessor

`@ubnf: v2` は format 2 を明示する。指定のない既存 grammar は format 1 として互換に扱う。
format 2 は Java/Rust の `ParseContext` の内部構造を公開しない。adapter provider が依存してよい
共通の read-only 表面だけを定義する。

| accessor | 意味 | Java | Rust |
|---|---|---|---|
| `source` | 入力全体 | `getSource()` | `source()` |
| `remaining` | consumed cursor から入力末尾まで | `peek(consumed, remaining length)` | `remaining()` |
| `position` | consumed cursor の Unicode code-point offset | `getConsumedPosition()` | `position()` |
| `matchedPosition` | match-only cursor の Unicode code-point offset | `getMatchedPosition()` | `matched_position()` |

これらは観測専用であり、context の cursor・capture・scope・診断・transaction を変更してはならない。
capture、scope、任意 user state、listener、transaction frame は Java/Rust で同じ安定契約をまだ持たないため
format 2 の accessor には含めない。必要な場合は provider 固有の実装として残し、`context` には列挙しない。

adapter の呼出しは既存 combinator / transaction の中に入る。新しい別 runtime は作らない。
失敗した枝は consumed / matched cursor、capture、登録された transactional state などを
既存契約に従って復元する。子の成功後に親が失敗する場合も同様。
Java/Rust の内部 CST が同一構造であるとは主張せず、共通契約は受理・位置・capture・AST とする。

custom provider は解析中の `ParseContext` を利用できる。通常の信頼された host parser code であり、
sandbox ではない。ファイル書込み・外部 I/O・共有オブジェクト変更を transaction が取り消すことは
できない。自動再解析や speculative trial を観測できる副作用は provider 側で避ける。

UBNF の読込み、registry の構築、`check`、code generation は provider をロード・初期化・実行しない。
**生成された parser を実際に走らせるときだけ、指定した host provider を呼ぶ**。
code fence 内の本文を parse 中にコンパイル・実行することとは別であり、本文は opaque な入力である。
明示許可付き本文 AOT は TinyExpression 側の専用機能で、この登録からは呼び出さない。

Rust 向け生成では custom adapter の nullability は不明として保守的に扱い、
零幅になり得る無限反復を両 host とも拒否する。
たとえば `{WORD}` は拒否するが、必ず消費する literal がある `{'[' WORD ']'}` は利用できる。
custom は memo-unsafe として扱い、`@memoSafeToken` による安全宣言は現在拒否する。
Java は組み込み adapter も外部 class として扱い、明示的な `@memoSafeToken` なしには
依存 rule を memo-safe にしない。Rust は既存の組み込み Expr へ lower するため、
従来どおり runtime が安全性を判定する。組み込み adapter の明示 `@memoSafeToken` は両 host で受理する。
未宣言 custom の診断再実行も
既存の保守的な経路を維持し、この登録を純粋性・再実行可能性の宣言には使わない。

## 共通 corpus と再現

[`spec-corpus/token-adapters`](../spec-corpus/token-adapters/) に schema と独立期待値を置く。

- 登録・参照・構造制約の23 fixture を LF / CRLF で検査し、両 host の JSON・終了コード・元位置を比較する。
- 独立実装の custom provider を両言語で生成・コンパイル・実行する。
  5文法16入力で prefix / 全入力受理、両 cursor、optional / repeated capture、AST の CP span、
  途中失敗・親 choice / repeat の rollback を比較する。Rust AST は CST の破棄後にも読む。
  UBNF 読込み・check・生成・Java compile まで provider の static initializer が動かないことも確認する。
  `WORD` / `SPACE` alias が Java 組み込み parser 名を隠しても、literal / trivia の意味を変えない。
- 実 TinyExpression `7d7bd1cbfab1bd9548ba53c73cc50502f0437c67` の Java class を oracle にする。
  文字列7文法39入力、fence14文法86入力を従来 FQN と ADAPTER 指定の両方で検証する。
  同名 test double は使わず、ロードした class の CodeSource も照合する。
- Java / native Rust の生成5ファイルを byte 比較し、native 生成と生成 binary は空の PATH でも実行する。

候補版を隔離 Maven repository へ通常の flatten 処理付きで install し、それに対して
固定 TinyExpression checkout を `test-compile` してから、次を実行する。
`-DskipTests` は oracle の build にだけ使い、下記の比較には指定しない。

```sh
mvn -B -pl unlaxer-common,unlaxer-dsl -am test \
  -Dtest=TokenAdapterRegistryTest,TokenAdapterConformanceTest,RustPortabilityConformanceTest,RustUbnfFrontendConformanceTest,RustUbnfSourceConformanceTest,TinyStringTokenConformanceTest,TinyCodeFenceConformanceTest \
  -DrustConformance=true \
  -Dtinyexpression.classes=/absolute/path/to/pinned/tinyexpression/target/classes \
  -Dsurefire.failIfNoSpecifiedTests=false
```

CI は `rust-token-adapter-diagnostics.tsv` / `rust-token-adapter-runtime.tsv` を
`rust-conformance` artifact に、`rust-tiny-string-adapters.tsv` /
`rust-tiny-code-fence-adapters.tsv` を `tiny-token-adapter-conformance` artifact に保存する。
各ファイルの非空チェックを必須にし、skip を一致の証拠にしない。
この token 契約だけで、残る UBNF annotation、全 TinyExpression 意味論、LSP / DAP を完了とはしない。
