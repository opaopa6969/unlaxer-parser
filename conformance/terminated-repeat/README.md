# 任意 terminator 付き反復の移植契約 (#846)

基準は master `6bc349c1cf60fc31a7c62fb0bc72a25f5e2f6236`（#855 merge）。master `dce213e9`（#1003 merge）へ統合した後も、
既存の期待値は変えずに両言語で通る。
Java の製品コードは変更しない。Rust には `Expr::repeat_java_until(terminator, min, max)`
（`Expr::JavaTerminatedRepeat`）を足し、既存 `Expr::JavaUntil` の Classic CST を Java に揃えた。
UBNF 構文は追加しない（生成器・frontend は不変）。

## Java の契約（Occurs.parse）

- 各反復で、body より**前に** terminator を試す。max に達した後は terminator を試さない。
- terminator は `Transaction.begin` で独自の transaction を開き（reset 方針が有効なら matched を
  consumed に戻す）、呼出し元の kind・invert に関係なく `parse(context)`、つまり consumed・非反転で解析する。
- 成功した terminator は、呼出し元の kind が consumed 系（consumed / virtualTokenConsumed）のときだけ
  `commit(terminator, terminator.getTokenKind())` され反復の token に入る。`CollectingParser` はここで
  もう一度収集されるので二重の token になる。該当するのは Chain・Choice・Occurs 系・EndOfSource・Empty と、
  `ConstructedSingleChildParser` を継承する単一子 wrapper 全部（Commit・Label・DoConsumePropagationStopper・
  InvertMatchPropagationStopper・NotPropagatableSource・Not・PegLookahead など）。Not / PegLookahead は
  子の token を残さないので、空の consumed token が1つ増える。MatchOnly も CollectingParser だが
  `getTokenKind()` が matchOnly なので、収集された token は consumed の反復では落ちる。Word・NamedCapture・
  NameResolutionScope・CatchLabels は CollectingParser ではないので、その token がそのまま追加される。
  matchOnly 系の呼出し元では成功しても rollback し、どちらでも反復はそこで終わる。
- terminator の失敗は rollback して body へ進む。raised failure（#700）は反復の失敗。
- body は反復自身の transaction の中で呼出し元の kind・invert で解析する。成功しても cursor が
  進まなければ1回数えて止まる。FIRST 集合による body の省略は terminator があるときは行わない。
- 最後に回数が min..=max なら成功。terminator 成功時に min 未満なら失敗し、terminator も rollback される。
- `WildCardStringTerminatorParser.parse(context)` は呼出し元の `resetMatchedWithConsumed` を false にしてから始める。

## 対応表

| Java | Rust |
|---|---|
| `ZeroOrMore(inner, terminator)`、`newWithTerminator`、`ChildOccursWithTerminator` の公開 constructor、`LazyZeroOrMore` | `inner.repeat_java_until(terminator, 0, None)` |
| `OneOrMore(.., inner, terminator)`、`newWithTerminator`、`LazyOneOrMore` | `inner.repeat_java_until(terminator, 1, None)` |
| `Optional` / `ZeroOrOne(name, inner, terminator)`、`LazyOptional` / `LazyZeroOrOne` | `inner.repeat_java_until(terminator, 0, Some(1))` |
| `Repeat#newWithTerminator`、`LazyRepeat` | `inner.repeat_java_until(terminator, min, Some(max))` |
| `WildCardStringTerminatorParser(String...)`（`true` / `Name` 付きも同じ） | `Expr::Any.repeat_java_until(Expr::choice([lit.match_only(), ..]), 0, None)` |
| `WildCardStringTerminatorParser(false, String...)` | `Expr::Any.repeat_java_until(Expr::choice([lit, ..]), 0, None)` |
| `WildCardStringTerminatorParser(Parser)` / `(true, Parser)` / `(false, Parser)` | `Expr::Any.repeat_java_until(t.match_only(), ..)` / 同左 / `t` |
| `WildCardStringTerminatorParser(";")`、UBNF `UNTIL(';')` | 既存 `Expr::JavaUntil(";")` |
| 直接の `parser.parse(context)` | Consumed・非反転で `parse_with_token_mode`。WildCardStringTerminatorParser は先に `set_reset_matched_with_consumed(false)` |

Java の `Class` / `Supplier` / Lazy 指定、`Name`、`ASTNodeKind` は Rust の `Expr` 値と `Rule` 名へ移す
（構築境界は #845）。Rust には parser の反射 identity や token stack がなく、`tokenStack` 列は常に 1。
Classic label（`parser_label`）は診断用で比較しない。

Rust の terminator commit は、Java で CollectingParser になる式（`java_collector_label`）だけを Classic node で
もう一度包む: `classic_collector_label` の collector（Sequence・Choice 系・Optional・反復系など）・Eof・
JavaEmpty・JavaUntil に加え、`commit()`・`label()`・
`stop_consume()`・`stop_invert()`・`invert_match()`・`Expr::JavaLookahead { positive: false, .. }`
（UBNF `!'x'` の生成形 `Not(Word)`）・`ahead()` / `not_ahead()`（PegLookahead）。
`Expr::JavaLookahead { positive: true, .. }`（`&'x'` の生成形 `MatchOnly(Word)`）と `match_only()` は包まない。

`Rule` を terminator にした場合: 生成器と同じ形（`LazyChain` / `LazyChoice` + `GrammarRule`）の rule では、
Java の二重収集（rule class の token をもう一度 rule class で収集）と、Rust の Rule node + 本体 node の段数が
一致する（`wrappers.tsv` の `rule-chain-term` / `rule-choice-term`）。WordParser などを継承した非 collecting の
手書き rule class は、terminator でない位置でも Rust の Rule node が1段深い既存の構築境界（#845）に従い、
terminator 位置でも同じく1段深い。この差は #846 固有ではないので fixture に入れていない。UBNF には terminator
構文がないので、生成 parser からこの位置には到達しない。比較では Rust の root rule node（Java の root は
反復そのもの）を外し、その子から比べる。

ParserWrapper / ParserHolder / MappedSingleCharacterParserHolder（`holders.tsv`）: 既定形は自分の CST node を
挟まない CollectingParser で、Java では terminator にすると二重収集される。Rust では新しい式も custom parser も
使わず、既存の公開 API に写す。どの位置（terminator・body・Chain の要素）でも、memo 2 × 診断 3 モードの全列が
Java と一致する。

| Java | Rust |
|---|---|
| `ParserWrapper(p)`・`ParserWrapper(name, p)`・`ParserWrapper(p, p.getTokenKind(), false)`・`ParserHolder(p)`・`ParserHolder(name, p)`・`MappedSingleCharacterParserHolder([name,] c)` | `p.stop_consume().stop_invert()`（p が Chain なら素の `p` も一致） |
| `ParserWrapper(p, matchOnly, false)`・`ParserWrapper(name, p, matchOnly, false)`・`ParserWrapper(MatchOnly(p))` | `p.match_only()`（Word なら `Expr::JavaLookahead { positive: true, .. }` も一致） |
| `ParserWrapper(p, matchOnly, true)` | `p.invert_match().match_only()` |
| `ParserHolder(MatchOnly(p))` | `p.match_only().stop_consume().stop_invert()` |

素の Word `p` は CST が1段浅く、`Expr::sequence([p])` は1段深いので、どちらも既定形の写しにならない
（terminator で 120/600 行が不一致）。`ParserHolder(MatchOnly(p))` を `p.match_only()` に写すと、原 token・CST・葉が
違う。次の4つは Java21 の probe（`eb8ecc61`）で測った境界で、#846 では完了扱いにせず、fixture にも入れていない。

- `ParserWrapper(p, consumed, true)`: Rust の `p.invert_match()` は NotPropagatableSource と同じく自分の node を持つ。
  そのため CST だけ1段深い（terminator 174/600、body 168/600、Chain の要素 90/600）。terminator 以外でも
  同じ差があり、#853 の境界。
- virtual kind を固定する `ParserWrapper(p, virtualTokenConsumed | virtualTokenMatchOnly, false)`: Rust には kind を
  virtual に固定する式がない。`stop_consume().stop_invert()` で写すと、受理と cursor まで違う行がある（194/600）。
  `match_only()` で写すと、原 token の kind が違う（162/600）。これも #853 の境界。
- `createMetaToken=false`（`CreateMetaTokenSpecifier.createMetaOff`）: Occurs 自身を含む MetaFunctionParser の
  収集を止める、context 全体の設定。Rust には対応する option がない。反復の root から CST が変わる
  （ParserHolder・ParserWrapper・Chain・body の全 scenario で 600/600）。terminator 固有の差ではなく、対応表の
  meta-function 行の境界。
- custom parser（`Expr::Custom` から `parse_with_token_mode` で kind・invert を固定する経路）は検証していない。

PredicateAnyMatchForParsedParser と OldMatchedTokenParser は Rust に経路がなく、未検証のまま。TagWrapper 系は
reduced CST の #851 に残す。`legacy-not`（任意 child の Not）も未検証のまま。

## fixture

- `cases.tsv`（732行）: 69 の scenario/入力 × 4 token kind × invert 2、consumed / matchOnly の reset=true、
  prior matched と reset 方針の組合せ 28 行、`parse(context)` 入口 14 行。入力は正常・空・EOF・不完全・
  CR / LF / CRLF・非 BMP、入れ子・同一 instance の再利用・choice 敗者後の再試行・MatchOnly 先読み・
  境界 max=0 / max<min を含む。観測は成否、consumed / matched CP、失敗 CP と expected の有無、
  `Parsed.getOriginalTokens()`（Rust は root の `original_tokens()`）、確定 root の kind 別 CST
  （`kind@CP:CP長:UTF8hex`、Word で止める）、token stack、終了後の reset 方針。
  terminator は Word・MatchOnly・Chain（途中失敗を含む）・Choice・Optional（nullable）・EndOfSource・Empty。
- `state.tsv`（24行）: `NamedCapture("t", ;)`（Rust `capture_owned`）で包んだ、user state を書き換える
  `;` terminator。成否・両 cursor・state・最後の capture・terminator 呼出し回数・失敗 CP。
  終端成功の commit / rollback、Chain の途中失敗、外側失敗、choice 後の再試行、先読み、同一 parser の再利用。
- `wrappers.tsv`（2342行、23 scenario）: wrapper・cut/label・生成形 rule・先読みを terminator にした反復。
  各入力（`a;xb`・`a;y`・`aa;x`・`a;`・`a`・空・`😀;x`・`;x`、scenario により `ab;`・`abac`・`a\n` を追加）で、
  4 token kind × invert 2（prior なし・reset=true）と、consumed / matchOnly の非反転で prior matched `a` と
  reset 方針の残り3組合せ。列は `cases.tsv` に加え、raised failure（`commit@CP` / `label:L@CP`）と、確定 root の
  kind 別 CST の葉（Rust は token trace の葉）。scenario: Commit / Label（Chain と Word）・3つの stopper/invert・
  Chain 内の Commit / Label（cut 後の失敗）・body 内の Commit・Choice 越しと MatchOnly 越しの raise・
  NamedCapture・MatchOnly(Chain)・WildCardStringTerminatorParser・ZeroOrMore・生成形 rule（Chain / Choice）・
  Not・MatchOnly(Word)・PegLookahead（正/負）・NameResolutionScope。
- `holders.tsv`（900行、9 scenario）: ParserWrapper / ParserHolder / MappedSingleCharacterParserHolder を terminator
  （Word・Chain・MatchOnly・matchOnly 固定・matchOnly+invert 固定）、body、Chain 内の terminator 要素に置いた反復。
  入力と列は `wrappers.tsv` と同じ。Java は上の表の全 constructor を同じ行で実行し、Rust は表の各 recipe を同じ行で実行する。
- `raised-state.tsv`（112行）: `state.tsv` と同じ capture 付き state 書換え terminator を、Commit(Chain)・
  Chain 内 Commit / Label・Choice 越しの raise・CatchLabels による捕捉と代替の再解析・MatchOnly 越し・外側失敗に
  置き、4 token kind で成否・両 cursor・state・capture・呼出し回数・失敗 CP・raised failure を比べる。
- `../resource-abort/cases.tsv` の `terminator` / `terminated-body` 行（4行）: 資源失敗が terminator と body の
  中で起きたとき、公開入口と外側の手動 transaction の復元が他の wrapper と同じになること。

期待値は Java21 の unlaxer-common（基準 commit）で実測し、上の規則に照らして見直した（例: matchOnly の
until は terminator の begin が matched を consumed へ戻すので `;` を越えて読み進む、invert の nullable body は
Optional の begin で同じ位置を2回読む）。Rust 実装はこの表を参照せずに書き、表と突き合わせた。
reduced CST（tag による AST 射影）は #851 の範囲で、ここの構成は tag を付けないので Java の
`filteredChildren` は原 CST と同じであることを Java 側で検査する。
`holders.tsv` は統合後の `eb8ecc61` で、同じ probe を使って Java21 で生成した。生成し直した旧 probe の 1300 行は、
`e3b7eda4` で測った行と byte 単位で一致した。
`wrappers.tsv` / `raised-state.tsv` は Java21 の probe（memo OFF・DETAILED）で生成し、Occurs の規則と照らして
見直した（例: matchOnly の呼出しでは terminator の begin が matched を consumed へ戻すので、cut の後ろに届かず
raise しない）。Rust の出力からは作っていない。

DETAILED_ON_FAILURE では、成功または通常失敗の行は失敗 CP=0・expected なしになるが、raised failure の行は
DETAILED と同じ診断を保つ（両言語）。Choice の FIRST 除外が reset 前の cursor を読む #861 は
依存 PR #862 の修正で解消する。`raise-choice`・`a;y`・matchOnly・prior `a`・reset=true の行も
Java / Rust の全診断モードで共通期待値へ比較し、Java 専用の例外値は使わない。

## 実行

- Java: `unlaxer-common` の `TerminatedRepeatParityTest`。各 scenario の全公開 constructor・factory・Lazy 形を
  同じ行で実行し、Memoization 全値 × Diagnostics 全値で比較する。
- Rust: `rust/unlaxer-runtime/tests/terminated_repeat.rs`。memo 2 × 診断 3 モード、通常入口と共有 grammar 入口、
  FIRST 除外の Off / On / Audit 同値性、memo・snapshot cache を承認しないこと、1500 段の入れ子が
  2 MiB stack で資源失敗になることを検査する。`wrappers.tsv` / `holders.tsv` は token trace（既定の上限）を有効にして葉を比べる。
- 資源失敗: Java `ResourcePublicationTest` と Rust `tests/resource_publication.rs` が `resource-abort/cases.tsv` を実行する。

trace 上限の扱い（Classic の collect が後で除外する葉も上限に数える）は terminator がない `seq + match_only` でも
起きる既存の問題で、ここでは既定の上限だけを使い、別 issue で扱う。
