# Snapshot cache と編集後の無効化

Java / Rust は、上限付きの **同一 snapshot の全文結果再利用**と、呼び出し側が管理する
chunk memo を提供する。編集された文書は全再解析する。部分 CST を編集位置に合わせて
再配置・接合する機能、依存範囲を計算する invalidation-range API は両版とも未実装。
旧 Java `IncrementalParseCache` にもこれらの機能はなく、chunk 分割と text-keyed memo
だけがあった。chunk の一致だけで文書全体の受理結果を決定してはならない。

## SnapshotParseCache

Java: `org.unlaxer.parser.incremental.SnapshotParseCache<T>`

Rust: `unlaxer_runtime::incremental::SnapshotParseCache<T>`

鍵は snapshot の **URI、version、原文全体**と、明示した grammar / profile / state
revision の完全一致。grammar revision には使用する entry、文法定義、解析オプションを、
state revision には結果に影響する外部状態を含める責任が呼び出し側にある。
同じ version で原文が変わっても miss。同じ原文で version だけが変わっても miss。
同時に存在する異なる snapshot は、それぞれ独立した結果を保持できる。

```java
var cache = new SnapshotParseCache<String>(64, 8192, value -> value);
var revisions = new SnapshotParseCache.Revisions("grammar-v2/root/options", "profile-v1", "symbols-v7");
String result = cache.getOrCompute(snapshot, revisions, () -> parseToImmutableResult(snapshot));
```

```rust
use unlaxer_runtime::incremental::{Revisions, SnapshotParseCache};
let mut cache = SnapshotParseCache::<String>::new(64, 8192);
let revisions = Revisions {
    grammar: "grammar-v2/root/options".into(),
    profile: "profile-v1".into(),
    state: "symbols-v7".into(),
};
let result = cache.get_or_try_compute(&snapshot, &revisions, || parse_to_owned_result(&snapshot))?;
```

- Java は渡した copier、Rust は `Clone` で store / hit の双方をコピーする。
  可変値は独立したコピーになる copier / Clone が必要。immutable な値は共有してよい。
  活動中の `ParseContext`、外部の可変 object を共有する AST、浅いコピーしかできない
  side-map を含む Token を、そのまま安全な cache 値と見なしてはならない。
- 計算が Java で throw / Rust で `Err` なら新しい結果を保存しない。以前の entry は保持。
  構文不成立を表す所有済みの通常値は保存できる。cache は副作用の再生や外部状態の
  rollback を行わないため、計算は再利用可能な純粋な観測結果を返すこと。
- `invalidate(uri)` はその文書の全 entry、`invalidateAll` / `invalidate_all` は全 entry を
  破棄する。これらは統計を維持し、`clear` は統計も 0 に戻す。
- 件数と保持原文の UTF-16 単位総数を制限する。LRU で退避し、上限を単独で超える文書は
  計算しても保持しない。件数 0 は cache 無効化。空文書は source 上限 0 でも件数上限内で
  保存可能。URI・revision・任意の結果 payload の heap サイズを制限する API ではない。
- Java 操作は synchronized。Rust は `&mut self` で排他的に使用し、共有時は呼び出し側で
  Mutex などを使用する。Rust の検索は最大 entry 数に制限された線形探索で、原文は厳密比較。
- snapshot の不正 UTF-16 は Java の `DocumentSnapshot` が拒否する。Rust `String` は
  有効な UTF-8 のみ。原文の正規化や CRLF の LF 化はしない。

## 生成 classic LSP

両版とも cache 上限は 128 entry / 原文 1,048,576 UTF-16 単位。
同じ snapshot の didSave / 再検証では結果を再利用し、新 version の didOpen / didChange、
close、成功した設定更新、明示した state の無効化で再解析する。古い version や full-sync
契約に反する ranged change は文書と version を更新しない。provider の追加診断や
semantic query はこの構文 cache の対象外で、その都度実行する。

Java の `parseDocument` / `parseDocumentIncremental` は同じ安全な全文 cache を使う。
`parseCacheStats()` / `invalidateParseCache()`、必要に応じた `parseStateRevision()` を提供する。
不明な外部 token/parser や import がある文法では既定で cache を無効化し、host が契約を
保証するときだけ `parseCacheEnabled()` を override できる。既存の `DocumentState.cache()`
は互換性のため残すが、生成 LSP は chunk を蓄積しない。parse 計算が例外で終了した場合は
文書と version を commit せず、`ParseContext` も close する。

Rust `GrammarBackend` は callback のない文法だけを自動 cache 対象とする。実際の
`SharedGrammar` の強参照、root、空白設定を鍵に含め、同じ pointer の再割当てや mutable
更新を誤って同一文法と扱わない。`Custom` / `CustomWith` は診断再試行の安全性とは別に
cache 対象外。一般の `Backend` は既定で opt-out で、純粋性を保証する host だけが
`validation_cache_key()` と revision を返せる。`Server::backend_mut()` は変更前に cache を
無効化する。`validation_cache_stats()` / `invalidate_parse_cache()` も提供する。

## 既存 chunk API の範囲

Java `IncrementalParseCache`、Rust `incremental::IncrementalParseCache<T>` は共通の
quote-aware 分割、text 完全一致の put/get/isCached/clear、hit/miss 統計を提供する。
既定上限は 1024 entry / 原文 1,048,576 UTF-16 単位で、constructor で変更可能。
区切りは単一 UTF-16 unit の文字だけで、引用符・backslash escape を扱う。言語固有の
comment や括弧の入れ子、複数文字や補助平面文字の区切りは扱わない。

chunk の再利用は、独立に解析できる文法・状態・source 座標を呼び出し側が保証した場合
だけ有効。Java は互換性のため Token を参照で返し、Rust は所有値を Clone して返す。
Java の hash は補助情報として残すが、lookup は原文完全一致に変更した。不正 UTF-16 が
UTF-8 化で同じ置換 byte になる場合も異なる原文を取り違えない。

## 共通証拠

- `spec-corpus/incremental-cache/edits.json`: 27 snapshot/edit/revision ケース。insert/delete/
  replace、CRLF、補助文字、EOF、空入力、受理/拒否、grammar/profile/state、並行 snapshot、
  回復を、生成 Java / Rust の全再解析と独立の CST/AST/span/診断/回復期待値と CP/UTF-16/UTF-8 座標に照合。
- `lsp.tsv`: 21 操作。生成 Java LSP と Rust Server の hit/miss/保持数、現文書を同じ oracle
  で比較。設定成功/失敗と外部state revision変更も比較。既存 Content-Length 実通信試験も実行する。
- `chunks.tsv`: 11 分割ケースを両 runtime API で実行。
- copy 分離、例外後の保持、LRU、保持上限、disabled cache、並行 snapshot とカスタム
  parser の外部状態変化は各言語の低水準テストでも検証。

```sh
RUSTUP_TOOLCHAIN=1.85.0 mvn -pl unlaxer-common,unlaxer-dsl -am test \
  -Dtest=SnapshotParseCacheTest,IncrementalParseCacheTest,IncrementalCacheConformanceTest,ClassicLspProtocolConformanceTest \
  -Dsurefire.failIfNoSpecifiedTests=false -DrustConformance=true
cargo +1.85.0 test --locked --manifest-path rust/Cargo.toml -p unlaxer-runtime --test incremental
cargo +1.85.0 test --locked --manifest-path rust/Cargo.toml -p unlaxer-lsp
```

素の Maven 実行では新しい Java oracle を実行し、Rust 生成コードとの cross-check は
`-DrustConformance=true` が必要。真の部分再解析を完了したという証拠にはしない。

LSP設定の成功時だけcacheを無効化し、設定失敗時は現在の設定とcacheを保持する。
Java/Rustとも外部state revisionが変わるとLSP cacheを全無効化する。
Javaの通知処理は既存の `parseDocument` / `parseDocumentIncremental` overrideを経由し、
例外が返ったときは文書とversionを直前の状態に戻す。`NonOrdered` 内のcustom callbackも
snapshot cache対象外となり、安全性検査が子parserを飛ばさないことを回帰検証する。
