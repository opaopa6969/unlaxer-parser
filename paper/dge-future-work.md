# DGE Session: unlaxer-parser Future Work の設計の穴

## テーマ
unlaxer-parser の Future Work 5項目の設計ギャップを発見する

## キャラクター
- ☕ ヤン・ウェンリー — 「要らなくない？」「最もシンプルな解は？」
- 🎩 千石武 — 「品質基準を示す」「ユーザーのために」
- ⚔ リヴァイ兵長 — 「汚い。動くもの見せろ。」
- 👤 今泉慶太 — 「そもそも」「誰が困るの」

## 対象
1. エラー回復戦略
2. 不完全な文法カバレッジ
3. @eval strategy の実装
4. インクリメンタル構文解析
5. IR層 / 多言語対応

---

## Scene 1: エラー回復戦略

今泉: 「そもそもエラー回復って、誰が困ってるんですか？ 今のunlaxerで」

ヤン: 「LSPユーザーだね。入力途中で補完が出ないのは不便。でも tinyexpression は月10億トランザクションで動いてるわけで、バッチ処理なら完全な式しか来ない。エラー回復が要るのはエディタだけだよ。」

千石: 「エディタでの体験が悪ければ、フレームワーク全体の評価が下がります。LSPを謳う以上、不完全な入力での振る舞いは品質の根幹です。」

リヴァイ: 「で、具体的に何が動かないんだ？」

ヤン: 「例えば `if($a > ` まで入力した時点で、パースが完全に失敗する。ANTLRなら `)` と `{` を仮挿入して構造を推測してくれる。」

今泉: 「他にないの？ ANTLRみたいなトークン挿入以外の方法は？」

ヤン: 「3つある。(1) トークン同期点（`;` や `}` まで読み飛ばし）、(2) トークン挿入/削除（ANTLRスタイル）、(3) エラープロダクション（文法にエラー規則を書く）。PEGだと(1)が一番やりやすい。」

  → **Gap 発見: 同期点方式の場合、どのトークンが同期点になるか文法から自動決定できるか？**
  → **Gap 発見: エラー回復中のContainerParserの振る舞いが未定義。エラー回復中もErrorMessageParserは動くのか？**

千石: 「部分的に成功したパース結果を返す仕組みは既にあるんですか？」

ヤン: 「CalculatorDemoTest で partial parse success はやってる。有効部分は緑、無効部分は赤で返す。」

リヴァイ: 「じゃあ partial parse を拡張すれば済む話だろう。新しい仕組みは最小限にしろ。」

  → **Gap 発見: partial parse success と error recovery の関係が不明確。同じものか別の仕組みか？**

今泉: 「前もそうだったっけ？ 前にエラー回復を試みて失敗した経験は？」

ヤン: 「PEGのバックトラック特性上、どこで「失敗」と判断するかが曖昧。ordered choice の最初の選択肢が3文字消費して失敗、2番目が5文字消費して成功、でも全体としては失敗...みたいなケースがある。」

  → **Gap 発見: PEGの「最深マッチ位置」の定義が曖昧。ordered choice の各分岐で消費量が異なる場合、どの位置を報告するか？**

千石: 「ErrorMessageParser が既にある以上、エラー回復時のメッセージ品質も保証すべきです。「構文エラー」だけでは不十分。「`)` が必要です」のような具体的なメッセージが必要です。」

  → **Gap 発見: エラー回復メッセージの具体性をどう保証するか？ 期待トークンの計算が必要。**

---

## Scene 2: 不完全な文法カバレッジ

今泉: 「そもそも、tinyexpression-p4.ubnf と tinyexpression-p4-complete.ubnf の2つがあるのはなぜですか？」

ヤン: 「complete の方が先に書かれた理想の文法で、p4 が実際に動いてる版。complete にあって p4 にないのが sin, cos, And/Or, 文字列メソッド、Ternary 等。」

リヴァイ: 「なぜ complete をそのまま使わない。」

ヤン: 「マッパージェネレータが complete の複雑な文法を正しく処理できなかったから。今日の MapperGenerator 修正で大分改善されたけど。」

  → **Gap 発見: MapperGenerator がまだ処理できない UBNF パターンはあるか？ complete の全ルールでテストすべき。**

千石: 「文法が2つある状態は保守の敵です。1つにすべきです。」

今泉: 「誰が困るの？ ユーザーはどっちを見ればいいの？」

  → **Gap 発見: 文法の正規版がどれか不明。README に書くべき。**

リヴァイ: 「complete の中で、今すぐ動く奴と動かない奴を分けろ。動く奴だけ p4 にマージしろ。残りは backlog だ。」

  → **Gap 発見: complete → p4 マージの優先順位が未定義。使用頻度ベースで決めるべき。**

ヤン: 「And/Or/Xor は手書きパーサーにある。文字列メソッドも。math 関数も。complete.ubnf にも書いてある。テストも既にある。あとはマージするだけ。」

今泉: 「それ、なんで今までやってないんですか？」

ヤン: 「...Mapper のバグが先だったから。今は直ったから、やれる。」

  → **Gap 発見: マージ後のパリティテストが必要。P4パスと手書きパスで同じ結果が出ることを保証。**

---

## Scene 3: @eval strategy の実装

今泉: 「そもそも @eval って、誰のためのものですか？」

ヤン: 「DSLを作る人。UBNF文法を書いたら評価器のコードも自動生成されてほしい人。今は GGP の abstract skeleton だけ生成されて、evalXxx() は全部手書き。」

千石: 「P4TypedAstEvaluator の28メソッドを手書きした。あれは退屈で間違いやすい作業です。自動化すべきです。」

リヴァイ: 「で、default strategy のコードはもう書いたんだろ？ P4DefaultJavaCodeEmitter が。」

ヤン: 「そう。問題は EvaluatorGenerator がまだ @eval アノテーションを読まないこと。UBNF パーサー → UBNFAST → Generator のパイプラインに @eval を通す必要がある。」

  → **Gap 発見: UBNFAST に @eval の AST ノードが定義されていない。EvalAnnotation record を追加する必要がある。**

今泉: 「template strategy の方は？ テンプレートファイルをどこに置くんですか？」

ヤン: 「今は eval-templates/ にある。でもジェネレータが読むパスがハードコードされてる。設定可能にすべき。」

  → **Gap 発見: テンプレートファイルの解決パスが未定義。grammar ファイルからの相対パス？ classpath？**

千石: 「strategy=manual の場合、生成される abstract メソッドに @eval(strategy=manual) のコメントが付くべきです。開発者が「ここは手書きが必要」と分かるように。」

  → **Gap 発見: manual strategy のドキュメント生成が未定義。**

リヴァイ: 「template の中の {{left}} とか {{eval(expr)}} 、これのパース誰がやるんだ。テンプレートエンジン書くのか？」

ヤン: 「...それが一番面倒なところ。正直、EvaluatorGenerator の中で StringBuilder でゴリゴリ書く default strategy の方が確実。template は後回しでいい。」

  → **Gap 発見: テンプレートエンジンの実装コストが見積もられていない。MVP では default + manual で十分か？**

---

## Scene 4: インクリメンタル構文解析

今泉: 「そもそもインクリメンタルパースって、どのくらいの効果があるんですか？」

ヤン: 「tinyexpression の式は長くても数百文字。毎回全パースしても1ms以下。インクリメンタルの恩恵はほぼゼロ。」

千石: 「tinyexpression では不要でも、unlaxer を他のDSLに使う場合は？ 数千行の設定ファイルとか。」

ヤン: 「そうなると話は変わる。1万行のDSLファイルを毎回全パースすると数百ms。LSPの応答性に影響する。」

  → **Gap 発見: インクリメンタルパースの要否は DSL のサイズに依存。tinyexpression には不要。汎用フレームワークとしては必要。**

リヴァイ: 「tree-sitter のインクリメンタルパースはどうやってる？」

ヤン: 「ツリーの各ノードにバイト範囲を記録して、変更があった範囲のノードだけ再パース。でもこれPEGのバックトラックと相性が悪い。PEGは失敗時に巻き戻るから、変更点より前のノードも影響を受けうる。」

  → **Gap 発見: PEGのバックトラックとインクリメンタルパースの整合性。Packrat cache を使えば解決できるか？**

今泉: 「他にないの？ フルパースじゃなくてインクリメンタルでもない、中間の方法は？」

ヤン: 「チャンクベース。ソースを行単位やブロック単位で分割して、変更があったチャンクだけ再パース。完全なインクリメンタルより簡単で、90%のケースで十分。」

  → **Gap 発見: チャンク境界の決定方法。tinyexpression なら `;` が自然な境界だが、他のDSLでは？ 文法から自動決定できるか？**

千石: 「インクリメンタルパースを入れるなら、Token のイミュータビリティが問題になります。今の Token は mutable ですか？」

ヤン: 「...filteredChildren は追加できる。mutable だね。」

  → **Gap 発見: Token の mutability がインクリメンタルパースを阻害する可能性。immutable Token が必要か？**

---

## Scene 5: IR層 / 多言語対応

今泉: 「そもそも LLVM 対応って、誰が嬉しいんですか？」

ヤン: 「...正直、tinyexpression のユースケースでは不要。JVM で十分。LLVM が必要なのは、unlaxer を汎用コンパイラフレームワークにしたい場合。」

リヴァイ: 「風呂敷広げすぎだ。今のユーザーは1人。そいつが困ってる問題を先に解け。」

  → **Gap 発見: IR/LLVM は現時点で実需がない。他の4項目が優先。**

千石: 「多言語対応は？ TypeScript版があれば、フロントエンドのDSLにも使えます。」

ヤン: 「問題は sealed interface 相当がTypeScriptにないこと。discriminated union で近いことはできるけど、exhaustive check はTypeScript 5.x でもまだ弱い。」

  → **Gap 発見: 各ターゲット言語でsealed interface相当の型安全性を保証できるか？ 言語ごとの制約調査が必要。**

今泉: 「要するに、IR層を作るということは、Java以外の言語でパーサーコンビネータランタイムを実装するということですよね？ それって unlaxer をもう一回作り直すってことじゃ...」

ヤン: 「...そうだね。各言語で unlaxer-common 相当を実装する必要がある。これは年単位の仕事。」

  → **Gap 発見: 多言語対応のコストが過小評価されている。パーサーランタイムの移植だけで数万行。**

リヴァイ: 「やるなら IR → TypeScript だけに絞れ。Rust と Python は後だ。」

千石: 「もしくは、UBNF → tree-sitter grammar.js の変換を作れば、tree-sitter のランタイム（C言語）が全言語で使えます。」

ヤン: 「おっ、それは賢い。tree-sitter はパース部分だけで、評価やLSPは別だけど、パーサーの多言語対応は一気に解決する。」

  → **Gap 発見: UBNF → tree-sitter 変換というアプローチ。全部自前で作るより効率的かもしれない。**

今泉: 「でもそうすると、PropagationStopperとかContainerParserはtree-sitterに変換できるんですか？」

ヤン: 「...できない。tree-sitter はPEGの変種で、propagation control の概念がない。パーサー部分だけの変換になる。」

  → **Gap 発見: tree-sitter 変換ではunlaxer独自の機能（PropagationStopper, ContainerParser）が失われる。パーサー層のみの変換に限定される。**

---

## Gap リスト (全体)

### エラー回復
| # | Observe | Suggest | Priority |
|---|---------|---------|----------|
| G1 | 同期点の自動決定方法が未定義 | @recover(sync=';') アノテーション | High |
| G2 | エラー回復中のContainerParserの振る舞い未定義 | 仕様を定義 | Medium |
| G3 | partial parse と error recovery の関係が不明確 | 統合設計 | High |
| G4 | PEGの「最深マッチ位置」の定義が曖昧 | 操作的意味論で定義 | Medium |
| G5 | エラーメッセージの具体性（期待トークン計算） | Expected token set の自動計算 | High |

### 文法カバレッジ
| # | Observe | Suggest | Priority |
|---|---------|---------|----------|
| G6 | complete と p4 の2文法が存在 | 統合 | High |
| G7 | MapperGenerator の未対応パターン | complete 全ルールテスト | High |
| G8 | マージ優先順位が未定義 | 使用頻度ベース | Medium |
| G9 | パリティテストが必要 | P4 vs 手書き全パス比較 | High |

### @eval strategy
| # | Observe | Suggest | Priority |
|---|---------|---------|----------|
| G10 | UBNFAST に @eval ノードがない | EvalAnnotation record 追加 | High |
| G11 | テンプレートファイルの解決パス未定義 | 設定可能に | Low |
| G12 | manual strategy のドキュメント生成未定義 | コメント生成 | Low |
| G13 | テンプレートエンジンの実装コスト未見積 | MVP は default+manual | Medium |

### インクリメンタル構文解析
| # | Observe | Suggest | Priority |
|---|---------|---------|----------|
| G14 | tinyexpression には不要 | 汎用フレームワークとして設計 | Medium |
| G15 | PEGバックトラックとの整合性 | Packrat cache 活用 | High |
| G16 | チャンク境界の自動決定 | 文法の同期点から導出 | Medium |
| G17 | Token の mutability 問題 | immutable Token 検討 | High |

### IR / 多言語対応
| # | Observe | Suggest | Priority |
|---|---------|---------|----------|
| G18 | 現時点で実需なし | 他4項目が優先 | Low |
| G19 | 各言語のsealed interface相当の調査不足 | 言語別制約調査 | Medium |
| G20 | パーサーランタイム移植コスト過小評価 | TypeScript のみに絞る | Medium |
| G21 | UBNF→tree-sitter変換アプローチ | パーサー層のみ変換 | Medium |
| G22 | PropagationStopper等がtree-sitter非互換 | 独自機能は変換対象外 | Low |

---

## 優先順位（リヴァイの判断）

```
今すぐやれ（動くものを出せ）:
  G6, G7, G9  — 文法統合。complete を p4 にマージ。パリティテスト。
  G10         — @eval アノテーションの UBNFAST 定義。

次にやれ:
  G1, G3, G5  — エラー回復の最小版。同期点方式。
  G15, G17    — インクリメンタルの前提条件（Packrat cache, immutable Token）

後でいい:
  G18-G22     — IR/多言語。実需が出てから。
  G11-G13     — template strategy。default で十分。
```

---

## Decision Record

- **エラー回復**: 同期点方式を最小実装として採用。@recover アノテーション設計。
- **文法統合**: complete → p4 のマージを優先。MapperGenerator の対応確認が先。
- **@eval**: default + manual を MVP とする。template は後回し。
- **インクリメンタル**: チャンクベースを検討。ただし Token の immutability 問題を先に解決。
- **IR/多言語**: 現時点では見送り。tree-sitter 変換は将来の選択肢として記録。
