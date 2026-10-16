# 大小文字を無視する token の共通契約

追跡: [#533](https://github.com/opaopa6969/unlaxer-parser/issues/533)。
この機能は **3.3.0公開後の開発版**に含まれる。公開済みMaven artifactやnative archiveの3.3.0には含まれない。

```ubnf
grammar Keywords {
  token SELECT = CI('select')
  @root @mapping(Query, params=[word])
  Query ::= SELECT @word;
}
```

Java/nativeの両UBNF frontendからRust parserを生成でき、Javaの生成parserにも同じ比較規則を使う。
Playground生成にもCI tokenを使用できる。任意のJava host bindingは引き続き対象外である。
Rustの低水準APIは `Expr::CaseInsensitive("select")`、Javaは
`new UnicodeIgnoreCaseWordParser("select")` である。
`CI`の引数は非空のUnicode scalar列とする。空tokenは文法検証で拒否する。
低水準APIへ空文字を渡した場合はtokenを生成せず解析が失敗する。
Javaで不正なUTF-16 surrogateを含むCIを指定した場合も、生成時の検証で拒否する。
手書きの固定版parserに不正な文字列を直接渡した場合は通常の解析失敗となる。

## 比較規則

各code pointに `simpleLowercase(simpleUppercase(cp))` を適用し、一対一で比較する。
表は[Unicode 17.0.0のUnicodeData.txt](https://www.unicode.org/Public/17.0.0/ucd/UnicodeData.txt)に固定し、
JVM・Rust標準ライブラリ・localeの変更に依存しない。
Javaの[String.equalsIgnoreCaseの比較手順](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/String.html#equalsIgnoreCase(java.lang.String))に合わせた規則であり、
Unicodeのfull case foldingや言語別の照合規則ではない。

| パターンと入力 | 結果 | 意味 |
|---|---|---|
| `select` / `SeLeCt` | 一致 | ASCII keyword |
| `i` / `I`・`İ`・`ı` | 一致 | localeによらない単純写像 |
| `k` / `K`、`s` / `ſ` | 一致 | ASCII外にも同値文字がある |
| `Σ` / `σ`・`ς` | 一致 | final sigmaも同値 |
| `ß` / `ẞ` | 一致 | 一対一の大文字・小文字 |
| `ß` / `SS` | 不一致 | 一対多への展開をしない |
| `é` / `e` + combining acute | 不一致 | 正規化をしない |
| `U+1C89` / `U+1C8A` | 一致 | JDK17/21内蔵表にないUnicode17の写像 |

CIはfold後の文字列へ書き換えず、tokenとcaptureのspanは**入力の原文**を保持する。
ASTのtextは既存mapperの文字列投影規則に従うため、外側空白・一重引用符の除去等はその規則のままである。
消費幅とspanはCPであり、たとえば `k` に一致する入力 `K` は1CP・1 UTF-16 unit・3 UTF-8 bytes。
補助文字やemojiが混ざっても、パターンのUTF-8 byte数を入力の消費幅に使わない。
CPからUTF-16/UTF-8への変換には元source snapshotを使う。
graphemeの分割や表示幅とは独立したtokenである。

## 既存Javaとの関係

手書きの `IgnoreCaseWordParser` は従来どおりJVMの `String.equalsIgnoreCase` を使う。
再生成したUBNFの `CI` は固定版の `UnicodeIgnoreCaseWordParser` を使うため、新しいUnicode文字の
大小文字ペアも認識する。3.3.0で生成済みのJavaソースをそのまま使う場合は従来の比較のままである。
固定版は `WordParser` のcursor・token・consume/invertの既存規則を引き継ぐ。
一般のJava `MatchOnly` とRust PEG `ahead()`の差は[別契約](positive-assertion-contracts.md)であり、
CIの追加によって同一になるわけではない。

JDK17/21の全scalarのsimple upper→lower値と固定表を比較すると、それぞれ95/55 scalarで値が異なる。
これは同値ペアの数ではない。Unicodeの版が新しくなることで生じる受理拡張として扱い、
`U+1C89/U+1C8A`等を共通fixtureに含める。旧Javaとの全Unicode入力での完全一致は主張しない。

既存JavaのFIRST推定には、`k`の候補を`k/K`だけに絞って`K`を誤って除外する問題があった。
大小文字を無視するtokenは保守的に予測し、Detailed/Deferredや選択肢の最適化で受理結果を変えない。
この修正は既存 `IgnoreCaseWordParser` にも適用する。

## 検証と残る範囲

`CaseInsensitiveConformanceTest` は独立期待値を使い、実際に生成したJava/Rust parserと両hostの
生成ソース一致を確認する。入力の大小文字・Unicode・補助文字・失敗とrollback・原文位置を検証する。
低水準runtimeではmemo/diagnostic policyと候補除外も検証する。
fixtureは [`spec-corpus/case-insensitive/`](../spec-corpus/case-insensitive/)、
両JDKと固定表の差分および再現方法は同directoryの `README-generated.md` を参照する。
固定表は `python3 scripts/generate-case-tables.py --check` でoffline検証でき、原本のSHA256も照合する。
通常の `mvn test` でRust連携がskipされた結果を、この比較の成功とは数えない。

```sh
RUSTUP_TOOLCHAIN=1.85.0 mvn -B -pl unlaxer-common,unlaxer-dsl -am test \
  -Dtest=UnicodeIgnoreCaseWordParserTest,CaseInsensitiveConformanceTest,RustUbnfFrontendConformanceTest \
  -DrustConformance=true -Dsurefire.failIfNoSpecifiedTests=false
```

汎用 `REGEX`、任意Java parserの自動翻訳、任意childのJava match-only互換wrapperはこの機能には含まれない。
残る差は[full-spec対応表](../rust/FULL-SPEC.md)に記録する。
