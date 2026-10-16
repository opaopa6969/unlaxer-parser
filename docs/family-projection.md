# AST family の透明投影（Java / Rust）

`@mapping(Family)` の引数なし rule が、別の mapped rule だけを単一参照の選択肢に持つ場合は、具体的な子 AST を返します。選択肢が一つの mapped alias、入れ子の family、共有 record も同じ規則です。`@commonField` は family の共通 accessor を追加する指定で、具体的な AST・値・原文・CP 位置を切り替える指定ではありません。

```ubnf
@root @mapping(Family) Root ::= A | B;
@mapping(Family.Item,params=[text]) A ::= '😀' @text;
@mapping(Family.Other,params=[text]) B ::= 'é' @text;
```

Java の `Family.Item` / `Family.Other` に対応する Rust の variant は `Family__Item` / `Family__Other` です。補助文字と結合文字を正規化せず、元の Unicode code point 単位の半開区間を保持します。Java の family 型を持つ capture に対応する Rust field は `Ast` の具体 variant を保持します。Rust は sealed subtype 専用の型を設けず、通常の `Ast` enum を使用します。

`Root` と子 rule の両方が mapped candidate です。preferred snapshot では concrete simple name（例: `Item`）で選択し、alias/family の token 深さも候補の順位に含めます。通常の生成 parser / mapper と preferred 用入口は同じ意味値を作ります。snapshot に保持した原文・位置は後続の成功・失敗・並行 mapping で変化しません。

同じ mapping 名を複数 rule で使う場合は既存の共有 schema の型・cardinality の一致条件を維持します。記号・Text だけの zero-field mapping は concrete node のままであり、選択肢を持つという理由だけで sum にしません。

## 生成前の検証と互換性

- mapping 名の `.` → `__` 正規化で別名が衝突する形は、Java/native 両 source host で拒否します。
- dotted mapping は `Outer.Inner` までです。3 段以上は生成前に拒否します。
- mapped family の循環・256 rule を超える深さは生成前に拒否します。構文解析の再帰 helper とは別の型階層の検査です。
- 上記は `@commonField` のない文法にも適用し、`E-COMMONFIELD-MISMATCH` を返します。既存の portability diagnostic 系列を維持しています。

Java の既存の具体 subtype と公開 mapper API は維持します。Rust は修正前に `@commonField` のない sum へ空の親 variant と `eval_family` を生成していましたが、修正後は具体的な子 variant とその evaluator を返します。旧 Rust 生成物の空親 variant / evaluator を参照する consumer は、再生成時に具体 variant へ移行が必要です。低水準の手書き IR の mapping は変更しません。

## 範囲と検証

`spec-corpus/family-projection/cases.json` は共通の候補型・深さ・値・原文 CP 範囲・失敗 stage を独立期待値として保持します。`FamilyProjectionConformanceTest` は Java21 実生成/compile/map/eval、両 source host の Rust 全ファイル一致と Rust1.85 実 compile/map/eval を行います。`@commonField` 有無を対にした同じ文法と、不正 schema の共通拒否 corpus も含みます。通常の preferred/commonField/typeOf/frontend 回帰も実行します。

```sh
mvn -pl unlaxer-common,unlaxer-dsl -am test \
  -Dtest=FamilyProjectionConformanceTest -Dsurefire.failIfNoSpecifiedTests=false \
  -DrustConformance=true
```

#592 は parent #569 の sum/dotted/shared 部分です。associative literal leaf・mixed operand と associative preferred 対応は #593 で継続します。nested container は #583、再帰 transparent helper は #582 の範囲です。#547 / #569 全体をこの変更だけで完了とはしません。
