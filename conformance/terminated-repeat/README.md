# 任意 terminator 付き反復の移植契約 (#846)

基準は master `6bc349c1cf60fc31a7c62fb0bc72a25f5e2f6236`（#855 merge）。
Java の製品コードは変更しない。Rust には `Expr::repeat_java_until(terminator, min, max)`
（`Expr::JavaTerminatedRepeat`）を足し、既存 `Expr::JavaUntil` の Classic CST を Java に揃えた。
UBNF 構文は追加しない（生成器・frontend は不変）。

## Java の契約（Occurs.parse）

- 各反復で、body より**前に** terminator を試す。max に達した後は terminator を試さない。
- terminator は `Transaction.begin` で独自の transaction を開き（reset 方針が有効なら matched を
  consumed に戻す）、呼出し元の kind・invert に関係なく `parse(context)`、つまり consumed・非反転で解析する。
- 成功した terminator は、呼出し元の kind が consumed 系（consumed / virtualTokenConsumed）のときだけ
  commit され反復の token に入る。`CollectingParser`（Chain・Choice・Occurs 系・EndOfSource・Empty）は
  commit でもう一度収集されるので二重の token になり、Word・MatchOnly・stopper・capture はそのまま追加される。
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
Classic label（`parser_label`）は診断用で比較しない。`Rule` を terminator にした場合は Rust の rule node のまま
で、Java の parser class 階層による二重収集は写さない。

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

期待値は Java21 の unlaxer-common（基準 commit）で実測し、上の規則に照らして見直した（例: matchOnly の
until は terminator の begin が matched を consumed へ戻すので `;` を越えて読み進む、invert の nullable body は
Optional の begin で同じ位置を2回読む）。Rust 実装はこの表を参照せずに書き、表と突き合わせた。
reduced CST（tag による AST 射影）は #851 の範囲で、ここの構成は tag を付けないので Java の
`filteredChildren` は原 CST と同じであることを Java 側で検査する。

## 実行

- Java: `unlaxer-common` の `TerminatedRepeatParityTest`。各 scenario の全公開 constructor・factory・Lazy 形を
  同じ行で実行し、Memoization 全値 × Diagnostics 全値で比較する。
- Rust: `rust/unlaxer-runtime/tests/terminated_repeat.rs`。memo 2 × 診断 3 モード、通常入口と共有 grammar 入口、
  FIRST 除外の Off / On / Audit 同値性、memo・snapshot cache を承認しないこと、1500 段の入れ子が
  2 MiB stack で資源失敗になることを検査する。
