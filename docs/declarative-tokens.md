# 宣言的 token — UBNF format 2

初めて読む場合は [図でわかる UBNF v2](ubnf-v2-illustrated-ja.md) から始めてください。
この文書は認識規約と実装範囲のリファレンスです。

この機能を含む Maven 開発版は `3.2.0-SNAPSHOT`。公開済み `3.1.1` は対応しない。
UBNF の format version と Maven artifact version は別に管理する。

`token NAME ::= expression ;` を認識仕様の正本にする。Java/Rust の generator は式を
構造化した字句プログラムへコンパイルする。生成 parser は FQN の認識実装を呼ばず、
UBNF ソースを実行時に再解析しない。従来の `token NAME = ...` は互換形式として残る。

## 中核の記法

```ubnf
grammar Numbers {
  @ubnf: v2
  @feature: declarativeTokensV1
  token DIGIT ::= CHAR_RANGE('0', '9');
  token NUMBER ::= [ '+' | '-' ]
    ( DIGIT+ '.' DIGIT* | '.' DIGIT+ | DIGIT+ )
    [ ( 'e' | 'E' ) [ '+' | '-' ] DIGIT+ ];
  @root @mapping(Value, params=[text]) Root ::= NUMBER @text;
}
```

- literal、token 参照、連接、ordered choice `|`、group `(...)`、optional `[...]`、
  repeat `{...}`、後置 `?` / `*` / `+` / `{n}` / `{n,m}` / `{n,}`。
- `ANY` は Unicode scalar 一つ、`EOF` / `BOF` / `BOL` / `EOL` は零幅の位置検査。

Java の `String` は孤立 UTF-16 surrogate を保持できるが、`ANY` / `CHAR_RANGE` /
`NEGATION` はこれを scalar として消費せず失敗する。Rust の `str` は同じ不正文字を
表現できない。正しい surrogate pair / UTF-8 非 BMP 文字は両側で 1 scalar として扱う。
設定値は識別子成分のみを値とし、`@ubnf: v2` の後などに置く `//` コメントは
版・package・空白設定へ混入しない。コメントは機械可読定義を補足する prose である。
  BOL は入力先頭または直前が CR/LF。EOL は EOF または現在位置が CR/LF。
  改行の消費は `'\r\n' | '\r' | '\n'` と明示する。
- `CHAR_RANGE('a','z')` と `NEGATION('excluded')`。新形式の範囲は非 BMP も扱う。
- `LOOKAHEAD(expression)` / `NEGATIVE_LOOKAHEAD(expression)` は任意の字句式を試し、
  成否だけを残す。consumed / matched cursor と局所束縛のすべてを復元する。
- `CAPTURE(name, expression)` は認識した raw text を現在の token 呼出しに束縛する。
  `SAME_AS(name)` はその raw text に完全一致する入力を消費する。
  token 参照先は独立した局所束縛を持ち、呼出し元の束縛を読まず、書き換えない。

token 内では空白・コメントを暗黙には消費しない。文法規則からの呼出し位置では従来の
trivia 規則が適用される。literal は文字列そのものへ一致し、暗黙の単語境界を付けない。
境界は `NEGATIVE_LOOKAHEAD(...)` で宣言できる。

## 可変長 fence

```ubnf
token BLOCK ::= BOL CAPTURE(fence, '`'{4,})
  IDENT ':' CLASS_NAME ( '\r\n' | '\r' | '\n' )
  { NEGATIVE_LOOKAHEAD(BOL SAME_AS(fence) EOL) ANY }
  BOL SAME_AS(fence) ( '\r\n' | '\r' | '\n' | EOF );
```

終端は同じ文字列だけからなる行。短い/長い fence、行中の fence、末尾空白付きの行は
本文である。本文は不透明であり、host 言語の文字列やコメントを解釈しない。

## 字句 grammar の部品化と名前空間

```ubnf
// lexical/numbers.ubnf
grammar Numbers {
  @ubnf: v2
  token DIGIT ::= CHAR_RANGE('0','9');
  token NUMBER ::= ['+'|'-'] DIGIT+;
}
// main.ubnf
grammar Expression {
  @import num from 'lexical/numbers.ubnf'
  @ubnf: v2
  token NEGATIVE ::= '-' num.DIGIT+;
  @root @mapping(Value, params=[text]) Root ::= num.NUMBER @text;
}
```

`@import alias from 'path'` の alias が名前空間。独立した namespace 宣言は設けない。
パスは **import を書いたファイルのディレクトリ** が基準。入れ子の相対 import と
同じファイルの別 alias での利用を許す。字句式と文法規則の両方から `alias.TOKEN` を参照できる。
export はそのファイルに宣言した token のみで、入れ子の alias を自動再 export しない。
必要なら `token PUBLIC ::= child.TOKEN;` を宣言する。

参照先は定義元で解決する。上の NUMBER が参照する DIGIT は Numbers の DIGIT であり、
呼出し側に同名 token があっても意味は変化しない。token 参照ごとの capture scope も維持する。
import 先の package、trivia、root、設定は呼出し側へ継承しない。

この段階での共通 module loader の対象は **一ファイル一 grammar、宣言的 token のみの部品**。
相互再帰する expression 規則は主 grammar に残す。規則 module、従来の FQN token module、
複数 grammar を含む曖昧な import、重複 alias、循環、未定義 export、ネットワーク import は拒否する。
暗黙の上書き、wildcard import、外部 package registry は導入しない。import 深さの上限は 64。
ファイル群は信頼する build 入力として扱い、ローカル絶対パスと `..` も利用できる。

Java の `UBNFModuleLoader.load(Path)`、native Rust の `modules::load(Path)` と
両 CLI のファイル生成が対応する。ファイル向け portability check は format 2 の import を解決する。
文字列だけの parse / portability API は I/O-free のまま。従来の Java `parseWithImports` API の
caller-supplied resolver による挙動も変更しない。
共通テストは `spec-corpus/lexical-modules/`。両言語の生成 Rust artifact もバイト単位で比較する。

## 観測可能な意味

選択は先勝ち、反復は greedy/possessive。後続の失敗を理由に反復を縮めない。
失敗した選択肢・反復試行の局所束縛は復元する。捕捉は trim・unquote・decode しない。
外側の AST mapper による従来の値変換とは区別する。公開 source span は code point 単位。
token 自身の失敗は両 cursor を開始時の値に保ち、成功は実際の字句長だけ消費する。
`NUMBER` は `1e+` から `1` を prefix として認識し、残りを残す。全入力検査は EOF と合成する。

undefined token、FQN への暗黙参照、token 間の循環、未束縛の再照合、零幅になり得る式の
無制限反復は生成前に拒否する。参照は字句式を持つ token に限定する。
nullable と局所束縛の解析は保守的に行う。capture は必須経路で束縛された場合だけ後続で
利用できる。scope 内だけの状態なので、入力と位置以外の外部状態への依存はない。
字句構文の入れ子と参照展開の深さは 128、各 token の展開は 4096 node まで。
生成物が外部の NumberParser / IdentifierParser を呼び出す mapping は行わない。
既存 parser は互換性テストの oracle にだけ用い、値変換と AST mapping は認識とは分離する。

実行可能な定義と入力・期待値は `spec-corpus/declarative-tokens/`。
現行 AST mapper の既定値変換（周辺空白除去、single quote の除去）は維持する。
この変換は字句プログラムの CAPTURE / SAME_AS や source span には適用しない。

## Parser 対応と残る設計

| 現行 Parser 群 | 宣言への対応 |
|---|---|
| ASCII/POSIX 文字、Word、Number、Identifier | literal / range / choice / repeat |
| Quoted、Escape | quote、escape、否定集合の合成 |
| Start/EndOfSource、StartOfLine、LineTerminator | BOF/EOF/BOL、明示的な改行式 |
| 短い/長い code fence | 行境界、局所 CAPTURE、SAME_AS |
| Chain、Choice、Optional、Repeat | 既存規則式と字句式 |
| LongestChoice、PredictiveChoice | 既存 rule annotation。字句式内の最長選択は別途設計 |
| NonOrdered | 宣言順の試行・各要素一回という実装意味を明示する拡張が必要 |
| MatchOnly、消費/反転伝播制御 | 既存 match-only cursor と新しい純粋な先読みを区別。全面移行は要検証 |
| MatchedToken/Reference、WordEffector、任意 Predicate | 局所 text 再照合は対応。履歴検索・変換 callback は個別の宣言化が必要 |
| AST tag/flatten、listener、recovery、scope | 既存 metadata / runtime 機能との対応を別に検証 |

引数付き字句定義、Unicode property と集合演算は中核の上に拡張する。Unicode property は
使用するデータ版を両言語で固定する。ASCII identifier の Unicode 化は互換移行と混ぜない。
この中核だけで全 Parser の置換完了とはしない。

## 検証条件

Java/native Rust frontend の AST、両 generator の出力、生成 Java/Rust の prefix/全入力受理、
consumed/matched cursor、raw span、失敗/先読み/choice の rollback を共通 fixture で比較する。
NUMBER/STRING/fence は現行実装とも比較する。LF/CRLF/CR、非 BMP、空入力、未閉じ delimiter、
capture の shadowing と呼出し間の漏出を含める。
