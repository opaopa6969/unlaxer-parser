# Changelog

All notable changes to unlaxer-parser are documented here.
Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
Versions are published to Maven Central (`org.unlaxer:unlaxer-common`, `org.unlaxer:unlaxer-dsl`).

---

## [Unreleased]

### Changed

公開済み 3.3.0 以降の開発版では互換性に注意し、生成物と利用側を更新する順序は [移行・再生成の手順](docs/migration-after-3.3.0.md) を参照してください (#769)。

- UBNF の `REGEX(...)` と旧 adapter binding を `E-REGEX-RETIRED` で拒否し、Java の `RegexTokenParser` 実行 API を削除した (PR #617、issue #612)。宣言的な `token NAME ::= expression;` へ置き換えて再生成する。[移行例と PEG の意味](docs/regex-retirement.md)。
- enum の AST field を scalar / optional / list とも宣言 enum 型へ揃え、enum-only root に wrapper を生成する。非 ASCII 定数名を共通の scalar escape に変更（例: `日本` → `U65E5__U672C_`）したため、再生成時に型・定数参照・root の利用箇所を更新する (PR #557)。混在 enum/Text/Node は Java `EnumValue` と Rust `AstValue` の enum variant を使い、網羅的な分岐も更新する (PR #563)。[enum の移行](docs/enum-values.md#migration-and-staged-scope)、[混在値](docs/enum-values.md#mixed-values)。
- Rust の直接の `NumberParser` / `DigitParser` capture を `String` から `i32` へ変更し、optional / list・`Semantics` 引数・canonical JSON も整数へ揃えた (PR #575、issue #566)。Java の field 型は従来どおり。再生成後の文字列変換を外し、低水準 IR の `Kind::Integer` を分岐に加える。[型・失敗条件・移行](docs/numeric-captures.md#compatibility-and-verification)。
- Java の `@commonField` accessor を実際の member の型・cardinality に揃えた (PR #570)。従来の `String` 前提の利用箇所を更新する。[型の契約](docs/common-fields.md)、[利用側の移行](docs/migration-after-3.3.0.md)。
- Java の自動 `@eval` literal / passthrough は unchecked cast に代わり `convertValue(Object)` の実装を要求する (PR #577)。再生成する evaluator に明示的な変換を実装する。Rust の自動評価も必要な変換・条件判定 hook を利用側が実装する。[値と移行](docs/evaluation-strategies.md#values-and-migration)。
- Rust の公開 IR `Rule` に `type_constraints` を追加した (PR #584)。直接構築する既存 consumer は `type_constraints: vec![]` を指定する。Java の旧 convenience constructor は保持する。[IR の移行](docs/typeof-constraints.md#generated-ir-and-verification)。
- Rust の引数なし AST family / alias は空の親 variant に代わり具体的な子 AST を返す (PR #597)。旧親 variant / `eval_family` の参照を再生成した具体子へ移す。[互換性と移行](docs/family-projection.md)。結合演算子の共有 literal spine は `NullableNode` を追加し、該当 operand / evaluator 引数を nullable Node 型へ変える (PR #605)。[型と移行](docs/associative-projection.md)。
- 生成 `CI('word')` の比較を Java/Rust 共通の Unicode 17.0.0 simple upper→lower に固定した (PR #534)。JDK に依存した受理ペアの期待値は再生成後に確認する。手書き Java `IgnoreCaseWordParser` は維持する。[生成 CI の契約](spec-corpus/case-insensitive/README-generated.md)、[移行手順](docs/migration-after-3.3.0.md)。
- Java も未変換の左再帰を警告だけで生成せず `E-LEFT-RECURSION` で拒否する (PR #600)。消費する基底と反復へ書き換える。[移行例](docs/left-recursion.md)。利用可能な final Java token class の継承も、生成前に `E-JAVA-TOKEN-FINAL` で拒否する (PR #645)。[置換する binding の条件](docs/java-token-bindings.md)。
- 一般規則の隣接した `CAPTURE(` / `SAME_AS(` / `CAPTURE_SCOPE(` と `COMMIT(` / `LABEL(` / `CATCH(` を組込み呼出しとして扱う (PR #641、#710)。既存の規則参照＋group の意味を保つ場合は名前と `(` の間に空白を入れる。[owned capture 構文](docs/general-owned-capture-syntax.md)、[cut 構文](docs/cut-and-labeled-failures.md)、[移行一覧](docs/migration-after-3.3.0.md)。
- 通常の `{0}` / `Repeat(0,0)` は子・terminator を実行しない (PR #611)。Classic の nullable repeat と separated list は、Rust 生成でも Java と同じく進捗しない成功を一度数えて停止する (PR #622、#623)。従来のゼロ回での副作用や no-progress 拒否を前提にした利用箇所を見直す。[ゼロ回と明示的な否定検査](docs/zero-repetition.md)、[反復](docs/nullable-repetition.md)、[区切り付き](docs/nullable-separation.md)、[移行](docs/migration-after-3.3.0.md)。
- Java memo の `PositionKey` に matched cursor の reset policy を追加した (PR #625)。旧5引数 constructor は reset=true として維持し、固定した record pattern / reflection は更新する。[移行](docs/memo-reset-policy.md)。Java/Rust の成功・失敗 memo に件数・payload の有限予算を適用し、失敗合計の既定は32 MiB、成功合計は16 MiBとした (PR #656、#661、#675)。memo 自体は既定 OFF、満杯では通常解析へ戻る。[失敗の設定・統計変更](docs/failure-memo-budget.md)、[成功の設定](docs/success-memo-budget.md)。
- Java/Rust の公開 CST mapping は不正な graph / span を `E-MAPPING-CST`、投影の資源上限を `E-MAPPING-LIMIT` で拒否する (PR #650、#682)。mapper を再生成し、手書き CST と失敗処理を確認する。[構造・上限](docs/public-mapper-cst.md)、[移行](docs/migration-after-3.3.0.md)。
- Java observer の失敗後は公開状態を復元して context の再利用を拒否する (PR #589)。close 後に新しい context で再試行する。通常 callback 例外の同 context 再試行と、資源失敗の fatal な扱いを区別する (PR #651、#636)。[observer](docs/parser-observer-failures.md)、[通常例外](docs/ordinary-parser-exceptions.md)、[資源失敗](docs/resource-abort-publication.md)。
- Rust Classic の atomic token 内部の trivia、Sequence の原文・CP 範囲、先行 matched cursor の診断を Java の契約へ揃えた (PR #607、#610、#658)。原文・位置を固定する利用側の期待値を見直す。[atomic](docs/classic-atomic-trivia.md)、[Sequence](docs/classic-sequence-trivia.md)、[診断](docs/prior-matched-diagnostics.md)、[移行](docs/migration-after-3.3.0.md)。下記の先読み capture・失敗位置修正も同じ移行一覧に含む (#706、#717、#729、#734)。
- Java/Rust LSP は初期化前の通常 request と重複 `initialize` を拒否する (PR #553)。再設定は明示的な `unlaxer/configure` へ移す。[移行例](docs/classic-lsp-lifecycle.md#migration-from-repeated-initialize)。TinyCalc / 新規 scaffold 拡張の最低 VS Code 版を1.97.2へ変更した (PR #689)。[editor の更新・起動手順](docs/rust-vscode-launch.md)。
- Java API impact は合法な overload を保持し、同名 method 群の `subject` に引数型列を含める (PR #579)。レポートの利用側は名前だけで一意と仮定しない。[差分の規則](docs/api-impact-report.md#差分の規則)、[移行](docs/migration-after-3.3.0.md)。
- 学習用 `unlaxer-learning` は未対応の `@enum` / `@typeof` を compile 時に明示拒否する (PR #581、#584)。対応済みの Java/Rust 生成 CLI・生成 Playground を使う。[利用境界](docs/enum-values.md#browser-lesson-boundary)、[移行](docs/migration-after-3.3.0.md)。

- global `@comment: { line: '//' }` を Java 生成器・Java-host Rust 生成器・native Rust 生成器で同じ意味にした (#712)。`@whitespace: javaStyle` と併記した場合は何も足さず、それ以外では全体の既定を継承する rule が Java の CPPComment（`//` から CR / LF / EOF の手前まで）だけを読み飛ばす。3.3.0 の利用者から見える変化は次のとおり。[契約](docs/rule-trivia.md#global-comment712)。
  - `@comment: { line: '//' }` 以外の形（`line` が `'//'` 以外、`line` が無い、余分な key や重複した key、スカラー値）、`@comment` の重複、名前付きの全体 `@whitespace` との併記は、`E-COMMENT-SETTING` / `E-COMMENT-WHITESPACE` で位置付きで拒否する。3.3.0 の Java はこれらを黙って生成していたので、生成できていた文法が通らなくなる。ただし全体の `@whitespace: javaStyle` と併記した場合は、3.3.0 が javaStyle 単独と同じに生成していたため拒否せず、生成物も変えずに警告 `W-COMMENT-IGNORED`（Java と Rust で同じ code と span）を出す。
  - `@comment` に規則ごとの `@whitespace` / `@interleave` を併記した文法では、3.3.0 の Java が継承 rule にも空白・block comment を読み飛ばさせていたのをやめたので、受理が変わる。例: `@whitespace R0 ::= 'a' R1 'd'; R1 ::= 'b' 'c';` に `a b c d` を与えると、3.3.0 は受理し、この版は位置 2 の syntax error になる。受理が変わらない入力でも、子 rule の node span と AST の source span が変わることがある（例: `R1[2,6]` → `R1[1,6]`）。
  - javaStyle も規則ごとの trivia も無い `@comment` だけの文法では、生成される public class の `<Grammar>LazyChain` と `<Grammar>SpaceDelimitor` が無くなり、`<Grammar>LineCommentLazyChain` と `<Grammar>LineCommentDelimitor` に置き換わる。これらを参照する手書きコードは compile できなくなり、CST に現れる parser class も変わる。javaStyle と `line: '//'` を併記する文法（tinyexpression P4 等）の Java 生成物は変わらない。
  - `@autoCommit` の `@comment` 対応は後述の #745 で追加した。

- UBNF の空 literal `''` を Rust 生成器でも Java と同じ意味で生成する (#713)。`unlaxer check --target rust` は `''` を含む文法を `P-EMPTY-LITERAL` で blocked とせず passed とし、通常の文法では Java の `WordParser("")`（常に失敗し、期待候補 `''` を記録する）、`@tokenStream` / `@lexicalContext` の文法では `Lexing.LiteralParser("")`（幅0で成功）として、Java host・native host の両方から生成する。runtime に `Expr::JavaEmptyWord` / `Expr::JavaEmptyLiteral` を追加し、手書きの `Expr::literal("")` の PEG 成功は変えない。cut・ラベル付き失敗・rule stack・`@autoCommit`・#706 の frontier 補正との組み合わせを含む19文法80入力を、Java 生成 parser と両 host の Rust を別々に compile・実行して比較する。[契約](docs/empty-literal.md)。
- 同一ファイルの複数 grammar を両 Rust host で独立生成・check できるようにした (#714)。単一 grammar の出力は維持し、複数では `grammar_<ASCII小文字名>/` と root `mod.rs` を生成する。[契約](docs/multiple-grammars.md)。
- Java 向け生成は package と小文字化した生成クラス名の組が衝突する場合を拒否し、report / manifest に検証エラーを出す (#714)。別 package の同名 grammar は従来どおり受理する。同一 package で完全同名、または大小文字だけ違うクラスは、case-insensitive な FS での上書きを防ぐため拒否する。
- Java 向け生成も全 generator の生成をメモリ内で終えてから書き出す (#714)。複数 grammar の後続エラーに加え、単一 grammar でも後続 generator が例外を投げた場合は生成ファイルを一つも書かない。
- 複数 grammar の生成失敗に grammar 名と元ソース CP 範囲を付け、Rust の root `mod.rs` は全子 module の後に書く。I/O エラーには失敗パスを付ける (#714)。Java-host Rust の出力先の祖先 symlink は従来どおり許容し、生成する子ディレクトリ・ファイルと明示した出力先自体の symlink は拒否する。native の祖先拒否は既存差として残す。OS 書き込み失敗時に既に書いた子ファイルを巻き戻す保証はしない。
- `E-RAISING-REPETITION` の nullable 判定で、通常の文法の空 literal `''`（Java の `WordParser("")`、常に失敗）を nullable とみなさないようにした (#749)。`{ COMMIT('') }`・`{ COMMIT('', 'm') }`・捕まえない `{ LABEL(L, '') }` は、Java の `GrammarValidator` / 生成器、native generator、両 portability check で位置付きで拒否する。`@tokenStream` / `@lexicalContext` の文法（`Lexing.LiteralParser("")`、幅0で成功）では従来どおり nullable で、拒否しない。Java と Rust の判定は lowering と同じ条件で揃えた。[契約](docs/empty-literal.md)。
- #729 の Parsed 原文の追跡を、owned capture（CAPTURE / SAME_AS / CaptureScope、call-by-value の脱糖後を含む）がある文法・式だけに限定した。未使用時は Parsed 原文を生成・伝搬せず、成功 memo にも保存しない。共有文法ごとの判定結果は Weak 参照で最大64件キャッシュし、小入力でも毎 parse の全 rule 走査を避ける。入れ子の呼び出しでは外側の追跡を引き継ぎ、終了時に復元する。原文の追加情報は既存の boxed source に移し、Rust の `Fragment` を x86_64 で 64 → 56 B（master と同サイズ）へ戻した。深さ255の非 Classic CAPTURE 再帰について、Rust 1.85.0・release（opt-level=3、debuginfo=1）で最小要求スタックを16 B刻み・各境界3回確認すると、A' `ae11e746` 747,904 B、修正前 `def95dc8` 784,640 B、修正後 768,256 B。A' 比の増加は約4.9%から約2.7%へ縮小したが、残差はある。再帰上限は変更していない。
- 生成に作用しない Simple annotation（`@custom` など、`@nestedCaptures`・`@autoCommit` 以外の `@name`）を、Java・Java-host Rust・native Rust のすべてで受理し、生成器は名前を使わない (#715)。生成物は、`@autoCommit` が解析しない文法では注釈が無い場合と byte 一致する。`@autoCommit` の解析は従来どおりこの注釈を持つ rule を opaque として外すので、`@autoCommit` を使う文法では挿入位置が変わり得て、警告文が `... is not used by the generator, but it excludes rule R from @autoCommit analysis` とそれを示す。3.3.0 の Java の挙動（受理して無視、parser IR には名前を残す）は変えず、native の `unlaxer check` は `P-ANNOTATION` で blocked とせず passed とする。注釈ごとに警告 `W-ANNOTATION-IGNORED`（span は注釈、近い既知名があれば `did you mean @leftAssoc?` の候補）を両 host で同じ code・位置・文字列で出す。native の frontend は Java に合わせ、`@ leftAssoc`（`@` の後に空白）を `@leftAssoc` として適用せず Simple annotation に、`@mapping`（引数が必要な名前を `(` なしで）を構文エラーにせず Simple annotation にする。生成物を変えない警告（`W-ANNOTATION-IGNORED`・`W-AUTO-COMMIT-SKIPPED`）を `GenerationWarnings.collect` / `unlaxer_generator::warnings::collect` に集め、native `unlaxer generate` と Java-host `generate --target rust` が stderr に `warning: CP <start>..<end>: <message>` を出し、新しい `--strict` / `--fail-on warning` で終了コード 5 として何も書かずに失敗する。Java の flag CLI は両方の警告を検証 warning（category `GENERAL`）として報告し（`--strict` で失敗）、その message を Rust CLI と同じ `warning: CP <start>..<end>: <message>` の 1 行にする（text 表示と JSON の `issues[].message` の両方。既存の警告の表示は変えない）。`GrammarValidator.validate` 自体はこの 2 つを返さない（書き換え後の木では位置と `@autoCommit` との関係が分からないため）。[契約](docs/simple-annotations.md)。
- UBNF 文法の `@precedence(level=N)`、量指定子 `{n}` / `{n,m}` / `{n,}`、`ADAPTER(..., version=N)` の数字を仕様どおり ASCII `0`–`9` に限定し、Java と Rust native の受理を揃えた (#811)。3.3.0 の Java は全角などの Unicode 数字も受理していたため、該当する `.ubnf` は ASCII 数字へ書き換える必要がある。公開クラス `org.unlaxer.dsl.bootstrap.UBNFParsers.DigitParser` も ASCII の数字だけに一致する。生成 parser が入力テキストを読む runtime の数字 token / combinator の挙動は変えない。

- 生成した Rust parser の失敗位置と rule stack を Java に揃えた (#706)。UBNF の正の `LOOKAHEAD`・`EMPTY`・`UNTIL` が matched cursor だけを進めた後の失敗は、Java と同じく matched の到達位置（たとえば `R0 ::= 'a' AHEAD 'é'` に `ax` で CP1 → CP2）で報告され、その後に始まる rule の開始位置も同じく後ろへ進む。受理・cursor・CST・AST は変わらない。式単位の入口（`ParseContext::parse` など）と token stream の中は従来どおり。[契約](docs/prior-matched-diagnostics.md#grammar-呼び出しの-frontier706)。

### Added

- cut とラベル付き失敗を Java/Rust runtime および UBNF の `COMMIT` / `LABEL` / `CATCH` に追加 (PR #707、#710、issue #700)。確定エラーは recovery 境界へ、ラベル付き失敗は指定した CATCH へ伝わる。[契約](docs/cut-and-labeled-failures.md)。
- 一階の引数付き規則を両 frontend で生成前に単相化し、`F(=x, y)` の値渡し引数を `CAPTURE_SCOPE` / `CAPTURE` / `SAME_AS` へ展開する (PR #708、#711、issue #701、#704)。[引数付き規則](docs/parameterized-rules.md)、[値渡し](docs/call-by-value-arguments.md)。
- FIRST 集合と外側の文脈による cut 位置の lint、規則単位で opt-in する `@autoCommit` を追加。文法全体の `@whitespace: javaStyle` にも対応する (PR #724、#731、issue #702、#728)。対象外の警告は lint API から取得する。[条件と制限](docs/auto-commit.md)。
- `@autoCommit` を global `@comment: { line: '//' }` に対応させた (#745)。javaStyle 併記は従来と同じ開始集合、comment 単独・none 併記は `/` だけを trivia の開始として解析する。改行終端の先読みと COMMIT の消費位置を区別し、Java・両 Rust host で挿入・警告・受理集合・確定エラー位置を共通 corpus と差分 fuzz で検証する。対象外の comment 設定・規則 trivia は具体的な `W-AUTO-COMMIT-SKIPPED` reason を返す。[証明と制限](docs/auto-commit.md#行コメントのある文法745)。
- DETAILED 診断の最終エラーに、外側からの規則名・開始 CP を保持する rule stack を追加 (PR #723、issue #703)。Java/Rust runtime API と両 LSP の `relatedInformation` / `data.ruleStack` で取得できる。[契約](docs/rule-stack-diagnostics.md)。
- seed 付き文法・入力生成、差分比較、失敗例の縮小と corpus 化による property-based テストを追加 (PR #709、issue #705)。Java/Rust の受理・cursor・CST・AST・診断と設定間の不変条件、両 host の Rust 生成物を検証する。[seed の再現手順](docs/property-based-testing.md)。
- Java 公開 parser API の基準 revision 付き [全API対応表](docs/java-api-rust-parity.md)、JDK 構文木による型・static API の列挙と更新漏れ検査、Rust の利用経路・共通検証の不足を追跡する子 issue を追加 (#716)。runtime の挙動は変更しない。
- 全API対応表の行を指す根拠に anchor（行頭の宣言文字列、必要なら宣言 scope）を持たせ、参照先の行の挿入・削除・宣言の改名でずれた根拠を検査で拒否する (#859)。`--remap` は anchor が一意に決まる行へ行番号だけを付け替え、見つからない・曖昧・形式不正の根拠があるか、付け替え後の検査（schema・公開 API の一致を含む）が一つでも失敗すれば JSON も Markdown も書き換えない。441 宣言の判定と根拠は変えない。
- PBT に値引数・owned capture・先読み・memo、コメント後の frontier / rule stack、autoCommit の nullable / raising 検査、COMMIT / LABEL / CATCH の小さな組み合わせを追加 (#770)。独立した生成モードで既存 seed 列を保ち、Java/Rust の既存 oracle と手数上限で比較する。構文の出現・受理と失敗・memo 再生を検査する。
- Rust の `CI`、transactional `NonOrdered`、Java の明示 PEG assertion `PegLookahead` を追加 (PR #534、#551、#560)。[CI](spec-corpus/case-insensitive/README.md)、[NonOrdered](docs/nonordered-runtime.md)、[PEG assertion](docs/peg-assertions.md)。
- Rust の一般 parser / custom parser に4種の Classic token kind・invert・stopper を伝播し、Tiny の CodeStart / CodeEnd / LongCodeBlock へ接続した (PR #576、#608)。[token mode](docs/classic-token-propagation.md)、[Tiny](docs/tiny-classic-modes.md)。
- Java/Rust に原文を所有する capture / replay / scope と、一般 UBNF の `CAPTURE` / `SAME_AS` / `CAPTURE_SCOPE` を追加 (PR #603、#641)。明示的な Value / Optional / List 境界の immutable snapshot も取得できる (PR #620)。[owned capture](docs/owned-capture-replay.md)、[UBNF](docs/general-owned-capture-syntax.md)、[境界 snapshot](docs/nested-capture-boundaries.md)。
- 規則単位の `@nestedCaptures` による再帰型 Optional / List の生成を追加。text / i32 / mapped node / enum、共有 mapping と family accessor、owned capture との合成、separated list を扱う (PR #627、#638、#643、#648、#654)。[型と対応範囲](docs/typed-nested-captures.md)、[mapped 葉](docs/nested-mapped-leaves.md)、[共有 field](docs/nested-shared-fields.md)、[separated](docs/nested-separated-captures.md)。
- nested capture 内の混在 text / i32 / node / enum、浅い `@typeof` 比較、非再帰の透過 alias 経由の container を追加 (PR #659、#666、#699)。循環 helper や nested 所有規則自身の `@eval` 等は引き続き拒否する (PR #698)。[混在値](docs/nested-mixed-values.md)、[型比較](docs/nested-typeof.md)、[対応範囲表](docs/typed-nested-captures.md#nested-の対応範囲と拡張先)。
- Rust の typed enum / 混在 enum、`@commonField` family view、`@eval` 自動評価、mapping 時の `@typeof` を Java と共通の契約へ揃えた (PR #557、#563、#570、#575、#577、#584)。[enum](docs/enum-values.md)、[共通 field](docs/common-fields.md)、[評価](docs/evaluation-strategies.md)、[型制約](docs/typeof-constraints.md)。
- 再帰する透過 helper の値投影、Rust の preferred AST snapshot、具体 AST family と結合演算子の投影を追加 (PR #594、#595、#597、#605)。左結合の固定 literal および1段の Optional / Repeat delimiter にも対応する (PR #678、#681)。[再帰 helper](docs/recursive-helpers.md)、[preferred](docs/preferred-ast-snapshots.md)、[family](docs/family-projection.md)、[結合演算子](docs/associative-projection.md)。
- Java/Rust の上限付き snapshot cache、Rust の pure success / Classic token mode の memo 再生、rollback に対応する state version 付き failure memo を追加 (PR #565、#633、#640、#647)。Rust の pure success memo 再生は既定 OFF で、`ParseOptions::with_memoization(Memoization::SafeFailures)` を指定した opt-in。外部 callback を含む文法は既定で snapshot cache の対象外 (issue #613)。編集は全再解析し、unsafe callback や stateful success の暗黙再利用は行わない。[snapshot cache](docs/incremental-cache.md)、[pure success](docs/pure-success-memo.md)、[Classic](docs/classic-memo.md)、[state version](docs/stateful-failure-memo.md)。
- Java/Rust LSP の full semantic-token provider と、TinyCalc / `unlaxer init` 拡張での Java/Rust stdio backend 選択を追加 (PR #550、#556)。[semantic tokens](docs/classic-rust-lsp.md)、[起動・再設定](docs/rust-vscode-launch.md)。
- Java / Rust（native・Java-host）の既知差・未対応・条件付き skip を issue へ結ぶ [台帳](docs/parity-ledger.md) と JSON、host 別 corpus（JSON・JSON Lines・TSV・CSV・YAML、host 列の行）とテスト内の無条件 skip・差分表・除外集合の登録漏れ、文書の不一致を検出する既存 CI の検査を追加 (#771)。

### Fixed

- Rust の `@f` が Java の LazyChain と同じく子の評価前に matched cursor の reset を解除し、`@f` が失敗して rollback したあともその解除を残すよう修正し、MatchOnly + reset=true の繰り返しを包む CAPTURE の受理・cursor・原文を Java に揃えた (#762)。
- Rust の token-stream / lexical-context 文法の非空 literal の Classic 反転を Java の幅0 assertion に揃え、EOF・lexical goal・先読み後の cursor・CST・失敗位置を両 Rust host の共通 corpus で固定した (#768)。同じ区別により、これらの文法では Classic の非反転照合も lexical goal を通し、optional / repeat 内の literal の失敗で matched cursor を戻さず、token trace の label が `LexicalTokenParser` になる。Java の既存挙動は変更しない。
- 量指定子 `{n}` / `{n,m}` / `{n,}` の整数 overflow を Java / Java-host Rust / native Rust の検証・生成で `E-BOUNDED-OVERFLOW` と元ソース CP 範囲として報告し、公開済み AST の整数型 API を維持した。境界値・非 BMP・未使用/ゼロ回反復を共通 fixture で検証し、仕様 corpus の既知差を削除 (#783)。
- `@precedence` の level overflow を Java / Java-host Rust / native Rust 共通の `E-PRECEDENCE-OVERFLOW` と元ソース CP 範囲で診断し、逆順境界と量指定子 overflow が併存する場合の診断順・`E-BOUNDED-INVALID` を Java に揃えた。overflow した level は優先度の順序・結合性の比較から除き、Parser IR export と BNF 変換も sentinel を出力せず同じ診断で拒否する。共通 corpus で境界値・先頭ゼロ・長い数字列・入れ子・未使用ルールを検証 (#802)。
- UBNF の量指定子 `{n}` / `{n,m}` / `{n,}` 内の全トークン間で通常の空白・`//` 行コメントを許し、Java / Java-host Rust の受理を Rust native に揃えた (#818)。3.3.0 の Java が拒否していた `{2, 3}` を受理し、コメント中の数字やカンマを境界値に混入させない。数字の途中を分割する `{1 0}` は両 frontend とも引き続き拒否する。
- Rust preferred AST の合成 capture を CST metadata 専用の `Expr::MetadataCapture` に分離し、通常 parser・Java と Classic の成否・cursor・原文・AST が異なる問題を修正 (#788)。Java/Rust 両生成ホストに適用し、ユーザーの `@f` の原文検査と reset 契約 (#762) は維持する。
- Rust の `@f` が生成・投影原文を Choice／rule 経由や全入力解析で受理する問題を修正し、Java と同じ入力由来検査・失敗位置を保つ (#761)。
- CI の memo budget 適合性の証跡 gate が、libtest の進捗行（`test X ... `）と Rust の oracle の `BUDGET` 行が同じ行に混ざると 600 件を 599 件と数えて落ちる問題を修正 (#789)。Rust の oracle は stdout ではなく環境変数 `UNLAXER_TEST_FAILURE_BUDGET_EVIDENCE` / `UNLAXER_TEST_SUCCESS_BUDGET_EVIDENCE` のファイルへ全行をまとめて書き、gate は `scripts/check-memo-budget-evidence.py` で Java / Rust の TSV の header・列数・値・case id・キーの一意性と全組み合わせ・600 行を検査する。キーの値は証跡から推定せず Java/Rust の固定した全600構成と照合する。再実行開始時に古い TSV を消し、Rust の証跡は全プロセス成功後に公開する。コンパイル・テスト失敗時のログも artifact `memo-budget-evidence` に残す。600 ケースと Java / Rust の oracle は変えていない。
- Rust の LSP の message と `ParseError` / `ParseDiagnostic` の `Display` で、空の期待候補（通常の文法の `''`）を Java の生成 LSP と同じ `''` と表示する (#750)。以前は `Expected  or b` のように候補が見えなかった。canonical JSON・`data.expectedTokens` の値 `""`、空でない候補の表示、LSP の range・code は変えない。表示用に `unlaxer_runtime::display_expected` を追加した。[表示の対応](docs/empty-literal.md#人が読む表示750)。
- Java host の Rust 生成で、整数 field の判定を native と同じ capture の規則へ統一し、無関係な規則を含む Java AST 型推論が作業量上限に達して生成に失敗する差を修正 (#758)。`@typeof` の制約の capture も同じ規則で判定する。不要な固定点呼び出しを省き、作業量上限・Java target の型推論・生成 parser/runtime は変更しない。再現例と filler 数の変種で両 host の生成物 5 ファイルが byte 一致することを検証。
- 生成した Rust parser の Number token が、不完全な指数（`2e`・`2e+`）で Java の `NumberParser` と同じく指数記号・符号の後を失敗位置として記録するよう修正 (#717)。`R0 ::= NUM 'a'` に `2e` の診断は CP1 → CP2（`2e+` は CP3）。受理と消費は従来どおり仮数部まで。共通 oracle `conformance/number-token/cases.tsv` を追加し、PBT の既知差 `number-incomplete-exponent` を削除。
- Rust native Number の仮数部・小数点・指数の内部失敗を、Java の `NumberParser` と同じ位置・候補・記録順で記録する (#752)。`Number.not_ahead()` に `2a` の診断は CP0 → CP1、`1e1e` は CP0 → CP3。Number 全体の失敗と不完全な指数の期待候補は、従来の `number` から Java と同じ名前（`SignParser`・`DigitParser`・`OneOrMore`・`.`・`Chain`・`NumberParser` など）に変わる。公開 `ParseError.expected` の並び（整列・重複除去）は変えない。記録は Detailed のときだけで、遅延診断（full-input API の Auto / DetailedOnFailure）の成功パスは変わらない。Detailed の成功パスは Number ごとに数件の記録が増える（[測定](conformance/number-token/README.md#性能)、#795）。
- provider transport の通常拒否と意図した timeout の予算を Java/Rust 共通 fixture で分離し、正常応答付き stderr の上限超過と stdout/stderr のちょうど 4MiB の受理を検証する (#721)。製品の timeout 既定値は変更しない。
- Rust の非 Classic 経路の CAPTURE が、Java と同じ子の root token の原文を保存するよう修正 (#729)。直接の LOOKAHEAD の値を保持し、consumed の Chain・EMPTY・UNTIL の先読み部分は値に含めない。SAME_AS は保存した値を consumed cursor から再照合する。入口のモード選択・AST field capture・CST は従来どおり。Classic 経路でも Choice／rule 参照が owned capture へ返す Parsed の原文を、CST に commit する原文と分離する。field capture と CaptureBoundary は commit 済み原文を使い、公開 `ParseMatch.span` も従来の値を保つ（先読み Choice の consumed span は `[0,0]`）。Optional／Recovery／CaptureBoundary が直接の先読み原文を集めないことを Java/Rust で検証した。field capture（`@f`）自身も子の Parsed 原文を破棄し、外側の owned capture へ伝えない。Classic・非 Classic・成功 memo 再生を共通 corpus と直接 API で固定した。
- CI の必須 opt-in を実 Surefire XML と Python unittest の skip / ゼロ実行検査で保証し、固定 revision の実 Tiny Catalog 証跡を保存する (#719)。
- 正の `LOOKAHEAD` の後に呼んだ rule が、中の trivia（global `@comment`・javaStyle・名前付き whitespace）や消費で matched cursor を下げてから失敗したとき、生成した Rust parser の診断位置が Java より小さかった問題を修正 (#753)。Java と同じく、失敗した rule の frame の開始 max(consumed, matched) を失敗位置として記録する（`a//c\nxy` で CP5 → CP7、`R1 ::= 'x' 'q'` の `axyz` で CP2 → CP4）。trivia の後の matched cursor は Java の transaction cursor のまま変えない。rule でない group・選択の frame は対象外（#793）。[説明](docs/prior-matched-diagnostics.md#失敗した-rule-frame-の開始753)。
- 正の `LOOKAHEAD` の後の group・選択・入れ子 group・回数指定の反復（`'x'{2}`）が失敗したとき、Rust の診断位置が Java より手前になる問題を修正 (#793)。既存 checkpoint の復元後に失敗した式の frame 開始を候補に加え、`axyz` は両版で syntax CP4 となる。matched cursor と成功時の挙動を維持し、Java と両 Rust 生成経路の6設定を共通 corpus で検証する。
- Rust の Classic 経路で、直接の正の LOOKAHEAD を AST field capture（`@f`）にした値が外側の CAPTURE に先読みの原文として渡る問題を修正 (#742)。Java の `@f` は LazyChain の collector なので、子の原文の TokenKind が現在の kind と違うときは Choice／rule と同じく現在の kind で集め直す。`CAPTURE(w, AHEAD @f) SAME_AS(w)` は入力 `x` で `w` が空文字列、context の両 cursor は CP0 になり、Java と一致する。
- Rust host の Rust 生成器で、再帰する未 mapping 規則の値の種類・個数を固定点から求め、反復・separated 内の preferred AST 用の深さと schema hash を Java host に揃えた (#730)。PBT seed 59 の生成物不一致を解消し、縮小した2文法を共有回帰 corpus に追加した。
- `W-COMMENT-IGNORED` を Java / Rust の共通警告収集 API と3経路の生成 CLI に統合し、同じ message・CP span、重複しない表示、`--strict` / `--fail-on warning` の終了コード 5 と無出力を揃えた。生成物は javaStyle 単独と同一のまま (#777)。`GrammarValidator.validate` と UBNF LSP の診断は #748 と同じくこの警告を返す（flag CLI だけが validator の行を捨て、収集 API の位置付きの行を一度表示する）。flag CLI の text 表示と JSON の `issues[]` では、この警告の `message` が subject（`comment`）から `warning: CP <start>..<end>: W-COMMENT-IGNORED: comment: ...` の 1 行に、`rule` が空から `comment` に、hint が `Remove this @comment setting (docs/rule-trivia.md).` に変わる。
- 両 Rust host の `check` が v2 import を含むファイルでも root ソース由来の診断の Unicode CP 範囲を保持し、別 grammar・別位置の同じ code/subject をまとめないよう修正 (#776)。import 由来・出典不明の診断は引き続き `span: null` とし、JSON schema と単一ファイルの契約は維持する。
- native Rust の `check` で複数の構造エラーが同居した際の優先順位を Java-host に揃えた (#825)。`@eval`・enum・embedding などより commonField schema のエラーを先に返し、その規則の CP span を保持する。bounded/precedence/nested と syntax/module/inventory の先行判定、単一エラー、JSON schema は維持する。
- Java-host の `check --target rust` が、nested leaf と通常 leaf の commonField 型不一致を生成前に `P-COMMONFIELD` として報告するよう修正 (#832)。Rust emitter と型判定を共有し、native と同じ規則の CP span・subject・終了コード 3 を返す。Java 向け生成と既存 corpus の受理・拒否は維持する。
- spec corpus の参照・mapping・whitespace・結合性/precedence・profile 診断を両 Rust host で Java validator の独立期待値に揃えた (#833, #834, #835)。対象コードを説明の先頭に付け、規則診断を root 欠如より先に返す。元入力を維持して既知差16件を解消し、check JSON と既存 CP 範囲は保持する。
- 両 Rust host の残る宣言診断を Java validator に揃えた (#843)。重複 interleave/scopeTree、precedence が1つある結合性の mapping 欠如・params の left/op/right 欠落・repeat 欠如・rightAssoc 非canonical、longestChoice 形状、import なし修飾参照に `E-*:` を付け、root 欠如・未定義参照より前の検査順を統一した。修飾参照の従来説明、単一エラーの CP 範囲・終了コード、check JSON、#825 / #833–#835 の優先順位と成功時生成物は維持する。
- UBNF LSP が生成警告を CLI と同じ `GenerationWarnings.collect` から取得し、`W-COMMENT-IGNORED`・`W-ANNOTATION-IGNORED` を同じ code・message・ソース範囲で一度だけ表示するよう修正 (#798)。CP span は既存の LSP 位置変換で UTF-16 の range に変換し、validator 由来の重複を除く。LSP は編集用に解析済みの snapshot を新しい `GenerationWarnings.collect(UBNFSourceSnapshot)` に渡し、キー入力ごとの再解析（文書あたり2回）を省く（`@autoCommit` を含みうる文書だけは CLI と同じく書いたままの形を解析し直す）。`GrammarValidator.validate` の戻り値は維持する。
- Rust native generator の debug ビルドが 2 MiB stack で深い `[ ]`・`( )?` を生成すると stack overflow する問題を修正 (#805)。ソース出力の書式化と lowering の透明な group を再帰の大きな frame から分離し、Java と同じ生成物と既存の深さ上限診断を維持する。frontend の構文解析も再帰する frame を小さくし、`CAPTURE(…)`・`CAPTURE_SCOPE(…)`・`COMMIT(…)`・`LABEL(…)`・`CATCH(…)` の 104 段以上、引数付き規則の呼出しの 126 段で起きていた stack overflow を解消した（受理する文法・AST・診断は不変）。上限付近の生成・診断を 2 MiB で固定し、両 host の全5ファイルを byte 比較する。[契約](docs/java-parser-limits.md)。
- PBT の harness が seed 81 で止まる問題を修正 (#739)。原因は memo なし PEG の指数的な探索で、`CAPTURE` / `SAME_AS` を含む相互再帰の文法は `SAFE_FAILURES` でも memo されず、Java の受理判定も Rust も 54 CP の予備の導出で数分以上返らなかった。harness は観測前に各入力を Java の `ParseOptions.DEFAULT` で 1 回解析して parser transaction 数を数え、100,000（`-Dpbt.stepBudget`）を超えた入力を両 host とも観測せず件数と入力を記録する。corpus は除外する入力を `overBudget` に過不足なく書く。縮小例を回帰 corpus に追加し、corpus と予備の選別の両方の経路を時間上限付きのテストで確かめる。子プロセスには標準入力をファイルから渡して timeout を起動時点から数え、timeout のときも進捗行から止まった入力を報告する。seed `1..320` は約 3 分・finding 0 で完走する。parser と runtime は変更していない。[説明](docs/property-based-testing.md#入力ごとの手数の上限739)。
- Java host の Rust 生成で、再帰する規則の値の形を native と同じく lowered IR 上の固定点で求め、構造解析の深さ 256 を共有するよう修正 (#740)。再帰する helper を括弧で包んだ文法（`H ::= 'n' R0 | 'y' (…H…)+ | 'a'`、separator 側の再帰も同じ）を 85〜122 段（separator 側は 84 段から）で `E-RECURSIVE-SHAPE` により拒否していたのをやめ、native と同一の生成物を出す。123 段からは両 host とも `structural analysis depth exceeds 256`。固定点は呼び出し元の深さを引き継がず 0 から数える（両 host）。このため native も、Java host が以前から受理していた文法（再帰する helper を `[ ]`・`{ }`・`CAPTURE`・`CAPTURE_SCOPE` で 62 段包んだもの、82 個の規則参照の列の先の再帰など）を受理するようになった。既存の生成物は変わらない。整数 field の判定で Java の型推論が深さの上限に達した場合だけ、native の `numeric_fields.rs` と同じ capture の規則を使う（作業量の上限と非収束は従来どおり失敗）。Java の Java 生成の上限は変えていない。UBNF frontend の入れ子の上限が Java（parser frame 1024、形により 126〜168 段）と Rust（本体 128 段）で異なることを契約として [docs/java-parser-limits.md](docs/java-parser-limits.md) に記載し、両方の境界をテストで固定した (#741)。
- Java の Java 生成が、深い括弧に包まれた再帰 helper を受理するよう修正 (#754)。固定点解析で単一選択肢の choice を追加の深さに数えず、Rust の preferred projection も同じ数え方に揃えた。深さ256・作業量100万の上限と値の計算は維持する。再現文法の84〜122段で Java parser/AST/mapper と両 Rust host の生成・解析結果を検証し、Java生成単体の126段成功・127段診断を cold な `-Xss1m -Xint` JVM でも固定した。[上限の契約](docs/java-parser-limits.md)。

- root choice の CST 比較を Java 3.3.0 の commit 済み root に揃え、`javaCst` の別期待値3件を削除した (#767)。Java の返却 `Parsed` は選択枝、生成 mapper と Rust `Tree` は commit 済み木を使うという API の区別を契約化し、両 Rust 生成経路・Classic・非 root choice の共通回帰を追加。Java の返却 `Parsed.getRootToken()` は最上位の choice（入れ子の choice rule を含む）を外した token なので、Classic のデータモデル文書（ja / en）と根拠文書に入口ごとの違いと commit 済み root の取り方を書いた。corpus の host 別期待値は診断表示（`javaDiagnostic` / `rustDiagnostic`）以外をテストが拒否する。公開 parser・生成物・Rust CST の利用者に見える振る舞いは変えない。[根拠](docs/root-choice-cst.md)。

- 共有 self-hosted runner の Rust 使用ジョブを `RUSTUP_TOOLCHAIN=1.85.0` に固定し、toolchain のインストール時に全体の既定値を変更しないよう修正 (#738)。Cargo と Java テストの子プロセスの `rustc` が並行ジョブの影響で異なる版になり、`E0514` で失敗する競合を防ぐ。
- Rust の lexing session 内で grammar を 2 段以上入れ子に呼ぶと、正の `LOOKAHEAD` 後の frontier 補正が復活する問題を修正 (#734)。session の範囲を入れ子へ引き継ぎ、何段でも consumed の失敗位置と rule frame 開始位置を保つ。session 外と shared call の子では従来どおり補正する。[契約](docs/rule-stack-diagnostics.md)。
- Java の `SAFE_FAILURES` で Choice の成功 memo を再生すると、CAPTURE の値と解析位置が memo OFF と変わる問題を修正 (#744)。Choice は miss のとき選んだ子の `Parsed` を返すが、hit では commit した Choice token を root にした `succeeded` の `Parsed` を返していたため、子が match-only の先読みだと `w` が `x` から空文字列になり、`stopped` を返す子は `succeeded` に変わって message も失われた。成功 entry に返した `Parsed` の token・status・message を同じ snapshot で保持し、hit でも同じ `Parsed` を返す。memo key は変えない。返す token の ID 配列（16 B + 4 B/件）、保持する message（64 B + 2 B/文字）、CST から外れた token の部分木を保存前に成功 memo の予算に計上するため、予算が飽和する入力では成功 entry・hit がわずかに減る（PR の計測文法で hit 9925 → 9864、2797 → 2792）。予算に収まらない entry は保存しない。[契約](docs/pure-success-memo.md)。
- Rust の `SAFE_FAILURES` で `(!Num 'x') | Num` に `-` / `+` を与えた際、負の先読み内の Number failure memo を再生せず期待候補の `negative lookahead` が欠ける問題を修正 (#794)。Java の Chain と同じく、Chain に当たる rule（Sequence・Number・Identifier・CAPTURE など、式の評価で reset を false にするもの）の matched cursor reset policy を memo lookup 前に false に揃える。失敗した rule の後の reset policy は memo OFF と同じに保つ。rule 深さの key・再帰上限・memo 予算は維持する。[共通 oracle](conformance/number-token/README.md)。
- Rust の通常経路で失敗した Chain が owner の matched cursor reset を元に戻していた差を修正 (#809)。Java と同じ false を memo OFF / SAFE_FAILURES の miss・hit 後に残し、rule 参照・rule 単位の空白/字句 context・`@scopeTree`/`@declares`・`@backref`・COMMIT / LABEL は Java と同じく透過させ、同じ context の後続 LOOKAHEAD / NEGATIVE_LOOKAHEAD / UNTIL / EMPTY・診断位置を揃える。候補や外側 transaction の rollback と資源中断の復元は維持する。[読み取り経路と共通 oracle](conformance/chain-reset-after-failure/README.md)。
- Rust の name scope / predicate 成功後に子の Chain の reset=false が親へ漏れ、Classic の rule 参照・透過 wrapper（手組みの `StopConsume` / `StopInvert` を含む）失敗後には逆に false が失われる差を修正 (#814)。Java の frame 境界に揃え、同じ context の後続 `NonOrdered(LOOKAHEAD)` の成否・consumed・matched を memo OFF / SAFE_FAILURES、生成 Java と両 Rust host の共通 corpus で固定する。[実測と境界](conformance/chain-reset-after-failure/README.md#name-frame-と-classic-の実測-814)。
- Rust の値投影用 `ValueBoundary` / `TextValue` が失敗した Chain の owner reset=false を失う問題を修正 (#820)。生成 Java の実行を基準に、helper 本体と生成 mixed 候補の後続 `NonOrdered(LOOKAHEAD)` を照合する。失敗後の透過 wrapper と SAFE_FAILURES の lookup 前の判定を共有し、`StopConsume` / `StopInvert` も同じ一覧へ揃える。[生成位置と共通 oracle](conformance/chain-reset-after-failure/README.md#値投影境界の実測-820)。
- Rust の通常呼出でも、Java に対応する frame の開始時に owner reset=true なら matched を consumed へ戻すよう修正 (#829)。先読み後に同じ context で Choice 等を呼ぶときの受理・拒否を Java に揃え、Chain の begin 前の reset=false と回数0の反復の保持を維持する。生成 Java の共通 corpus、両 Rust host、通常 / Classic と memo・診断設定で検証する。
- Rust の rule を介さない frame の診断に reset 前の `max(consumed, matched)` を反映し、直接の MatchOnly / Not と LongestChoice / UniqueLongestChoice などを Java に揃える (#838)。負の先読み成功の到達位置は失敗候補と区別して外側の frame（MatchOnly の直下を含む）へ伝え、診断 OFF・memo hit・cursor / reset の契約を維持する。
- Rust の正の先読みから成功時の診断到達位置を記録し、nested mixed grammar と後続 MatchOnly の診断を Java に揃える (#826)。Classic capture の失敗 frame と UNTIL の失敗した終端候補も記録し、成功時の到達位置より実際の失敗候補を優先する。成否・両 cursor・reset・memo hit は維持する。

- Java frontend の annotation 名の prefix 誤認、`@eval` の名前付き引数と `@commonField` の comma 後 trivia の解析を修正 (PR #540)。左再帰の診断順と `@eval` パラメータの表示順も決定的にした (PR #726)。
- Java の token stream / lexical context 内の literal capture と、左結合の同じ parser class を使う literal の取得箇所を修正 (PR #672、#677)。[token stream](docs/token-stream.md)、[結合演算子](docs/associative-projection.md)。
- LSP の最遠構文失敗位置、Java 生成補完 literal の escaping、DAP 切断後の終了と transport 失敗通知、初期化未完了の LSP 起動失敗後の cleanup を修正 (PR #554、#561、#562、#691)。[LSP 診断](docs/classic-lsp-diagnostics.md)、[起動と終了](docs/rust-vscode-launch.md)。
- memo 再試行で資源の深さ制限を飛び越えないようにした (PR #639)。`E-RAISING-REPETITION` の検査を実際に捕まえる失敗種別へ揃え、Rust CATCH の循環検査での stack overflow を修正 (PR #727、#733)。[cut / label の契約](docs/cut-and-labeled-failures.md)。

## [3.3.0] - 2026-10-16

### Added

- Java/Rust の stateful な `@recovery` と回復診断・CST marker を追加 (#331)。失敗した試行の cursor/capture/scope/登録状態を戻して同期点へ進む。回復領域を含む木は通常の typed mapper が拒否し、評価値を捏造しない。[契約](docs/error-recovery.md)。
- `@tokenStream: enabled` で直接解析・trivia cache・遅延 token 化・事前 token 化を解析ごとに選択できる。名前付きの空白/コメント定義、module/package import、明示した terminal 集合を切り替える lexical context を Java/Rust の両生成経路に追加 (#385, #393, #377, #467)。既定は従来の直接解析。[token stream](docs/token-stream.md)、[lexical context](docs/contextual-lexing.md)。
- immutable parse bindings による adapter の合成と、同じ ParseContext・元入力上で明示登録した別 grammar の公開 entry を呼ぶ shared-input language calls を追加 (#369)。境界内を全文解析する `@embedded` と、現在位置から prefix を消費する共有入力呼出しを区別し、状態・元文書の CP span・子 CST の所有を保持する。[bindings](docs/parse-composition.md)、[embedded](docs/embedded-grammar-declarations.md)、[shared calls](docs/shared-language-calls.md)。
- URI/version/原文に束縛した snapshot、CP/UTF-16/UTF-8/LSP 変換、多段 source map、言語領域、partial CST/editor result と言語 query の転送を追加。未完成の埋込み領域、空領域、EOF でも所有元を検査し、古い snapshot の応答や曖昧な edit を拒否する。[source map](docs/source-maps-and-language-regions.md)、[query forwarding](docs/language-query-forwarding.md)。
- 共通の型・scope・symbol モデル、文書間 symbol index、型関係と上限付き呼出し推論、snapshot/dependency を管理する semantic queries、条件付きの解析 phase pipeline、別 JSON に記述する宣言的意味規則を Java/Rust に追加。補完・hover・定義・診断の provider と、原文 trivia を保持する検査付き rename/edit plan へ接続する。[意味モデル](docs/semantic-model.md)、[型推論](docs/type-system.md)、[query](docs/semantic-queries.md)、[意味規則](docs/declarative-semantics.md)、[edit](docs/source-preserving-edits.md)。
- UBNF package の builtin/local/HTTPS 依存、exact version・SHA-256・推移依存を固定する manifest/lock/cache、語彙の出典表示を Java/native Rust CLI に追加 (#368, #401, #407, #412)。取得は明示した `deps resolve` だけで行い、通常の生成/check はオフライン。`std/layout` を同梱し、credential を lock や grammar と分離する。[package 契約](docs/ubnf-packages.md)。
- Java 21 / TypeScript 5.9.3 / Rust 1.85.0 の固定した**部分文法**と source-only language package、明示 profile 選択、登録した外部解析 provider、LSP/Playground の capability 表示を追加。実 javac/TypeScript/rustc の provider は明示設定時に利用し、対象言語全体の構文・意味対応とは区別する。[profile](docs/language-profiles.md)、[外部 provider と機能差](docs/external-language-providers.md)。
- `@uniqueLongestChoice` と version 付き immutable name snapshot による `@namePredicate` を追加。最大消費の候補が一意な場合だけ選び、同率/空一致、未知の名前分類、version 不一致を拒否する。既存 ordered/longest choice の選択規則は維持する。[曖昧性](docs/explicit-ambiguity-profiles.md)、[名前判定](docs/versioned-name-snapshots.md)。
- Unicode 17.0.0 固定の `XID_IDENTIFIER` と v2 の scalar escape、`GraphemeIndex` / `GraphemeLength`、snapshot に結び付く G/CP/UTF-16/UTF-8 境界変換、1 extended grapheme cluster を消費する `GraphemeParser` を追加 (#335, #288, #510)。Java/Rust 共通データを用い、書記素の公式766ケースと独立 oracle を検証する。[XID](spec-corpus/xid-identifier/README.md)、[書記素](docs/grapheme-coordinates.md)。
- Classic Rust の framed stdio LSP と、両 host の opt-in `--lsp` 生成を追加。full document sync、profile、構文/意味診断、keyword/typed/provider 補完、provider hover/definition を対応範囲として公開する (#451)。[契約](docs/classic-rust-lsp.md)。
- Classic Rust の framed stdio DAP と、両 host の opt-in `--dap` 生成を追加。typed AST と上限付き実 terminal trace による AST/token inspection、breakpoint/next/continue、変数 hook を Java と共通検証する (#462, #463)。portable token metadata と明示した consume/invert stopper API も追加 (#438)。[DAP](docs/classic-rust-dap.md)、[token trace](docs/token-trace.md)、[metadata](docs/portable-token-metadata.md)。

- VSIX の ▶ / Help から保存済み UBNF → 生成 parser → WASM playground を開く操作 (#358)。
  画面内に環境準備・保存・再生成・トラブル時の案内を追加。信頼済み workspace のみ、
  新規生成先・shell 不使用・時間制限付きで実行し、配布 VSIX の実 webview まで検証する。
- Java-hosted / native Rust の `playground --grammar --output` から parser、vendored runtime、
  WASM wrapper、静的ブラウザ UI を生成。CST/AST、診断、消費位置、`@doc` catalog、
  authoring help を同梱する (#356)。生成物の一致と Java/Rust/WASM の共通70入力を検証。
- UBNF のメタ文法自身から playground を生成し、入門文法と自身を読めることを検証。
- UBNF VSIX の画面内 catalog/help。6 段階の入門、全文サンプルと成功・失敗入力、
  構文検索、症状別の修正方法、新規文書で開く操作を追加 (#354)。サンプルの
  Java / Rust 生成 parser・AST・Unicode 位置を共通テストで検証する。
- Rust の両生成経路で `@doc` を順序付き `RULE_DOCS` metadata として保持。
  認識と AST は変えず、portability check でも対応済みとして扱う。

### Changed

- 過深解析を通常の構文不一致と区別して中断する。Java は同時 parser frame 1024 を超えると `ParseLimitException` (`ULX-PARSE-DEPTH-LIMIT`) を返し、Rust は rule 呼出256 / 同時 expression1024 の上限を公開再入にも適用して `resource_limit` を返す (#506, #516)。否定 assertion/optional/choice/recovery はこの制限を成功へ変換しない。上限に達した context は再利用せず、新しい context を作る。Java は close 時に登録 transaction 状態を復元する。[Java](docs/java-parser-limits.md)、[Rust の上限と適用範囲](rust/README.md)。
- Java `PositionResolver` の EOF は最終行の実際の CP 列を返す。従来の常時0列に依存した表示/テストは更新が必要。CP↔UTF-16 の EOF 双方向変換と CRLF 内部の scalar 境界を保持し、`lines` / `lineSize` に改行で終わらない最終行を含める (#503)。空入力と末尾改行後の仮想空行は line slice に追加しない。snapshot の LSP 変換は引き続き CRLF 内部を拒否する。

- UBNF LSP の用語をメタ文法の AST から抽出。宣言的 token を索引へ登録し、
  既定の snippet は外部 Java parser binding ではなく v2 の認識定義を使用する。
- Maven artifactの版を3.3.0へ更新。Java 21 / Java 17の別artifactと、既定のCP単位のcursor/spanを維持する。

### Fixed

- Java `REGEX` が UTF-16 の一致幅を CP 数として消費し、補助文字の後ろまで誤消費する問題を修正 (#505)。指定 TokenKind の現在位置を使うため match-only の位置も正しくなる。反転不一致は存在しない matcher group を読まず次の1 scalar を消費し、EOF/ゼロ幅一致では非空 token を作らない。Rust の汎用 REGEX 対応を追加したものではない。[共通 Unicode 契約](spec-corpus/unicode-tokens/README.md)。
- Java/Rust snapshot の逆位置変換を候補ごとの再走査から単一走査へ変更し、二乗時間を解消 (#509)。CP/UTF-16/UTF-8/LSP の結果、EOF、CRLF、符号化途中位置の拒否を維持する。
- DAP の `configurationDone` 再送による step 巻戻りと終了後の stopped event を防止し、launch ごとに一度だけ受理する (#504)。breakpoint の source path を正規化した program と照合し、別 source の要求で既存 breakpoint を上書きしない (#507)。[lifecycle/source 契約](docs/classic-rust-dap.md)。
- Java/Rust LSP の shutdown 後 request を InvalidRequest で拒否し、exit によって transport と所有 worker を終了する (#508)。組込み API は host process を終了せず、stdio main が status を適用する。shutdown 後の exit は0、shutdown のない exit / exit のない EOF は1。[終了契約](docs/classic-rust-lsp.md)。
- 空の埋込み文書の origin anchor、未閉鎖領域の EOF 所有、shared-input 呼出しの lexical session 分離、古い snapshot から構築した非同期 query の拒否、consumer への意味診断公開を修正。package cache は推移依存の検証後に公開し、manifest/lock/artifact の上限は両言語とも UTF-8 byte 数で判定する。

- UBNF VSIX の import-aware 補完・hover・定義移動と module 診断 (#360)。開いた module の
  編集を反映し、AST 起点の rename でコメント・文字列・capture と grammar ごとのスコープを区別する。
  未完成・曖昧・外部公開 token の rename は拒否し、画面内 help で対応範囲を明記。
- VSIX が実行時依存の `vscode-languageclient` を除外していた梱包設定を修正。
  配布 VSIX を隔離した VS Code に読み込み、拡張起動・help・Java LSP 補完を CI で検証する。
- 配布VSIXの内部versionを3.3.0へ更新。従来の0.3.0固定値ではなく、今回の配布ファイル名と同じ版をインストール時に識別できるようにする (#523)。
- 配布VSIXに既存MIT LICENSE本文を同梱する。著作権表示・許諾条件を変更せず、リポジトリrootの本文と同一内容を収録する (#529)。

### Release notes

- Maven の版は **3.3.0**、UBNF の書式は **v2**。Java 21 の通常 artifact と Java 17 の `-jdk17` artifact は引き続き別配布。同じ source tree を使い、既存 CP cursor/Token/CST/AST span の単位を変更しない。
- 書記素は表示桁幅や正規化ではない。家族 `👨‍👩‍👧‍👦` は1G/7CP/11UTF16/25UTF8。G→CP/UTF16/UTF8 は対象 snapshot の表で変換し、逆変換のクラスタ内部は strict に拒否する。前後境界は floor/ceiling を明示する。長さだけの換算はできず、開始/終了の変換と差分が必要。[契約](docs/grapheme-coordinates.md)。
- 再帰上限は文法の括弧数そのものではなく runtime frame 数を制限する。Java/Rust で数値・同じ入力の最大深さ・失敗表現を同一化しない。検証対象は Java `-Xss1m` 以上、Rust 2MiB thread stack。任意 callback の独自再帰、独自 parser の通知省略、極小 stack、mapper 等の別再帰を隔離する sandbox ではない。[本番品質の対象](docs/production-quality.md)、[Java 上限](docs/java-parser-limits.md)。
- token stream は明示 opt-in。最長一致と literal 優先により、直接解析と受理言語が変わる場合がある。例えば `'if' ID` に対する `ifx` は direct と token mode で異なり得る。`XID_IDENTIFIER` は legacy ASCII identifier の置換ではなく、先頭 `_` を受理しない追加 token。[token stream](docs/token-stream.md)、[XID](spec-corpus/xid-identifier/README.md)。
- Rust は引き続き汎用 `REGEX` / `CASE_INSENSITIVE` / 任意 Java parser の自動移植、incremental parse cache、全 consume/invert/MatchedToken 互換を完了扱いにしない。Java `MatchOnly` と Rust PEG `ahead()` は既存の異なる契約を保つ。[対応表](rust/FULL-SPEC.md)、[positive assertion](docs/positive-assertion-contracts.md)。
- Rust LSP の cancellation/semanticTokens/rename/format/code-action transport、高度な DAP/live evaluator 制御、任意の下流 VSIX からの Rust 起動は今回の範囲外。runtime の query/edit API の実装と protocol 全機能の対応は区別する。部分言語 profile は公式言語全体の parser ではなく、一般左再帰/GLR/Earley、rule/evaluator/host-code import、Grapheme の tokenStream lexical opcode も未対応。[対応表](rust/FULL-SPEC.md)。
- Rust workspace は **0.1.0 / Rust 1.85** の別版体系を維持し、全 crate の `publish = false` を変更しない。crates.io 公開は行わない。GitHub Release の native generator / example assets は、Maven artifact と別の配布物として対象 platform・同梱 license を明記する。
- Java/Rust の本番品質は公開した対応範囲と固定検証条件に対するもの。エンジン再利用評価 #435 は隔離実験であり、Classic の engine/既定値を変更せず、ubnfc を production engine として組み込まない。[品質基準](docs/production-quality.md)、[実験結論](docs/engine-reuse-experiment-ja.md)。

## [3.2.0] - 2026-10-03

### Added

- UBNF format 2 の宣言的 token (`token NAME ::= expression;`) から Java/Rust の
  認識処理を生成。文字範囲・補集合・反復・位置検査・純粋な先読み・局所 raw
  `CAPTURE` / `SAME_AS` により NUMBER・IDENTIFIER・STRING・可変長 fence を
  host の認識クラスへ依存せず定義できる (#339)。
- `@import alias from 'path'` による字句 grammar の部品化。alias を名前空間とし、
  定義元の参照と capture scope を保存する。共通 loader は 1 ファイル 1 grammar の
  宣言的 token 部品を対象とし、Java/native Rust のファイル API と CLI で解決する (#341)。
- 外部 token の target-neutral adapter registry、UBNF v2 の認識・消費契約、
  read-only context accessor を追加。従来の FQN token は互換形式として維持する (#321, #334)。
- UBNF source snapshot、code point 単位の位置付き diagnostics、Java/native Rust の
  portability check と生成 API 差分レポートを追加 (#319, #320, #322)。
- Java/Rust で scope-less capture equality、skip による AST 投影境界を整合させ、
  両 Rust generator に `ERROR` 要素と atomic long code-block token の生成を追加
  (#317, #324, #326, #330)。
- [図でわかる UBNF v2](docs/ubnf-v2-illustrated-ja.md): 6 枚の図、実行可能な grammar、
  Java/Rust 共通の 41 入力ケースを含む入門。本文と例の一致もテストする (#348)。

### Fixed

- Rust failure memo の保持量を制限し、backtracking 後にも cache を再利用する (#315)。
- 宣言的 token の文字認識で Unicode scalar の意味を統一。Java の孤立 surrogate は
  拒否し、非 BMP 文字は両言語で 1 code point として扱う (#343)。
- UBNF の設定値に後続コメントが混入しないよう、識別子成分から値を構築する (#343)。

### Release notes

- Maven の版は **3.2.0**、UBNF の書式は **v2**。公開済み 3.1.1 には上記の新機能はない。
  親 POM と Java 21 / Java 17 の 4 library artifact を一緒に公開する。
- Rust crate の版体系は別管理。今回 crates.io への公開は行わない。
- 既存 Parser 全体の宣言化、任意 context の可変操作、共通 loader の rule module、
  Unicode property、引数付き token は完了扱いにしない。
- CI は repository 専用 self-hosted runner label を使用する。公開条件は CI 成功、
  guarded release script、Central 上の全 artifact の確認であり、ローカル成功だけでは公開しない。

## [3.1.1] - 2026-09-26

### Added
- **Java 17 artifacts** `org.unlaxer:unlaxer-common-jdk17` and `org.unlaxer:unlaxer-dsl-jdk17` (#311):
  the same sources, packages and classes as `unlaxer-common` / `unlaxer-dsl`, compiled with
  `--release 17` (class file major 61), with their own flattened POM, sources and javadoc jars.
  **Java 17 users: depend on `<artifact>-jdk17`** instead of the plain artifact;
  `unlaxer-dsl-jdk17` depends on `unlaxer-common-jdk17` only. They are built by the new reactor
  modules `unlaxer-common-jdk17/` and `unlaxer-dsl-jdk17/` (no copied sources) and published by
  `scripts/release-central.sh` / `release-central.yml` together with the main artifacts.
- `CodegenMain --java-release 21|17` (`new EvaluatorGenerator(17)`): with `17` all generated Java
  (Parser / AST / Mapper / Evaluator / LSP / DAP) compiles with `--release 17`. Only the Evaluator
  dispatch changes, to an `instanceof` chain in the same order as the sealed `switch`; a stale
  dispatch that misses a new AST variant is then reported at run time
  (`IllegalStateException("unhandled node: ...")`) instead of by javac (`not.exhaustive`). Missing
  handwritten `evalXxx` semantics are still compile errors. The default stays `21` (output
  unchanged) and `argsHash` only changes when a non-default value is given.
- CI: a JDK 17 job builds and tests the `-jdk17` modules on Java 17 and requires class file major 61
  in their jars; the JDK 21 build requires major 65 in the main jars.

### Changed
- The main artifacts `unlaxer-common` / `unlaxer-dsl` are unchanged: still built for Java 21
  (major 65), same generated output by default.
- The sources compile with `--release 17` so that one source tree serves both builds: Java 21
  pattern-matching `switch` in `unlaxer-dsl` (code generators, validator, mapper, Rust lowering) was
  rewritten as equivalent `instanceof` chains in the same case order (an `IllegalStateException`
  where the switch had no `default`, previously unreachable for sealed types). `new String(byte[])` /
  `getBytes()` / reading a `.ubnf` file now name UTF-8 explicitly (the Java 18+ default), so the
  Java 17 build decodes exactly like the Java 21 one. No behaviour change for non-null input.
- Tests that compile generated sources in memory use `--release 17` (the Evaluator follows the
  running JDK: sealed `switch` with `--release 21` on Java 21+, `--java-release 17` on Java 17).

## [3.1.0] - 2026-09-25

### Added
- Java `Memoization.SAFE_FAILURES` also replays safe successes for exact classes directly implementing `SafeSuccessMemoizable`. Entries retain rule-local diagnostics, independent token snapshots, cursors, and choice selections.
- The Java generator proves success safety transitively, excludes listener/state-dependent rules and uncertified custom tokens, and marks generated whitespace delimitors. Runtime replay also covers their `Occurs` entry point. Memoization remains off by default; Rust behavior and generated Rust sources are unchanged.
- `Source.sourceRange()`: a default method that returns a token's `[start, start+length)` extent directly, without building an intermediate `CursorRange` (#286).
- `docs/engine-selection-guide-ja.md`: a decision guide between unlaxer Classic (this repository's combinator runtime) and the sibling `ubnfc` UBNF compiler — what each one offers, a decision procedure, and the maintenance policy (Classic performance work stops at round 6 below; ubnfc is where further speed work goes). Linked from the READMEs (#303).
- `.github/workflows/release-central.yml` (`workflow_dispatch`): publishes `org.unlaxer:unlaxer-parser` to Maven Central from GitHub Actions as an alternative to the local `scripts/release-central.sh` path, with a secrets-free `guard` job that checks the version/confirmation/monthly cap before any credentialed step (#304).
- AGENTS.md and test Javadoc now document that `RustUbnfFrontendConformanceTest` (and other `-DrustConformance=true` tests) are skipped under a plain `mvn test` and verified only in CI; a skipped run now prints an `[assumption] ...` line naming the property instead of silently passing (#283).
- `scripts/release-central.sh` now prefers `~/.m2/settings-central.xml` (or a `MAVEN_SETTINGS` override) for Maven Central credentials, since `~/.m2/settings.xml` on the release machine is rewritten by unrelated self-hosted-runner jobs' `actions/setup-java` steps and loses the `central` server entry (#305).

### Changed
- The parse tree itself is smaller. A sub-source is now a view over its root's code point array
  (start/length) with its String materialized on demand instead of a fresh `String` and `int[]`
  per source; empty child lists share one immutable backing list until first written; a source's
  depth and both offsets are int fields behind the same accessors; and the root position resolver
  keeps its three indices in `int[]` instead of `HashMap`s with one boxed entry per code point.
  On a 20 KB input the tree retained by a parse falls from 34.7 MB / 1,256,693 objects to
  23.2 MB / 756,303 objects (-33% / -40%). `TokenList.toSource` only takes the view when the
  children are verifiably one contiguous slice of the root and otherwise concatenates as before,
  so nothing about the produced tokens changes. Rust behavior is unchanged: `unlaxer-runtime`'s
  `Tree` already stores nodes as spans over one owned source.
- The packrat memo table no longer keeps every entry for the whole parse. Entries whose start
  position falls more than `unlaxer.memo.window` code points (default 1024) behind the furthest
  position the cursor reached are dropped, which bounds the memo live set by the grammar's
  backtracking distance instead of the input length; on a 20 KB input the peak entry count falls
  from 102,054 to 8,424 with identical memo hit counts. Eviction cannot change a parse result — a
  miss simply re-parses — and a probe below the watermark widens the window past twice the
  observed look-back, so a grammar that backtracks further degrades to the previous behaviour.
  Inputs shorter than the window are untouched. Set `-Dunlaxer.memo.evictBelowFrontier=false` to
  restore the previous table. Rust behavior is unchanged.
- The UBNF bootstrap (`UBNFMapper`/`UBNFParsers`) now rejects trailing unconsumed input after a
  grammar file's closing `}` (e.g. `grammar G { R ::= 'x'; } garbage`) with a positioned
  `IllegalArgumentException` (`line N, column M`), and accepts an empty grammar body
  (`grammar G { }`), matching both the spec (`unlaxer-dsl/specs/ubnf-syntax.md`) and the Rust
  `unlaxer-ubnf` frontend, which already behaved this way. Previously the Java bootstrap silently
  accepted a prefix of the input and rejected empty grammars. Trailing whitespace and trailing
  `//` comments remain accepted (#278, #280, #283).
  - The `RustUbnfFrontendConformanceTest` fixture that had encoded the old (incorrect) Java
    behaviour as a known cross-language difference is fixed to assert the new, spec-conforming
    rejection, including that Java and Rust report the same line/column (#283).
- Chained (3+ segment) dotted rule references (`a.b.Value`) in `unlaxer-dsl/grammar/ubnf.ubnf`
  now parse correctly through the *generated* self-hosting frontend, matching the hand-written
  `UBNFParsers`/Rust `unlaxer-ubnf` frontends, which already accepted them. The `QuantifiedRef`
  production changed from a single optional dot to an unbounded `{ IDENTIFIER '.' }` repeat
  (#284, #285).
- Deferred-diagnostics mode (`ParseOptions.Diagnostics.DETAILED_ON_FAILURE`) no longer builds or
  retains the bookkeeping objects that failure recording doesn't read on a mode where recording
  is already skipped: per-call `ParseFrame` push/pop and memo-transaction-frame management are
  trimmed to what transaction replay still needs semantically. Counted on `complex-x64`: no
  change in observable results, measurable reduction in objects created per parse (#263, #287).
- Deferred-diagnostics mode also now computes each parser's FIRST set (leading code points, as a
  fixed point over the combinator graph) once and uses it to skip a choice candidate, chain
  element, or repetition body whose leading code point cannot match — without opening a
  transaction, creating a `Token`, or touching the memo table for it. This only applies when
  diagnostics are deferred and no listener/action/trial recording is active; `DETAILED` mode
  executes exactly the same instructions as before. Disable with
  `-Dunlaxer.parser.firstSetExclusion.disabled=true` (#292, #302). See **Performance** below.
- Spec documentation corrections found by the sibling `ubnfc` project's spec-derived oracle
  corpus: several error-code names documented in `validation.md`/`annotations.md`
  (`E-MAPPING-EXTRA-CAPTURE`, `E-ASSOC-WITHOUT-PRECEDENCE`, `E-PRECEDENCE-WITHOUT-ASSOC`) did not
  match what `GrammarValidator` and ubnfc actually emit (`E-MAPPING-UNLISTED-CAPTURE`,
  `E-ASSOC-NO-PRECEDENCE`, `E-PRECEDENCE-NO-ASSOC`); the docs are renamed to the implemented
  names. Implementations are unchanged — both implementations already agreed with each other, so
  the spec text was the stale side (#277, #279).
- Release policy: the "one Maven Central publish per calendar month" rule is now a guideline, not
  a hard cap. `release/central-release-queue.yml`'s `maxPublishOperationsPerCalendarMonth` is
  raised to accommodate multiple ready candidates in the same month, and readiness (green CI,
  updated changelog, version bump) gates a release instead of a fixed publish count (#296, #299).

### Fixed
- A repetition body was skipped one iteration too eagerly: the "skip when nothing was consumed"
  check compared the wrong cursor pair, so a failed terminal that reset the matched-but-not the
  consumed cursor could make a following repetition wrongly treat its body as a no-op and accept
  input it should have rejected (e.g. `(T) ['x'] (U) 'ab'` incorrectly accepting `"ab"`). The
  check now requires the consumed and matched cursors to coincide before skipping. Found by the
  Rust conformance CI job's cross-language corpus, not by either implementation's own test suite
  (#302).
- `UBNFMapper`'s hand-written `toAtomicElement`/`toQuantifiedRef` only ever used the first two
  identifiers of a dotted rule reference (`namespace = segments[0]`, `name = segments[1]`),
  silently dropping segments beyond the second for 3+ segment references even though parsing
  itself already accepted them. Both call sites now share a `buildRuleRef()` helper: the last
  identifier is always `name`, everything before it joins with `.` into `namespace` (#285).

### Performance
Six rounds of profiling work against issue #276/#292 targeted the packrat combinator runtime's
opt-in deferred-diagnostics mode (`ParseOptions.Diagnostics.DETAILED_ON_FAILURE`), using the
`complex` (332 B) and `complex-x64` (20,923 B, `complex` concatenated 64x) tinyexpression P4
fixtures. Each round is its own A/B session on a shared, noisy host (load average observed
7-49), measured as wall-clock minimum over repeated interleaved runs; a round's "before" number
is that round's own baseline run, not necessarily the previous round's "after" number, so
`complex`'s value moves within measurement noise round to round while `complex-x64` shows the
cumulative effect of shrinking allocation and the live object set:

| Round | Case | PR | What changed | `complex` before → after | `complex-x64` before → after |
|---|---|---|---|---:|---:|
| 1 | 32 | #281 | Stop allocating always-empty per-token/per-diagnostic-frame containers (-12% allocation) | 14.38 → 14.54 ms | 1,552.84 → 1,390.95 ms (-10.4%) |
| 2 | 33 | #286 | Token ranges without `CursorRange`; expected-source index without `IdentityHashMap` (-27% allocation) | 12.28 → 9.95 ms (-18.9%) | 1,276.26 → 989.86 ms (-22.4%) |
| 3 | 34 | #287 | Stop building `DETAILED_ON_FAILURE` bookkeeping the mode never reads (#263) | 8.109 → 6.294 ms (-22.4%, median) | 664.836 → 568.791 ms (-14.4%, median) |
| 4 | 35 | #289 | Evict packrat memo entries outside the backtracking window (peak entries 102,054 → 8,424 on x64) | 5.920 → 5.998 ms (±noise) | 558.187 → 498.397 ms (-10.7%) |
| 5 | 36 | #291 | Shrink the retained CST itself: source views, shared empty lists, unboxed value objects (-33%/-40% retained memory) | 6.673 → 5.794 ms (-13.2%) | 550.735 → 466.352 ms (-15.3%) |
| 6 | 37 | #302 | Runtime FIRST-set candidate exclusion (rule evaluations -41%, transactions -47% on x64) | 7.129 → 5.087 ms (-28.6%) | 419.701 → 370.888 ms (-11.6%) |

Across the six rounds, `complex-x64` wall-clock minimum went from round 1's baseline (~1,553 ms)
to round 6's result (~371 ms) on this host; allocation, GC pause time, and operation counts each
fell by roughly a third to a half per round and are the more load-independent evidence for the
improvement (see `docs/performance-tuning-ja.md` cases 32-37 for the full JFR/GC-log breakdown).
**Issue #292's target of `complex-x64` ≤ 350 ms in deferred mode was not confirmed on this shared
host** — the best observed minimum was 370.9 ms; subtracting GC pause and extrapolating the
round's CPU-time improvement onto round 5's quieter-host baseline (466 ms) estimates roughly
290 ms, but this has not been directly measured.

Default (`DETAILED`) diagnostics mode was not the target of this work and is deliberately
untouched by the FIRST-set exclusion (round 6 changes zero instructions on that path). Round 6
re-verified no regression: operation counts and allocation are bit-identical between base and
candidate, and wall-clock time on the four fixtures moved by -0.5% to +4.8% (noise), e.g.
`complex-x64` 667.4 ms → 658.6 ms.

### Deprecated
- Nothing is removed in this release. `Token.tokenString`/`tokenRange` (deprecated since an
  earlier release in favor of `sourceRange()` and related accessors) remain as working bridges;
  their removal is deferred to 4.0, consistent with this being a backward-compatible minor
  release.

### Tests
- The spec-derived UBNF oracle is now a permanent test on both implementations. 129 cases derived
  from the normative sentences of `unlaxer-dsl/specs/{ubnf-syntax,annotations,validation}.md`
  (origin: the sibling `ubnfc` repository; see `spec-corpus/ubnf/SOURCE.md`) are committed under
  `unlaxer-dsl/src/test/resources/spec-corpus/ubnf/` and driven by `UbnfSpecCorpusTest` (Java
  bootstrap: 126 cases through `UBNFMapper` and `GrammarValidator`, including canonical-AST
  expectations) and `rust/unlaxer-ubnf/tests/spec_corpus.rs` (Rust frontend: the 108 parse-scope
  cases) from the same fixture files. Both tests also verify that every cited heading and sentence
  still exists verbatim in the spec, so editing the spec breaks this repository's tests rather
  than the sibling's. One documented deviation is pinned with its observed behaviour
  (`quantifier/huge-bound`: the Java mapper throws `NumberFormatException` before
  `E-BOUNDED-OVERFLOW` can be reported).
- `ParseEqualityGoldenTest` turns the round 3-5 one-off equality dumps into a committed golden
  file. 35 inputs x {shipped, memo-safe grammar} x {DETAILED, DETAILED_ON_FAILURE} x
  {memo OFF, SAFE_FAILURES} = 280 combinations dump the whole CST (kinds, parsers, source kinds,
  offsets, ranges, code point and char lengths, child counts, source strings), the memo and
  transaction counters, and every `ParseFailureDiagnostics` getter. Regenerate with
  `-Dunlaxer.golden.regenerate=true`.
- `ParserScalingSmokeTest` pins the "the parser itself is linear" result of cases 32-36 with
  load-independent counters: transactions, memo hits and entries, and `getThreadAllocatedBytes`
  must not grow by more than x1.3 per byte between x1, x4 and x16 concatenations, for both a
  generated parser (with memoization) and the shipped UBNF bootstrap.
- Non-BMP coverage for the offset conversions: `LSPNonBmpOffsetTest` drives a generated LSP server
  and pins that code point offsets become UTF-16 offsets before they reach `Position`, and
  `UbnfNonBmpOffsetTest` pins the bootstrap's line/column reporting across surrogate pairs. The
  DAP side was already covered by `DAPNonBmpLineMappingTest` and the Rust side by
  `rust/unlaxer-ubnf/tests/frontend.rs`.

## [3.0.15] - 2026-09-01

### Added
- Generated Mappers expose `mapParsedToken(Token[, preferredAstSimpleName])` and return both the selected token and mapped AST. Consumers can map an existing parse tree without reflection into private mapper methods or state.
- Public parser boundary behavior now has regression coverage for source cursors, repeat bounds, case-insensitive words, and supplementary code points.
- Java grammar settings can explicitly certify stateless custom token aliases for safe failure memoization with repeatable `@memoSafeToken: ALIAS` declarations; invalid, duplicate, and non-simple aliases are rejected deterministically.

### Deprecated
- Correctly spelled public APIs now replace `Source.sourceToStgring()`, `NonTerminallSymbol`, and `HierarcyLevel`. The misspelled symbols remain as source- and behavior-compatible deprecated bridges and will not be removed before 3.2.0.

### Changed
- `ScopeStore.addReference` / `addDiagnostic` no longer advance the memoization state version.
  References and symbol diagnostics are write-only during parsing (read after the parse), so
  recording them cannot change what a memoized rule produces; advancing the version on every
  `$var` reference made the rest of the parse miss the memo table. tinyexpression's five-level
  nested `if` formula parses in about 0.1 s instead of 4-6 s with `Memoization.SAFE_FAILURES`
  (#269). Declarations and scope depth still advance the version.
- Maven Central publication now defaults to bundle-only mode and requires the organization-wide monthly release guard to opt into upload. The Central publishing plugin is updated to 0.11.0 so token identifiers are not written to release logs.

### Fixed
- The `javaStyle` whitespace profile now generates both line and block comment parsers.
- Generated DAP adapters convert parser code-point offsets to UTF-16 coordinates, including non-BMP source characters.
- Rule/token parser-name collisions are diagnosed for regex, negation, and character-range tokens, and their duplicate wrappers are not emitted.
- `MappedSingleCharacterParser` no longer indexes its ASCII lookup table for non-ASCII input in inverted mode.
- The MCP server rejects unsafe input/output paths instead of allowing traversal outside its intended roots.

---

## [3.0.14] - 2026-08-26

### Added
- Generated DAP adapters can now override `resolveDebugSource(...)` to select a line-aligned DSL slice from a container document while retaining the original file path, line mapping, and breakpoint coordinates. The default hook preserves the prior whole-file behavior.

---

## [3.0.13] - 2026-08-26

### Added
- Generated LSP syntax diagnostics now expose the stable code `ULX-PARSE-001`, the farthest failure position, expected tokens/hints/parsers, and the deepest matched rule. The same values are included in `Diagnostic.data` as a deterministic repair envelope for editor extensions and LLM clients.
- Grammar validation now rejects rule/token names that resolve to the same generated parser class with `E-RULE-TOKEN-NAME-COLLISION`, preventing a generated parser from silently recursing into itself at runtime.
- Generated DAP adapters expose their runtime-variable refresh hook to language-specific adapters, allowing a typed application context to be edited and re-evaluated without hand-editing generated code.

### Fixed
- Generated LSP servers now diagnose failed empty input and underline only the failing character (or the zero-width EOF position) instead of marking the entire remaining document. Parser code-point offsets are converted to LSP UTF-16 positions, including after non-BMP characters. Hover uses the same actionable parse message as diagnostics.
- Railroad SVG node IDs are now deterministic traversal IDs instead of JVM identity hashes, so regenerating an unchanged grammar no longer creates review noise for humans or LLMs.

---

## [3.0.12] - 2026-08-26

### Added
- **Runnable generated DAP/VSIX path**: `DAPLauncherGenerator` now emits a concrete stdio launcher with `main()`, so a UBNF project generated with `DAP,DAPLauncher` can be started directly from a VSIX. The scaffold and TinyCalc example default to AST stepping and entry stop, and a generated-adapter smoke test covers launch, stop, stack, and variables.
- **Application runtime hook for generated DAP adapters**: generated adapters expose `runtimeVariables(source, runtimeMode, launchArguments)`. UBNF continues to generate protocol, source mapping, breakpoints, and structural stepping; language-specific evaluation and typed variables can be supplied without coupling `unlaxer-dsl` to an application runtime.

### Changed
- Java generated parsers now expose immutable, default-off `ParseOptions` with
  `Memoization.SAFE_FAILURES`. Generated dependency analysis marks only exact classes proven free
  of scope, declaration, back-reference, custom-parser, and other state-dependent behavior;
  failures replay rule-local diagnostics, successes are never cached, and the deprecated
  `enableMemoize()` / `memoize()` adapters obey the same fail-closed policy (#194).
- DAP launch uses standard `program` with `formulaSource` retained as a compatibility alias, and separates execution `runtimeMode` from structural `steppingMode`.
- AST stepping fails explicitly when AST mapping is unavailable instead of silently switching to token stepping.
- `StringSource.peek` no longer double-allocates: it returned `new StringSource(this, subSource(...), offset)`, wrapping an already-equivalent `subSource` in a second `StringSource` (a redundant String + int[] copy on every peek — a hot path in deeply nested grammars). Now returns the single `subSource` directly. Byte-for-byte equivalent; full `unlaxer-common` suite green (108 files). NOTE: this halves peek allocation but does **not** by itself bring the deeply nested-`if` formula (tinyexpression #19 example 5) under a second — that case is GC/allocation-bound and needs dedicated profiling (its cost is not `(rule,position)` re-derivation, which memoization already removes).

---

## [3.0.10] - 2026-06-28

### Added
- **Opt-in packrat memoization** (`unlaxer-common`, issue #40). Enable per parse session via `parseContext.enableMemoize()` or `new ParseContext(source, ParseContext.memoize())`. Memoizes the outcome of parsing a rule (`ChainInterface`/`ChoiceInterface`/`AbstractParser`) at a position, keyed by `(parser identity, consumed, matched, tokenKind, invertMatch)`, collapsing the exponential backtracking that ambiguous-paren expression grammars trigger (downstream hang in opaopa6969/tinyexpression#19). **Off by default — default parsing is byte-for-byte unaffected** (full `unlaxer-common` suite green: 108 test files, 0 failures).
  - **Failure memoization**: a rule that already failed at a position cannot succeed on a retry, so the retry short-circuits instead of re-deriving the whole sub-tree. A failing input that was ~2^depth (≈11.5s at depth 18, intractable beyond) parses in milliseconds. A `TransactionListener` (scope tree / declarations / back-reference) is never failure-memoized, since its outcome can depend on mutable scope state.
  - **Success memoization**: a rule that already succeeded at a position replays its cached tokens (deep-copied via `Token.deepCopy()` — `parent` is mutable, so shared instances would corrupt the tree) and advances the cursor, instead of re-deriving. Only applied to **success-memoizable** rules — those whose sub-tree contains no `TransactionListener` — so scope/declaration/back-reference side effects are never skipped. Token-tree parity (on vs off) is verified by test. NOTE: because expression rules that reference variables embed a `@backref` (`VariableRef`) listener, they are excluded from success memoization; failure memoization already collapses the exponential for those grammars (see `docs/packrat-memoization.md`).

---

## [3.0.9] - 2026-06-27

### Fixed

- **Multiple non-assoc rules mapping to one AST class only generated from one rule (tinyexpression #32, nested slice)**: when two `@mapping` rules target the same class with DIFFERENT capture structures — e.g. `SliceExpr`: `SliceBaseExpression`'s `@value` is a `SliceBaseReceiver` while `SliceNestedExpression`'s `@value` is a `SliceBaseExpression` (the mapped class itself) — the generated `to<Class>` was emitted from a single representative rule and mis-resolved tokens of the other rule (a nested slice `'gateman'[::-1][0:4]` dropped the inner `[::-1]`, grabbing the innermost receiver). `emitPlainMappingBody` now dispatches each additional non-assoc rule by `token.parser.getClass()` (resolution extracted to `emitPlainMappingResolution`), with the representative rule as the unguarded fallback — exactly one rule is guarded and the other falls through, so the result is correct regardless of representative choice. Single-rule and structurally-identical multi-rule classes (e.g. `IfExpr`'s `ArgumentTernary`) are behavior-unchanged.
- **`findCapturedToken` fallback leaked an absent capture into a nested sub-expression**: the global descendant fallback let an outer slice's ABSENT `@step` (`[0:4]`) match the inner `[::-1]`'s step. The fallback is now a BOUNDED descendant search (`findDescendantsBounded`) that stops at `CAPTURE_BOUNDARY_PARSERS` — the set of all rule parser classes that map to their own AST node — so a capture missing at this level cannot cross into a separate captured sub-node. Captures genuinely nested under anonymous wrappers (Optional/Group/Repeat) still resolve. `MapperRuleEmitter.emitUtilities` now takes `(parsersClass, mappedRuleNames)`.

---

## [3.0.8] - 2026-06-27

### Fixed

- **Mapper captures used a global descendant index that miscounted across nesting (tinyexpression #32, load-bearing `if` shadow)**: scalar/optional capture resolution emitted `findDescendantByIndex(token, ParserClass, n)`, which recurses through ALL descendants. When the captured parser class also appears nested inside a *sibling* capture's subtree, the global Nth-of-class index points at the wrong node. Concretely, `IfExpression`'s `@thenExpr`/`@elseExpr` (`Expression`) were mis-resolved to an `Expression` nested inside the `@condition` — e.g. `if(min(0,0)==0){1}else{0}` mapped `thenExpr` to a `0` from the condition instead of the literal `1`, so the pure-AST evaluation took the wrong branch. Captures are direct children of the rule's Chain, so resolution is now **structural-position based**: new `findCapturedToken` prefers the rule token's direct children (`findDirectDescendants`) and only falls back to the global descendant index when no direct child of the class exists — backward-compatible where the old index was already correct. Fixes if-branch and slice-index resolution on the AST path (downstream tinyexpression: `min`/`max` and many `if(...)` cases now evaluate faithfully without the source shadow).
- **`@name`/identifier-token captures resolved to empty on the AST path (#43 family; tinyexpression #32)**: for a fully-qualified token parser, the parse tree holds the generated *wrapper* subclass (`<Grammar>Parsers.IdentifierParser`, per ParserTokenEmitter / `resolveParserClass` "Plan S"), but `MapperElementUtil.parserClassLiteral` referenced the base `org.unlaxer.parser.clang.IdentifierParser`. Since `findDescendants` matches by exact `getClass()`, the capture silently came back empty (e.g. `VariableRefExpr.name`, import `@alias`/`@method`), and only the source-snippet shadow recovered it. It now mirrors `resolveParserClass` exactly: the wrapper class for fully-qualified token parsers, the base class for short-named ones.

---

## [3.0.7] - 2026-06-26

### Fixed

- **Associative folds flattened transparent-choice operands to source text (#43 family; tinyexpression #32)**: a `@leftAssoc`/`@rightAssoc` rule whose operands reference a transparent mapped choice with no `@mapping` of its own (e.g. `StringExpression ::= StringTerm @left { '+' @op StringTerm @right }`, where `StringTerm ::= StringMatchExpression | SliceExpression | VariableRef | …`) inferred its operand fields as `String` and emitted `stripQuotes(firstTokenText(token))`, silently dropping the matched node. Such operands are now inferred as `Object` and resolved to their real AST node via the existing `mapTransparentValue(token)` helper. The same widening applies to comparison rules over transparent choices (e.g. `BooleanEqualityExpression ::= BooleanComparable @left EqualityOp @op BooleanComparable @right`). Downstream (tinyexpression #32) this makes string concatenation and boolean-equality operands evaluate faithfully on the AST instead of via a source re-parse.
  - `MapperTypeResolver.inferTypeFromElement` and `ASTGenerator.inferTypeFromElement` now yield `Object` (not `String`) for a reference to a transparent mapped choice, via the new `MapperTypeResolver.isTransparentMappedChoice` (same ≥2-distinct-mapped-alternatives criterion as `MapperElementUtil.isTransparentMappedChoice`).
  - `MapperRuleEmitter`'s assoc-fold emission now maps left/right operands through `mapExpressionForTargetType` (which routes `Object` + transparent choice to `mapTransparentValue`), delegating to the prior `mapExpressionForElement` behavior for AST-class / `String` operand types — so number-style and node-operand folds are unchanged.

---

## [3.0.6] - 2026-06-26

### Fixed

- **MapperGenerator dropped heterogeneous `@value` captures to source text (#43 family)**: a `@value` bound to a transparent choice rule with no `@mapping` of its own whose alternatives map to several different AST classes (e.g. `BooleanFactor ::= … | BooleanComparable @value`, where `BooleanComparable ::= InMethod | IsPresentFunction | …`) was emitted as `stripQuotes(firstTokenText(token))`, silently flattening the matched node (an `InExpr`, `IsPresentExpr`, …) to its source string. The generated mapper now resolves the real node via a new `mapTransparentValue(token)` helper (`mapToken` → `findBestMappedToken` → text fallback), so the `Object`-typed field carries the actual AST node. Downstream (tinyexpression #32) this makes `.in()` / `isPresent` / dot-predicate boolean factors inside `if(...)` conditions evaluate faithfully on the AST instead of via a source re-parse. New `MapperElementUtil.isTransparentMappedChoice` gates the change (≥2 distinct mapped alternatives → `Object` field, so emitting a node-or-text value is type-safe). Golden mapper snapshots updated.

---

## [3.0.5] - 2026-06-25

### Fixed

- **MapperGenerator collapsed heterogeneous assoc operands to `firstTokenText` (#43 family)**: left-/right-associative rules (`@leftAssoc`/`@precedence`) whose operands were function or conditional factors (e.g. `abs(-3)+pow(2,3)`) dropped the function node or recursed into `StackOverflowError`. Operands are now mapped to their real AST node via `mapAssocOperand*` (#43 case 1).
- **Variadic `{ ',' X @rest }` capture re-collected the leading scalar (#43 case 3)**: a variadic list re-walked the first operand, so e.g. range-membership checks evaluated as always-true. The repeat capture now skips the leading scalar.
- **Generated `findDescendants` recursed past rule boundaries**: it collected nested matches, so `findDescendantByIndex(StringExpr, 1)` could pick a deeply-nested literal instead of the rule-level operand, breaking `node.left()/right()` resolution (e.g. string comparison). It now collects rule-level matches only.

### Added

- **Rich UBNF VS Code extension** (`ubnf-vscode` 0.2.0): the UBNF grammar editor is now on par with the tinyexpression extension. `UBNFLanguageServerExt` wires `GrammarValidator` into live diagnostics (all `E-*`/`W-*` codes incl. left-recursion), adds quick fixes (`W-TOKEN-UNRESOLVED` → insert fully qualified parser class), context-aware completion (annotation snippets, bundled parser FQNs, declared rule/token names, block snippets), hover docs for annotations and rules, go-to-definition / references / rename / linked editing, document outline, folding, signature help for annotation arguments, and a full semantic-token highlighter. The TextMate grammar was rewritten (15 scopes), client-side snippets added, and the extension now reports server start failures.

### Fixed (ubnf-vscode)

- **ubnf-vscode server jar was unlaunchable**: the shaded jar's `Main-Class` pointed at the abstract generated `UBNFLspLauncher` (no `main`). It now points at `UBNFLspLauncherExt`.

---

## [3.0.4] - 2026-06-12

### Fixed

- **Regression in 3.0.3: `@scopeTree`/`@declares`/`@backref` scope features silently broken** (found by tinyexpression downstream verification): `ChainInterface.parse()`'s self-notification (introduced in 3.0.3 via #31) passed the raw pre-collect child token list to `onCommit`, while the dispatcher path passed the collected rule token. Generated listeners therefore never registered symbol declarations — LSP definition / linked-editing / hover-set / completion features broke. Self-notification now lives in `TransactionListenerContainer` (the single source of begin/commit/rollback events), guaranteeing payload parity for all parser types. `ChainInterface.parse()` is reverted to its 3.0.2 body, which also removes an unconditional `TokenList` copy on every failed chain parse (performance).
- `ScopeStore.registerDispatcher` is now a deprecated **no-op**: the container's self-notification makes forwarding redundant, and keeping the dispatcher active would double-notify. Existing callers remain source- and behavior-compatible.

### Notes

- Downstream verification: tinyexpression `p4-smoke` (86 tests) and LSP module smoke (25 tests) pass against 3.0.4; both had failures against 3.0.3.

---

## [3.0.3] - 2026-06-12

### Fixed

- **`ParseContext.peek(CodePointIndex, CodePointIndex)` infinite recursion** (#30, #31, #32): the overload delegated to itself and threw `StackOverflowError` when called. It now delegates to the source.
- **`SingleCharacterParser` supplementary code point handling** (#30, #31, #33): matching is now code-point based via a new primary `isMatch(int codePoint)` hook; `isMatch(char)` remains as a `@Deprecated` backward-compatible bridge for existing subclasses. `WildCardCharacterParser` continues to match supplementary characters (e.g. emoji).
- **`ParserCursor.addMatchedPosition(Index)` infinite recursion** (#37): found by the newly introduced Error Prone check; now converts to `CodePointOffset` and delegates.
- **Generated `@declares`/`@backref` listeners** could throw `NoSuchElementException` on partially-built token structures: `ParserRuleEmitter` now emits `getChildWithParserAsOptional(...).orElse(null)` instead of the throwing `getChildWithParser(...)`.

### Added

- **`TransactionListener` auto-notification** (#30, #31, #34): `ChainInterface.parse()` now calls `onBegin`/`onCommit`/`onRollback` directly on parsers that implement `TransactionListener`. The `ScopeStore.registerDispatcher` workaround is still supported and now skips `ChainInterface` implementors to avoid double notification.
- **Left-recursion warnings in the codegen pipeline** (#36): `CodegenMain`/`CodegenRunner` now report `W-LEFT-RECURSION` issues through the standard validation report machinery (stderr summary, `--strict`, `--fail-on warning`, report files). New structured API: `GrammarValidator.detectLeftRecursionIssues(GrammarDecl)`.
- **`W-TOKEN-UNRESOLVED` fully-qualified-name suggestions** (#36): when a token declaration uses an unqualified parser class name that exists in a bundled parser package, the warning hint lists the candidates (e.g. `Did you mean 'org.unlaxer.parser.elementary.NumberParser'?`).
- **Migration guide** (#35): `docs/migration-2.x-to-3.x.md` (+ Japanese) consolidating all 2.x → 3.x breaking changes and a pre-flight validation procedure, based on downstream feedback (#27, #28).
- **Static analysis in CI** (#37): Error Prone (bug-class ERROR checks) + SpotBugs (`threshold=High`, baseline in `config/spotbugs-exclude.xml`) via the `static-analysis` Maven profile.

### Notes

- **3.0.2 was never published to Maven Central** (#27); its changes are included in this release. Upgrade directly from 3.0.1 to 3.0.3.
- **The parent POM `org.unlaxer:unlaxer-parser` is published again as of this release.** The 3.0.x child POMs previously referenced a parent version absent from Central (latest published parent was 2.8.0).
- The 3.0.0 "Removed" section below was amended retroactively: `new StringSource(String)`, `StringBase`/`StringSource2`/`StringIndexAccessor*`, and `WildCardStringTerninatorParser` were removed in 3.0.0 but previously undocumented (#27, #28).
- README now documents the API deprecation policy and clarifies the `foundation-poisonpills` artifact status.

---

## [3.0.2] - 2026-04-20 (not published to Maven Central — see 3.0.3)

### Added

- **Left-recursion detection** (#25, #26): `GrammarValidator.validateWithWarnings(GrammarDecl)` new method that detects direct and indirect left-recursive cycles in a grammar and returns `Optional<List<String>>` warnings. **Warning only — never throws.** Existing grammars continue to parse without modification.
- **`@Generated` annotation** (#24): All codegen output files now carry `@javax.annotation.processing.Generated("<GeneratorClass>")`. Enables IDEs and tools (e.g. Checkstyle, SonarQube) to skip generated code. No breaking API change.

### Notes

- No API changes from 3.0.1. Safe to upgrade **from 3.0.1** without code changes.
- Projects upgrading from **2.x** must review the [3.0.0 breaking changes](#300---2026-04-18) below and the [2.x → 3.x migration guide](./docs/migration-2.x-to-3.x.md). Earlier revisions of the 3.0.0 entry omitted several removals (e.g. `new StringSource(String)`); they are now listed under 3.0.0 Removed (reported by downstream feedback #27, #28).
- CI workflow already present at `.github/workflows/maven.yml`; no new workflow needed.

---

## [3.0.1] - 2026-04-19

### Fixed

- **Simple wrapper generation**: `ParserGenerator` now skips generating a Simple wrapper class when the wrapper name conflicts with an existing rule name (issue #22). This was causing a compile error in grammars where a rule and its derived wrapper shared the same identifier.

### Notes

- No API changes from 3.0.0. Safe to upgrade from 3.0.0 without code changes.
- Downstream projects still on **2.x** should review the [2.8.0 → 3.0.0 breaking changes](#3-0-0---2026-04-xx) before upgrading.

---

## [3.0.0] - 2026-04-18

### Added

- **Bootstrap / self-hosting**: `unlaxer-dsl/grammar/ubnf.ubnf` (the UBNF grammar written in UBNF) now drives generation of `UBNFParsers.java`, `UBNFAST.java`, and `UBNFMapper.java` inside `org.unlaxer.dsl.bootstrap.generated`. The hand-written bootstrap files are retained as a verification target but are no longer the authoritative source.
- **`@enum` annotation**: Generate Java `enum` types from grammar rule alternatives. Replaces hand-written switch boilerplate in evaluators.
- **`@commonField` annotation**: Lift shared fields from multiple `@mapping` variants into a common sealed-interface method.
- **`AtomicElement` re-architecture**: `UBNFAST` body types now use a sealed `AtomicElement` hierarchy; `QuantifiedRef` covers postfix quantifiers (`?`, `*`, `+`) uniformly.
- **Token type inference**: `NumberParser` tokens are now inferred as `int` in `ASTGenerator` and `MapperTypeResolver`, eliminating manual casts.
- **Plan S — Simple token wrappers**: For every token rule without a `@mapping`, a lightweight value-record wrapper (e.g., `NumberToken(int value)`) is generated, making AST nodes fully typed without boilerplate.
- **Grammar `@import`**: Import rules from another `.ubnf` file with `@import alias from 'path'`. Enables grammar composition and shared rule libraries (issue #9).
- **Incremental parsing**: LSP `didChange` events are now connected to an incremental parse layer, reducing re-parse cost on keystroke (issue #10).

### Changed

- **`CodegenMain` replaces `UbnfCodeGenerator`** as the CLI entry point. Update your Maven `exec-maven-plugin` configuration:
  - Old: `<mainClass>org.unlaxer.dsl.UbnfCodeGenerator</mainClass>`
  - New: `<mainClass>org.unlaxer.dsl.CodegenMain</mainClass>`
- **`UBNFAST.TokenDecl`** is now a sealed interface (`Simple` / `Until` / `Negation` / `Lookahead` / `NegativeLookahead`). Code that `instanceof`-checks `TokenDecl` against the old class must be updated.
- **`@mapping` params order** is now strictly positional and validated at codegen time. Grammars with out-of-order `params=` lists will produce a build warning and may generate incorrect mappers.
- `groupId` and `developers` are now explicit in child POM files (Maven Central coordinate resolution fix).

### Removed

- `UbnfCodeGenerator` (old CLI class) — removed. Use `CodegenMain`.
- **`new StringSource(String)`** (single-argument constructor) — removed during the StringSource/StringSource2 unification. Use `StringSource.createRootSource(String)` instead. *(Added to this entry retroactively — reported by tinyexpression, #28.)*
- **`StringBase`, `StringIndexAccessor`, `StringIndexAccessorImpl`, `StringSource2`** — removed; replaced by Java 21 standard APIs and the unified `StringSource` (#7). *(Added retroactively — reported by onigiri-parser, #27.)*
- **`WildCardStringTerninatorParser`** (typo class name) — removed; use the correctly spelled `WildCardStringTerminatorParser`. Note the constructor signature is `WildCardStringTerminatorParser(boolean, Parser)`. *(Added retroactively — #27, #28.)*

### Downstream Drift Warning

> The following projects were validated against **2.x** and have not been tested against 3.0.0 / 3.0.1. Breaking changes in `CodegenMain`, `UBNFAST`, and `@mapping` params ordering may affect them.
>
> - [tinyexpression](https://github.com/opaopa6969/tinyexpression) — last validated 2.8.0
> - [onigiri-parser](https://github.com/opaopa6969/onigiri-parser) — last validated 2.6.0
> - [fraud-alert](https://github.com/opaopa6969/fraud-alert) — last validated 2.8.0

---

## [2.8.0] - 2026-04-10

### Added

- `@scopeTree` / `@declares` / `@backref` annotations: semantic scope modeling for symbol definition, resolution, and backreference constraints in generated LSP diagnostics.
- UBNF extension Tier 1–4 completions: `UNTIL`, `+` quantifier, `NEGATION`, `LOOKAHEAD`, `NEGATIVE_LOOKAHEAD` token forms (see `UBNF-EXTENSION-ROADMAP.md`).
- `IntegerValue.lessEquals` fix + `CodePointIndex.of(int)` / `CodePointIndex.ZERO` (issue #15, #16).

### Changed

- `WildCardStringTerminatorParser` now takes a constructor argument for the terminator string (breaking change for direct users of this elementary parser).

---

## [2.6.0] - 2026-03-15

### Added

- Self-hosting milestone: `ubnf.ubnf` can be processed by unlaxer-dsl to generate all six artifact types (Parsers, AST, Mapper, Evaluator, LSP, DAP). See `docs/ubnf-self-hosting.md`.
- Railroad diagram export (`RailroadMain`, `UBNFToRailroad`).
- BNF converter (`UBNFToBNFConverter`) for documentation tooling.

### Fixed

- `IntegerValue.lessEquals` off-by-one.

---

## [2.0.0] - 2026-01-20

### Added

- Parser IR (`ParserIrDocument`, `ParserIrAdapter` SPI) — a parser-agnostic intermediate representation allowing non-UBNF parsers to plug into the codegen pipeline.
- `GrammarToParserIrExporter` — exports UBNF grammar rules as ParserIR nodes with annotations.
- `ParserIrConformanceValidator` — validates IR documents against the v1 schema.
- `GrammarValidator` — static analysis of token declarations and rule references, emitting structured warnings (e.g., `W-TOKEN-UNRESOLVED`).

### Changed

- Minimum Java version raised from 17 to **21**.
- `unlaxer-dsl` package root reorganized: `codegen/`, `bootstrap/`, `ir/`, `runtime/` subpackages.

---

## [1.0.0] - 2025-09-01

### Added

- Initial public release of `unlaxer-common` and `unlaxer-dsl`.
- Core parser combinators: `Chain`, `Choice`, `ZeroOrMore`, `OneOrMore`, `Optional`, `NonOrdered`, `Not`, `Flatten`.
- Elementary parsers: `SingleCharacterParser`, `WordParser`, `NumberParser`, `QuotedParser`, `EndOfSourceParser`, `WildCardStringParser`, and more.
- UBNF grammar format with `@root`, `@mapping`, `@leftAssoc`, `@rightAssoc`, `@whitespace`.
- Code generators: `ParserGenerator`, `ASTGenerator`, `MapperGenerator`, `EvaluatorGenerator`, `LSPGenerator`, `DAPGenerator`.
- Published to Maven Central.
