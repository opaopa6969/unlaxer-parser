# Choice の候補開始位置

#861 の共通回帰。`predictive.tsv` の4入力は UTF-8 hex で入力・消費prefix・先読み文字列・候補literal・reset方針と、
仕様から決めた成功後の consumed / matched CP を持つ。ASCII、補助文字、複数CPの先読みを含む。

prefix を消費した後に先読みだけを進め、親のreset方針に従い matchOnly / virtualTokenMatchOnly で
`choice(CAPTURE(selected, literal), optional(Z))` を呼ぶ。literal の predictor と後続 Any を指定しても、
最初の候補が成功し、候補literalを capture しなければならない。reset 前の位置で予測すると、
後続の nullable 候補が先に成功して matched と capture とCSTを変えていた。

- Java `ChoiceCursorExclusionTest`: 通常ChoiceとPredictiveChoiceを比較する。受理・両cursor・reset方針・
  選んだparser identity・capture・original CSTを全診断policy/memo設定で照合する。
  CSTのrootクラス名だけをChoiceへ正規化し、子・kind・CP範囲・原文を保持する。
  通常Choiceは別途、4 token kind・invert/reset各2種・ASCII/EOF/CRLF/非BMPで診断モード間を比較する。
- Rust `choice_cursor`: 同じ4行を既存の公開Classic入口で実行し、両cursor・owned captureと正しい候補選択を確認する。
  memo2種・診断3種・matchOnly系2kindを実行する。Rustの製品実装は変更しない。
- 行境界の1088行は [line-boundary](../line-boundary/README.md) でJava/Rust双方のoriginal/reduced CST、
  状態・metadataのrollbackまで別途検証する。

これは手書き公開runtimeの回帰であり、新しいUBNF構文/生成経路を追加しない。
FIRSTを無効にしたfresh JVMでも同じJava回帰を実行し、通常Choiceの判定値を比べる。
診断の候補文言と、低水準contextのDETAILED_ON_FAILUREが診断を記録しない契約は維持する。

reset=false の対照行 `xab` は、C=1/M=2 から後方の `b` を選んでM=3へ進む。
常にconsumed側を読む退行は、Any/nullable側の成功とcapture欠落として失敗する。
FIRST無効化はローカルの補完試験であり、CIは通常設定の回帰と全Cargo試験を実行する。
