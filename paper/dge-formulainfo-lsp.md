# DGE Session: FormulaInfo LSP — 3層コンテキスト切り替えとJavaCode編集

## テーマ
FormulaInfo ドキュメントの LSP を、現在の formula セクション専用から、メタデータ補完・Java コード編集・クロスリファレンスに拡張する設計のギャップを発見する。

## キャラクター
- ☕ ヤン・ウェンリー — 「要らなくない？」「最もシンプルな解は？」
- 🎩 千石武 — 「品質基準を示す」「ユーザーのために」
- ⚔ リヴァイ兵長 — 「汚い。動くもの見せろ。」
- 👤 今泉慶太 — 「そもそも」「誰が困るの」

## 前提コンテキスト
- FormulaInfo: メタデータ + formula + optional Java コードブロックの複合ドキュメント
- 現行 LSP: formula セクションのみ対応（completion, diagnostics, hover, semantic tokens）
- `DocumentFilter.formulaInfo()` が formula: 行以降を抽出し、行オフセットを追跡
- FormulaInfo フィールド: calculatorName, resultType, numberType, tags, description, dependsOn, executionBackend, periodStartInclusive/EndExclusive, formula, javaCode, byteCode
- Java コードブロック: ` ```java:ClassName ... ``` ` (fenced blocks)
- 本番: 10^9 tx/month, FormulaInfo は `---END_OF_PART---` で区切られた複数セクション
- `@FormulaInfoField` アノテーションがフィールド定義のソースオブトゥルース
- `ExecutionBackend` enum: JAVA_CODE, JAVA_CODE_LEGACY_ASTCREATOR, AST_EVALUATOR, DSL_JAVA_CODE, P4_AST_EVALUATOR, P4_DSL_JAVA_CODE

---

## Scene 1: ユーザーの1日（FormulaInfoを編集する人の体験）

今泉: 「そもそも FormulaInfo を編集する人って誰ですか？ どんな作業をしてるんですか？」

ヤン: 「大きく2種類いる。ビジネスルール担当者が式を書く人と、Java コードブロックで複雑なロジックを書く開発者。前者は `resultType:float` って何？って思うかもしれない。後者は Java の補完がないと辛い。」

今泉: 「ビジネスルール担当者は、プログラマーじゃないんですか？」

ヤン: 「微妙なところ。式は書けるけど Java は書けない人もいる。`executionBackend:AST_EVALUATOR` を選ぶ人は式だけ書く。`executionBackend:JAVA_CODE` を選ぶ人は Java も書く。つまりペルソナが executionBackend の値で変わる。」

  → **Gap 発見: ユーザーペルソナが executionBackend の値によって分岐する。LSP の機能セットもペルソナ別に変えるべきか？**

千石: 「ペルソナが誰であれ、`dependsOn:calcBase` と書いて calcBase が存在しなかったら、本番で初めてエラーになるんですよね？ それはユーザーへの侮辱です。」

今泉: 「本番で？ 10^9 tx/month の本番で？ 誰が困るんですか？」

千石: 「全員です。計算結果が出ない。ビジネスが止まる。」

  → **Gap 発見: dependsOn のバリデーションが実行時のみ。エディタ時点で検出すべき最優先の診断対象。**

リヴァイ: 「今、FormulaInfo を編集するときのワークフローを見せろ。テキストエディタで開いて、保存して、何が起きる？」

ヤン: 「保存 → ファイルウォッチャーが検知 → FormulaInfoParser がパース → Calculator 生成 → テスト実行。LSP がいれば保存前にフィードバックが来る。」

  → **Gap 発見: 保存からテスト実行までのフィードバックループの時間が不明。LSP の即時フィードバックとの比較データがない。**

---

## Scene 2: メタデータセクションの LSP

千石: 「メタデータのフィールド名を間違えても、今は何も言われないんですよね？ `reusltType:float` と書いてもスルー。」

ヤン: 「そう。FormulaInfoElementHeaderParser は `JavaClassNameParser` でキー部分をパースして、`FormulaInfo` クラスの `@FormulaInfoField` アノテーション付きフィールドと突合してる。でもこれはランタイムの話で、LSP はこの情報を使ってない。」

今泉: 「要するに、正しいフィールド名の一覧はどこにあるんですか？」

ヤン: 「`FormulaInfo` クラスの `@FormulaInfoField` が付いた public フィールド。リフレクションで取得できる。実際に static ブロックで `fieldByName` マップを構築してる。」

  → **Gap 発見: LSP がメタデータフィールド名の補完・バリデーションに使うスキーマは `FormulaInfo.fieldByName` から取得可能だが、LSP プロセスからこのマップにアクセスする経路が未設計。**

千石: 「フィールド名だけでなく、値の補完も必要です。`resultType:` の後に `float`, `string`, `boolean`, `object` を出すべきです。」

ヤン: 「resultType と numberType は `ExpressionType` enum だね。executionBackend は `ExecutionBackend` enum。tags は自由テキストだけど既存の tags 一覧からサジェストできる。」

リヴァイ: 「enum の値は何個ある。」

ヤン: 「ExecutionBackend は6個: JAVA_CODE, JAVA_CODE_LEGACY_ASTCREATOR, AST_EVALUATOR, DSL_JAVA_CODE, P4_AST_EVALUATOR, P4_DSL_JAVA_CODE。ExpressionType は...確認が必要だけど、少なくとも Float, String, Boolean, Object はある。」

  → **Gap 発見: ExpressionType enum の全値リストが未確認。LSP の値補完に必要。**
  → **Gap 発見: LEGACY_ASTCREATOR のような非推奨値を補完候補に出すべきか？ 非推奨フラグが ExecutionBackend にない。**

千石: 「`periodStartInclusive` と `periodEndExclusive` は日付ですよね？ フォーマットのバリデーションは？」

ヤン: 「`MultiDateParser` が複数の日付フォーマットを受け付ける。でもどのフォーマットが正規かは...曖昧だね。」

  → **Gap 発見: periodStartInclusive/EndExclusive の許容日付フォーマットが LSP のバリデーションに必要だが、MultiDateParser の受理フォーマット一覧が文書化されていない。**

今泉: 「`extraValueByKey` って何ですか？ `@FormulaInfoField` にないフィールドも書けるんですか？」

ヤン: 「そう。FormulaInfo は任意の key:value を受け付けて `extraValueByKey` に格納する。`FormulaInfoAdditionalFields` で追加フィールドを定義できる。つまりスキーマは固定じゃない。」

  → **Gap 発見: スキーマが拡張可能。`FormulaInfoAdditionalFields` で追加されたフィールドも LSP の補完対象にすべきだが、追加フィールドの発見メカニズムが未設計。**

---

## Scene 3: dependsOn のクロスリファレンス

今泉: 「`dependsOn:calcBase` って書いて、calcBase が存在しなかったらどうなるんですか？」

ヤン: 「実行時に `TinyExpressionsExecutor` が依存グラフを構築するとき、見つからなくてエラー。本番で。」

千石: 「それは許されません。LSP なら赤線でリアルタイムに警告できるはずです。」

リヴァイ: 「やるなら go-to-definition もセットだろう。dependsOn:calcBase をクリック → calcBase の定義ファイルに飛ぶ。」

今泉: 「でもそのためには、全ての FormulaInfo ファイルの calculatorName を知ってる必要がありますよね？ それってどうやって？」

ヤン: 「workspace レベルのインデックスが要る。全 `.formulainfo` ファイルをスキャンして `calculatorName` → ファイルパス + 行番号のマップを構築する。」

  → **Gap 発見: workspace レベルの calculatorName インデックスが必要。ファイル数が多い場合の初期スキャン時間とインクリメンタル更新の設計が未定義。**

今泉: 「`dependsOn` に複数の値を書けるんですか？ カンマ区切り？」

ヤン: 「...`@FormulaInfoField` の型は `String` だね。カンマ区切りかどうかは `FormulaInfoParser` の実装次第。確認が要る。」

  → **Gap 発見: dependsOn の値フォーマット（単一値 vs カンマ区切り vs 複数行）が不明確。LSP のパースと参照解決に影響。**

千石: 「rename も必要です。calcBase を calcBaseV2 に rename したら、全ファイルの dependsOn:calcBase も追従すべきです。」

リヴァイ: 「10^9 tx/month の本番で使ってる calculatorName を rename するのか？ 怖すぎるだろ。」

ヤン: 「ワークスペース内の rename はエディタ上の話。デプロイは別のステップ。でもリヴァイの懸念はもっともで、rename の影響範囲をプレビューする機能が要る。」

  → **Gap 発見: calculatorName の rename は影響範囲が広い。rename preview（変更されるファイル一覧）の表示が必要。**

今泉: 「前もそうだったっけ？ calculatorName のタイポで本番障害が起きたことは？」

ヤン: 「...10^9 tx/month のシステムなら、おそらく起きてる。だから dependsOn の検証は最優先。」

  → **Gap 発見: dependsOn 起因の本番障害の実績データがあれば、この機能の ROI を定量化できる。**

---

## Scene 4: Java コードブロックの編集

リヴァイ: 「` ```java:ClassName ` の中で Java の補完を出せるか。できるのか、できないのか。」

ヤン: 「技術的には2つのアプローチがある。(1) embedded languages / virtual document: VSCode の HTML 内 CSS/JS と同じ。FormulaInfo LSP が Java 部分を virtual document として切り出して、JDT.ls に委譲する。(2) Language Server Protocol の `textDocument/linkedEditingRange` を使う。」

千石: 「(1) の virtual document アプローチは、VSCode のプロトコル拡張ですよね？ 標準 LSP ではない。」

ヤン: 「そう。VSCode 専用の `EmbeddedLanguages` API。Neovim や他のエディタでは動かない。標準 LSP でやるなら、FormulaInfo LSP 自体が Java の補完を提供するか、ユーザーにファイルを分割してもらうか。」

  → **Gap 発見: embedded languages は VSCode 専用。エディタ非依存の解決策が必要。LSP 標準だけでは embedded language のサポートが困難。**

リヴァイ: 「JDT.ls に委譲するとして、Java コードのクラスパスはどうなる。FormulaInfo が依存してるライブラリは？」

ヤン: 「Java コードブロックの中では `Calculator` インターフェースを実装するクラスを書く。つまり tinyexpression の依存関係が必要。プロジェクトの pom.xml か build.gradle からクラスパスを解決する必要がある。」

  → **Gap 発見: Java コードブロックのクラスパス解決。FormulaInfo ファイル単体にはクラスパス情報がない。プロジェクトの build 設定との紐づけが必要。**

今泉: 「そもそも Java コードブロックを FormulaInfo の中に書く理由は何ですか？ 別ファイルじゃダメなんですか？」

ヤン: 「いい質問だね。FormulaInfo は1ファイルに全情報を含む self-contained な単位として設計されてる。calculatorName, 式, Java コード, メタデータが1箇所にある。分割すると管理が煩雑になる。」

千石: 「でも Java コードが長くなると、FormulaInfo ファイル自体が読みにくくなります。」

  → **Gap 発見: Java コードブロックの推奨サイズ上限が未定義。長い Java コードは別ファイルに分離すべきか？ その場合の参照メカニズムは？**

リヴァイ: 「virtual document のライフサイクルはどうなる。FormulaInfo を開いたときに作って、閉じたときに消すのか。編集中にどうやって同期する。」

ヤン: 「FormulaInfo の変更イベントのたびに、Java コードブロック部分を切り出して virtual document を更新する必要がある。行番号のマッピングも維持しないと、Java のエラー位置が FormulaInfo 内のどこか分からなくなる。DocumentFilter と同じ仕組みだね。」

  → **Gap 発見: Java virtual document の行番号マッピング。DocumentFilter.formulaInfo() は formula 用の lineOffset を追跡しているが、Java コードブロック用の同等機能がない。複数の Java コードブロックがある場合は複数の virtual document が必要。**

千石: 「Java コードのコンパイルエラーを FormulaInfo 内の正しい位置にマッピングして表示する必要があります。行がずれたら混乱の元です。」

  → **Gap 発見: JDT.ls からの diagnostic の位置を FormulaInfo のソース位置に逆変換する機構が必要。**

---

## Scene 5: 3層コンテキスト切り替えの設計

ヤン: 「カーソル位置からどのコンテキストか判定する方法を考えよう。Line-based で十分だと思う。`formula:` の次の行から `---END_OF_PART---` または ` ```java ` の前まで → TinyExpression コンテキスト。` ```java:ClassName ` から ` ``` ` まで → Java コンテキスト。それ以外 → メタデータコンテキスト。」

千石: 「`formula:` の行自体はどのコンテキストですか？ キーはメタデータ、値の開始は formula...」

ヤン: 「formula: の行自体はメタデータ。次の行から formula コンテキスト。」

  → **Gap 発見: `formula:` 行にインラインで式が書かれるケース（`formula:$x+$y`）の扱い。同一行にメタデータキーと式が混在する場合のコンテキスト判定。**

リヴァイ: 「DocumentFilter が既にやってる部分があるだろ。使い回せ。」

ヤン: 「DocumentFilter.formulaInfo() は formula セクションの抽出だけ。Java コードブロックの抽出はない。でも同じパターンで `DocumentFilter.javaCode()` を追加できる。」

  → **Gap 発見: DocumentFilter に Java コードブロック抽出メソッドがない。formula と同様の FormulaSection 相当の JavaSection（テキスト + lineOffset + className）を返す設計が必要。**

今泉: 「他にないの？ 3層じゃなくて4層になるケースは？」

ヤン: 「...考えてなかった。例えば formula の中にコメントがあるとか？ あるいは将来 ` ```python ` みたいな別言語ブロックが追加されるとか？」

  → **Gap 発見: コンテキスト層の拡張性。現状は3層だが、将来のブロックタイプ追加を見据えた抽象化が必要か？**

千石: 「セマンティックトークンの境界で色が変わりますよね？ メタデータ部分は key:value で1色、formula 部分は式の構文ハイライト、Java 部分は Java のハイライト。境界でスムーズに切り替わるべきです。」

ヤン: 「セマンティックトークンは各コンテキストの LSP が独立に返す。問題は merge。3つのコンテキストのセマンティックトークンを1つの応答にマージする必要がある。」

  → **Gap 発見: 3層のセマンティックトークンのマージ戦略。各コンテキストが返すトークンの位置を FormulaInfo のソース位置に変換して結合する実装が必要。**

リヴァイ: 「切り替え境界のエッジケースを列挙しろ。」

ヤン: 「(1) `formula:` の行自体。(2) ` ```java:ClassName ` の行自体（Java コンテキストか、メタデータか）。(3) ` ``` ` 閉じ行（Java の末尾か、メタデータの開始か）。(4) `---END_OF_PART---` の行。(5) 空行がどのコンテキストに属するか。」

  → **Gap 発見: 境界行（fence 行、END_OF_PART 行）のコンテキスト帰属ルールが未定義。補完やホバーが境界行で何を返すべきか。**

---

## Scene 6: DAP は要るか？

今泉: 「FormulaInfo のステップ実行って需要ありますか？ ユーザーが『ここをデバッグしたい』と思うのはどの部分ですか？」

ヤン: 「式のステップ実行は既に DAP で実装してある。`$x + $y * $z` を1ステップずつ評価して中間結果を見れる。これは有用。」

千石: 「メタデータのステップ実行は意味がありません。key:value をステップ実行する意味がない。」

リヴァイ: 「Java コードブロックのステップ実行は？ JDT の DAP に委譲するのか？」

ヤン: 「技術的には可能だけど、Java コードのデバッグは通常の Java デバッガでやれば済む。FormulaInfo LSP が Java DAP まで統合する必要はないと思う。」

今泉: 「誰が困るの？ Java コードブロックのデバッグを FormulaInfo の中でやりたい人って？」

ヤン: 「...正直、Java 開発者なら IntelliJ か VSCode の Java デバッガを使う。FormulaInfo 内の DAP は式だけで十分。」

  → **Gap 発見: Java コードブロックのデバッグ需要の有無。現時点では不要と判断するが、ユーザーヒアリングで確認すべき。**

千石: 「式のデバッグで1つ気になるのは、`dependsOn` で参照している他の式の値が、デバッグ時にどう見えるかです。」

ヤン: 「いい指摘。今の DAP は単一式のステップ実行。依存先の式の評価結果をウォッチ変数として表示できれば便利だね。」

  → **Gap 発見: DAP で dependsOn 参照先の評価結果をウォッチできるか。クロスリファレンスの DAP 拡張。**

---

## Scene 7: 複数 FormulaInfo の workspace 機能

千石: 「FormulaInfo が100個あるプロジェクトを想像してください。全 calculatorName を一覧表示できますか？」

ヤン: 「`workspace/symbol` を実装すれば、全 calculatorName を一覧表示できる。型は `SymbolKind.Function` あたりで。」

今泉: 「この calculatorName を dependsOn してるのは誰？ って逆引きしたいですよね。」

ヤン: 「`workspace/references`。calculatorName の定義位置で references を呼ぶと、全 dependsOn からの参照が一覧される。」

リヴァイ: 「循環依存の検出は？ A depends on B, B depends on C, C depends on A。」

ヤン: 「workspace 全体の依存グラフを構築して、サイクル検出。diagnostic として全ファイルに赤線を出す。」

  → **Gap 発見: 循環依存検出のタイミング。ファイル保存のたびにワークスペース全体のグラフを再構築するのか、インクリメンタルに更新するのか。**

千石: 「`---END_OF_PART---` で区切られた複数セクションがある場合、1ファイル内に複数の calculatorName が定義されますよね？」

ヤン: 「そう。1ファイル = N 個の FormulaInfo。workspace/symbol は1ファイルから複数のシンボルを返す必要がある。」

  → **Gap 発見: 1ファイル内の複数 FormulaInfo セクションの識別。`---END_OF_PART---` を境界としたセクション分割のパースが LSP 側に必要。DocumentFilter は最初の formula セクションしか抽出しない可能性。**

今泉: 「そもそも、FormulaInfo ファイルの拡張子は何ですか？ LSP がどのファイルを対象にするか、どうやって決まるんですか？」

  → **Gap 発見: FormulaInfo ファイルの拡張子 / ファイル識別方法が不明。LSP の `documentSelector` 設定に影響。**

リヴァイ: 「workspace のインデックスが腐ったらどうなる。」

ヤン: 「ファイルシステムのウォッチイベントで更新するけど、外部ツールでファイルを変更されたら検知が遅れる。定期的なフルスキャン or manual refresh が要る。」

  → **Gap 発見: workspace インデックスの整合性保証。ファイルシステムウォッチの限界と、フォールバック戦略。**

---

## Scene 8: MVP

リヴァイ: 「全部やるな。最小限で最大の効果を出せ。何が一番効くか、優先順位を付けろ。」

ヤン: 「ROI で考えると: (1) dependsOn のバリデーション — 本番障害を防ぐ。(2) メタデータフィールド名の補完 — タイポを防ぐ。(3) メタデータ値の補完 — enum 値を覚えなくて済む。この3つが最小工数で最大効果。」

千石: 「Java コード編集は？」

ヤン: 「embedded languages は工数が大きい。MVP には入れない。Java コードを書く人は、別途 .java ファイルに書いてから FormulaInfo にペーストするワークフローで回避できる。」

今泉: 「でも、それだと FormulaInfo の self-contained という設計理念が崩れませんか？」

ヤン: 「崩れるけど、MVP だから。完璧は後。」

リヴァイ: 「3層コンテキスト切り替えは？」

ヤン: 「MVP では2層で十分。メタデータ層 + formula 層。Java 層は Phase 2。DocumentFilter にメタデータ用の抽出ロジックを追加するだけで済む。」

  → **Gap 発見: MVP のスコープ定義。2層（メタデータ + formula）で開始し、Java 層は Phase 2。ただし Phase 2 を見据えた拡張ポイントを Phase 1 で用意すべきか、YAGNI か。**

千石: 「go-to-definition と rename は？」

リヴァイ: 「go-to-definition は入れろ。dependsOn のバリデーションとセットで実装コスト小さい。rename は後。影響範囲が怖い。」

ヤン: 「まとめると MVP は: (1) メタデータフィールド名の補完・バリデーション、(2) メタデータ値の補完（enum 値）、(3) dependsOn の存在チェック diagnostic、(4) dependsOn の go-to-definition。以上。」

今泉: 「DAP は？」

ヤン: 「既存の式 DAP で十分。追加なし。」

リヴァイ: 「よし。それでいい。」

---

## Gap リスト (全体)

### ユーザーペルソナ / 体験
| # | Observe | Suggest | Priority |
|---|---------|---------|----------|
| G1 | ユーザーペルソナが executionBackend 値で分岐 | ペルソナ別の LSP 機能セット設計 | Low |
| G2 | 保存→テスト実行のフィードバックループ時間が不明 | 計測して LSP の即時性と比較 | Low |

### メタデータ LSP
| # | Observe | Suggest | Priority |
|---|---------|---------|----------|
| G3 | LSP がメタデータスキーマにアクセスする経路が未設計 | FormulaInfo.fieldByName を LSP に公開 | High |
| G4 | ExpressionType enum の全値リストが未確認 | 確認して補完候補に追加 | High |
| G5 | 非推奨 ExecutionBackend 値の扱い未定義 | @Deprecated 相当のフラグ追加 | Low |
| G6 | periodStart/EndExclusive の日付フォーマット一覧が未文書化 | MultiDateParser の受理フォーマットを列挙 | Medium |
| G7 | FormulaInfoAdditionalFields の動的フィールド発見が未設計 | プロジェクト別の追加フィールド登録 API | Medium |

### dependsOn クロスリファレンス
| # | Observe | Suggest | Priority |
|---|---------|---------|----------|
| G8 | dependsOn のバリデーションが実行時のみ | LSP diagnostic で存在チェック | **Critical** |
| G9 | workspace レベル calculatorName インデックス未設計 | 初期スキャン + インクリメンタル更新 | High |
| G10 | dependsOn の値フォーマット（単一 vs 複数）が不明確 | FormulaInfoParser の実装を確認して仕様化 | High |
| G11 | rename の影響範囲プレビュー未設計 | workspace/prepareRename で変更ファイル一覧 | Medium |
| G12 | dependsOn 起因の本番障害データ未収集 | ROI 定量化のため過去障害を調査 | Medium |

### Java コードブロック
| # | Observe | Suggest | Priority |
|---|---------|---------|----------|
| G13 | embedded languages が VSCode 専用 | エディタ非依存の代替策を検討 | Medium |
| G14 | Java コードブロックのクラスパス解決手段がない | プロジェクト build 設定との紐づけ | Medium |
| G15 | Java コードブロックの推奨サイズ上限未定義 | 長い場合の分離メカニズム | Low |
| G16 | Java virtual document の行番号マッピング未設計 | DocumentFilter パターンで JavaSection を追加 | Medium |
| G17 | JDT diagnostic → FormulaInfo 位置の逆変換が未設計 | lineOffset 逆マッピング機構 | Medium |

### 3層コンテキスト切り替え
| # | Observe | Suggest | Priority |
|---|---------|---------|----------|
| G18 | `formula:$x+$y` のインライン式のコンテキスト判定が曖昧 | 同一行混在時のルール定義 | Medium |
| G19 | DocumentFilter に Java コードブロック抽出がない | JavaSection（text + lineOffset + className）を追加 | Medium |
| G20 | コンテキスト層の拡張性（将来の言語ブロック追加） | 汎用 ContextDetector インターフェース | Low |
| G21 | 3層セマンティックトークンのマージ戦略が未設計 | 位置変換 + 結合の実装 | Medium |
| G22 | 境界行のコンテキスト帰属ルールが未定義 | fence 行 = メタデータ、内容行 = 対象言語 | High |

### DAP
| # | Observe | Suggest | Priority |
|---|---------|---------|----------|
| G23 | Java コードブロックのデバッグ需要が未確認 | ユーザーヒアリング | Low |
| G24 | dependsOn 参照先の評価結果を DAP で表示できない | ウォッチ変数の拡張 | Low |

### Workspace
| # | Observe | Suggest | Priority |
|---|---------|---------|----------|
| G25 | 循環依存検出のタイミング・方式が未設計 | インクリメンタル依存グラフ更新 | High |
| G26 | 1ファイル内の複数 FormulaInfo セクションの扱い | DocumentFilter が全セクションを返す API | High |
| G27 | FormulaInfo ファイルの拡張子 / 識別方法が不明 | documentSelector 設定の仕様化 | High |
| G28 | workspace インデックスの整合性保証 | ファイルウォッチ + 定期フルスキャン | Medium |

### MVP スコープ
| # | Observe | Suggest | Priority |
|---|---------|---------|----------|
| G29 | MVP スコープが未確定 | 2層（メタデータ + formula）で Phase 1 | High |

---

## 優先順位（リヴァイの判断）

```
今すぐやれ（MVP — Phase 1）:
  G3, G4      — メタデータフィールド名・値の補完。FormulaInfo.fieldByName を LSP に公開。
  G8          — dependsOn の存在チェック diagnostic。本番障害防止の最優先。
  G9, G10     — workspace calculatorName インデックス + dependsOn フォーマット確認。
  G22, G27    — 境界行ルール定義 + ファイル拡張子の確定。
  G26         — 複数セクション対応。DocumentFilter の全セクション返却。

次にやれ（Phase 2 — Java コードブロック対応）:
  G16, G17, G19 — JavaSection 抽出 + 行番号マッピング + diagnostic 位置変換。
  G13, G14      — embedded languages の実装方式決定 + クラスパス解決。
  G21           — セマンティックトークンマージ。
  G25           — 循環依存検出。

後でいい:
  G1, G2       — ペルソナ分析、フィードバック計測。
  G5, G15, G20 — 非推奨フラグ、サイズ上限、拡張性。
  G11          — rename。影響範囲が怖い。
  G23, G24     — DAP 拡張。現状の式 DAP で十分。
```

---

## Decision Record

- **DAP**: 式の DAP で十分。Java コードブロックの DAP は不要（現時点）。
- **MVP スコープ**: メタデータ補完 + dependsOn バリデーション + go-to-definition の3機能。Java コード編集は Phase 2。
- **コンテキスト切り替え**: Phase 1 は2層（メタデータ + formula）。Phase 2 で3層（+ Java）。
- **embedded languages**: VSCode 専用を避け、エディタ非依存の方式を Phase 2 で検討。
- **workspace インデックス**: calculatorName → ファイル位置のマップを構築。初期フルスキャン + ファイルウォッチでインクリメンタル更新。
- **DocumentFilter 拡張**: formulaInfo() の横に javaCode() を追加する設計。ただし Phase 1 では不要。
- **「もう作った気もする」**: DocumentFilter.formulaInfo() は既に存在し formula セクション抽出は実装済み。メタデータ補完とクロスリファレンスは未実装。

---

## Action Items

| # | Task | Phase | Depends on |
|---|------|-------|------------|
| A1 | FormulaInfo.fieldByName を LSP プロセスから参照可能にする | 1 | G3 |
| A2 | ExpressionType enum の全値を確認・列挙 | 1 | G4 |
| A3 | メタデータフィールド名の textDocument/completion 実装 | 1 | A1 |
| A4 | メタデータ値の textDocument/completion 実装（enum 値） | 1 | A1, A2 |
| A5 | workspace calculatorName インデックスの構築 | 1 | G9, G27 |
| A6 | dependsOn の存在チェック diagnostic 実装 | 1 | A5, G10 |
| A7 | dependsOn の go-to-definition 実装 | 1 | A5 |
| A8 | DocumentFilter を全セクション返却に拡張 | 1 | G26 |
| A9 | FormulaInfo ファイルの documentSelector 定義 | 1 | G27 |
| A10 | 境界行のコンテキスト帰属ルールを仕様化 | 1 | G22 |
| A11 | DocumentFilter.javaCode() の設計・実装 | 2 | G19 |
| A12 | Java virtual document + 行番号マッピング | 2 | G16, G17 |
| A13 | セマンティックトークンマージ | 2 | G21 |
| A14 | 循環依存検出 diagnostic | 2 | A5, G25 |
