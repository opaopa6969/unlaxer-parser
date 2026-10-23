# Pure rule success memo (#624)

Java/Rustとも既定はOFFです。既存の `Memoization.SAFE_FAILURES` (Java) /
`Memoization::SafeFailures` (Rust) を明示すると、failureに加え、静的にpureと証明された
ruleの成功結果を保存します。歴史的なenum名は互換性のため変更しません。
任意のparserをpureと仮定する機能ではありません。

Rustでは既存failure proofに加え、`RuleEffects`（scope、宣言、参照）を含むruleとその祖先を
success対象外とします。custom、owned capture/replay、state/name依存predicate、recovery等は
従来どおり対象外です。通常のimmutable AST captureは保存・再生できます。
Java側は生成クラスの直接の `SafeSuccessMemoizable` markerとlistener vetoで同じpure境界を扱います。

成功entryは終了consumed/matched、終了cursor policy、局所syntax診断、captureへの追加順、
owned CST snapshotを持ちます。CSTのedgeはentry内の相対indexとして保存し、再生先arenaへ付け替えます。
copy-in/outにより返却treeの変更をcacheへ戻しません。NodeIdや可変portable metadataは保存しません。
JavaのChoice（LongestChoice / PredictiveChoiceを含む）はmissのとき選んだ子の `Parsed` を返すため、
成功entryはその `Parsed` のtoken（root と original tokens）・status（`stopped` を含む）・messageも
同じsnapshotに保持し、hitでも同じ `Parsed` を返します（#744）。CSTにあるtokenは再生したCSTの複製を
共有し、match-onlyの先読みのようにCSTから外れたtokenだけを隠れた部分木として複製します。
CAPTUREの値はmemoの設定に依存しません。返すtokenのID配列（16 B + 4 B/件）は確保する前に、
隠れた部分木はCSTと同じnode単位で、成功entryの予算に計上します。予算を超えるentryは保存しないため、
予算が飽和する入力ではhitが修正前よりわずかに減ることがあります。
rollbackしたIDはstaleのまま、再生tokenには新しいgenerationのIDを発行できます。
既存のtransactionが再生CST/capture/新metadataもrollbackします。診断は通常のspeculative failureと同様に保持します。

keyは既存のrule、両byte cursor、rule depth、whitespace、reset/nonOrdered policyを使います。
成功replayはさらにactive expression depthが保存時以下の場合に限定します。
高いframeからのcache hitでresource limitを飛び越さず、resource failureは保存せずsessionを中断します。
異なるgrammar呼出しは独立したmemo tableを使い、終了時に親のtableを復元します。

Rustのsuccess snapshot payloadは単体2 MiB、context内の当該grammar session合計16 MiBが上限です。
計数はnode本体、edge/capture vector、capture publication、診断ID配列の長さを含む保守的な値です。
allocator/hash-table overhead、input、grammar、通常のCST arena全体を含むprocessメモリ上限ではありません。
予算を超えたentryは保存せず、通常の再解析に戻します。構文を拒否する制限ではありません。
failure同様、CP位置のbucketを保持窓に従って解放し、successの予算計数も同時に戻します。
古い位置への再試行では窓を拡大し、失ったentryを再解析して再保存できます。
保持窓は任意文法に対する定量的メモリ上限やlinear-timeの保証ではありません。

| 経路 | Java | Rust |
| --- | --- | --- |
| 通常のpure rule | success/failure memo | success/failure memo |
| listener / scope等の成功 | success対象外 | `RuleEffects`等はsuccess対象外 |
| custom/stateful rule | exact marker/epoch等の既存契約 | purity未証明のため対象外 |
| 明示Classic token mode | exact marker条件下で対応 | #628でpure success/failureに対応 |
| trial/terminal traceの記録中 | trial recordingはmemoを停止 | opt-in terminal traceはsuccess replayを停止 |

Rust traceを停止するのではなく、success memoをmissにして元のparserを実行します。
traceをcache hitで欠落させることはありません。failure memoはそのままです。
[Classic/general modeは #628で実hitへ対応](classic-memo.md)。[version付きstateful failureは #629で対応](stateful-failure-memo.md)、記録付きsuccess replayの差は親 #184 / #197 に残ります。
この単位だけでproduction workloadのdeadlineや全stateful parityの完了とはしません。

`ParseContext::memoized_success_hits()` は実際に成功entryを再生した数を返します。
Javaの `getPackratMemoTable().successHits()` と同様、別contextでの診断retryは含めません。
候補除外で再試行そのものが省かれた場合は、成功hitと数えません。

[共有fixtureと実行手順](../conformance/pure-success-memo/README.md)を参照してください。
EOF/zero-width、Unicode/family emoji、typed AST、3診断mode、相対edge、ID/metadataのrollback、
snapshot予算超過時の安全なmiss、eviction後の予算復元を検証します。
