# REGEX から宣言的字句式への移行

3.3.0 公開後の開発版では、Java/Rust 共通の `token NAME ::= expression;` を字句定義に使います。`token NAME = REGEX('pattern')` は `E-REGEX-RETIRED` で拒否します。公開済み 3.3.0 は変更しません。Java の `org.unlaxer.dsl.runtime.RegexTokenParser` API は削除しました。再生成が必要です。

旧 REGEX、旧 adapter の短いクラス名／完全修飾名は、両 frontend で該当 keyword／クラス名の CP 範囲と行・列を示して拒否します。直接 AST を生成器へ渡した場合も同じ code で拒否し、ソースのない AST には位置を捏造しません。Java の deprecated `TokenDecl.Regex` と Rust の `TokenKind::Regex` はこの拒否検査用の AST 形状としてだけ残します。生成 Java／Rust に REGEX 実行器を出力しません。文字列・コメント内の `REGEX`、利用者定義の `token REGEX ::= ...;` は合法です。

診断の選択は Java を基準に、ファイル全体の構文解析・末尾チェックの後で移行診断を出します (#949)。旧 `token X = REGEX('x')` に終端の `;` はありません。`REGEX('x');`、複数の `;`、引数や後続 grammar の構文不正があれば、check は `P-SYNTAX`（span=null、subject=`UBNF syntax`）です。空白・`//` 行コメントだけの追加では移行診断を維持します。複数の旧宣言では最初の宣言の位置を使い、非 BMP 前置文字を UTF-16 の2単位として数えず CP で報告します。native だけの規則本体の入れ子上限（128段、Java は構文として受理）は構文エラーとして扱わず、移行診断を優先します。

local import 先の失敗は check / Playground では `P-MODULE` に包まれ、generate / deps inspect は import 先の原因を表示します。generate / deps inspect の本文は既存契約どおり、Java の先頭80 UTF-16単位または未消費入力表示と、native の行・CP列・原因表示に分かれます。共通 fixture は各 host の独立期待値と出力先・staging 不在を検証します。Rust は旧宣言の解析中に移行診断を保留しますが、部分 AST を返さず、展開・生成・実行へ渡しません。runtime parser のホットパスは変更しません。

## 値検証と構文認識の順序（#983）

native も Java と同じく、ファイル全体・後続 grammar・末尾入力の構文を認識した後に最初の mapper 値エラーを報告します。旧 REGEX／旧 adapter と値エラーを入れ替えた場合も、grammar → token → rule annotation の Java mapper 順を保ちます。失敗時の仮値は内部だけで使い、部分 AST・parameter 展開・生成・実行には渡しません。保留スロットは #949 の既存 `Option<Diagnostic>` を共用し、正常入力の追加走査・診断用の確保はありません。

`rust/unlaxer-ubnf/src/parser.rs` の途中検証を全件棚卸しした結果（基点 cbdeff0f）:

| 検証の種類／箇所 | 変更前の native | Java の報告段階 | 対応／残差 |
| --- | --- | --- | --- |
| 旧 REGEX・旧 adapter 短名／FQN (`token_decl`) | #949 の移行診断を保留 | 全体構文後、token mapper | 共通の最初の値診断スロットへ統合。旧表記を実行しない |
| v1 CHAR_RANGE の空・複数文字・非 BMP 境界 (`bmp`) | `InvalidValue` で中断 | token mapper の境界変換 | 保留。既存の native 文面・位置を維持 |
| v1 CHAR_RANGE の min > max (`token_decl`) | `InvalidValue` で中断 | token mapper / CharRange constructor | 保留。値だけの入力では最初の拒否を維持 |
| v2 codepoint escape の桁数・hex・上限・surrogate (`class_quoted`) | `InvalidValue` で中断 | token mapper の class literal decode | 保留。NEGATION / CHAR_RANGE と宣言的字句式の両方を対象 |
| v2 CHAR_RANGE の scalar 数・順序・surrogate をまたぐ範囲 (`token_decl`) | `InvalidValue` で中断 | token mapper の min 境界→max 境界→range 検証 | 保留。min の境界不正と max の escape 不正の競合も Java 順にする |
| 宣言的字句式の range / escape (`lex_atom`) | `InvalidValue` で中断 | LexicalBodyParser は構文だけ認識、token mapper が class を検証 | 保留。位置付き E-TOKEN-* を維持 |
| 宣言的字句式の repeat 整数 overflow・min > max (`lex_integer` / `lex_element`) | `Syntax` で中断 | **構文認識**の LexicalSyntax.parseSyntax でも検証 | 構文拒否を維持。保留中の値診断より優先 |
| 宣言的字句式の入れ子128上限 (`lex_expression`) | `Syntax` で中断 | **構文認識**の LexicalSyntax.parseSyntax でも同じ上限 | 構文拒否を維持 |
| `@eval` の kind / strategy / 任意 key の重複 (`annotation`) | `InvalidValue` で中断 | rule annotation mapper | 最初の重複を保留し、残りの引数・rule・grammar を読む |
| `@recovery` の不正 mode (`annotation`) | `InvalidValue` で中断し、先行移行診断に隠れる | **構文認識**の RecoveryAnnotationParser | `Syntax` に分類。既存 native 文面・位置を維持し、移行診断より優先 |
| 規則の bounded repeat / precedence の整数 overflow (`repeat_bound`) | 飽和値を AST へ渡す | mapper は整数範囲を記録し、後段の意味検証で拒否 | 既に全体構文後なので変更なし |
| 未知の token constructor / 引数付き annotation | `UnsupportedSyntax` で中断 | 全体構文で拒否 | 構文拒否を維持 |
| punctuation・ASCII identifier / integer・空の sequence・参照 prefix の連続性 | `Syntax` で中断 | 全体構文で拒否 | 構文拒否を維持 |
| native 固有の規則本体入れ子上限 (`body`) | `NestingLimit`。保留中の移行診断を優先 | Java の frame 上限は形ごとに異なる。group は168段まで構文受理 | **残差 [#996](https://github.com/opaopa6969/unlaxer-parser/issues/996)**。先行する値／移行診断は維持するが、その先の構文不正へは届かない |

`params::expand` は全体構文と保留診断の判定後です。lexer の引用符未閉鎖・不正文字も元から全体構文の失敗であり、ここで値診断へ移しません。Java の製品コード・既存診断・公開済み3.3.0は変更しません。

入れ子上限は再帰的 AST の構築・破棄と debug CLI の2 MiB stack 保証も制限しています。仮の scalar/string を置ける値失敗と異なり、上限でさらに再帰すること自体が危険なので引き上げません。#996 は明示 stack 等による安全な構文継続と Java/Rust 双方の境界検証を受け入れ条件にしています。`REGEX → 128〜168段 group → S ::= ;` の既存差が残るため、#983 全体は完了扱いにしません。

共通 fixture `spec-corpus/cli-stderr.json` の `value` suite は79形×入口/local importを LF/CRLF で処理し、両実 CLI の2,528観測（check JSON全文・Playground stderr・generate / deps inspect の exit・原因・位置、出力/staging不在・入力不変）を host 別の固定期待値と比較します。同じ fixture の158入力を Java 通常/snapshot API と native inspect の byte/CP span・行・列へ照合します。字句 repeat と recovery は構文失敗の対照、v2 の複数不正は mapper 順の対照です。既存 `regex` / `qualified` suite も維持します。 native の期待値（value 158・regex 40・qualified 24 入力）は `rust/unlaxer-ubnf/tests/diagnostic_priority.rs` でも照合し、Rust 単体の `cargo test` でも診断順の退行を検出します。

次は同等の正規表現へ自動変換する規則ではなく、目的を明示して選び直す例です。

```ubnf
grammar Lexical {
  @ubnf: v2
  token DIGIT ::= CHAR_RANGE('0','9');
  token LETTER ::= CHAR_RANGE('a','z') | CHAR_RANGE('A','Z') | '_';
  token IDENTIFIER ::= LETTER (LETTER | DIGIT)*;
  token DECIMAL ::= ['+'|'-'] DIGIT+ ['.' DIGIT+];
  token QUOTED ::= '"' { '\\' ANY | NEGATION('"\\') } '"';
  token NL ::= '\r\n' | '\r' | '\n';
  token FAMILY ::= '👨‍👩‍👧‍👦';
  token KEYWORD ::= 'if' NEGATIVE_LOOKAHEAD(LETTER | DIGIT);
  token PAIR ::= CAPTURE(name, IDENTIFIER) ':' SAME_AS(name);
  @root Root ::= IDENTIFIER;
}
```

| 旧記法の意図 | 選び直した定義 |
| --- | --- |
| `[A-Za-z_][A-Za-z0-9_]*` | 上記 IDENTIFIER。Unicode 識別子には `XID_IDENTIFIER` も選択可能 |
| `[+-]?[0-9]+(?:\.[0-9]+)?` | 上記 DECIMAL。指数、末尾小数点、先頭小数点は受理しない選択 |
| エスケープ付き二重引用符 | 上記 QUOTED。バックスラッシュ＋次の scalar を原文のまま保持 |
| CRLF／CR／LF | 上記 NL。長い CRLF を先に置く |
| Unicode 文字列 | リテラルを直接指定。family emoji は 7 CP / 11 UTF-16 / 25 UTF-8 bytes |
| keyword の後ろの識別子境界 | `NEGATIVE_LOOKAHEAD` で対象の LETTER/DIGIT を明示 |
| 同じ名前の局所再照合 | `CAPTURE(name, ...)` と `SAME_AS(name)`。正規表現のグループ番号とは別契約 |

字句式の選択は PEG の順序付き選択です。`('a' | 'ab') 'c'` は `abc` に失敗します。最初の `'a'` が成功した後で `'ab'` へ戻りません。必要なら `('ab' | 'a') 'c'` のように選択順を設計します。

反復は possessive です。`'a'+ 'a'` は `aa` に失敗します。正規表現 `a+a` のように最後の一文字を返す後戻りはしません。区切りまで読む意図なら、例えば `{ NEGATIVE_LOOKAHEAD('!') ANY } '!'` のように停止条件を明示します。空で成功する式の無制限反復は生成前に拒否します。

先読みは消費しません。失敗した選択肢／optional／先読み内の局所 capture は rollback されます。`SAME_AS` は字句呼出しの scope 内で原文を比較し、JDK Pattern の backreference/canonical equivalence/flags を再現しません。

`ANY`、範囲、除外集合は Unicode scalar 単位です。正規化や grapheme への暗黙変換はありません。Java に渡した不正な単独 surrogate は scalar 原始式で拒否します。CP・UTF-16・UTF-8 の変換では surrogate/UTF-8 内部を境界として扱いません。字句 token は原文を保持しますが、既存 mapper のテキスト AST 投影には trim 規則があります（改行だけの token の AST 値は空文字列）。

任意の Java Pattern 構文、Unicode property 名、先読み後戻り／後読み、検索 API を自動翻訳しません。必要な文字集合や終端条件を構文として定義してください。内部 tooling のテンプレート展開・TextMate の固定正規表現は DSL 実行エンジンとは別なので残します。

共有根拠は [移行 fixture](../spec-corpus/regex-retirement/) と `RegexRetirementConformanceTest` です。両 frontend の移行 code/位置、両 host の生成 Rust byte 一致、実 javac/rustc consumer の parse/map、CP/UTF-16/UTF-8、EOF、capture rollback、PEG と後戻りの差を手書き期待値で検証します。
