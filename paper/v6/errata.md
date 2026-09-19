# v5からv6への訂正と主張の監査

v1〜v5は模擬論文・模擬査読の開発記録である。実際の学会の採録証明でも、独立した査読の結果でもない。v6は投稿準備用のTool Paper草稿として別に作成した。旧本文を残すのは誤りの経緯を追跡するためであり、旧主張の再承認ではない。

| v5の主張 | v6での扱い | 根拠・検証 |
|---|---|---|
| `{ww^R}`は非文脈自由 | 撤回。偶数長回文はCFGで生成可能 | `S → aSa \| bSb \| ε`。奇数長を含める場合は`a,b`の生成規則も追加 |
| 回文実験がCFGを超える能力を証明 | 撤回。補助例を区切り付きコピー言語に変更 | `CopyLanguageTest`。空語を含む定義と全入力消費条件を一致させる |
| CFGで書けないのでPEGでも書けない | 撤回。PEGの表現力についての結論を出さない | [FordのPEG論文](https://bford.info/pub/lang/peg/) |
| 5つの写像から7元の閉じたモノイド | 8元に訂正 | `PropagationAlgebraTest`が閉包を列挙し、全64積・512結合則を検査 |
| `AllStop . X = AllStop`は右零元 | 左零元に訂正。両側零元ではない | `NotProp . AllStop = AllTrue`。`AllTrue=(C,true)`も左零元 |
| 合成表の`StopInvert . NotProp = ForceInvert` | `StopInvert`に訂正 | 合成は右から左へ適用。順序を明記した8×8表を再生成 |
| `AllPropagationStopper`が現行APIに存在 | 現行ツリーには存在しない | `AllStop`は消費固定と反転停止の合成に付けたモデル上の名前 |
| AST型追加は常にswitch非網羅性で検出 | 2種類の検出を分離 | 古いdispatch＋新ASTは非網羅switch。再生成後は具体subclassのabstract method未実装 |
| あらゆる文法変更に対する完全性 | 型構造の整合性に限定 | 文字列演算子追加はコンパイル成功後に実行時失敗し得る。実験に負例を含める |
| 生成ASTにソース情報を直接保持 | parse treeおよびmapperの別テーブルに保持 | v6で解析結果に所有される不変スナップショットを追加。合成ノードには未登録位置があり得る |
| generated DAPが意味評価を自動的にstep実行 | AST/tokenの構造走査と意味評価traceを区別 | DAPの`next`とEvaluatorの`DebugStrategy`は別経路。意味実行の接続は言語固有コードが必要 |
| `@eval(dispatch=..., methods=...)`等が実装済み | 現行実装の`kind`/`strategy`記法に修正 | `EvaluatorGenerator`の5種類は`binary_arithmetic`, `variable_ref`, `conditional`, `passthrough`, `literal` |
| 他workbenchには意味論やIDE機能がない | 撤回、公平な設計比較に変更 | Xtext/Xbase、Spoofax/DynSem、Langiumの一次資料を参照 |
| 15,000行・8週間との比較による13倍削減 | 実測評価から除外 | 比較側の実装・作業ログがない推計。v6は固定fixtureの差分だけ計測 |
| 1,400倍高速、99%以上cache hit | v6の実測結果として不採用 | 本artifactでは再現していない。JMH比較や編集workloadの別評価が必要 |
| 10億transactions/月、445+550件のテスト | 著者による旧版の報告としてのみ扱う | 運用の独立監査ではない。現行テスト数はSurefireレポートから確認し、旧合算値を使わない |
| DGEが201以上のgapを発見したので効果がある | 開発経験として補足、因果的な有効性は主張しない | 対照群・独立分類・投入時間の比較がない。模擬査読は実際に代数の誤りを見逃した |

v6では論文本文から生成範囲・手書き範囲・保証範囲・検証手順に直接辿れることを優先する。訂正後も、実験は小規模な作者実装の例であり、他workbenchに対する優位性や産業規模での生産性向上の証明ではない。
