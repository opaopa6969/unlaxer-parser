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
