# 意味解析 query の更新と寿命

Java の `SemanticQueries` と Rust の `semantic_query_cache::SemanticQueries` は、
固定された provider の純粋な query を文書・設定・解析 artifact の入力に結び付ける。
既存 parser、CST、SemanticModel、ProjectSymbolIndex の動作は変更しない。
入力と結果の payload は UTF-8 で表現できる文字列で、JSON や不変 AST の安定 ID を
language provider が選べる。任意のオブジェクトを共有 cache に格納する API ではない。

## 入力・key・依存

`update(projectVersion, inputs)` はプロジェクトの入力集合を一度に置き換える。
Input は URI、非負の文書 / artifact version、value を持つ。同じ slot と URI で
内容を変えるには version を増やす。projectVersion は毎回増やす。入力の削除と
再追加には内部で新しい revision を割り当てる。失敗した update は公開 snapshot を変えない。

Key は query kind、対象 identity、正規化した引数の組である。非同期要求は、その key を作った snapshot version を Java の `evaluate(version, key, token)` /
Rust の `evaluate_at(version, key, token)` に渡す。古い入力位置から作った key を新 snapshot
として解析することを拒否する。version を省く evaluate は同じ editor lane 上で現在の入力から
key を作る同期呼出し向けである。Result はこの Key に
project identity / version と query revision を付けて返す。kind の executor は
データベースの寿命中に差し替えない。grammar、provider の設定、package の lock hash、
AST schema など、結果に影響する可変データは必ず Input として登録する。

Executor は `Context.input(id)` と `Context.query(key)` を通して読む。
存在しない input を読んだことも依存であり、後からその input が追加されると無効化する。
子 query の input 依存は親へ伝播し、直接呼び出した query の key / revision も結果に
記録する。query graph 自体を永久に保持せず、各 cache entry にその依存を保存する。
入力を更新すると、revision が変わった依存を持つ entry を取り除く。
無関係な入力の変更では値を再利用できる。

実行中だけ有効な Context を使って、すべての依存を申告することは provider の契約である。
環境変数、時計、ネットワーク、外部の可変オブジェクトを直接読む executor は cache に
適さない。それらは上位 adapter が snapshot 化して渡す。Java は Context の持出しと
評価中の update を拒否し、Rust は借用期間と可変参照で同じ範囲を制約する。

## 評価・キャンセル・公開

1回の evaluate は cache 登録のトランザクションである。子 query の計算も一時領域へ
置き、最上位が正常終了して cancellation を確認した時点で登録する。キャンセル、
例外、CYCLE、LIMIT、UNSUPPORTED は一時結果を登録しない。provider が子 query の
失敗を捕捉しても、失敗したトランザクションを成功へ変えない。

Cancellation は別スレッドから送れる。入力の読出し、子 query、executor の終了時に
確認する。長時間かかる provider は `checkpoint` で協調する。外部プロセスの強制停止は
外部 adapter が担当する。Java の database 操作は直列化され、Rust は可変借用を使う。

Result の value は履歴の確認用にも読めるが、診断を表示したり補完 edit を適用したり
する直前には `accept(result)` を使う。別 database の結果、古い project snapshot、
キャンセルされた結果、close 済みの結果を拒否する。同じ cache の値を再利用した場合も
新しい Result には現在の project version が付く。`accept` と実際のエディタ反映は
同じ直列化された editor lane で行う。別スレッドへ渡して時間が経過したら再確認する。

文法エラーの診断や部分 AST の UNKNOWN は、正常に計算できた解析データであり、
provider は state を payload に含めて cache できる。query engine 自体の failure と
言語入力の不正を混同しない。source span は payload 内で元の URI / version とともに
保持し、region source map の変更も独立した Input にする。

## 予算と破棄

Limits は cache entry 数、cache の論理 byte 数、現在の Input 数、1回の query 訪問数、
entry 当たりの依存数を指定する。訪問数と依存数は1〜4096、再帰深さは128未満。
query の訪問数には cache hit も含む。任意の provider コードの命令数を制限するものではない。

LRU で entry を破棄する。entry 容量0は cache 無効、byte 予算より大きい結果は返すが
cache に保持しない。予算は payload / key / 依存名の UTF-8 byte 数と、仕様で定める
固定の会計単位を合計した値である。Java heap / Rust allocator の実メモリ量ではない。
入力文書・provider 自身のデータ・評価中の作業領域・呼出し元が保存した Result はこの
cache byte 予算の対象外であり、それぞれの所有者が寿命と上限を管理する。

`close` は入力、cache、保持している executor の参照を解放する。呼出し元へすでに
返した Result はその所有者が破棄する。Stats の hit / evaluated は成功して公開された
トランザクションだけを数える。最大値の数値だけを保持し、過去の文書履歴を保持しない。

## 既存機能との分担

- #184 の parse memo は同じ parse 内の探索を減らす。本 API は編集を跨ぐ意味 query を扱う。
- #211 の CST 保存と #264 の parsing engine 選択には干渉しない。
- AnalysisPipeline の粗い phase 結果を Input にする場合は、pipeline instance identity、
  phase id、公開 revision を合わせて識別する。別 instance の同じ revision は同じ値ではない。
- ProjectSymbolIndex は完全再構築を正しさの基準として保持する。初版 query cache の
  粒度は provider が宣言した入力・query 単位であり、任意の AST 編集を自動で差分化する
  compiler ではない。scope graph や型宣言の入力分割は language adapter が行う。

## 共通検証と計測

[基本 corpus](../spec-corpus/semantic-queries/corpus.json) は29操作の列で、無関係な入力、
負の依存、追加・削除、引数別 key、古い結果、キャンセル、循環、予算、破棄を検証する。
[意味解析の編集列](../spec-corpus/semantic-queries/semantic-edits.json) は12 snapshotで、
実際の SemanticModel / ProjectSymbolIndex を再構築し、型追加・削除・parent 変更、
import、scope、失敗→修復、unknown、region の origin 移動に対する補完と CP span を
手書き期待値・cache 経由・毎回の再構築・Java / Rust の間で照合する。
この corpus の入力は provider が供給する意味 snapshot であり、parser の編集性能の測定ではない。

別に1,000更新と3,000 query を同じ列で cache ON / OFF にして計測する。
ON は3,001評価・1,999 hit、最大4 entry / 464論理byte、OFF は7,000評価・0 hit / 0保持。
終了時は両実装とも Input / cache の保持が0になる。実行時間は各実行の TSV に保存し、
正しさの pass 条件には使わない。Maven の JVM とデバッグ設定の rustc probe、共有実行環境での
小さな workload なので、この数値から実プロジェクトの速度改善や言語間の優劣は主張しない。

```sh
mvn -B -pl unlaxer-common,unlaxer-dsl -am test \
  -Dtest=SemanticQueriesConformanceTest,SemanticQueryEditsTest \
  -DrustConformance=true -Dsurefire.failIfNoSpecifiedTests=false
cargo +1.85.0 test --locked --manifest-path rust/Cargo.toml \
  -p unlaxer-runtime --test semantic_queries
```

CI は共通出力 `rust-semantic-queries.tsv` / `rust-semantic-query-edits.tsv` と、
`java-query-retention-metrics.tsv` / `rust-query-retention-metrics.tsv` を必須 artifact として保存する。
別スレッドのキャンセル、容量0、結果が予算を超える場合、失敗した update の原子性、
Rust の通常 test thread stack 上での深さ制限も検証する。
