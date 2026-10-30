# REGEX から宣言的字句式への移行

3.3.0 公開後の開発版では、Java/Rust 共通の `token NAME ::= expression;` を字句定義に使います。`token NAME = REGEX('pattern')` は `E-REGEX-RETIRED` で拒否します。公開済み 3.3.0 は変更しません。Java の `org.unlaxer.dsl.runtime.RegexTokenParser` API は削除しました。再生成が必要です。

旧 REGEX、旧 adapter の短いクラス名／完全修飾名は、両 frontend で該当 keyword／クラス名の CP 範囲と行・列を示して拒否します。直接 AST を生成器へ渡した場合も同じ code で拒否し、ソースのない AST には位置を捏造しません。Java の deprecated `TokenDecl.Regex` と Rust の `TokenKind::Regex` はこの拒否検査用の AST 形状としてだけ残します。生成 Java／Rust に REGEX 実行器を出力しません。文字列・コメント内の `REGEX`、利用者定義の `token REGEX ::= ...;` は合法です。

診断の選択は Java を基準に、ファイル全体の構文解析・末尾チェックの後で移行診断を出します (#949)。旧 `token X = REGEX('x')` に終端の `;` はありません。`REGEX('x');`、複数の `;`、引数や後続 grammar の構文不正があれば、check は `P-SYNTAX`（span=null、subject=`UBNF syntax`）です。空白・`//` 行コメントだけの追加では移行診断を維持します。複数の旧宣言では最初の宣言の位置を使い、非 BMP 前置文字を UTF-16 の2単位として数えず CP で報告します。native だけの規則本体の入れ子上限（128段、Java は構文として受理）は構文エラーとして扱わず、移行診断を優先します。

local import 先の失敗は check / Playground では `P-MODULE` に包まれ、generate / deps inspect は import 先の原因を表示します。generate / deps inspect の本文は既存契約どおり、Java の先頭80 UTF-16単位または未消費入力表示と、native の行・CP列・原因表示に分かれます。共通 fixture は各 host の独立期待値と出力先・staging 不在を検証します。Rust は旧宣言の解析中に移行診断を保留しますが、部分 AST を返さず、展開・生成・実行へ渡しません。runtime parser のホットパスは変更しません。

既存の残差: `REGEX('x')` の後に値が不正な `CHAR_RANGE('z','a')`、そのさらに後に構文エラーがある場合、native は途中の値検証で止まります。Java の `P-SYNTAX` に対して最初の `E-REGEX-RETIRED` が残る一般の値検証順の差は [#983](https://github.com/opaopa6969/unlaxer-parser/issues/983) で追跡します。後続構文エラーのない場合は両版とも最初の移行診断を維持します。

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
