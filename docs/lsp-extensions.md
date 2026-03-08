# LSP Extensions — 拡張ランゲージサーバー機能

> 最終更新: 2026-03-08
> ステータス: draft — バックログアイテム化済み

拡張LSP機能の実装ロードマップ。ScopeStore API を活用したセマンティック機能群。

---

## 概要

現在の実装状態:
- ✅ completion, hover, codeAction
- ✅ definition, references（ScopeStore 活用）
- ✅ semanticTokens

残りの実装候補（優先度順）:
1. **documentSymbol** — 式/変数/メソッドのアウトライン表示
2. **rename** — 変数・メソッド名の一括リファクタリング
3. **documentHighlight** — 同一識別子の全出現箇所ハイライト
4. **signatureHelp** — メソッド呼び出し時のパラメータヒント
5. **codeLens** — 式の評価結果表示（DAP連携）

---

## LSE-1: documentSymbol（アウトライン / ツリービュー）

### 概要

VS Code のエクスプローラーパネル「アウトライン」で、式に含まれる変数・メソッド・注釈を階層表示。

### 仕様

**LSP 対応メソッド**: `textDocument/documentSymbol`

**戻り値**: `DocumentSymbol[]`

```java
record DocumentSymbol(
    String name,
    SymbolKind kind,        // Variable, Method, Class, etc.
    Range range,            // シンボル宣言範囲
    Range selectionRange,   // ハイライト時のキャレット位置
    List<DocumentSymbol> children  // 子シンボル（メソッド内のパラメータなど）
) {}
```

### 表示イメージ

```
Formula
├─ variables
│  ├─ $age (Number)
│  ├─ $name (String)
│  └─ $enabled (Boolean)
├─ methods
│  ├─ isAdult($age) → Boolean
│  └─ greet($name) → String
└─ annotations
   └─ @Cacheable(ttl=3600)
```

### 実装詳細

**入力**: `DocumentSymbolParams`

**処理フロー**:
1. `ExtDocumentState` から `declarations: List<ScopeStore.SymbolInfo>` を取得
2. 各 `SymbolInfo` を `DocumentSymbol` に変換
   - `name` ← `SymbolInfo.name()`
   - `kind` ← シンボル型判定（方法: UBNF パーサー型から推定）
   - `range` ← `SymbolInfo.sourceOffset()` + token length
   - `selectionRange` ← `range` と同じ
3. `List<DocumentSymbol>` として返す

**シンボル型判定ロジック**:

| パーサー型 | SymbolKind | 備考 |
|-----------|-----------|------|
| `NumberVariableDeclaration` / `StringVariableDeclaration` 等 | `Variable` | 変数宣言 |
| `NumberMethodDeclaration` / `StringMethodDeclaration` 等 | `Method` | メソッド宣言 |
| `MethodParameter` | `Variable` | メソッドパラメータ |
| `ImportDeclaration` | `Class` | インポート（クラス） |
| `Annotation` | `Class` | アノテーション |

**検出方法**:
- `ScopeStore.getAllDeclarations()` が返す `SymbolInfo` には source position 情報のみ
- Token 型を推定するため、document state で保持するパーサー型メタデータが必要
  OR UBNF AST から直接シンボル種を抽出（推奨）

**実装難度**: M（ScopeStore API 活用で容易）

### 実装箇所

**unlaxer-dsl 側** — 不要（generator のみ）

**tinyexpression 側**:
- `TinyExpressionP4LanguageServerExt.TextDocumentServiceImpl#documentSymbol()`

**新規 method**:
```java
@Override
public CompletableFuture<Either<List<SymbolInformation>, List<DocumentSymbol>>> documentSymbol(
    DocumentSymbolParams params) {
  // uri → ExtDocumentState 取得
  // declarations から DocumentSymbol[] 構築
  // LSP Either で DocumentSymbol[] を返す
  return CompletableFuture.completedFuture(Either.forRight(symbols));
}
```

---

## LSE-2: rename（リファクタリング）

### 概要

カーソル位置の変数・メソッド名を全箇所で一括変更。

### 仕業

**LSP 対応メソッド**: `textDocument/rename`

**入力**: `RenameParams` = `{ uri, position, newName }`

**戻り値**: `WorkspaceEdit` = 複数ファイルへの TextEdit 一括指定

```java
record WorkspaceEdit(
    Map<String, List<TextEdit>> changes  // { uri → [TextEdit, ...] }
) {}

record TextEdit(
    Range range,        // 変更範囲
    String newText      // 新しいテキスト
) {}
```

### 処理フロー

1. `position` の単語を取得（`wordAt()` 既存 helper 使用）
2. `ExtDocumentState.declarations` から `name` 一致するシンボルを検索 → 定義位置取得
3. `ExtDocumentState.references` から同じ `name` の全参照を検索 → 参照位置リスト作成
4. 定義位置 + 全参照位置を `TextEdit[]` に変換
5. `WorkspaceEdit` 構築（現在のファイルのみ → `uri` キー1つ）

### 実装詳細

**制約**:
- 単一ドキュメント対応（複数ファイル横断は今後）
- パラメータ妥当性チェック（未使用パラメータ rename は許可するが、警告）

**バリデーション**:
- 新しい名前が妥当か（identifierルール準拠）
- 重複定義を作らないか → 変更対象位置の確認のみ（runtime は除外）

**実装難度**: M（ScopeStore API + TextEdit 構築）

### 実装箇所

**tinyexpression 側**:
- `TinyExpressionP4LanguageServerExt.TextDocumentServiceImpl#rename()`

**新規 method**:
```java
@Override
public CompletableFuture<WorkspaceEdit> rename(RenameParams params) {
  // position の単語取得
  // declarations/references から一致する name を検索
  // TextEdit[] 構築
  // WorkspaceEdit { uri → [TextEdit] } で返す
  return CompletableFuture.completedFuture(edit);
}
```

---

## LSE-3: documentHighlight（同一識別子ハイライト）

### 概要

カーソル位置の変数名と同じ名前の全出現箇所をハイライト表示。

### 仕様

**LSP 対応メソッド**: `textDocument/documentHighlight`

**戻り値**: `DocumentHighlight[]`

```java
record DocumentHighlight(
    Range range,                    // ハイライト範囲
    DocumentHighlightKind kind      // Text / Read / Write
) {}

enum DocumentHighlightKind {
    Text,   // 通常参照
    Read,   // 読み込み専用
    Write   // 書き込み（代入箇所）
}
```

### 表示イメージ

```
var $age as number set 42 description = "...";
    ^^^
if ($age > 18) {    // ← ハイライト
    ^^^
  return $age + 1;  // ← ハイライト
         ^^^
}
```

### 処理フロー

1. `position` の単語を取得
2. `ExtDocumentState.references` から同じ `name` の全参照を検索
3. 各参照を `DocumentHighlight` に変換（全て `Text` kind）
4. `DocumentHighlight[]` として返す

### 実装難度

S（references 既存、単なるマッピング）

### 実装箇所

**tinyexpression 側**:
- `TinyExpressionP4LanguageServerExt.TextDocumentServiceImpl#documentHighlight()`

---

## LSE-4: signatureHelp（パラメータヒント）

### 概要

メソッド呼び出し時に、括弧を入力するとパラメータ情報をポップアップ表示。

### 仕様

**LSP 対応メソッド**: `textDocument/signatureHelp`

**戻り値**: `SignatureHelp`

```java
record SignatureHelp(
    List<SignatureInformation> signatures,
    Integer activeSignature,        // 現在アクティブなシグネチャ index
    Integer activeParameter         // 現在アクティブなパラメータ index
) {}

record SignatureInformation(
    String label,                  // "methodName(param1, param2) → returnType"
    String documentation,          // ホバーテキスト
    List<ParameterInformation> parameters
) {}

record ParameterInformation(
    String label,                  // "paramName as Type"
    String documentation
) {}
```

### 表示イメージ

```
call add($
            ↓ hint popup
            add($a as number, $b as number) → number
                ↑ activeParameter = 0
```

### 処理フロー

1. `position` 付近で `(` 直前のトークンを探す → メソッド名取得
2. `ScopeStore.resolve()` でメソッド定義を検索
3. メソッド AST ノードからパラメータリスト抽出（UBNF メタデータ）
4. 括弧内のカーソル位置からパラメータ index 計算
5. `SignatureHelp` 構築

### 実装難度

M（AST traverse, parameter extraction 必須）

### 実装箇所

**tinyexpression 側**:
- `TinyExpressionP4LanguageServerExt.TextDocumentServiceImpl#signatureHelp()`

---

## LSE-5: codeLens（評価結果表示）

### 概要

式の実行結果をコード上に "code lens" として表示。DAP と連携して runtime 値を表示。

### 仕様

**LSP 対応メソッド**: `textDocument/codeLens`

**戻り値**: `CodeLens[]`

```java
record CodeLens(
    Range range,           // lens が表示される範囲
    Command command,       // クリック時に実行するコマンド
    Object data            // lens refresh 時の状態保持用
) {}

record Command(
    String title,         // 表示テキスト（例: "= 42"）
    String command,       // コマンド ID
    List<Object> arguments
) {}
```

### 表示イメージ

```
expression = (10 + 20) * 3
                           ↑ code lens: "= 90"

if ($age > 18) { ... }
   ↑ code lens: "$age = 42"
```

### 処理フロー

**複雑度**: 高（DAP インテグレーション必須）

1. パース後、expression AST ノード群を列挙
2. 各 expression に対して code lens を発行
3. ユーザークリック時に DAP evaluator に値の評価をリクエスト
4. 評価結果を表示

### 実装難度

L（DAP 連携、evaluator API 理解必須）

### 実装箇所

**unlaxer-dsl 側**:
- CodeLens generator 追加（オプション）

**tinyexpression 側**:
- `TinyExpressionP4LanguageServerExt.TextDocumentServiceImpl#codeLens()`
- DAP runtime bridge との連携

---

## 実装優先度表

| ID | 機能 | 規模 | 難度 | 優先度 | 利用API | ブロッカー |
|----|------|------|------|--------|---------|-----------|
| LSE-1 | documentSymbol | M | 低 | ⭐⭐⭐ | ScopeStore.getAllDeclarations | なし |
| LSE-2 | rename | M | 中 | ⭐⭐⭐ | ScopeStore.getAllDeclarations/References | なし |
| LSE-3 | documentHighlight | S | 低 | ⭐⭐ | ScopeStore.getAllReferences | なし |
| LSE-4 | signatureHelp | M | 中 | ⭐⭐ | ScopeStore.resolve + AST traverse | なし |
| LSE-5 | codeLens | L | 高 | ⭐ | DAP evaluator | DAP 実装完了 |

---

## 実装計画（tinyexpression への適用）

### Phase 1（優先度⭐⭐⭐）
- **LSE-1**: documentSymbol — アウトラインパネル実装
- **LSE-2**: rename — リファクタリング機能実装

### Phase 2（優先度⭐⭐）
- **LSE-3**: documentHighlight — 同一識別子ハイライト
- **LSE-4**: signatureHelp — パラメータヒント

### Phase 3（優先度⭐）
- **LSE-5**: codeLens — DAP連携評価表示（後続）

---

## Tier 4 — 拡張 LSP 機能（2026-03-08 Session 2 追加）

### LSE-EXT-1: signatureHelp 拡張（パラメータ型情報）

**概要**: 現在の signatureHelp は簡略表示。AST から実際のパラメータ型情報を抽出して表示。

**仕様**:
- 現在: `method() → any`
- 目標: `method($param1 as number, $param2 as string) → number`

**実装アプローチ**:
1. `ScopeStore.resolve()` でメソッド定義の AST ノードを取得
2. MethodParameter 子ノードを走査してパラメータリスト抽出
3. 各パラメータの型情報（ReturnType）を抽出
4. SignatureInformation に ParameterInformation 配列を追加
5. 括弧内のカーソル位置から activeParameter index を計算

**実装難度**: M
**優先度**: ⭐⭐⭐（signatureHelp の完全機能化）

---

### LSE-EXT-2: codeLens DAP 連携

**概要**: codeLens から DAP evaluator を呼び出して、実際の評価結果をインラインで表示。

**仕業**:
1. codeLens の command クリック時に DAP evaluator を実行
2. 非同期で評価結果を取得
3. CodeLens 結果を LSP クライアントに返却（resolveCodeLens）
4. VS Code に「= 42」のような結果を表示

**実装アプローチ**:
```java
// codeLens: 基本的な lens generation
@Override
public CompletableFuture<List<? extends CodeLens>> codeLens(CodeLensParams params) {
  // ... lens generation with null command
  return CompletableFuture.completedFuture(lenses);
}

// codeLensResolve: DAP 連携で command を追加（非同期）
@Override
public CompletableFuture<CodeLens> resolveCodeLens(CodeLens unresolved) {
  // Expression AST の offset から evaluator を呼び出し
  // 結果を Command.title に設定
  String result = evaluateExpressionAtOffset(...);
  unresolved.setCommand(new Command("= " + result, ...));
  return CompletableFuture.completedFuture(unresolved);
}
```

**実装難度**: L（DAP bridge との統合）
**優先度**: ⭐⭐（値の可視化）
**依存**: TinyExpressionDapRuntimeBridge の evaluator API

---

### LSE-EXT-3: documentSymbol 子シンボル対応

**概要**: 現在は単純な DocumentSymbol[]。メソッド内のパラメータを子シンボルとして表示。

**仕業**:
```
Formula
├─ variables
│  ├─ $age (Number)
│  └─ $name (String)
├─ methods
│  ├─ isAdult($age: Number) → Boolean
│  │  └─ $age (Parameter)
│  └─ greet($name: String) → String
│     └─ $name (Parameter)
└─ annotations
```

**実装アプローチ**:
1. documentSymbol 時に各メソッドの MethodParameter 子ノードを走査
2. DocumentSymbol.children フィールドに ParameterInformation として追加
3. 階層化表示で VS Code の outline tree に反映

**実装難度**: M
**優先度**: ⭐⭐（階層化 UI 向上）

---

### LSE-EXT-4: inlayHints（変数型ヒント）

**概要**: 変数宣言の型情報をコード上にインラインで表示。

**表示イメージ**:
```
var $age as number set 42     // no hint needed (型明記)
var $count = 10     ⟵ : number  // inlay hint
var $name = 'John'  ⟵ : string  // inlay hint
```

**仕業**:
```java
@Override
public CompletableFuture<List<? extends InlayHint>> inlayHint(InlayHintParams params) {
  // declarations から型情報がない変数を検索
  // 初期値の評価から型を推論
  // InlayHint[] として返却
}
```

**実装難度**: L（型推論エンジン必須）
**優先度**: ⭐（オプション機能）

---

## 実装優先度表（Tier 4: 拡張機能）

| ID | 機能 | 規模 | 難度 | 優先度 | 依存 | ステータス |
|----|------|------|------|--------|------|-----------|
| LSE-EXT-1 | signatureHelp 拡張 | M | 中 | ⭐⭐⭐ | AST traverse | ✅ 完了 |
| LSE-EXT-2 | codeLens DAP 連携 | L | 高 | ⭐⭐ | DAP bridge | ✅ 完了 |
| LSE-EXT-3 | documentSymbol 子ノード | M | 中 | ⭐⭐ | AST traverse | ✅ 完了 |
| LSE-EXT-4 | inlayHints 実装 | L | 高 | ⭐ | 型推論 | ✅ 完了 |

---

## Tier 5: さらなる LSP 拡張機能

### LSE-EXT-5: completion 拡張（メソッド・変数補完）

**概要**: 現在はキーワードのみ補完。メソッド名・変数参照・関数を自動補完。

**表示イメージ**:
```
メソッド呼び出し時:
  ism[TAB] ⟹ isAdult(...)    // メソッド補完
  $[TAB]   ⟹ $age, $name ... // 変数補完

変数参照時:
  $a[TAB]  ⟹ $age, $admin    // マッチする変数候補
```

**実装アプローチ**:
```java
@Override
public CompletableFuture<CompletionList> completion(CompletionParams params) {
  // 1. カーソル前の単語を取得（word prefix）
  String prefix = wordAt(content, position);

  // 2. ScopeStore.getAllDeclarations() からメソッド・変数を取得
  List<CompletionItem> items = new ArrayList<>();
  for (SymbolInfo decl : declarations) {
    if (decl.name().startsWith(prefix)) {
      CompletionItem item = new CompletionItem(decl.name());
      item.setKind(inferCompletionKind(decl.name()));
      item.setDetail(inferTypeHint(decl.name()));
      items.add(item);
    }
  }

  // 3. キーワード補完も追加
  items.addAll(keywordCompletions(prefix));

  return CompletableFuture.completedFuture(new CompletionList(items));
}
```

**推定型情報**:
- `is*` / `check*` メソッド → Boolean 返却
- `count*` / `get_number*` → Number 返却
- `get_string*` / `format*` → String 返却
- `$varName` 変数 → 宣言時の型情報

**実装難度**: M
**優先度**: ⭐⭐⭐（開発生産性向上）
**依存**: ScopeStore.getAllDeclarations(), 型推定エンジン

---

### LSE-EXT-6: hover 拡張（型情報・シグネチャ表示）

**概要**: 識別子にマウスホバーで型情報・メソッドシグネチャを表示。

**表示イメージ**:
```
// ホバー時:
var $age = 42        ⟸ Hover: $age: number
var $name = 'John'   ⟸ Hover: $name: string
isAdult($age)        ⟸ Hover: isAdult(age: number) → boolean
$count + 10          ⟸ Hover: expression type: number
```

**実装アプローチ**:
```java
@Override
public CompletableFuture<Hover> hover(HoverParams params) {
  String word = wordAt(content, params.getPosition());

  // 1. 宣言を検索（変数・メソッド）
  Optional<SymbolInfo> decl = declarations.stream()
      .filter(d -> d.name().equals(word))
      .findFirst();

  // 2. 型情報を構築
  String typeInfo = decl
      .map(d -> buildTypeHint(d))
      .orElse("expression");

  MarkupContent content = new MarkupContent("markdown", "```\n" + typeInfo + "\n```");
  return CompletableFuture.completedFuture(new Hover(content));
}

private String buildTypeHint(SymbolInfo decl) {
  if (decl.name().startsWith("is") || decl.name().startsWith("check")) {
    return decl.name() + "() → boolean";
  }
  if (decl.name().startsWith("count")) {
    return decl.name() + "() → number";
  }
  if (decl.name().startsWith("$")) {
    // 変数の推定型
    String type = inferVariableType(decl.name());
    return decl.name() + ": " + type;
  }
  return decl.name();
}
```

**実装難度**: M
**優先度**: ⭐⭐⭐（開発体験向上）
**依存**: ScopeStore, 型推定エンジン

---

### LSE-EXT-7: callHierarchy（メソッド呼び出し階層）

**概要**: メソッドがどこから呼ばれているか、どこを呼んでいるか を階層表示。

**表示イメージ**:
```
callHierarchy の UI:
  ┌─ isAdult(age)
  │  ├─ Incoming Calls (isAdult を呼び出す場所)
  │  │  ├─ Formula フォーミュラ内 (Line 5)
  │  │  └─ someOtherMethod 内 (Line 10)
  │  └─ Outgoing Calls (isAdult が呼び出す)
  │     ├─ $age > 18 (comparison)
  │     └─ （内部メソッド呼び出しがあれば）
```

**実装アプローチ**:
```java
@Override
public CompletableFuture<List<? extends CallHierarchyIncomingCall>>
    incomingCalls(CallHierarchyIncomingCallsParams params) {

  CallHierarchyItem item = params.getItem();
  String methodName = item.getName();

  // 1. getAllReferences() でこのメソッドへの参照を全検索
  List<CallHierarchyIncomingCall> calls = references.stream()
      .filter(ref -> ref.name().equals(methodName))
      .map(ref -> new CallHierarchyIncomingCall(
          new CallHierarchyItem(methodName, SymbolKind.Method, ...),
          List.of(new Range(refStart, refEnd))
      ))
      .collect(toList());

  return CompletableFuture.completedFuture(calls);
}

@Override
public CompletableFuture<List<? extends CallHierarchyOutgoingCall>>
    outgoingCalls(CallHierarchyOutgoingCallsParams params) {

  // フォーミュラ内から他メソッドへの呼び出しを検出
  // （複雑度が高いため、簡易版: 直接呼び出しのみ）
  return CompletableFuture.completedFuture(List.of());
}
```

**実装難度**: L（複数メソッド間の参照トラッキング）
**優先度**: ⭐⭐（大規模フォーミュラ分析時に有用）
**依存**: ScopeStore.getAllReferences(), AST traverse

---

### LSE-EXT-8: foldingRange（コードブロック折りたたみ）

**概要**: if/match ブロックを折りたたみ対象として登録。

**表示イメージ**:
```
if condition then    ⟸ [−]  ← クリックで折りたたみ
  expression1
  expression2
else
  expression3
endif                ⟸ 対応するブロック終了
```

**実装アプローチ**:
```java
@Override
public CompletableFuture<List<? extends FoldingRange>>
    foldingRange(FoldingRangeParams params) {

  List<FoldingRange> ranges = new ArrayList<>();

  // 1. AST を走査して if/match ブロックを検出
  // （Token ツリーから IfExpr, *MatchExpr を探索）

  for (IfExpr ifExpr : findAllIfExpr(ast)) {
    Position start = offsetToPosition(ifExpr.start());
    Position end   = offsetToPosition(ifExpr.end());
    FoldingRange range = new FoldingRange(start.getLine(), end.getLine());
    range.setKind(FoldingRangeKind.Region);
    ranges.add(range);
  }

  // 2. match ブロックも同様
  for (NumberMatchExpr matchExpr : findAllMatchExpr(ast)) {
    // ...
  }

  return CompletableFuture.completedFuture(ranges);
}
```

**実装難度**: S（基本的なブロック検出のみ）
**優先度**: ⭐（オプション機能）
**依存**: AST traverse, Token offset 追跡

---

## Tier 5 実装優先度表

| ID | 機能 | 規模 | 難度 | 優先度 | 依存 |
|----|------|------|------|--------|------|
| LSE-EXT-5 | completion 拡張 | M | 中 | ⭐⭐⭐ | ScopeStore |
| LSE-EXT-6 | hover 拡張 | M | 中 | ⭐⭐⭐ | ScopeStore, 型推論 |
| LSE-EXT-7 | callHierarchy | L | 高 | ⭐⭐ | ScopeStore, AST |
| LSE-EXT-8 | foldingRange | S | 低 | ⭐ | Token offset |

---

## 参考資料

- [LSP Specification](https://microsoft.github.io/language-server-protocol/)
  - `DocumentSymbol`: https://microsoft.github.io/language-server-protocol/specifications/specification-3-17-0/#textDocument_documentSymbol
  - `Rename`: https://microsoft.github.io/language-server-protocol/specifications/specification-3-17-0/#textDocument_rename
  - `DocumentHighlight`: https://microsoft.github.io/language-server-protocol/specifications/specification-3-17-0/#textDocument_documentHighlight
  - `SignatureHelp`: https://microsoft.github.io/language-server-protocol/specifications/specification-3-17-0/#textDocument_signatureHelp
  - `CodeLens`: https://microsoft.github.io/language-server-protocol/specifications/specification-3-17-0/#textDocument_codeLens

- 関連 classes:
  - `org.eclipse.lsp4j.DocumentSymbol`
  - `org.eclipse.lsp4j.WorkspaceEdit`, `TextEdit`
  - `org.eclipse.lsp4j.SignatureHelp`, `SignatureInformation`
  - `org.eclipse.lsp4j.CodeLens`, `Command`
