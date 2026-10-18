# Classic memo の共通oracle (#628)

`cases.tsv` は実装出力から採取していない手書き契約の136ケースです（成功114/失敗22）。
UTF8 hexの空は`-`、CP位置は半開区間です。4kind/invert、reset両policy、prior consumed/matched、
MatchOnly/Stopper/二重invert、optional/repeat/EOF/zero-width、NonOrdered、Number/Identifier/Quoted、
family emoji、同名capture、投影source `input=abc, span=CP1..3, text=bb` を含みます。

最初の15列はid/input/式/kind/invert/開始consumed/開始matched/reset/受理/終了consumed/終了matched/
root開始/root長/root原文/original raw token列の原文結合です。
次の8列はconsumed/matched/root開始/root終了のUTF16・UTF8位置、最終列は失敗CP位置です（成功は-1）。
root sourceと`Parsed.originalTokens`相当を別に観測します。cacheなしの各hostのCST形は固有でも、
同じhostのOFF/ONは全CST（Rustはstructural TokenInfo/original edge/ID登録有無も含む）を比較します。
Javaの`capture`行は同じ字句/CST/位置のoracle、実immutable AST captureの共通検証は下記生成経路です。

- Java: 136 × observer有無2 × 診断3 × OFF/ON2 × 3trial = **4896試行**。
- Rust: 136 × terminal trace有無2 × 診断3 × OFF/ON2 × 3trial = **4896試行**。
- 両者とも各case/configuration内のwarm/再試行/再試行を比較し、報告はそれぞれ **1632設定**です。
- observer/traceなしONは成功114 × 診断3 × 2再試行 = **684回の実success hit要求**、
  失敗22 × 診断3 × 2再試行 = **132回の実failure hit要求**です。warmはhit0です。
- Java trial observer有効時はfailure/successともhit0を要求し履歴をOFFと比較します。
- Rust trace有効時はsuccess hit0を要求して内容をOFFと比較し、failureの実hit132回は維持します。

別controlは1 grammar sessionで4kind × invert2 × reset2の16keyを2回ずつ呼び、初回はmiss、
同じkeyは8success/8failureの実hitとなることをJava/Rust双方で検証します。
Rustの通常mode entryと明示Consumedも衝突しないことを検証します。
NodeIdはrollback後と次のreplay中の双方でstale、再生metadataはcaller変更から独立します。
Rustはscope/user state/captureのrollbackと、2MiB stackでのsticky resource failureも検証します。
追加unitはstructural metadataを除けば収まる予算を設定して安全なmissを要求し、
予算を戻した実hit、window eviction後の計数0と再保存を確認します。

`PureSuccessMemoizationConformanceTest.generatedClassicRetriesKeepTypedCapturesInBothRustHosts` は
1つの手書き文法で4kind × invert2 × reset2 × 診断3 × OFF/ON2 = **96観測/host**です。
Java/両host Rust生成物の5module byte equalityと実compile/map、typed AST値・CP範囲を確認します。
ONの48設定すべてで実success hitを要求し、Rustではreplay後のfield captureが各1回だけ公開されることも確認します。
本検証はobserver付きsuccess replayを提供した根拠ではありません。

```sh
mvn -pl unlaxer-common,unlaxer-dsl -am test \
  -Dtest=ClassicMemoizationConformanceTest,PureSuccessMemoizationConformanceTest,MemoResetPolicyTest \
  -DrustConformance=true -Dsurefire.failIfNoSpecifiedTests=false
cargo test --manifest-path rust/Cargo.toml -p unlaxer-runtime --test classic_memo
```

Java21/Rust1.85。報告: common `target/classic-memo-java.tsv` / `classic-memo-rust.log`、
DSL `target/classic-memo-generated.tsv`。shared fixtureから期待値を生成したり、cacheの無効gateを実hitと呼んだりしません。
source module追加はなく、従来のstandalone rustc/Playground resource契約を維持します。

後続 #629 は [stateful failure](../stateful-failure-memo/README.md)へ対応。
通常failureのresource guard #634は通常/Classic共通の深さ条件と実hit/resource oracleで検証します。
未完:
記録付きsuccess replay/production workload #184/#197。
[保存予算・key・metadata契約](../../docs/classic-memo.md)を参照してください。
