# スコープツリー仕様

> ステータス: 実装中
> 最終更新: 2026-03-08
> 関連: [annotations.md](annotations.md) · [ubnf-syntax.md](ubnf-syntax.md)

---

## 概要

`@scopeTree` / `@declares` / `@backref` の3つのアノテーションを組み合わせることで、
UBNF 文法にシンボルスコープ管理を宣言的に記述できる。

```
"構文エラーは文法ファイルが、意味エラー（未定義変数など）はスコープアノテーションが検出する"
```

### 解決できる課題

| 課題 | 従来（手書き） | スコープアノテーション後 |
|---|---|---|
| 変数スコープ管理 | `TransactionListener` 実装 + `globalScopeTreeMap` 直接操作 | `@scopeTree` + `@declares` |
| 未定義変数検出 | 評価フェーズで手書き | `@backref` でパース時に自動検出 |
| LSP: go-to-definition | 手書き | スコープツリーから自動生成 |
| LSP: find-references | 手書き | スコープツリーから自動生成 |
| LSP: 未定義変数 diagnostics | 手書き | `@backref` 失敗 → 自動 warning |
| タグ名一致検証（XML など） | 手書き | `@backref` のみで記述 |

---

## アノテーション一覧

### `@scopeTree(mode=lexical|dynamic)` — スコープ境界

スコープの境界となるルールに付ける。ルールのパース開始でスコープをネスト、終了でポップする。

```ubnf
@scopeTree(mode=lexical)
Block ::= '{' { Statement } '}' ;
```

| モード | 意味 | ユースケース |
|---|---|---|
| `lexical` | 静的ネスト（コード上の構造でスコープが決まる） | 変数スコープ、関数スコープ |
| `dynamic` | 動的ネスト（実行時のネストでスコープが決まる） | マクロ展開、テンプレート |

### `@declares(symbol=captureName)` — シンボル登録 *(新規)*

このルールのパースが成功したとき、`@captureName` でキャプチャした識別子を
現在のスコープに登録する。

```ubnf
@declares(symbol=varName)
VarDecl ::= ('variable'|'var') VARNAME @varName [ ':' Type ] [ '=' Expr ] ';' ;
```

### `@backref(name=captureName)` — シンボル参照検証

1. **スコープ参照モード**: `@scopeTree` が存在するとき —
   `@captureName` でキャプチャした識別子が現在のスコープ内で宣言済みかを検証する。
   未定義であれば LSP diagnostics に警告を追加する。

2. **後方参照モード**: `@scopeTree` がないとき —
   同一ルール内で先行する同名キャプチャと一致することを検証する（XML タグ名など）。

```ubnf
// スコープ参照モード（@scopeTree がある文法内）
@backref(name=varName)
VarRef ::= '$' IDENTIFIER @varName ;

// 後方参照モード（@scopeTree がない文法内）
@backref(name=tagName)
Element ::= '<' IDENTIFIER @tagName '>' Content '</' IDENTIFIER '>' ;
```

---

## tinyExpression での使用例

### 現在の手書き実装

tinyExpression では変数スコープを以下の手書きコードで管理している:

```java
// 変数宣言パーサーが TransactionListener を実装
public class VariableDeclarationParser extends LazyChoice implements TransactionListener {

    @Override
    public void onCommit(ParseContext parseContext, Parser parser, List<Token> committedTokens) {
        // パース成功 → globalScopeTreeMap に変数情報を登録
        VariableInfo variableInfo = extractVariableInfo(committedTokens.get(0));
        variableDeclarations.set(parseContext, variableInfo);
    }
}

// VariableDeclarations: globalScopeTreeMap を直接操作するストア
public static class VariableDeclarations {
    public static final Name STORES = Name.of(VariableDeclarations.class, "Stores");

    Map<String, VariableInfo> infoByName(ParseContext parseContext) {
        return (Map<String, VariableInfo>) parseContext.getGlobalScopeTreeMap()
            .computeIfAbsent(STORES, name -> new HashMap<>());
    }

    public void set(ParseContext parseContext, VariableInfo variableInfo) {
        infoByName(parseContext).put(variableInfo.name, variableInfo);
    }
}

// 変数参照パーサーが宣言マップを検索し、型付きパーサーに差し替える
public class ExclusiveNakedVariableParser extends NakedVariableParser {
    @Override
    public Parsed parse(ParseContext parseContext, TokenKind tokenKind, boolean invertMatch) {
        // ... $varName をパース ...
        Optional<VariableInfo> variableInfo =
            VariableDeclarations.SINGLETON.get(parseContext, variableName);
        // 宣言が見つかれば型付きパーサー（NumberVariableParser など）に差し替え
        VariableParser matchedParser =
            variableInfo.map(VariableInfo::matchedVariableParser).orElse(this);
        Parsed committed = new Parsed(parseContext.commit(matchedParser, tokenKind));
        return committed;
    }
}

// さらに後処理でトークンツリーを全走査して型解決
Token rootToken = VariableTypeResolver.resolveVariableType(rootToken);
```

### `@scopeTree` / `@declares` / `@backref` を使った記述

上記の手書き実装が以下の UBNF 宣言に置き換わる:

```ubnf
grammar TinyExpression {
    @whitespace: javaStyle

    token VARNAME  = REGEX('\$[a-zA-Z_][a-zA-Z0-9_]*')
    token NUMBER   = NumberParser
    token STRING   = SingleQuotedParser

    @root
    @scopeTree(mode=lexical)
    Program ::= { Statement } ;

    Statement ::= VarDecl | Expr ;

    @declares(symbol=varName)
    VarDecl ::= ('variable' | 'var') VARNAME @varName [ ':' Type ] [ '=' Expr ] ';' ;

    @backref(name=varName)
    VarRef ::= VARNAME @varName ;

    Expr ::= VarRef | NUMBER | STRING | BinaryExpr ;
}
```

**削減される手書きコード:**
- `VariableDeclarations` クラス（〜40行）
- `VariableDeclarationParser.onCommit()` の登録ロジック（〜15行）
- `ExclusiveNakedVariableParser.parse()` の宣言検索ロジック（〜20行）
- `VariableTypeResolver.resolveVariableType()` の走査ロジック（〜15行）

---

## 別ユースケース: 関数スコープ（ネストあり）

```ubnf
@scopeTree(mode=lexical)
FunctionDef ::= 'function' IDENTIFIER '(' Params ')' Block ;

@declares(symbol=paramName)
Param ::= IDENTIFIER @paramName [ ':' Type ] ;

// ネストした Block でも同じ @scopeTree が適用される
@scopeTree(mode=lexical)
Block ::= '{' { Statement } '}' ;

@declares(symbol=varName)
VarDecl ::= 'let' IDENTIFIER @varName '=' Expr ';' ;

@backref(name=varName)
VarRef ::= IDENTIFIER @varName ;
```

`mode=lexical` では内側スコープから外側スコープへ順にシンボルを検索する（レキシカルスコープの標準動作）。

---

## 別ユースケース: XML タグ名一致（後方参照モード）

`@scopeTree` なしで `@backref` のみを使うと、同一ルール内の後方参照になる:

```ubnf
@backref(name=tagName)
Element ::= '<' TAGNAME @tagName '>' Content '</' TAGNAME '>' ;
```

`<div>` を開いたら `</div>` で閉じることをパース時に検証する。
現在の手書き実装では `backref` ロジックをカスタムパーサーで書く必要がある。

---

## ランタイム設計: `ScopeStore`

生成パーサーが利用するランタイムクラスを `org.unlaxer.dsl.runtime.ScopeStore` として提供する。

```
ScopeStore
  enter(ParseContext)          — スコープレベルを1つ深くする
  leave(ParseContext)          — スコープレベルを1つ浅くする
  declare(ParseContext, name)  — 現在スコープにシンボルを登録
  isDeclared(ParseContext, name) — スコープチェーンを検索して宣言済みか確認
  currentScopeDepth(ParseContext) — 現在のネスト深さ
```

**内部構造（globalScopeTreeMap に格納）:**

```
Key: ScopeStore.SCOPE_STACK
Value: Deque<Map<String, SymbolInfo>>
  — 先頭が最内側スコープ
  — SymbolInfo: { name, ruleName, sourceOffset }
```

**スナップショット整合性:**

`globalScopeTreeMap` はパーサーのロールバック時に `ParseContext.Snapshot` から復元される。
スタック内の各 `Map` は Snapshot 生成時に浅いコピーが取られるため、
`declare()` で追加したエントリはロールバック時に自動的に巻き戻される。

---

## 生成コードの仕様

### `@scopeTree(mode=lexical)` 付きルール

```java
// 生成されるパーサークラス（例: BlockParser）
public static class BlockParser extends LazyChain
        implements org.unlaxer.listener.TransactionListener {

    @Override
    public void onOpen(ParseContext ctx) {}

    @Override
    public void onBegin(ParseContext ctx, Parser parser) {
        // スコープを1段深くする
        org.unlaxer.dsl.runtime.ScopeStore.enter(ctx);
    }

    @Override
    public void onCommit(ParseContext ctx, Parser parser, TokenList tokens) {
        org.unlaxer.dsl.runtime.ScopeStore.leave(ctx);
    }

    @Override
    public void onRollback(ParseContext ctx, Parser parser, TokenList tokens) {
        org.unlaxer.dsl.runtime.ScopeStore.leave(ctx);
    }

    @Override
    public void onClose(ParseContext ctx) {}
}
```

### `@declares(symbol=varName)` 付きルール

```java
public static class VarDeclParser extends LazyChain
        implements org.unlaxer.listener.TransactionListener {

    @Override
    public void onCommit(ParseContext ctx, Parser parser, TokenList tokens) {
        // @varName キャプチャのテキストを現在スコープに登録
        String name = extractCaptureName(tokens, "varName");
        if (name != null) {
            org.unlaxer.dsl.runtime.ScopeStore.declare(ctx, name);
        }
    }
    // onBegin / onRollback / onOpen / onClose は no-op
}
```

### `@backref(name=varName)` 付きルール（スコープ参照モード）

```java
public static class VarRefParser extends LazyChain {
    // パース自体は通常通り行い、パース後に診断情報を追加
    @Override
    protected Parsed afterCommit(ParseContext ctx, Parsed parsed) {
        String name = extractCaptureName(parsed, "varName");
        if (name != null && !org.unlaxer.dsl.runtime.ScopeStore.isDeclared(ctx, name)) {
            ctx.addDiagnostic(ParseDiagnostic.warning(
                parsed.getRootToken(), "未定義のシンボル: " + name));
        }
        return parsed;
    }
}
```

### `@backref(name=tagName)` 付きルール（後方参照モード）

```java
public static class ElementParser extends LazyChain {
    // 後方参照: 先行する @tagName キャプチャと末尾の TAGNAME が一致することを検証
    // 生成コードは Backref バリデーターを LazyChain 末尾に挿入する
}
```

---

## 手書き実装ガイド（フレームワーク利用者向け）

`@scopeTree` を使わずに直接 `TransactionListener` + `ParseContext` で
スコープ管理を実装する場合のパターン。

### 基本パターン

```java
// 1. スコープストアを定義
public class MyScope {
    private static final Name KEY = Name.of(MyScope.class, "store");

    @SuppressWarnings("unchecked")
    private static Map<String, Object> store(ParseContext ctx) {
        return (Map<String, Object>) ctx.getGlobalScopeTreeMap()
            .computeIfAbsent(KEY, k -> new HashMap<>());
    }

    public static void declare(ParseContext ctx, String name, Object info) {
        store(ctx).put(name, info);
    }

    public static Optional<Object> resolve(ParseContext ctx, String name) {
        return Optional.ofNullable(store(ctx).get(name));
    }
}

// 2. 宣言パーサーに TransactionListener を実装
public class DeclParser extends LazyChain implements TransactionListener {

    @Override
    public void onCommit(ParseContext ctx, Parser parser, List<Token> tokens) {
        String name = extractName(tokens);  // トークンから識別子を取り出す
        MyScope.declare(ctx, name, buildInfo(tokens));
    }

    @Override public void setLevel(OutputLevel level) {}
    @Override public void onOpen(ParseContext ctx) {}
    @Override public void onBegin(ParseContext ctx, Parser parser) {}
    @Override public void onRollback(ParseContext ctx, Parser parser, List<Token> tokens) {}
    @Override public void onClose(ParseContext ctx) {}
}

// 3. 参照パーサーでストアを検索
public class RefParser extends LazyChain {
    @Override
    public Parsed parse(ParseContext ctx, TokenKind kind, boolean invert) {
        Parsed parsed = super.parse(ctx, kind, invert);
        if (parsed.isSucceeded()) {
            String name = extractName(parsed);
            if (MyScope.resolve(ctx, name).isEmpty()) {
                // 未定義 → 診断情報を追加、またはパース失敗として扱う
            }
        }
        return parsed;
    }
}
```

### ロールバック整合性について

`ParseContext.getGlobalScopeTreeMap()` に格納したオブジェクトは、
`ParseContext.Snapshot` に **浅いコピー** として保存される。

- `Map<String, Info>` を value として入れた場合:
  - Snapshot 時点の **キーセット** は保存される（ロールバックで以前のキーセットに戻る）
  - Map の **中身（Info オブジェクト自体）** は参照共有なので注意が必要
- `Deque<Map>` のような多段構造は Snapshot で正しく復元されない

**推奨**: value には新しい Map をそのまま入れる（`new HashMap<>()` ベース）。
多段構造が必要な場合はスコープ深さをキーに含める（`"scope_0_varName"` など）。

### TransactionListener の登録

パーサーが `TransactionListener` を実装している場合、
`ParseContext` にリスナーとして登録する必要がある:

```java
// ParseContext にリスナーとして登録（通常は最初の parse() 呼び出し前）
parseContext.getTransactionListenerByName()
    .put(Name.of(DeclParser.class, "listener"), myDeclParser);
```

生成パーサーでは `@scopeTree` / `@declares` 付きルールに対してこの登録が自動的に行われる。

---

## 実装ステップ

### Phase 1: ランタイム + `@declares` アノテーション（unlaxer-dsl）

1. `runtime/ScopeStore.java` — スコープスタック管理クラス
2. `UBNFAST.DeclaresAnnotation(String symbolCapture)` — sealed hierarchy に追加
3. `UBNFParsers.DeclaresAnnotationParser` — `@declares(symbol=IDENTIFIER)` 構文
4. `UBNFMapper` — `DeclaresAnnotation` マッピング
5. `ParserGenerator` — `@scopeTree` / `@declares` 付きルールに `TransactionListener` 生成
6. `ParserGenerator` — `@backref` 付きルールに診断コード生成（スコープ参照モード）

### Phase 2: LSP 統合（unlaxer-dsl + tinyexpression）

7. 生成パーサーが `SymbolTable` を構築するコードを生成
8. LSP サーバーが `SymbolTable` から go-to-definition / find-references を提供
9. `@backref` 失敗を LSP diagnostics に変換

### Phase 3: tinyExpression 移行

10. tinyExpression の UBNF 文法に `@scopeTree` / `@declares` / `@backref` を追加
11. `VariableDeclarationParser` の手書き TransactionListener を削除
12. `ExclusiveNakedVariableParser` の宣言検索ロジックを削除
