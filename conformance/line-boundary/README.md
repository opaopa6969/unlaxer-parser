# 行境界 parser の移植契約 (#848)

基準は master `6bc349c1cf60fc31a7c62fb0bc72a25f5e2f6236`（#855 merge）。
Java/Rust の runtime と frontend は変更しない。`probe.rs` は公開 API のみを使う
実行可能な Rust 移植例であり、runtime に新しい combinator・memo 安全宣言を追加しない。

## 対応範囲

| Java | Rust の実行経路 | 状態 |
|---|---|---|
| StartOfLineParser | `Expr::Custom(bol)` → `LexicalExpression::match_at(Op::BOL)` | 以下の共通契約を検証 |
| StartOfSourceParser | `Expr::Custom(bof)` → `LexicalExpression::match_at(Op::BOF)` | 以下の共通契約を検証 |
| LineTerminatorParser / EndOfLineParser | `Choice([Literal(CRLF), Literal(CR), Literal(LF), Eof])`（`probe.rs::line_end`） | 以下の共通契約を検証。Java の既知欠陥 #861 の30行だけ Java 側の期待値を分ける |
| EmptyLineParser / WildCardLineParser | BOL + 文字クラス反復 + 行終端、guard付き反復、terminator-first Choice の反復 | **未完了、#846 依存**。3案の反例を実行 |

6型自身に公開 static method の宣言はない（全API inventoryでも型の6行）。
関連する `Parser.get(Class)`、`getSupplier(Class)`、`get(Supplier)`、`newInstance(Class)`
を4型の全ケースで実行する。Class/Supplier cache の同一性、独立 instance、
`isTokenParsedBySpecifiedParser` / `checkTokenParsedBySpecifiedParser` と
`LineTerminatorParser.isEndOfSource` も観測する。
Rust は factory が構築する Expr と安定したラベルを使い、同一 Expr の再試行を実行する。
JVM の反射・任意 Object 全般の移植は #845/#854 の範囲であり、この4型のために使わない。

## 独立 oracle

`cases.tsv` の1,088行は、17組の入力/開始位置 × 4型 × 4 token kind × invert 2 × reset 2。
Java/Rust の出力から転記・生成した snapshot ではない。次の規則から期待値を記述した。
入力と token テキストは UTF-8 hex、`~` は空文字または空の token forest、位置はすべて CP。

- BOL は CP0 または直前が CR/LF。CRLF の途中も真。StartOfLine の invert=true は常に失敗。
- BOF は CP0、StartOfSource は invert と XOR。両型とも token を作らず cursor を動かさず、失敗候補も追加しない。
- 位置を読むのは正確に `consumed` のときだけ consumed、それ以外（virtual consumed を含む）は matched。
- 行終端は CRLF → CR → LF → EOF の順序付き選択。EOL の語彙境界検査とは違い改行を消費する。
  invert は各候補へ伝播する。EOF は Classic の `Any.zero_match()` であり、invert=true では途中でも零幅成功し得る。
- 候補の transaction は reset=true なら matched を consumed に戻す。成功した consuming leaf は consumed から長さを進めて matched も揃える。
- 診断は成功の最大到達位置と失敗候補の最大位置を区別する。失敗候補があればその CP を採用し、
  EOF の zero-match は試行を戻した frame の位置で失敗する。先に成功した遠い先読みを失敗位置へコピーしない。
- virtual kind の零幅 token（EOF など）は、Java の detached source と同じく CP0 の `@0:0` になる。Rust も既存の `TokenSource::Generated { anchor: 0 }` で同じ値を返す。
- 原 CST は **context に commit された token の originalChildren**、reduced CST はその deep copy に
  Java `MetaFunctionTokenReducer` を実際に適用した forest。Rust 側も対応する構造を観測する。
  `LineTerminatorParser` / `EndOfLineParser` と対応 Choice のラベルだけを `Line` に統一し、
  token kind・CP範囲・元テキスト・空 token・子の順序を保持する。返却 Parsed と committed Choice は別である。

memo OFF / SAFE_FAILURES × AUTO / DETAILED / DETAILED_ON_FAILURE で両版を独立に期待値へ照合する。
各行を二重の外側 transaction 内で呼び、両方を rollback して同一 parser をもう一度呼ぶ。
cursor、reset flag、token 数、capture の非漏出、scope 深さ/宣言、ユーザー状態、既存 token metadata の復元を検査する。
対象の4型自体には capture / scope / user state / metadata を書く演算はないので、試行中の値を変更しないことも検査する。
登録した所有値を外側で変更し、target 成功後にも失敗後にも元へ戻ることを検査する。
未登録の任意外部 Object の副作用は契約に含まれない。

`transactions.tsv` の32行では通常の Chain、choice敗者、PEG先読み、
`Chain(subject, NUL)` の実際の外側失敗後の同一 subject 再試行を比較する。
`lexical.tsv` は非BMP・CR/LF/CRLF途中・EOFを含む全CP位置で、
Java `LexicalTokenParser` と Rust `Expr::Lexical` の BOL / BOF / EOL を実行する。
語彙 token は零幅 token を作るが、Classic の StartOfLine/StartOfSource は作らないため adapter で分ける。
新規 UBNF 生成経路は追加せず、既存の declarative-tokens / contextual-lexing conformance も関連検証として実行する。

## 遅延診断モードと Java の既知欠陥 #861

直接 context を呼ぶ AUTO は DETAILED に解決される。DETAILED_ON_FAILURE の直接呼出しは再解析せず、診断を記録しない
（両版とも farthest=0、expected なし）。

Java Choice の FIRST 候補除外は、候補の transaction を `begin` する**前**の cursor で lookahead を取る。
親の `resetMatchedWithConsumed=true` では `begin` が matched を consumed に戻すので、実際に読む位置と除外に使う位置がずれる。
その結果、DETAILED_ON_FAILURE のときだけ成功するはずの候補が除かれ、受理が変わる。
例: `"x\n"`、consumed=1 / matched=2 / reset=true / kind=matchOnly は、DETAILED では LF で成功し、DETAILED_ON_FAILURE では失敗する。
これは「成功時の結果を変えない」という除外の前提（`ChoiceInterface` / `ParseContext` のコメント）に反する Java の欠陥で、#861 で追跡する。
`-Dunlaxer.parser.firstSetExclusion.disabled=true` では DETAILED_ON_FAILURE も成功する。

- `cases.tsv` の末尾列 `java-deferred-defect`（30行）は、この欠陥の Java 現状値。Java のテストだけが DETAILED_ON_FAILURE でこの列を使う。
  #861 を直したら列を除き、Java も主の期待値に戻す。
- Rust の Classic Choice は token mode で除外を行わない。Rust は欠陥を移植せず、全モードで主の期待値（Java DETAILED と同じ結果）に照合する。
  利用者が書く経路は `line_end()` の Choice そのままで、診断モードで分岐する adapter は置かない。
- ledger では `java-first-exclusion-reset` を未修正の差として記録する。

## EmptyLine / WildCardLine の未完了範囲

3案を両言語で実行し、全観測の同等性を主張しない。

1. `BOL; repeat_java(not_ahead(line_end); body); line_end` は通常 consumed の受理と両cursorが揃う。
   しかし反復 body ごとに追加の Chain node が原 CST に残る。`compositions.tsv` は
   空/CR/LF/CRLF/空白/非BMP/EOF/拒否の10行について、その構造差を個別に固定する。
   成功した terminator の内側 Parsed 原 token と、kind で収集済みの外側 CST は区別する。
2. `BOL; repeat_java(choice(match_only(line_end), body)); line_end` では match-only の終端で停止しない。
   `repeat-choice.tsv` の `"\n\nx"` は Java WildCardLine が consumed=0/matched=1 で止まる一方、
   候補式は matched=3 まで進む。EmptyLine は Java成功に対して候補式が失敗する。
3. `BOL; repeat_java(char_class); line_end` は、終端の CR/LF を body の文字クラスから外す（Space は ` \t\v\f`、WildCard は CR/LF 以外）。
   terminator を反復の前に試さない、既存 API で書ける最も近い形。`char-class.tsv` の10行で両言語を実行する。
   consumed / matchOnly の受理・両cursor・原CST（parser ラベルの対応付けのみ）は Java と一致し、案2の `"\n\nx"` の差も消える。
   しかし Java は consumed 系の kind で成功した match-only terminator を commit し、matched cursor を改行の後へ進める。
   virtualTokenConsumed では後続の行終端がその位置を読むので、Java の WildCardLine は `"\r\n"` を C=0/M=0 の EOF で受理し、
   `"😀x\r\n?"` を拒否する。候補式はそれぞれ C=2/M=2、C=4/M=4 で受理する。
   terminator の試行は失敗診断の最遠位置にも残る（例: EmptyLine `"\n"` は Java 1、候補式 0）。

任意 terminator の「成功時に反復を止め、親kindに応じてその試行をcommit/rollbackする」契約を
Repeat の body 成功/失敗へ単純置換できない。独自の反復 runtime をこの issue で重複追加せず、
#846 の実装でこれら2型の原CST・返却値・診断・全token kind/invertを共通検証する。
対応表では2型だけを未完了のまま残し、PR は `Refs #848`。#848 / #716 を close しない。

## 実行

- Java: `LineBoundaryConformanceTest` の全7メソッド（Rust連携には `-DrustConformance=true`）。
- Rust: `cargo test -p unlaxer-runtime --test line_boundary`。JUnit からも同じ `probe.rs` を rustc で実コンパイル/実行する。
- CI の Cross-language fixtures と `.github/optin-test-contracts.json` の両方に登録。
- 原CSTの縮約はこの4型とテストの外側 Chain/Choice に限定した移植例。一般の tag/reducer 契約は #851 で追跡する。
