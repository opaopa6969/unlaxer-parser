# 明示的な nested capture の runtime 境界（#609）

Java / Rust に value、optional、list の CST 境界を保存する明示 API を追加します。既存の flat capture、owned capture/replay、生成 record の field 型は変更しません。UBNF の nested opt-in、再帰型 schema、typed mapper / evaluator / commonField / typeof / preferred の接続は親 #583 の後続作業です。これはその前提となる、手書き runtime から使用できる単位です。

```java
import static org.unlaxer.parser.combinator.CaptureBoundaryParser.*;
Parser parser = optional(list(new WordParser("a")));
var parsed = parser.parse(context); // 成功を確認してから snapshot を取得する。
// 成功した CST の root token から、context を閉じても使える immutable 値を作る。
CaptureSnapshot.Element value = CaptureSnapshot.from(parsed.getRootToken());
```

```rust
let expression = Expr::literal("a").capture_list(0, None).capture_optional();
let parsed = context.parse(&expression)?;
let tree = context.tree(parsed.nodes[0]).unwrap();
let value = capture_tree::snapshot(&tree, parsed.nodes[0])?;
```

| 形 | 空入力 | `aa` |
|---|---|---|
| optional(value(`a`)) | Optional([]) = 欠損 | Optional([Value(`a`)])、prefix 1 CP |
| list(value(`a`)) | List([]) = 空 list | List([`a`, `a`]) |
| optional(list(value(`a`))) | Optional([List([])]) = 存在する空 list | Optional([List([`a`, `a`])]) |
| list(optional(value(`a`))) | List([Optional([])]) | List([Optional([`a`]), Optional([`a`]), Optional([])]) |

最後の行は既存 Java repetition の規約です。成功した zero-progress item を一つ保持して停止するため、末尾の欠損 optional を消しません。max=0 の list は子を一度も評価せず空になります。通常 parser の prefix / EOF 契約も変えません。

`value` / `capture_value` は、子の成功全体を一つの leaf にします。optional/list の convenience API は、直接の capture boundary を structured item として保持し、それ以外の parser を value に包みます。group や separator を明示的に扱う場合は Java `wrap(Kind, child)` / Rust `Expr::CaptureBoundary(kind, child)` を使用し、残す item をそれぞれ boundary にします。snapshot は plain CST wrapper を透過して最寄りの boundary を順序どおり集め、value の内部は leaf として扱います。separator/trivia の非 boundary token を item として追加しません。optional に二つ以上の boundary が現れる構造は snapshot で拒否します。

## 原文・CP と transaction

CST 生成時に境界を記録します。snapshot を作る時に入力を再 parse せず、同一位置の空値や重複を区別します。snapshot の各 element は boundary kind、token kind、原文、半開 CP 範囲、生成 text かどうか、immutable な child 列を持ちます。Java は token に小さな immutable origin metadata を保持し、原文のコピーは snapshot 要求時だけ行います。Rust は reserved rule ID と既存 token source を使います。

通常の Input と Projected は既存 collector の論理 CP 範囲を保持します。Projected を元入力の slice として読み直しません。原文のない nonempty Generated text は zero-width anchor と実 text を保持します。既存の virtual 空 collector の anchor=0 も維持します。この範囲を UTF-16 や表示幅と解釈しません。

4 token kind、inversion、consume stopper、外側 trivia は既存 Classic 契約を継承します。kind の異なる子が collector から除外されれば、その境界も snapshot の item には入りません。純粋 PEG lookahead、失敗した choice / sequence の rollback、owned capture と通常 flat capture は既存 transaction に従います。新しい global mode や capture の共有 lifetime は追加しません。

## 制限と検証

snapshot traversal は depth < 256、訪問数 <= 65,536、保持する全 element の UTF-8 text 合計 <= 2 MiB に制限します。大きな親 text と子 text はそれぞれ合計へ数えます。この 2 MiB は保持 text の上限であり、全 heap 使用量ではありません。Rust は予備 traversal で必要な Input span の端点だけを集め（訪問上限の最大 2 倍）、昇順に一度だけ原文を走査します。入力全体の CP 座標表は作らず、一時領域は訪問数に比例します。Java の標準 StringSource は既存 CP slice から byte 数を検査してから選択 slice だけを String 化します（独自 Source 実装の内部 allocation はその実装の契約です）。Java は `CaptureSnapshot.SnapshotException` の `error()`、Rust は `capture_tree::Error` で NotBoundary / InvalidTree / Limit を区別します。snapshot 失敗は解析済み context / CST を変更しません。既存 parser の資源制限は別に働きます。Java の ParseLimitException 後は context を閉じ、Rust の sticky resource failure 後は新しい context を使用します。

`conformance/capture-boundaries/runtime.tsv` の独立 104 行は、両言語の全 4 kind、memo 2 種、diagnostics 3 種で同じ構造・原文・CP・成功失敗・両 cursor を比較します。Rust は trace 有無も比較します。空/欠損/重複/順序、nested optional/list、group、補助文字/CRLF、trivia、inversion、stopper、Projected、Generated、zero/invalid bounds、rollback を含みます。実 memo failure hit と診断/後続再解析も確認します。追加試験は owned capture と先読み、context 破棄後の snapshot、深い parser / snapshot、不正 optional、容量制限を検証します。

既存 flat capture の生成 consumer と Playground runtime 同梱は別途回帰検証します。#609 だけで #583 / #568 を完了扱いにしません。
