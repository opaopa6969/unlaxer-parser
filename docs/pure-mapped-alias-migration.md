# 純 mapped alias の AST 契約

追跡: #163（親 #111）。この移行は mapped node を文字列へ暗黙変換していた
Java/Rust の差を解消するもので、Rust full-spec 全体の完了を意味しない。

## 変更対象

```ubnf
@root @mapping(Box, params=[value]) Root ::= Alias @value;
Alias ::= Leaf;
@mapping(Leaf, params=[text]) Leaf ::= 'x' @text;
```

Java の旧生成 API は `Box.value(): String` として入力 `x` を返した。
移行後は mapped boundary の `Leaf` ノードを保持する。複数段の alias、group、
区切り付き wrapper、複数 mapped target も、元の mapped node の identity と
source span を保つ。区切り文字は子ノードの span に後付けしない。
純粋な字句ルールは引き続き文字列であり、Text/Node が混在する選択肢とも区別する。

Java の transparent field は既存の mixed capture と同じ `Object` 系 API を使い、
Rust は AST の所有値を保持する。ここでの一致は言語ごとの型名の一致ではなく、
Node/Text の区別、optional/list の cardinality、全フィールドの値と位置の一致である。
Java の `Object` 型だけで利用者の任意代入まで防止できるとは主張しない。

## 利用側の移行

生成された accessor を `String` 変数へ代入するコード、文字列の直接メソッド呼び出し、
古い record constructor 呼び出しは再生成時に更新する必要がある。
数値の意味を使う処理はノードを評価し、字句表現が必要な処理は保持した source と
所有する source-map snapshot を使う。`Node.toString()` は元ソースではない。

Java では `selectParsedTokenWithSourceMap` により、preferred 選択した token と
同一 mapping の immutable snapshot を得られる。snapshot の位置は Unicode
codepoint の半開区間であり、文字列への適用時には UTF-16 index に変換する。
parser 前にコメント等を正規化する利用者は、元の入力ではなく実際の parser 入力を保持する。
後続 parse や別スレッドの mapping 後に global な位置表を再参照しない。

tinyexpression の先行移行 PR #106 はこの ownership を evaluator と Java emitter
へ渡す。slice 添字の Node 化は、AST evaluator の `Integer.valueOf` 契約を
任意の数値式評価へ拡張する許可ではない。UTF-16/CodePoint、境界、step 0 等の
既存 backend 差は独立の回帰テストで固定し、本移行では統一しない。

## 検証と公開版

共通 semantic-cardinality corpus は、受理と両 cursor、Node の構造・全 span、
Text の独立した span oracle を照合する。純 alias の既知差を成功扱いで隠さず、
同値 fixture への昇格で検証する。Java frontend と native Rust frontend の生成物も照合する。

公開済み unlaxer 3.0.15 は変更しない。開発版は同じ Maven version を使うため、
公開版と開発版の検証は独立 repository で行い、生成 API の能力と実際の型を assert する。
この変更を含む新しい release の公開と version 選定は別作業である。
