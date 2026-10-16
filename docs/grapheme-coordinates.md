# Unicode 17の書記素境界・位置・parser

追跡: [#510](https://github.com/opaopa6969/unlaxer-parser/issues/510)。
Java/Rustの**既存cursor・Token/CST/AST spanはUnicodeコードポイント（CP）のまま**。
新しい`GraphemeParser`は、原文全体の拡張書記素クラスタを1個消費する。
文字単位を切り替えるglobal modeはない。

## 単位と変換

| 単位 | 意味 | `👨‍👩‍👧‍👦`の長さ |
|---|---|---:|
| CP | Unicode scalar。Rust `char`はこの単位 | 7 |
| UTF-16 | 16-bit code unit。Java `char`はこの単位 | 11 |
| UTF-8 | byte | 25 |
| G | Unicode 17 extended grapheme cluster | 1 |

Javaは`org.unlaxer.GraphemeIndex` / `GraphemeLength`、Rustは
`unlaxer_runtime::grapheme::{GraphemeIndex, GraphemeLength}`でG単位を明示する。
数値型自体はsource identityを持たない。必ず対象sourceの境界表と組で扱う。
Javaの両型は負数を`IllegalArgumentException`で拒否するimmutable record、Rustは
`usize`のnewtype。既存`IntegerValue`の無条件な単位混合演算は継承しない。
加減乗除APIは提供しないため、暗黙のoverflowや単位変換は行わない。
表の外のindex/offsetはJavaで`IllegalArgumentException`、Rustで`Err`となる。
JavaのUTF-8 byte総数が`int`の表現範囲を超える文書は構築時に
`ArithmeticException`で拒否し、折り返したoffsetを返さない。

```java
var source = new org.unlaxer.source.DocumentSnapshot("file:///example", 3, "👨‍👩‍👧‍👦e\u0301");
var table = source.graphemes();
var afterFamily = new org.unlaxer.GraphemeIndex(1);
table.codePoint(afterFamily).value(); // 7
table.utf16(afterFamily);             // 11
table.utf8(afterFamily);              // 25
table.fromCodePoint(new org.unlaxer.CodePointIndex(7)); // G1
table.fromUtf16(11);                  // G1
table.fromUtf8(25);                   // G1
// table.fromCodePoint(new CodePointIndex(1)) はクラスタ内部のため例外
table.floorCodePoint(new org.unlaxer.CodePointIndex(1));   // G0
table.ceilingCodePoint(new org.unlaxer.CodePointIndex(1)); // G1
```

```rust
use unlaxer_runtime::source::Snapshot;
use unlaxer_runtime::grapheme::GraphemeIndex;
let source = Snapshot::new("file:///example", 3, "👨‍👩‍👧‍👦e\u{301}").unwrap();
let table = source.graphemes();
assert_eq!(table.code_point(GraphemeIndex(1)), Ok(7));
assert_eq!(table.utf16(GraphemeIndex(1)), Ok(11));
assert_eq!(table.utf8(GraphemeIndex(1)), Ok(25));
assert_eq!(table.from_utf16(11), Ok(GraphemeIndex(1)));
assert_eq!(table.from_utf8(25), Ok(GraphemeIndex(1)));
assert!(table.from_code_point(1).is_err());
assert_eq!(table.floor_code_point(1), Ok(GraphemeIndex(0)));
assert_eq!(table.ceiling_code_point(1), Ok(GraphemeIndex(1)));
```

strict逆変換はG境界だけを受理する。CP境界でもクラスタ内部なら拒否する。
UTF-16 surrogate pair内部、UTF-8多byte符号化内部も拒否する。
`floorUtf16/ceilingUtf16`、`floorUtf8/ceilingUtf8`（Rustではsnake_case）は、
符号化内部を含む明示offsetから前後のG境界を探す。境界自身はそのまま返す。
負数・文書外は丸めず拒否する。空文書の境界は0のみ、長さは0G。
EOFは末尾境界として変換できるが、parserで1Gを消費することはできない。

## 長さには起点が必要

`GraphemeLength`と`CodePointLength`だけを渡して換算するAPIはない。
同じ2CPでも`ab`は2G、`e\u0301`は1Gだからである。
同じsourceの開始/終了境界をそれぞれ変換し、その差を取る。

```java
var table = new org.unlaxer.source.DocumentSnapshot("length", 1, "Ae\u0301B").graphemes();
int beginCp = table.codePoint(new org.unlaxer.GraphemeIndex(1)).value(); // 1
int endCp = table.codePoint(new org.unlaxer.GraphemeIndex(2)).value();   // 3
var cpLength = new org.unlaxer.CodePointLength(endCp - beginCp);        // 2CP = この区間の1G
```

逆方向も開始/終了CPをstrictにGへ変換して差を取る。途中位置がある区間を
許容したい場合だけ、呼出側がfloor/ceilingを明示して新しい区間を選ぶ。
`GraphemeIndex`は文書全体のoffsetであり、行内のG列ではない。

## snapshot・原文・行列

`DocumentSnapshot.graphemes()` / `Snapshot::graphemes()`はimmutableな
`GraphemeBoundaryTable`を作る。表はURI・version・textを保持する。Rustは
元snapshotをcloneして所有するため、呼出側が元の公開fieldを書き換えても変化しない。
外部に保持したindexを別文書/versionへ適用する前には
`requireSnapshot(current)` / `require_snapshot(&current)`を呼ぶ。同じURI/versionでも
textが異なれば拒否する。編集後は新しいsnapshotから表を作り直す。

NFC/NFD変換はしない。`é`は1G/1CP/2UTF8、`e\u0301`は1G/2CP/3UTF8として
それぞれの原文を保持する。Java `DocumentSnapshot`はunpaired surrogateを拒否する。
`GraphemeParser`の遅延境界準備にも同じ制約を適用する。Rust `String`/`str`は
valid UTF-8のみを表し、surrogateや不正byte列を入力できない。byte列の復号は呼出側の責任。

CRLFは**1G・2CP・2UTF16・2UTF8**。CRとLFの間はG境界でもLSP境界でもない。
既存`lsp/fromLsp`のcharacter列はUTF-16。G indexを直接LSP characterとして使わず、
同じsnapshotのG→CP→LSP、LSP→CP→G strict変換を合成する。
例えば家族絵文字の直後はG1/CP7/LSP `(line=0, character=11)`。
続くCRLFの直後はG2/CP9/LSP `(1,0)`。空行・末尾改行も同じ契約。
G数は表示桁幅ではなく、タブ幅・全角幅・フォント・emoji描画・行折返しは扱わない。

## parserと生成

Javaは`org.unlaxer.parser.elementary.GraphemeParser`、Rustは`Expr::Grapheme`または
`grapheme::GraphemeParser`を提供する。CP parserと同じChain/Sequence/Choiceで併用できる。
parserは**原文全体**のG境界からだけ開始する。例えばCP parserが`e\u0301`の`e`だけを
消費した後、G parserは残った結合文字を新しいクラスタとして扱わず失敗する。
地域指示記号の奇偶・ZWJ・Indic文脈もsuffixを別文書として再分割しない。
通常の失敗・親の失敗は既存transactionで両cursor/token/captureを巻き戻す。

先読みには各言語の既存APIを使う。Java `MatchOnly(GraphemeParser)`はconsumedを
進めず、matchedとmatch-only tokenを公開する。Rust `Expr::Grapheme.ahead()`は
両cursorとcaptureを戻す。この既存差を同一化しない。
[positive assertion契約](positive-assertion-contracts.md)も参照。

```ubnf
grammar Graphemes {
  @package: example.graphemes
  token G = org.unlaxer.parser.elementary.GraphemeParser
  @root @mapping(Value, params=[text]) Root ::= G @text;
}
```

既存external token bindingを利用し、短い名前`GraphemeParser`でも指定できる。
Java frontend→Java parser/AST/mapper、Java frontend→Rust、native Rust frontend→Rustを対応。
新annotationやlexical expression opcodeは追加しない。Gを含むString text captureは
原文を保持し、CRLF/space/quoteをstripしない。直接capture・group・透明rule経由を
実生成・コンパイル・実行して検証する。既存tokenだけのcaptureは従来の投影規則を保つ。
`AstValue`混在枝など既存の意味投影については、CST原文とASTの変換契約を区別する。
`@tokenStream`の宣言的lexical expressionとしてのG opcodeは未提供。

## 固定版・資源量・検証

分割規則は[Unicode 17.0.0 / UAX #29 revision 47](https://www.unicode.org/reports/tr29/tr29-47.html)
のdefault extended grapheme cluster。GB3–GB999、Indic GB9c、emoji GB11、RI奇偶を含む。
Unicode 18の規則を混ぜない（例えばGB9cの条件が異なる）。JDK `BreakIterator`、
OSのUnicode版、外部crateには依存しない。正規化・locale tailoring・word segmentationは含まない。

[`spec-corpus/grapheme`](../spec-corpus/grapheme/)に固定入力データ・SHA256・ライセンス・公式testを保存。
`generate-grapheme-tables.py`はGCB、InCB、Extended_Pictographicのみを両言語の同じrange表へ生成する。
各scalarを1回読み、固定property表を二分検索する。Indic/ZWJ/RI文脈は定数状態で更新し、
後方走査しない。構築O(n)、境界保存O(g)、source保持O(n)、strict/丸め検索O(log g)、G→offsetはO(1)。
parser contextは初回使用時に一度だけ境界を構築し、不変入力に対するcacheをrollbackと独立に保持する。
1Gの中のCP数に固定上限はないため、G数による入力制限だけをbyte/メモリ上限として使わない。

検証は公式766ケースをJava/Rust各々で実行し、別の独立fixtureで全scalar/encoding内部、
snapshot違い、原文、LSP、CP/G混在、rollback、先読みを比較する。
19 parser入力×memo2×diagnostic3設定、20,000 combining markの単一Gと10,000G反復を含む。
両host生成Rustの5module byte一致、Java/Rust実行結果、AST text/CP spanも比較する。

```sh
python3 scripts/generate-grapheme-tables.py --check
(cd spec-corpus/grapheme/unicode-17.0.0 && sha256sum --check SHA256SUMS)
cargo test --locked --manifest-path rust/Cargo.toml -p unlaxer-runtime --test grapheme
mvn -B -pl unlaxer-common,unlaxer-dsl -am test \
  -Dtest=GraphemeTest,GraphemeConformanceTest,RustUbnfFrontendConformanceTest \
  -DrustConformance=true -Dsurefire.failIfNoSpecifiedTests=false
```

`GraphemeConformanceTest`のRust比較は`-DrustConformance=true`なしではskipされる。
CIは明示的に有効化し、`unlaxer-dsl/target/grapheme-conformance.tsv`へ証跡を出す。
Java 17用moduleでも同じsourceとUnicode表を使う。
