# Classic token mode の実memo (#628)

Java/Rustとも既定はOFFです。既存のSafe policyを明示したとき、pure ruleのClassic成功/失敗も
実際にcacheします。4 TokenKindとinvertをkeyに含め、reset/nonOrdered、両cursor、rule depth、
whitespaceの既存区別を維持します。通常modeと明示Consumedも別entryであり、CSTを混同しません。

Rust keyは #629 のstate version追加で5個のusizeです（#628導入時は4個）。
modeは既存depth/modes wordの空きbitに追加します。
bit0=whitespace、bit1=nonOrdered、bit2=reset、bits3..10=rule depth、bits11..13=mode
(None0/Consumed1/MatchOnly2/VirtualConsumed3/VirtualMatchOnly4)、bit14=invertです。
active expression depthはkeyへ入れずentryに保持し、現在depthが記録時以下のときだけ再利用します。
浅い再試行のhitを維持し、残予算が少ない深い呼出しでは通常実行へ戻します。
resource failureは保存せずstickyであり、optional等が通常の不一致へ変換できません。
通常modeにも同じ深さ条件を適用します（#634）。共通12行を通常/Classicの両経路で検証し、
同深さ・浅い再試行の実hitと、深い再試行のmiss・sticky resource failureを区別します。

成功snapshotはCSTのrelative edge/capture、両cursorと終了policy、局所診断に加え、
structural TokenInfo（kind/source/parser label/original token edge）を保持します。
`Parsed.originalTokens`相当のraw列はcommitted CSTのexact-kind filteringとは別に保存します。
projected sourceを元input sliceへ戻さず、例えばinput `abc` のCP1..3に投影された `bb` を保持します。
virtual kindはsource provenanceと独立しており、入力-backed virtual tokenもそのままです。

portable NodeIdは保存しません。各nodeについて登録有無だけを保存し、必要な登録へfresh generationを
発行します。MatchOnlyでIDを失ったまま保持されたstructural CSTをeager登録しません。
古いIDは新replay中もstaleです。callerのextra/related metadataはcacheへコピーせず、
純粋性が崩れた新しい種類のdataが見つかれば安全なmissへ戻します。
MatchOnly/Stopperのpure closureも解析でき、任意callbackやstateful bodyを無条件でpureにしません。
`Character` predicate、custom、owned binding、recovery、scope/listener effect等のunsafe境界は維持します。

保存予算は [pure success memo](pure-success-memo.md) と共通です。structural metadataのvector、original edge、
projected/generated textも計数します。超過は構文エラーではなくmissです。
syntax診断にはrule局所のClassic max-reached/failure-seenも保存・mergeし、以前のsiblingの値を保存しません。
CST/capture/stateのrollbackと、speculative syntax診断を保持する既存契約は変えません。

observer記録を省略してhitを増やしません。Java trial/listener記録中は既存どおりmemoを停止し、
Rust opt-in terminal trace中はsuccess replayを停止して通常実行します（failure memoは有効）。
それぞれのobserver内容は同じhostのOFF/ONで比較します。異なる2種類のtraceを同じformatと主張しません。
記録付きsuccess replay、production workload (#184/#197) は未完です。
#624のno-retry microbenchmarkで約41% overheadを観測した事実と既定OFFは維持します。

[共通fixtureと正確な観測数](../conformance/classic-memo/README.md)を参照してください。

[stateful failureのepoch契約](stateful-failure-memo.md) は #629 で対応しました。

Classic の公開 context API から取得する failure/success hit も host 依存の内部会計であり、Java/Rust 間の一致を契約しません (#827)。Tiny Classic harness の結果 JSON の `failure_hits` / `success_hits` も同じ扱いです。成否・cursor・診断・reset は引き続き memo OFF/ON と各 host の間で検証します。[公開範囲と共通 corpus](../conformance/chain-reset-after-failure/README.md#memo-hit-accounting)。
