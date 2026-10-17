# matched cursor reset policy と memo key (#621)

`cases.tsv` は手書きの14ケースです。入力・literal・token文字列はUTF-8 hex、位置はCPです。
`start_consumed/start_matched` はwarm試行前の独立cursor、`warm_reset/next_reset` は
各試行の入力policyです。warmは必ず外側transactionでrollbackしてから次のpolicyへ移ります。
`token_start/token_end` は次の成功で得る原文tokenのCP範囲（失敗は-1/-1）。
`java_hits` は次の試行で期待する実hit数です。warm成功ならsuccess cache、warm失敗ならfailure cacheです。

Javaはfalse↔trueでcacheを分離し、同policyは実hitを維持します。tokenはcopy-in/outされ、
保持したwarm tokenの変更が次の結果へ漏れず、外側rollbackで両cursorとcommitted CSTが戻ることも検証します。
診断は両hostそれぞれのOFF/ONで比較します。共通位置・token期待値をnativeの既存hint名へ変形しません。

Rust Classic一般modeは現在cache対象外です。`memo_reset_policy.rs` は同じoracleの意味を検証して
**hit0を要求**します。これを一般mode memo対応の証拠とはしません。別の独立試験では同一grammar session内の
pure child ruleを公開APIから呼び、通常modeでreset=false→true→falseのhit数が0→0→1になること、
Classicでは3回とも0になることを検証します。Rust runtime製品のkey/gateはこの修正で変更しません。

```sh
mvn -pl unlaxer-common -am test -Dtest=MemoResetPolicyTest \
  -DrustConformance=true -Dsurefire.failIfNoSpecifiedTests=false
cargo test --manifest-path rust/Cargo.toml -p unlaxer-runtime --test memo_reset_policy
```

Java21/Rust1.85基準。報告はcommonの `target/memo-reset-policy-java-{failure,success}.tsv` と
`target/memo-reset-policy-rust.log`。本修正は#184/#197のpure-success/stateful-failure parity全体を完了させるものではありません。
