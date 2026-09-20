# Java の安全な failure memoization

Java 生成 parser は、曖昧な選択肢が同じ `(rule, position)` を再試行する際の指数的な
バックトラックを抑えるため、parse session 単位の failure memoization を提供する。
既定値は従来どおり `OFF` であり、明示的に有効化した session だけが対象になる。

```java
ParseOptions options = ParseOptions.withMemoization(Memoization.SAFE_FAILURES);
try (ParseContext context = ParseContext.withOptions(source, options)) {
    Parsed parsed = GeneratedParsers.getRootParser().parse(context);
}

// 生成 Mapper も同じ option を受け取る。
Ast.Root root = GeneratedMapper.parseWithOptions(text, options);
```

## 契約

- `ParseOptions` は immutable。`ParseOptions.DEFAULT` と既存 Mapper overload は `OFF`。
- `SAFE_FAILURES` は失敗だけを保存する。成功 token、cursor、commit action は保存・再生しない。
- キーは parser identity、consumed/matched position、`TokenKind`、invert flag。
- failure hit は最初の rule call が生成した診断を再生するため、OFF/ON で farthest offset と
  expected hints が変わらない。
- cache は 1 個の `ParseContext` に閉じ、session をまたいで共有しない。
- parser graph（rule instance、その children、parse 結果へ影響する設定）は session 中 immutable
  であることを前提とする。graph を変更する可能性がある callback/state facility は下記の
  fail-closed 条件に従う。

診断 cache は global snapshot ではなく、対象 rule の開始時にだけ作る local accumulator を
保存する。cursor、failure、expected hint の更新は入れ子になった全 accumulator に伝播し、
成功時は破棄、失敗時だけ保存する。hit 時は global 診断と外側の active accumulator の双方へ
merge するため、先行 sibling の farther failure を誤って cache しない。成功した negative
lookahead 内の診断も speculation 境界で破棄される。stack は rule 開始深度以降の suffix だけを
保存し、hit 時の現在 stack へ rebase するので、以前の呼出元を診断へ復活させない。`OFF` と
unsafe rule には frame overhead がない。

次の session 状態では、既存 entry を含め lookup/store を停止し、通常の parser lifecycle を
必ず実行する。状態が後から追加・切替された場合も同じである。

- `ParserListener` または `TransactionListener` が 1 個以上登録されている
- persistent commit action が 1 個以上登録されている
- `TransactionalState` が 1 個以上登録されている
- trial recording が有効である

これにより callback、action、state checkpoint、trial record が cache hit によって欠落しない。
これらの要因を一度でも追加または有効化した session は、その後 remove/clear/stop されても
memoization を再開しない。unsafe 中の parser/state 変化より前の stale entry が復活するのを防ぐ。

## 安全規則の生成時解析

生成器は grammar の rule dependency graph を推移的に解析する。状態依存の leaf だけでなく、
そこへ到達可能な全 ancestor を対象外にする。以下は fail-closed で unsafe となる。

- `@scopeTree`、`@declares`、`@backref`
- custom/unknown token parser（`token X = SomeParser`）
- import namespace 経由など、生成器が純粋性を証明できない参照

capture、scope、back-reference、rollback、user state、`MatchedTokenParser` のように結果が
現在の context に依存し得る処理は、明示的に安全と証明されない限り cache されない。

安全と証明された生成 class は `SafeFailureMemoizable` を直接 implements する。runtime は
`instanceof` ではなく exact class の直接 interface を検査するため、生成 class の subclass が
parse 動作を override しても安全性を暗黙に継承しない。

## 互換 API

`ParseContext.enableMemoize()` と `ParseContext.memoize()` は deprecated。互換性のため残るが、
新しい `SAFE_FAILURES` と同じ安全規則限定の failure-only 動作へ固定される。旧 API でも
unknown/custom/state-dependent parser や成功結果が cache されることはない。

## 性能上の位置づけ

failure memoization は、複数の選択肢が同じ内側部分木を最後まで調べて失敗する文法で特に
有効になる。メモリ量は概ね「安全 rule 数 × 試行 position 数」。success memoization の安全な
再設計と下流生成物のベンチマークは別課題とする。

関連: #40, #184, #192, #194
