# 同名captureのAST cardinalityと移行（#177）

同一ルールの同じ名前のcaptureは、成功した各出現を完了順に収集する。
内外で同名のcaptureを置くと、内側の値、外側の値の順になる。
これは単に文法に同名が何回書かれているかではなく、**同じ解析で何個の値が共存し得るか**の契約である。

| ルール本体の例 | AST fieldの形 | 入力と値 |
|---|---|---|
| `('a' @value 'b') @value` | list | `ab` → `["a", "ab"]` |
| `('a' @value) @value` | list | `a` → `["a", "a"]`（同一tokenでも2出現） |
| `'a' @value 'b' @value` | list | `ab` → `["a", "b"]` |
| `'a' @value \| 'b' @value` | scalar | `b` → `"b"` |
| `'a' @value \| 'b'` | optional | `b` → 欠損 |
| `[ 'a' @value ] @value` | list | 空入力 → `[]`、`a` → `["a", "a"]` |
| `{ 'a' @value } @value` | list | `aa` → `["a", "a", "a", "a"]` |

量指定子へのcaptureは従来どおり各要素を対象とする。反復全体の文字列をさらに1件追加するものではない。
group、optional、repeat、plus、bounded、separatedの内部を辿るが、別の名前付きルールの内部captureは
呼出し元のcaptureへ混ぜない。scopeイベントも独立に同じ完了順を保持する。
Javaのplain text captureも出現ごとのString identityに位置を登録するため、
同じ文字列や空文字列が複数回現れても`sourceSpanOf`で位置を区別できる。

## Java生成APIへの影響

これまでは入れ子や並列の同名captureを1つのscalarと誤判定し、外側または最初の値だけを残す場合があった。
修正後はJavaの生成record fieldとevaluator引数が、たとえば`String`から`List<String>`へ変わる。
型の中身は文字列・数値・mapped node・混合値の既存規則を維持する。
Rustの`Vec`と`Semantics`のスライス引数は既存の全出現契約を維持する。
欠損する選択肢も型へ反映する。たとえば`NUMBER @value | 'absent'`は
Javaで`Optional<Integer>`となり、`absent`を数値欠損例外にせず`Optional.empty()`で表す。
直接数値captureの変換・範囲検査はそのままで、Rustの字句保持との既知の差も今回変更しない。

parser・AST・mapper・evaluatorを同じgenerator revisionで一緒に再生成し、
手書きconsumerを新しいlist型へ更新する。旧scalarが必要なら、不要な内側captureを外して
`('a' 'b') @value`のように1出現を明示する。値を黙って切り捨てる互換モードは設けない。

同じmapped classを共有するルールは、field名・順序・cardinalityが一致している必要がある。
一方がscalar、他方がlistになる共有schemaは、従来どおり明示エラーとする。
この修正で誤判定が解消され、以前通っていた文法がエラーになる場合は、capture構造を揃えるかmapped classを分ける。
要素型のjoinや結合演算子専用のschema契約は変更しない。

## 検証と限界

`NestedCaptureConformanceTest`はJava/Rustで実生成・コンパイルし、共通corpusの独立期待値に対して
全AST field/node span・受理・cursor・scope履歴を検証する。
RustのText値の位置はASTのcanonical JSONには含まれないため、別途spanを検査する。
Java frontendとnative frontendの5生成fileをbyte比較し、空PATHでのnative生成も検証する。
CIは`target/rust-nested-captures.tsv`を必須artifactとする。

Rust単独の`nested_capture_shapes`とnative compile/runテストは、shape推論・全出現・scope位置・
CST破棄後のASTとlist型Semanticsを検証する。
これは入れ子container型全般や、Java数値変換とRust字句保持の完全統一を完了するものではない。
