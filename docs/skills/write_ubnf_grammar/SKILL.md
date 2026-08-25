---
name: write_ubnf_grammar
description: UBNF文法を定義・編集する手順
volta:
  version: 1
  namespace: unlaxer
  locality: global
  applies_when: "UBNF文法を定義・編集するとき"
  requires:
    tools:
      - unlaxer__validate
      - unlaxer__convert_to_ebnf
  min_role: VIEWER
---

# UBNF文法を書く手順

## 1. ファイル作成

`.ubnf` 拡張子のファイルを作成する。改行コードは LF（CRLF はパースエラーになる場合がある）。

## 2. ヘッダーアノテーション

ファイル先頭にメタアノテーションを書く:

```
(* @package: story.calc *)
(* @whitespace: javaStyle *)
```

- `@package` — 生成コードの Java パッケージ
- `@whitespace` — ホワイトスペース処理（`javaStyle` を推奨）

## 3. トークン定義

Java パーサクラスを割り当てる:

```
(* token: NUMBER = org.unlaxer.parser.elementary.NumberParser *)
(* token: EOF = org.unlaxer.parser.elementary.EndOfSourceParser *)
```

## 4. 文法ブロック

```
grammar TinyCalc {
  Formula = Expression , EOF ;
  Expression = Term @left , { AddOp @op , Term @right } ;
}
```

### 構文要素
- 選択: `A | B`
- 結合: `A , B`
- 繰り返し: `{ A }` (0+), `[ A ]` (0or1), `A *` (1+)
- 区切り繰り返し: `A % B`
- グループ: `( A )`

### AST マッピング
`@left`, `@right`, `@op` で演算子ノードの結合を指定する。

## 5. 検証

`unlaxer__validate` で文法を検証する。エラーがあれば修正して再検証。

## 6. 確認

`unlaxer__convert_to_ebnf` で EBNF に変換して、意図した文法になっているか確認する。

## 注意

- CRLF はパースエラーになる場合がある（LF を使う）
- `@left`, `@right`, `@op` で演算子の結合を明示する
- トークンは Java パーサクラスに割り当てる必要がある
