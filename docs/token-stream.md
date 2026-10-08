# 文字入力と token stream の選択

3.3.0-SNAPSHOT では、UBNF v2 から生成する Java / Rust parser に、解析ごとの字句処理の選択を追加できる。既存の入口と既定の文字列直接解析は維持する。空白・コメントを削除した別の文字列を構文解析する方式ではなく、原文上の token 境界を使うため、診断・capture・AST の位置を原文へ戻せる。

```ubnf
grammar Input {
  @ubnf: v2
  @package: example.lexing
  @tokenStream: enabled
  @whitespace: javaStyle
  token ID ::= CHAR_RANGE('a', 'z')+;
  @root @mapping(Pair, params=[left, right])
  Root ::= ID @left ':' ID @right;
}
```

## 実行時の選択

Java の生成コード:

```java
import org.unlaxer.dsl.runtime.Lexing;

var out = InputParsers.parseWithLexing("alpha /*memo*/ : beta",
    new Lexing.Options(Lexing.Mode.TOKENS_LAZY, true));
if (out.succeeded()) {
    var ast = InputMapper.mapParsedTokenWithSourceMap(out.root()).ast();
}
for (var part : out.session().lexemes()) {
    System.out.println(part.kind() + ": " + out.session().text(part));
}
```

Rust の生成コード:

```rust
use unlaxer_runtime::lexing::{Mode, Options};

let mut out = generated::parser::parse_with_lexing(
    "alpha /*memo*/ : beta",
    Options { mode: Mode::TokensLazy, preserve_trivia: true },
)?;
if out.succeeded {
    let ast = generated::mapper::map(out.tree.as_ref().unwrap())?;
}
for part in out.session.lexemes() {
    println!("{}: {}", part.kind, out.session.text(&part));
}
```

| Java / Rust mode | 字句処理 | 用途とコスト |
|---|---|---|
| `DIRECT` / `Direct` | parser が必要な位置で文字列を認識 | 既定。token 列の構築を避ける |
| `TRIVIA_CACHE` / `TriviaCache` | 直接解析 + 空白・コメントの終端位置をキャッシュ | 分岐の再試行で同じ trivia を繰り返し走査する場合 |
| `TOKENS_LAZY` / `TokensLazy` | 必要な位置まで順に字句解析し、token 境界を再利用 | 途中で失敗する入力も含め、先行する解析量を抑えたい場合 |
| `TOKENS_EAGER` / `TokensEager` | 全文を先に字句解析 | 全 token の一覧を最初から使う場合 |

`Lexing.Options.DEFAULT` / `Options::default()` は直接解析・trivia 公開あり。既存の生成 parser の入口には自動で token 化を挿入しない。現状の playground 画面にも mode selector は追加していない。新しい生成 API で明示して使う。

## token の境界と優先順位

token mode は、構文 rule に書かれた非空 literal と、構文 rule から直接参照された宣言的 token を候補にする。字句定義の内部でだけ参照する fragment は独立の候補にならない。

1. 候補全体で、完全に一致した長さが最長のものを選ぶ。
2. 同じ長さなら構文 literal を優先する。literal 同士は rule 本文での初出順。
3. 宣言的 token 同士は宣言順で決める。構文の分岐順では変えない。

個々の字句定義内部の `|` は従来どおり順序付き選択。候補の最長一致とは区別する。`@whitespace: javaStyle` なら token 境界にある空白・コメントを先に認識する。文字列や code block の token 内部へは入らず、コメントに見える文字も保持する。

これにより直接解析と token mode の受理言語が変わる場合がある。`'if' ID` と英字の `ID` では、`ifx` は直接解析で `'if'` + `x` となり得るが、token mode は `ID(ifx)` を選ぶため失敗する。`if if` も後半が予約語 literal になる。選択が意味を持つ差であり、自動で mode を切り替えて成功させることはしない。

どの候補にも一致しない文字は Unicode scalar 1 文字の `error` entry にする。構文解析はその位置で失敗し、読み飛ばして成功とはしない。閉じていない `/*` はコメントとして認識しない。行コメントは CR / LF の手前まで、改行は別の `space` entry になる。

## 対応する profile

| 条件 | Java | Rust |
|---|---|---|
| UBNF v2、宣言的 token、構文 literal、既存の分岐・反復・capture・mapping | 対応 | 対応 |
| 全体の `@whitespace: javaStyle` / `none` | 対応 | 対応 |
| token 内部の先読み・BOL・EOF・CAPTURE・SAME_AS・SCOPE | 対応 | 対応 |
| 構文の `token END = EOF` / `token E = EMPTY` | ゼロ幅 | ゼロ幅 |
| 外部 Java parser、adapter、旧形式の ANY 等 | 明示拒否 | 明示拒否 |
| 空一致する token を構文から参照 | 明示拒否 | 明示拒否 |
| 独自 `@comment`、rule ごとの `@whitespace` / `@interleave` | 明示拒否 | 明示拒否 |
| rule の `@backref`、recovery | 明示拒否 | 明示拒否 |

空一致する fragment は、非空 token の内部で使える。`EOF` と `EMPTY` は token 列へ項目を追加しないゼロ幅の条件。profile を有効にした Java の `EMPTY` は純粋な空一致で、従来の `EmptyParser` 内部の文字先読みを行わない。profile のない文法は従来どおり。

不正な設定・未対応機能は `E-TOKEN-STREAM-SETTING` / `VERSION` / `TOKEN` / `TRIVIA` / `ANNOTATION` で生成前に拒否する。Java validator と Java / native Rust の portability check で位置も報告する。文法自体の既存の制限も引き続き適用する。

字句 mode の切替・文脈依存キーワード・構文解析中の字句規則変更は [#377](https://github.com/opaopa6969/unlaxer-parser/issues/377)、入れ子の異言語 grammar の振分けは [#369](https://github.com/opaopa6969/unlaxer-parser/issues/369) の範囲。ここでは字句定義と原文を解析中に変えない。

## 原文、trivia、診断の契約

- 公開する span は Unicode code point の半開区間 `[start, end)`。Java の UTF-16 index、Rust の byte index を呼出側で換算する必要はない。CRLF は 2 code point。Java は孤立 surrogate を `E-LEXING-SOURCE` で拒否する。Rust の `&str` は有効な Unicode 入力を前提にする。
- `lexemes()` は token / error / space / lineComment / blockComment の一覧。`text()` は原文を切り出す。`preserveTrivia=true` なら連結すると原文に戻る。AST の文字列変換は従来の mapper の契約に従い、raw text の完全保存にはこの一覧を使う。
- `preserveTrivia=false` は公開一覧から trivia を除く選択。原文と内部の解析用境界は保持するため、メモリからコメントを消す指定ではない。
- Java Session は不変の String を保持する。Rust Session は入力 `&str` を借用し、返される Outcome の寿命も入力に従う。Rust の Tree は自身の原文を所有する。Session は各解析に独立し、共有 parser / grammar にキャッシュを置かない。Java Session を複数 thread から同時変更する用途は対象外。コメントの span から原文を編集し、新しい文字列を再解析できる。既存 Session の原文を直接変更する API は設けない。
- `lexemes()` は失敗位置より後も含め、残る一覧を遅延構築する。直接解析でも得られる一覧は同じ優先順位による字句的な見方であり、その parser が実際に選んだ分岐の記録ではない。
- 分岐の失敗では既存の transaction で cursor と capture を戻す。原文と規則が固定された字句キャッシュは残して再利用する。診断の失敗位置と不正 token を原文上で観測できる。診断候補の文字列は backend 固有。
- `consumed` / `matched` は transaction 後の消費・一致 cursor、`farthest` は失敗診断の最大到達位置。字句 atom 内部で失敗した途中位置を構文の一致位置として漏らさない。
- `succeeded` は入力末尾までの成功。末尾に未消費文字があれば false になるが、`root` / `tree` は成功した prefix を含むことがある。AST を確定するときは先にこの flag を確認する。

## 検証と測定

共通 corpus は [`spec-corpus/token-stream`](../spec-corpus/token-stream/README.md)。Java の既存入口との比較、Java / Rust の全 mode と trivia 公開有無、native Rust generator と Java generator の生成物一致を検証する。判定・AST 値と位置・named CST capture・trivia の位置・分岐失敗位置は独立の期待値を使う。

```sh
mvn -pl unlaxer-common,unlaxer-dsl -am test \
  -Dtest=TokenStreamConformanceTest,RustUbnfFrontendConformanceTest \
  -DrustConformance=true -DtokenStreamBench=true \
  -Dsurefire.failIfNoSpecifiedTests=false
```

結果は `unlaxer-dsl/target/rust-token-stream.tsv` と `token-stream-benchmark.tsv`。CI でも両方を必須 artifact にする。通常の `mvn test` だけでは Rust 比較と測定は skip される。

測定は同じ文法、同じ入力、trivia 公開なし、5 回の warmup 後に実行する。時間・割当 bytes・字句候補評価回数・trivia 評価回数・保持 entry 数を記録する。Rust は既存の allocation audit で最大同時要求 bytes も記録する。Java は thread allocation counter を使うが、最大常駐メモリは測らない。

`terminalEvaluations` は外側の候補 matcher 呼出回数で、matcher 内部の文字検査数ではない。token mode は全候補を評価するため、この回数が増える場合もある。`inventoryEvaluations` は `lexemes()` で追加した一覧作成の評価数。測定には一覧作成を含めない。`retainedEntries` は token / trivia 境界と trivia-cache 項目の合計で、bytes や GC 後の保持量ではない。

短い入力、trivia のない反復入力、コメントが長い反復入力を比べる。token 化はコメントの再走査を減らす一方、一覧と index の構築・保持を要する。短い入力や trivia の少ない入力で速くなるとは限らない。[測定スナップショット](../spec-corpus/token-stream/measurement.tsv)は動作確認用であり、速度保証や Java / Rust 間の性能順位ではない。新しい API の 4 mode 同士を比較しており、従来入口の絶対性能との比較ではない。JIT・GC・実行順・host 負荷の影響を含む少数回の計測なので、時間の閾値を CI の成功条件にはしない。
