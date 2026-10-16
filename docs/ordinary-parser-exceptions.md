# 通常 callback 失敗と解析状態の復元

Java の組み込み parser に入った後で子 parser の callback が通常の例外を投げた場合、最外の解析呼び出しが公開する前の状態へ戻し、元の例外オブジェクトを呼び出し元へ再送出する。`RuntimeException` と `AssertionError` を検証対象とする。`VirtualMachineError`、メモリ枯渇、プロセス停止からの復旧を保証するものではない。

復元対象は consumed/matched CP cursor、公開 CST、解析・transaction frame、登録した `TransactionalState`、portable token metadata と関連 ID、owned capture と scope、selection 状態である。失敗した子が残した内部 frame は巻き戻すが、呼び出し側が先に開いた manual transaction は保持する。その transaction を含め、通常例外の伝播が完了した context は再利用できる。失敗中に発行した token ID は無効になり、失敗前に取得した metadata snapshot は変わらない。

組み込み境界の内側で例外を catch し、成功として返す子がいても、外側の解析 guard が unwind し終わるまでは最初の例外が優先される。これは、失敗を一部だけ隠して破損状態を公開しないためである。callback の呼び出しより前の引数検証エラーは、この解析中の復元契約とは別である。任意の custom root が組み込み parser の入口をすべて迂回する場合にまで、自動的な例外境界は追加されない。

登録状態の checkpoint と restore は例外を投げず、transaction 構造を変更しない契約である。未登録の host の変数、外部 I/O、fixture の故障注入スイッチなどは復元対象ではない。observer の fatal failure と、資源上限による sticky failure は通常例外とは別の既存契約であり、通常例外後の再利用保証をそれらへ適用しない。

## Java 例外と Rust `Result::Err` の違い

Rust の `ParseResult::Err` は構文不一致を表す通常の制御フローである。choice は別の候補を試し、optional と repetition は不一致を吸収でき、negative assertion は不一致を成功へ変える。Java callback の例外はこれらの構文不一致とは区別され、途中の組み込み境界から呼び出し元まで伝播する。同じ入力でも成功・失敗の結果を機械的に同一視しない。

対応する復元保証は、失敗した branch の登録状態・metadata・owned capture の変更が公開されず、最終的に失敗した root の CP cursor と CST が開始点へ戻ることである。Rust の明示的 `transaction` は closure が `Err` を返した場合に復元する。Java の手動 begin/rollback に対応する観測を、この lexical closure API で検証する。Java の子が begin したまま例外を投げる abandoned frame は別途検証し、Rust に存在しない同一 API を仮定しない。

Rust callback が panic した context の再利用は保証しない。unwind を捕捉する host は、その context を破棄し、新しい context を使う。テストは context を `catch_unwind` の closure 内で所有させ、unwind による破棄後、新規 context だけを使用する。`panic=abort` では unwind は行われずプロセスが終了する。診断 buffer、例外 message、Rust の期待 token 集合も、例外と構文不一致の間で同じ形式にはしない。

## 独立した共通 oracle

- `conformance/ordinary-exceptions/state.tsv`: 9 形式（chain、optional、repeat、choice、negative assertion、match-only、owned capture、capture scope、catch-and-success）。Java/Rust の制御フロー差は別の期待値列に記載する。補助文字を含む入力 `p😀;x` で CP cursor、登録状態、metadata、owned capture、fresh ID 無効化、再試行を比較する。
- `OrdinaryExceptionOracleTest`: 9 形式 × memoization 2 × diagnostics 3 × manual transaction 2 × abandoned frame 2 = 216 ケース。
- Rust `ordinary_exception_oracle`: 同じ 9 形式 × 6 モード × transaction 2 = 108 ケース、および panic 後 context 破棄の 4 形式。
- `spec-corpus/ordinary-exceptions/cases.json` と `OrdinaryParserExceptionConformanceTest`: separated item / separator / tail、typed nested list の item / tail、typed nested separated の item / separator / tail、合計 8 故障位置。Java 生成 consumer は 192 観測、Java host と native host の Rust 生成 consumer は各 96 観測。両 host の Rust 出力一致だけで代替せず、各出力を実 compile/run する。

最後の typed nested separated fixture は #646 と本例外境界の統合 gate である。共通入力 `p😀;😀!` の CP1 / CP2 / CP3 で故障させ、再試行後は field を `List<String>` / `Vec<String>` の二つの `😀` として復元する。list boundary の原文範囲は CP1..4、二つの item は CP1..2 / CP3..4 と固定し、separator の `;` は値へ混入させない。通常 CST に separator marker を一つ保持し、その marker 自体は snapshot root として拒否する。外側 manual rollback 後も、保持済み AST source map / boundary snapshot と先行 metadata snapshot は有効である。

生成 consumer は root 末尾に必須の `!` を置く。Rust repetition が callback の `Err` を反復停止として吸収しても、故障位置以降の残りを成功として公開できず root 全体が失敗する。その後、同じ context と parser/grammar で故障を無効化して再試行し、AST 値、CST/AST の原文 CP span、owned scope の復元、保持 snapshot を確認する。native generator と生成実行ファイルは空の `PATH` でも実行する。

ローカル実行例:

```sh
RUSTUP_TOOLCHAIN=1.85.0 mvn -pl unlaxer-common,unlaxer-dsl -am test \
  -Dtest=OrdinaryExceptionOracleTest,OrdinaryParserExceptionConformanceTest \
  -DrustConformance=true -Dsurefire.failIfNoSpecifiedTests=false
RUSTUP_TOOLCHAIN=1.85.0 cargo test --locked --manifest-path rust/Cargo.toml \
  -p unlaxer-runtime --test ordinary_exception_oracle
```

生成 Rust consumer の検証は `-DrustConformance=true` が必要であり、通常の Maven test だけでは skip される。
