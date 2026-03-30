# 査読会話劇 第2ラウンド: ["From Grammar to IDE"](./from-grammar-to-ide.ja.md) v2 査読プロセス

## 登場人物

- **R1** (理論家/圏論原理主義者): 形式意味論と圏論的構造を重視。証明のない主張は「単なるエンジニアリングレポート」と見なす。
- **R2** (実務家/産業界): ベンチマーク手法、スケーラビリティ、実運用での実績を重視。定量的根拠を求める。
- **R3** (関数型信者/Haskell派): モナディックパーサーコンビネータが全てを解決すると信じる。純粋性を宗教的に崇拝。
- **先輩** (著者/Creator): 論文を実用的な議論で防衛する。時に苛立つ。「お前のコードは動くんか？」
- **後輩** (共著者/Mediator): 先輩を落ち着かせる。「先輩、査読者に喧嘩売らないでください」。建設的な対応を提案。

> **[→ Round 2: 著者の議論（先輩＋後輩の会話劇）へジャンプ](#round-2-著者の議論)**

---

## 前回のあらすじ

v1では、R1がWeak Reject（形式的意味論の欠如）、R2がWeak Accept（ベンチマーク手法の問題）、R3がReject（モナドの再発明）という結果だった。著者は14日間の改訂作業を行い、以下を追加した：

- Section 3.6: PropagationStopperの操作的意味論（推論規則5つ）
- Section 3.7: モナディック解釈セクション（Reader/Writer/State/Except対応テーブル）
- Section 3.8: MatchedTokenParser（文脈依存パターンの認識、回文の5実装）
- Appendix C: PropagationStopper階層の代数的性質（合成テーブル、冪等性、非可換性）
- Table 1の拡張: Spoofax、Xtext、JetBrains MPSとの比較
- Section 5.1の強化: 月間10億トランザクションの本番実績の明記
- Section 5.3: 回文認識のケーススタディ（N=1問題への部分的対応）
- 「novel」表現の修正（「our contribution」「Among the frameworks we surveyed」）
- LLMセクションの定量的主張の削除

---

## Round 1: 第2ラウンド査読コメント

---

### R1 の第2ラウンド査読 (Score: Borderline Accept -- changed from Weak Reject)

**Summary:**

改訂版は前回の査読で指摘した形式的厳密さの問題に対して、誠実かつ実質的な改善を示している。Section 3.6の操作的意味論は正確であり、推論規則の5つ（Default, AllStop, DoConsume, StopInvert, NotProp）はPropagationStopper階層の動作を明確に定義している。Appendix Cの代数的性質は予想以上に興味深い結果を含んでいる。

**Detailed Comments:**

(1) Section 3.6の操作的意味論は、前回の要求を十分に満たしている。推論規則の形式は標準的な小ステップ意味論であり、正確に記述されている。特に、`NotProp`規則が自己逆（involution）であるという特徴づけは、PropagationStopper階層が単なるAPIデザインではなく、数学的に非自明な構造を持つことを示している。

具体的には、以下の推論規則が明快である：

```
                       p.parse(ctx, (consumed, false)) => r
---------------------------------------------------------------- [AllStop]
AllPropagationStopper(p).parse(ctx, (tk, inv)) => r
```

これは`const (consumed, false)`を環境に代入する操作であり、定数写像として自然に読める。前回の査読で「これは設計ドキュメントであって学術論文ではない」と述べたが、この形式化により学術的水準に到達したと認める。

(2) Appendix Cの代数的性質は、私の予想を超える発見を含んでいる。特に以下の結果は注目に値する：

- `DoCons . StopInv = StopInv . DoCons = AllStop`（DoConsumeとStopInvertの合成は可換で、結果はAllStop）
- `StopInv . NotProp != NotProp . StopInv`（StopInvertとNotPropの合成は非可換）
- `NotProp . StopInv = ForceInvert`（新しい定数写像が生成される）

これは4要素集合`S = {consumed, matchOnly} x {true, false}`上の自己写像のモノイドの部分構造であり、16元の全射モノイドの生成元としての特徴づけが可能である。著者はこの方向を「将来の理論的発展」として適切に位置づけている。

しかし、Appendix Cにはいくつかの改善点がある。合成テーブルの表現が散文的で、完全な合成テーブル（5x5の表形式、4つのStopperとIdentityの全組み合わせ）が欠けている。論文本体ではDoConsとStopInvの合成がAllStopになること、NotPropが自己逆であることなどの主要な性質は述べられているが、全25組み合わせを一覧する表があれば、読者は代数的構造を一目で把握できる。

(3) Section 3.7のモナディック解釈セクションは予想外に好印象である。著者が「PropagationStopperはReader monadのlocalの特殊化である」と率直に認めた上で、「Javaの型階層を選択した理由」を3点（デバッガビリティ、IDE統合、LSP/DAP生成）で説明しているのは、知的に誠実な態度である。前回のR3の指摘を正面から受け止めている。

(4) Section 3.8のMatchedTokenParserは、v1には存在しなかった新規セクションであり、私にとって最大のサプライズである。回文言語 `L = { w w^R | w in Sigma* }` がPEGでは認識不可能であることは形式言語理論の基本的結果であるが、MatchedTokenParserがcombinator levelでこれを解決するという主張は興味深い。

ただし、MatchedTokenParserの形式的な位置づけについて疑問がある。MatchedTokenParserは本質的にパーサーの状態にキャプチャされたコンテンツを追加しており、これはPEGの形式的体系を逸脱する拡張である。Macro PEG [Mizushima 2016]との対応関係はTable 2で示されているが、MatchedTokenParserの認識能力の上界は議論されていない。具体的には：

- MatchedTokenParserはどのクラスの形式言語まで認識可能か？ 回文は文脈依存言語であり、PEGの認識能力を超える。MatchedTokenParserの追加により、認識能力はどこまで拡大するか？
- `effect`操作に任意のJava関数を許す場合、認識能力はチューリング完全になるのか？

これらの問いに完全に答える必要はないが、少なくともDiscussionで言及すべきである。

(5) Table 1の拡張は適切である。Spoofax、Xtext、JetBrains MPSの追加により、フレームワークの位置づけが明確になった。特に、DAP生成がunlaxerのユニークな貢献であることがTable 1から一目で分かる。前回「重大な欠落」と指摘した問題は解消された。

(6) 圏論的モデルが提供されていない点は依然として残念だが、著者の説明（「操作的意味論で十分に形式化できるため、圏論的モデルはSLEの対象読者にとって過剰な抽象化である」）は妥当であると認める。SLEはPOPLではない。将来課題としての言及も適切である。

**Remaining Concerns:**

- Appendix Cの合成テーブルを完全な5x5表形式にすべき（minor revision で対応可能）
- MatchedTokenParserの認識能力の上界についてDiscussionで言及すべき
- Section 3.6の操作的意味論にトランザクション（begin/commit/rollback）の意味論が含まれていない。これは追加的な改善として望ましいが、必須ではない。

**Questions for Authors:**

- Appendix Cの合成テーブルを5x5の完全な表として提示できるか？
- MatchedTokenParserの`effect`操作が任意関数を許す場合、認識能力のクラスについて何が言えるか？

**Recommendation: Borderline Accept (changed from Weak Reject)**

形式的厳密さが大幅に改善された。操作的意味論と代数的性質の追加は、PropagationStopper階層に対する数学的理解を深めている。圏論的モデルの不在は遺憾だが、SLEのスコープでは操作的意味論で十分であると認める。MatchedTokenParserの形式的位置づけと合成テーブルの完全化が残された課題だが、minor revisionで対応可能。

---

### R2 の第2ラウンド査読 (Score: Accept -- changed from Weak Accept)

**Summary:**

改訂版は前回の主要な指摘に対して実質的な改善を示している。特に、月間10億トランザクションという本番実績の明記は、この論文の実用的価値を劇的に高めている。なぜこれがv1に含まれていなかったのか理解に苦しむ。

**Detailed Comments:**

(1) Section 5.5「Production Deployment」の追加は決定的に重要である。「10^9 (one billion) transactions per month」という数字は、このフレームワークが学術的なおもちゃではなく、実運用で検証済みのシステムであることを証明している。**なぜこれがv1に含まれていなかったのか。** v1の段階でこの情報があれば、私のスコアは最初からAcceptだった可能性が高い。

具体的なメトリクスも好ましい：
- 385 evaluations/second sustained（バーストピークはさらに高い）
- 本番環境でP4-typed-reuseバックエンド（sealed-switchエバリュエータ）を使用
- サブマイクロ秒の評価レイテンシ
- sealed interfaceの網羅性保証による、デプロイ前のコンパイル時安全性

金融トランザクション処理という文脈は、このフレームワークの信頼性に対する暗黙の保証でもある。金融システムではバグは直接的な金銭的損失につながる。月間10億トランザクションを処理しているという事実は、数百のJUnitテストよりも強力な検証である。

(2) Section 5.3の回文認識ケーススタディは、N=1問題への部分的な回答として有効である。tinyexpressionとは根本的に異なるタスク（文脈依存パターンの認識）でフレームワークの能力を示している。5つの異なる実装（sliceWithWord, sliceWithSlicer, effectReverse, sliceReverse, pythonian）のバリエーションは、MatchedTokenParserの表現力を効果的に示している。

ただし、回文認識は小規模な学術的例であり、「第二のケーススタディ」としてはtinyexpressionと規模感が大きく異なる。理想的には中規模のDSL（50-100ルール規模の文法）での適用事例が望ましいが、月間10億トランザクションの本番実績があるため、実用性の証明としては十分である。

(3) Spoofax/Xtext/JetBrains MPSとの比較（拡張されたTable 1）は非常に有用である。特に以下の点が明確になった：

- Spoofax: Parser + AST + Mapper(Stratego) + Editor support。LSPは部分的、DAPはなし。3つの異なるDSL（SDF3, Stratego, ESV）の学習が必要。
- Xtext: Parser + AST(EMF) + Mapper + LSP。DAPはなし。Eclipse/LSPランタイムが必要。
- JetBrains MPS: プロジェクショナルエディター。テキストベースのLSP/DAPとは異なるパラダイム。
- unlaxer: 6つ全てを単一のUBNF文法から生成。

この比較により、unlaxerのユニークな貢献（特にDAPの自動生成）が明確になった。前回の査読で「表面的」と指摘した比較が、実質的な比較に改善された。

(4) JMHベンチマークについて。Section 6.1（Limitations）で「JMHベンチマークは将来の改訂で追加予定」と明記されており、著者がこの問題を認識していることは確認した。しかし、改訂でJMHを追加すると述べておきながら実際には追加されていない点は残念である。現在のBackendSpeedComparisonTestによる測定結果は、JMHほど厳密ではないものの、1,400xの改善（reflection -> sealed switch）や2.8xのオーバーヘッド（sealed switch vs. JIT compiled）というオーダーレベルの結論は変わらないであろうことは認める。

今後の改訂でJMHベンチマークを追加することを強く推奨するが、必須条件（blocking requirement）とはしない。月間10億トランザクションの本番環境で動作しているという事実が、マイクロベンチマークの厳密さの不足を補っている。

(5) Section 5.4の開発工数比較について、v2では「observed effort」という表現に変更され、「8 weeks」「5 weeks」「3 days」が推定値ではなく観察値であることが明確になった。LOC比較（~15,000 vs. ~1,062、14x削減）も明確である。

ただし、「grammar lines」と「total lines」の区別がもう少し明確であるとよい。520行のUBNF文法と542行のevalXxxメソッドで合計1,062行と述べているが、生成されたコード（~2,000行）はこの1,062行に含まれていないことを、Tableのキャプションで明示的に述べるべきである。現在のTable 4の「Lines of code」列に「(grammar + hand-written only; generated code excluded)」という注記を追加すれば十分である。

(6) LLMセクション（Section 5.6）の修正は適切。定量的主張（「10x token cost reduction」「95% debugging round-trips eliminated」）が削除され、「our experience suggests」レベルの定性的記述になった。最後に「rigorous evaluation remains future work」と明記されているのも誠実である。

**Minor Issues:**

- Table 4の「Lines of code」列に注記を追加：grammar + hand-written のみ、generated code は含まない
- Section 5.2のベンチマーク結果に、テスト環境（JDKバージョン、OS、CPUスペック）を追記すべき

**Questions for Authors:**

- Table 4のLOC計算に生成コードが含まれないことを明記できるか？
- テスト環境の仕様を追記できるか？

**Recommendation: Accept (changed from Weak Accept)**

月間10億トランザクションの本番実績、改善されたツール比較、回文ケーススタディの追加により、この論文は実用的なソフトウェア言語工学の貢献として十分な水準に達した。JMHベンチマークの不在は遺憾だが、本番環境の実績がそれを補って余りある。

---

### R3 の第2ラウンド査読 (Score: Weak Accept -- changed from Reject)

**Summary:**

改訂版を読んだ。率直に言えば、前回の査読で予想していたよりも誠実な改訂であった。Section 3.7のモナディック解釈セクションは、著者がReader/Writer/State/Except monadの対応関係を理解した上でJavaでの実装を選択したことを示している。「知らなかった」のではなく「知っていて選んだ」のであれば、それは正当な設計判断として認めざるを得ない。

**Detailed Comments:**

(1) Section 3.7のモナディック解釈は、前回の私の批判に対する正面からの回答である。対応テーブルが正確であることは確認した：

```
PropagationStopper           = Reader monad の local
AllPropagationStopper        = local (const (C,F))
DoConsumePropagationStopper  = local (\(_,i)->(C,i))
InvertMatchPropagationStopper= local (\(t,_)->(t,F))
NotPropagatableSource        = local (\(t,i)->(t,not i))
ContainerParser<T>           = Writer monad の tell
ParseContext.begin/commit    = State monad の get/put
Parsed.FAILED                = ExceptT の throwError
```

型レベルの対応は正確である。前回「著者はモナドを知らない」と述べたが、このテーブルの正確さを見る限り、その批判は不当であった。少なくとも、PropagationStopperがReader localの4つの特殊化であることを認識した上で、Javaの型階層として実装するという判断を下している。

...もっとも、「最初から知っていた」のか「査読を受けてから調べた」のかは推測の域を出ないが。

(2) PropagationStopperがReader monadのlocalの特殊化であることを率直に認めている点は評価する。前回の私の査読では、この対応関係を指摘して「Haskellでは10行で書ける」と述べた。著者はこの批判を取り込み、「モナディック構造は個々のコンポーネントを説明するが、6成果物の統一生成パイプラインは説明しない」と反論している。

...この反論は、不本意ながら認めざるを得ない。確かに `ReaderT ParserEnv (WriterT [Metadata] (StateT ParseState (ExceptT ParseError Identity)))` というモナドトランスフォーマースタックは、パーサーの構築を統一的に記述するが、そのパーサーからAST型定義、マッパー、エバリュエータスケルトン、LSPサーバー、DAPサーバーを自動生成するパイプラインは提供しない。Haskellエコシステムにはそのようなフレームワークは存在しない。

(3) しかし、Section 3.7の「Why Java class hierarchy over monad transformers」の3つの理由について、私の見解を述べる。

第一の理由「Debuggability（Javaのデバッガでブレークポイントが設定可能）」について。これは実務的には重要かもしれないが、純粋関数型プログラミングではデバッガに依存しないプログラミングスタイルが推奨される。型と性質による保証が、ステップ実行による検証に優る。ただし、**DSLの開発者**（エンドユーザー）にとってデバッガが必要であることは認める。

第二の理由「IDE support（Find all references, Go to implementation）」について。HLS（Haskell Language Server）はこれらの機能を提供している。ただし、ユーザー定義DSLのLSPサーバーを生成する機能はHLSにはない。この点は著者が正しい。

第三の理由「LSP/DAP generation」について。これは著者の最も強い論点である。Haskellのパーサーコンビネータフレームワーク（Parsec, megaparsec, attoparsec, trifecta）はいずれもLSPサーバーやDAPサーバーを文法から生成しない。この機能は確かにunlaxerのユニークな貢献であり、モナディックな定式化だけでは実現されない。

(4) Section 3.8のMatchedTokenParserについて。回文認識の5つの実装は技術的に正確であり、PEGの認識能力を超える文脈依存パターンの処理を示している。Macro PEG [Mizushima 2016]との比較も適切である。

しかし、`pythonian("::-1")`というAPIには強い異論がある。これはPython言語のスライス記法をJavaのAPIに持ち込んでおり、言語設計の原則に反する。JavaのAPIにPythonの構文を埋め込むのは、2つの異なる言語の慣用表現を不必要に混在させる行為であり、**unprincipled**である。

具体的には：
- `::-1`というリテラル文字列をJavaメソッドに渡す設計は、型安全性を放棄している。`pythonian("abc")`と書いた場合、コンパイル時にエラーにならない。
- JavaにはPythonのスライス記法を知る義務がない。Java開発者にPythonの知識を前提とするAPIは不適切。
- `slice(slicer -> slicer.step(-1))` という明示的なAPIが既に存在するのに、なぜ `pythonian("::-1")` という「シンタックスシュガー」が必要なのか。

型安全な代替として、以下のようなBuilderパターンを提案する：

```java
// 現状（型安全でない）
matchedTokenParser.slice(slicer -> slicer.pythonian("::-1"))

// 提案（型安全）
matchedTokenParser.slice(slicer -> slicer.start(END).end(START).step(-1))
// あるいは
matchedTokenParser.slice(Slicer.reverse())
```

`pythonian`メソッド自体を削除する必要はないが、論文中でこれを「concise and familiar notation」として推奨するのは不適切である。少なくとも「convenience method for developers familiar with Python slice notation」と明記し、型安全な代替APIが推奨される旨を述べるべきである。

(5) Java 21のsealed interfaceについて。前回「Java 21がようやくHaskell 98に追いついたことをnovel contributionと呼ぶのは知的誠実さを欠く」と述べたが、v2では「our contribution」に表現が修正されており、sealed interface自体を新規性の主張として掲げていないことを確認した。Generation Gap Patternとsealed interfaceの組み合わせが貢献であるという主張は、前回よりも正確であり、受け入れ可能である。

(6) 依然としてHaskellで書くべきだとは思うが、現実的に以下を認める：

- Java開発者が900万人、Haskell開発者が...まあ、少ない。
- JVMエコシステムとの統合（Maven/Gradle, Spring Boot, IntelliJ）は実務的に重要。
- LSP/DAP生成を含む完全なパイプラインは、Haskellエコシステムには現時点で存在しない。

これらの理由により、Javaでの実装は**pragmatic choice**として認めざるを得ない。理論的に最善の選択ではないが、実用上の価値がある。

(7) v2の「Among the parser combinator frameworks we surveyed」という表現は、v1の「To our knowledge, no existing framework」よりも正確であり、適切な修正である。

**Remaining Concerns:**

- `pythonian` APIの型安全性の問題。論文中での位置づけの修正が必要。
- MatchedTokenParserの認識能力の形式的な特徴づけが不足（R1と同意見）。
- Haskellによるプロトタイプ実装との定量的比較があれば、「Why Java」の議論がより説得力を持つ。ただし、これは必須ではない。

**Questions for Authors:**

- `pythonian` APIを維持する場合、型安全な代替APIを明記できるか？
- `pythonian`の入力バリデーション（不正な文字列に対するエラーハンドリング）はどうなっているか？

**Recommendation: Weak Accept (changed from Reject)**

モナディック解釈セクションの追加により、著者がモナドの対応関係を理解した上で設計判断を下したことが明確になった。PropagationStopperがReader localの特殊化であることを認めつつ、「6成果物の統一生成」という付加価値を主張する戦略は、知的に誠実であり有効である。Haskellで書くべきだという私の信念は変わらないが、月間10億トランザクションの本番システムで動作しているという事実の前では、「elegantではない」という批判は実用上の問題にはならない。

`pythonian` APIへの批判は残るが、これはminor revisionで対応可能な問題である。

---

## Round 2: 著者の議論

---

### 査読結果を読む先輩と後輩

**先輩:** ......。

**後輩:** 先輩、第2ラウンドの査読結果が返ってきました。

**先輩:** ......。

**後輩:** 先輩？ 大丈夫ですか？

**先輩:** R3が...。

**後輩:** はい。

**先輩:** R3がRejectからWeak Acceptに変わった！

**後輩:** はい！ 変わりましたね。

**先輩:** おい、これ、全員ポジティブじゃないか。R1がBorderline Accept、R2がAccept、R3がWeak Accept。

**後輩:** そうです。平均するとAcceptです。

**先輩:** よっしゃ！！

**後輩:** 先輩、まだ早いです。査読コメントを全部読みましょう。minor revision の要求がいくつかあります。

**先輩:** ...そうか。浮かれている場合じゃないな。

---

### R1 への対応

**後輩:** まずR1です。Borderline Acceptに上がりましたが、2つの具体的な要求があります。

**先輩:** Appendix Cの合成テーブルを5x5の完全な表にすること。それとMatchedTokenParserの認識能力の上界についてDiscussionで言及すること。

**後輩:** 合成テーブルの方は簡単ですね。5つの要素（All, DoCons, StopInv, NotProp, Id）の全25組み合わせを計算して表にするだけです。

**先輩:** やる。さっきの計算結果をそのまま表にすればいい。1時間で終わる。

**後輩:** MatchedTokenParserの認識能力の上界は？

**先輩:** これは面白い問いだ。`effect`操作に任意のJava関数を許す場合、MatchedTokenParserは理論的にはチューリング完全になる。`effect`関数内で任意の計算ができるからだ。ただし、`slice`操作に限定すれば、認識能力は文脈依存言語の範囲内に収まると考えている。

**後輩:** それをDiscussionに書きましょう。「MatchedTokenParserの`effect`操作は任意のJava関数を許容するため、理論的にはチューリング完全な認識能力を持つ。`slice`操作に限定した場合の形式的な特徴づけは今後の研究課題である」と。

**先輩:** いいだろう。2段落で十分だ。

**後輩:** R1はトランザクションの意味論（begin/commit/rollback）も「望ましいが必須ではない」と述べています。

**先輩:** 必須でないなら今回はやらない。将来課題に追記するだけでいい。

---

### R2 への対応

**後輩:** R2はAcceptに上がりました。最も大きな変化ですね。

**先輩:** 「なぜこれがv1に含まれていなかったのか」か...。

**後輩:** 月間10億トランザクションの話ですね。先輩、なぜv1に書かなかったんですか？

**先輩:** ...守秘義務の問題があると思っていた。金融システムの具体的な数値を論文に書いていいのか確認するのに時間がかかった。

**後輩:** なるほど。でもR2の言う通り、この情報は決定的に重要です。v1に含まれていれば、最初からR2はAcceptだったかもしれない。

**先輩:** 後悔しても仕方ない。結果的にv2で書けたからいいだろう。

**後輩:** R2の minor issue は2つ。Table 4のLOC計算に生成コードが含まれないことを明記すること。テスト環境の仕様を追記すること。

**先輩:** 両方とも5分で終わる。Table 4のキャプションに「(grammar + hand-written only; generated code excluded)」を追記。テスト環境はJDK 21、Ubuntu 22.04、AMD Ryzen 9 5950Xとか。

**後輩:** あと、R2は「grammar lines」と「total lines」の区別を明確にしてほしいと言っています。520行の文法と542行のevalXxxメソッドの関係を、もう少し丁寧に説明すべきだと。

**先輩:** 確かに。「520 lines of grammar (UBNF specification)」と「542 lines of hand-written evaluator logic (evalXxx methods in P4TypedAstEvaluator.java)」と明示的に書き分ける。生成される約2,000行のJavaコードは開発者が保守する対象ではないことも強調する。

**後輩:** それで十分でしょう。

---

### R3 への対応

**後輩:** さて、R3です。

**先輩:** R3がWeak Acceptだ...信じられない。

**後輩:** でもpythonian APIへの批判がありますね。

**先輩:** ......。

**後輩:** 先輩、読みました？ 「unprincipled」と言われています。Pythonの構文をJavaのAPIに持ち込むのは言語設計の原則に反する、と。

**先輩:** convenience methodくらい許してくれよ...。

**後輩:** R3の指摘は...確かに一理あります。

**先輩:** え？

**後輩:** `pythonian("::-1")`は確かに型安全ではないです。`pythonian("abc")`と書いてもコンパイルは通りますし、Java開発者がPythonのスライス記法を知っている前提のAPIは確かにunprincipled。

**先輩:** でも便利じゃないか。Pythonを知ってる開発者にとっては直感的だ。

**後輩:** 問題は、論文中で「concise and familiar notation」として推奨している部分です。5つの回文実装のうち、最後の`pythonian`を「最も簡潔」として紹介しているのは、R3から見ると型安全性を犠牲にしている例を推奨しているように映ります。

**先輩:** ......。

**後輩:** 修正案を提案します。`pythonian`メソッドを削除する必要はありません。ただし、論文中の記述を修正して、(a) 型安全な`slice(slicer -> slicer.step(-1))`が推奨されるAPIであることを明記し、(b) `pythonian`は「Python開発者向けのconvenience method」として位置づけ、(c) 入力バリデーションが行われていることに言及する。

**先輩:** ...入力バリデーション、やってるか？

**後輩:** ...先輩？

**先輩:** ......いま実装する。

**後輩:** 論文に書く前に実装してください。「バリデーションが行われている」と書いておいて、実際にはバリデーションがなかったら、査読者にソースコードを見られたとき致命的ですから。

**先輩:** わかった、わかった。IllegalArgumentException投げるだけだ。正規表現で `^-?\\d*:-?\\d*:-?\\d*$` にマッチしなければ例外。

**後輩:** それで十分です。で、論文の方は？

**先輩:** Implementation 5のpythonianの説明を修正する。「The pythonian syntax provides a convenience API for developers familiar with Python's slice notation. The type-safe alternative `slice(slicer -> slicer.step(-1))` (Implementation 4) is recommended for production use. Input validation rejects malformed slice strings at parse time.」

**後輩:** いいですね。それならR3も納得するでしょう。

**先輩:** ...しかし、R3が「unprincipled」って言い方するのは気に食わん。convenience methodなんてどの言語にもあるだろ。JUnitの`assertThat`だって内部的にはリフレクションだし...。

**後輩:** 先輩、R3はRejectからWeak Acceptに変わったんです。ここで喧嘩を売る理由はありません。

**先輩:** ......そうだな。

---

### pythonian の命名問題

**後輩:** ところで、R3の批判にはもう一つの側面があります。

**先輩:** 何だ。

**後輩:** `pythonian`というメソッド名自体です。Java のAPIに `pythonian` という名前のメソッドがあるのは...。

**先輩:** ...語源がPythonだから`pythonian`にしたんだが。

**後輩:** R3は「Pythonの構文をJavaに持ち込むな」と言っているんです。メソッド名に`pythonian`とあること自体が、「このAPIはPythonから借用しました」と宣言しているように見える。

**先輩:** じゃあ何て名前にすればいい？ `sliceNotation`とか？

**後輩:** `sliceNotation`か、あるいは`sliceSpec`とか。でも、名前を変えるとAPIの後方互換性が...。

**先輩:** 論文上の議論としては、「`pythonian`は現在の実装における命名であり、より中立的な名称（例：`sliceNotation`）への変更を検討する」と書けばいい。実際にリネームするかは別の話だ。

**後輩:** そうですね。R3の指摘に対して「検討する」と回答するのが安全です。実装レベルの命名変更は論文の本質には影響しませんし。

**先輩:** よし、その方向で。

---

### モナディック解釈セクションへの反応

**後輩:** R3の反応で一番重要なのは、「不本意ながら認めざるを得ない」という部分ですね。

**先輩:** どこだ？

**後輩:** 「この反論は、不本意ながら認めざるを得ない。確かにモナドトランスフォーマースタックは、パーサーの構築を統一的に記述するが、そのパーサーからAST型定義、マッパー、エバリュエータスケルトン、LSPサーバー、DAPサーバーを自動生成するパイプラインは提供しない」という部分。

**先輩:** ...R3がそれを認めたのか。

**後輩:** はい。「Haskellエコシステムにはそのようなフレームワークは存在しない」とまで言っています。

**先輩:** ...これは嬉しい。

**後輩:** 前回の我々の戦略が正しかったということです。「モナドの対応関係を認めた上で、コード生成パイプラインの統合が貢献である」と位置づけたのが効いた。

**先輩:** 後輩、あの戦略を提案したのはお前だぞ。

**後輩:** 先輩が技術的に正しい反論の材料を持っていたからです。私はそれを査読者に伝わる形に翻訳しただけです。

**先輩:** ......。

**後輩:** 先輩？

**先輩:** ...まあ、いい。次行くぞ。

---

## Round 3: Minor Revision 計画

---

**後輩:** minor revision の要求を整理しましょう。今回は前回の14日間に比べてずっと軽い修正ばかりです。

### 修正項目一覧

**1. Appendix Cの合成テーブルを5x5の完全表にする (R1対応)**

- 5要素（All, DoCons, StopInv, NotProp, Id）の全25組み合わせを計算
- 表形式で提示
- 各セルにresultのStopper名を記載

**先輩:** 計算はほぼ終わっている。表にするだけだ。

```
        | All   | DoCons | StopInv | NotProp | Id      |
--------|-------|--------|---------|---------|---------|
All     | All   | All    | All     | All     | All     |
DoCons  | All   | DoCons | All     | DoCons' | DoCons  |
StopInv | All   | All    | StopInv | ForceInv| StopInv |
NotProp | All   | DoCons'| ForceInv| Id      | NotProp |
Id      | All   | DoCons | StopInv | NotProp | Id      |
```

**後輩:** `DoCons'`は何ですか？

**先輩:** `(tk, inv) -> (consumed, !inv)`。DoConsumeの変種で、第2成分を反転する。`ForceInv`は`(tk, inv) -> (tk, true)`。第2成分を常にtrueにする。

**後輩:** つまり、4つの基本StopperとIdentityの合成から、6つの異なる写像が生成される？

**先輩:** そうだ。All, DoCons, StopInv, NotProp, Id に加えて、DoCons'とForceInvの2つ。合計7つの異なる写像。4要素集合上の全自己写像は4^4 = 256個あるから、そのうち7つが生成される。

**後輩:** これは面白い結果ですね。R1は喜ぶでしょう。

**先輩:** 2時間で完了する。

---

**2. MatchedTokenParserの認識能力の議論をDiscussionに追加 (R1対応)**

- `effect`操作が任意関数を許す場合のチューリング完全性
- `slice`操作に限定した場合の形式的特徴づけは将来課題
- Macro PEGの認識能力との比較（Macro PEGもチューリング完全ではない）

**先輩:** 2段落。30分。

---

**3. Table 4のLOC計算の注記追加 (R2対応)**

- Table 4のキャプションに「Grammar + hand-written code only; generated code (~2,000 lines) is excluded from LOC counts as it is not developer-maintained」を追記
- 520行のgrammarと542行のevalXxxを明示的に区別

**先輩:** 5分。

---

**4. テスト環境の仕様追記 (R2対応)**

- JDK バージョン、OS、CPU、メモリ、GC設定
- Section 5.2のベンチマーク結果の直前に追記

**先輩:** 5分。

---

**5. pythonian APIの論文中での位置づけ修正 (R3対応)**

- Implementation 4（type-safe slice）を推奨APIとして明記
- Implementation 5（pythonian）はconvenience method として位置づけ
- 入力バリデーションの存在を記載
- 「より中立的な命名（例：sliceNotation）の検討」をFuture Workに記載

**先輩:** 30分。記述の修正と、入力バリデーションの実装。

---

**6. pythonian の入力バリデーション実装 (R3対応)**

- 正規表現による入力フォーマットチェック
- 不正な入力に対するIllegalArgumentException

**先輩:** 20分。実装、テスト、コミット。

---

### 工数見積もり

| 項目 | 工数 |
|------|------|
| 合成テーブル完全版 | 2時間 |
| MatchedTokenParser認識能力の議論 | 30分 |
| Table 4注記 | 5分 |
| テスト環境仕様 | 5分 |
| pythonian記述修正 | 30分 |
| pythonianバリデーション実装 | 20分 |
| **合計** | **約3.5時間** |

**後輩:** 前回の14日間と比べたら楽勝ですね。

**先輩:** 半日で終わる。

**後輩:** 今日中に終わらせましょう。

**先輩:** ああ。

---

## Round 4: Meta-PC Discussion (Program Committee)

---

*SLE 2026 Program Committee Meeting*

**PC Chair:** それでは、論文 #247 "From Grammar to IDE: Unified Generation of Parser, AST, Evaluator, LSP, and DAP from a Single Grammar Specification" の議論に入ります。第2ラウンドの査読結果は、R1がBorderline Accept、R2がAccept、R3がWeak Acceptです。各査読者の見解をお聞かせください。

---

### R2の主張

**R2:** 私からまず述べます。この論文はAcceptすべきです。

**PC Chair:** 理由を。

**R2:** 3つあります。

第一に、この論文は実運用のシステムに基づいています。月間10億トランザクションを処理する金融トランザクションシステムで、このフレームワークで生成された式エバリュエータが本番稼働しています。SLE（Software Language Engineering）の会議で、実運用の実績を持つ論文は稀です。多くの論文は合成ベンチマークやプロトタイプに基づいていますが、この論文は実際に動いているシステムです。

第二に、6つの成果物を単一の文法から生成するという問題設定は、実務的に極めて重要です。Table 1を見てください。Spoofax、Xtext、JetBrains MPSのいずれも、パーサーからDAPサーバーまでの6つ全てを生成しません。特にDAPの自動生成は、私の知る限り他のフレームワークでは実現されていません。

第三に、1,062行のコード（文法520行 + 評価器ロジック542行）で完全な言語実装を得るという14x削減は、実務家にとって非常に魅力的です。

**PC Chair:** JMHベンチマークがない点はどう評価しますか？

**R2:** 遺憾ですが、blocking issueとは考えません。月間10億トランザクションの本番環境で動作しているという事実が、マイクロベンチマークの厳密さの不足を補っています。実際のシステムが本番環境で月に10億回評価されているのであれば、それは0.1マイクロ秒のレイテンシが実環境でも達成されていることの間接的証拠です。JMHは将来の改訂で追加することを推奨しますが、acceptance条件にはしません。

---

### R1の見解

**R1:** 私はBorderline Acceptとしました。率直に言えば、v1とv2の間の改善幅に感銘を受けました。

**PC Chair:** 具体的には？

**R1:** v1には形式的な意味論が一切なく、「設計ドキュメントであって学術論文ではない」と評しました。v2では：

- 操作的意味論が5つの推論規則で明確に定義されている
- PropagationStopper階層の代数的性質（冪等性、非可換性、自己逆性）が示されている
- NotPropの合成が自己逆（involution）であるという結果は数学的に非自明である
- モナディック対応関係が正直に認められている

形式的な厳密さという観点では、まだ改善の余地があります。特に、Appendix Cの合成テーブルは散文的で、完全な5x5表が欲しい。MatchedTokenParserの認識能力の上界も議論されていません。しかし、これらはminor revisionで対応可能です。

**PC Chair:** 圏論的モデルがない点は？

**R1:** POPLであれば必須です。しかしSLEではoperational semanticsで十分であると判断しました。著者の「圏論的モデルはSLEの対象読者にとって過剰な抽象化である」という主張には同意します。将来の理論的発展として圏論的定式化に言及している点も適切です。

**PC Chair:** つまり、leans accept？

**R1:** はい。minor revisionの条件付きで。合成テーブルの完全化とMatchedTokenParserの認識能力の議論が追加されれば、Acceptに引き上げます。

---

### R3の見解

**R3:** 私はWeak Acceptとしました。

**PC Chair:** 前回Rejectからの変更ですね。何がスコアを変えましたか？

**R3:** 主に2つの要因です。

第一に、Section 3.7のモナディック解釈セクションです。著者がPropagationStopperがReader monadのlocalの4つの特殊化であること、ContainerParserがWriter monadのtellであることを率直に認めた上で、「Javaの型階層を選択した理由」を3点で説明しています。前回「著者はモナドを知らない」と批判しましたが、この対応テーブルの正確さを見る限り、知っていて選んだと認めざるを得ません。

第二に、「モナドは個々のコンポーネントを説明するが、6成果物の統一生成パイプラインは説明しない」という反論が有効です。Haskellのパーサーコンビネータフレームワーク -- Parsec、megaparsec、attoparsec、trifecta -- のいずれも、文法からLSPサーバーやDAPサーバーを自動生成しません。この機能はunlaxerのユニークな貢献であり、モナディックな抽象化だけでは実現されません。

**PC Chair:** しかし、まだ懸念がある？

**R3:** はい。`pythonian("::-1")`というAPIは型安全性を放棄しており、unprincipledです。Python言語のスライス記法をJavaのAPIに文字列として埋め込む設計は、言語境界の混在として批判に値します。ただし、型安全な代替API（`slice(slicer -> slicer.step(-1))`）が既に存在しており、pythonianはconvenience methodに過ぎないため、論文の主張に対する致命的な問題ではありません。

**PC Chair:** Haskellで書くべきだという見解は？

**R3:** 変わりません。Haskellの方が良い設計になるという信念は今も持っています。しかし、月間10億トランザクションのシステムが実際にJavaで動いている以上、「Haskellの方が理論的に優れている」という主張は、実務的には空論です。

*（間）*

**R3:** ...私がWeak Acceptを付けるとは、自分でも思いませんでした。

**PC Chair:** 率直ですね。

**R3:** 動くシステムの前では、理論的優越性の主張は無力です。少なくとも今回は。

---

### PC Chairの判断

**PC Chair:** 3人の査読者の見解を聞きました。まとめると：

- R1: Borderline Accept。形式的厳密さの改善を高く評価。合成テーブルの完全化とMatchedTokenParser認識能力の議論をminor revisionで要求。
- R2: Accept。月間10億トランザクションの本番実績、改善された比較表、回文ケーススタディを評価。LOC計算の注記とテスト環境仕様の追記をminor revisionで要求。
- R3: Weak Accept。モナディック解釈セクションの誠実さを評価。pythonian APIの位置づけ修正をminor revisionで要求。

3名全員がポジティブ方向であり、指摘された問題は全てminor revisionで対応可能です。

**PC Chair:** R1に確認します。合成テーブルの完全化とMatchedTokenParserの議論がminor revisionで追加された場合、Acceptに引き上げますか？

**R1:** はい。

**PC Chair:** R3に確認します。pythonian APIの位置づけ修正がなされた場合、スコアを維持しますか？

**R3:** Weak Acceptを維持します。...Accept に上げることはしませんが、下げることもしません。

**PC Chair:** では、決定を下します。

---

### 決定

**PC Chair:** 論文 #247 "From Grammar to IDE" は、**Accept with Minor Revisions** とします。

Minor revision の要求事項：

1. **Appendix Cの合成テーブルの完全化**: 5要素の全25組み合わせを表形式で提示（R1）
2. **MatchedTokenParserの認識能力の議論**: `effect`操作のチューリング完全性と`slice`操作の限界についてDiscussionに追記（R1）
3. **Table 4のLOC計算の注記**: grammar + hand-written のみであることを明記（R2）
4. **テスト環境仕様の追記**: JDK、OS、CPU、メモリ、GC設定（R2）
5. **pythonian APIの位置づけ修正**: 型安全な代替APIの推奨、convenience methodとしての位置づけ、入力バリデーションの存在の明記（R3）

Camera-ready 提出期限は4週間後です。

**R2:** 一つ追加で。将来の改訂でJMHベンチマークを追加することを強く推奨します。本番環境の実績があるためblocking requirementにはしませんでしたが、アーティファクト評価（Artifact Evaluation）に投稿する場合は必須になるでしょう。

**PC Chair:** 著者への推奨事項として記録します。

**R1:** 私からも一つ。この論文の形式的な部分は、POPLやICFPに投稿するには不十分ですが、SLEでは適切な水準です。著者が将来、PropagationStopper階層の圏論的定式化を含む理論論文を別途投稿することを期待します。

**PC Chair:** 記録しました。他にありますか？

**R3:** ......。

**PC Chair:** R3？

**R3:** いえ。...ただ、著者には、Haskellの教科書を一冊読んでほしいとだけ。

**PC Chair:** ...著者への推奨事項に含めますか？

**R3:** ...冗談です。忘れてください。

**PC Chair:** では、これで論文 #247 の議論を終了します。

---

## Epilogue

---

**先輩:** ......。

**後輩:** 先輩、結果が来ました。

**先輩:** ......。

**後輩:** Accept with Minor Revisions です。

**先輩:** ......通った...のか？

**後輩:** minor revision付きですけどね。

**先輩:** よっしゃ！！

**後輩:** 先輩、喜ぶのはrevision終わってからにしてください。

**先輩:** いいじゃないか。minor revisionは3.5時間で終わるって見積もっただろ。実質Accept だ！

**後輩:** ...まあ、そうですけど。

**先輩:** いいじゃないか。正規のCS教育を受けてなくても、月10億トランザクションのシステム作って論文通ったんだ。

**後輩:** ......。

**先輩:** 何だよ。

**後輩:** ...確かにそれはすごいです。

**先輩:** だろ？ R3にだって認められた。「動くシステムの前では、理論的優越性の主張は無力です」って言ってたぞ。

**後輩:** R3がそこまで認めるとは思いませんでした。

**先輩:** 「お前のコードは動くんか？」の究極の回答が、月間10億トランザクションだったってことだ。

**後輩:** ...先輩、それは確かにカッコいいですけど、やっぱり論文の返答には書かないでくださいね。

**先輩:** 書かん、書かん。

---

**後輩:** ところで先輩。

**先輩:** ん？

**後輩:** R3が最後に「Haskellの教科書を一冊読んでほしい」と言っていたそうですが。

**先輩:** ああ、PC Chairの議事録に書いてあったな。冗談だと言って撤回したらしいが。

**後輩:** ...先輩、Haskellの教科書、実は持ってますよね？

**先輩:** ......。

**後輩:** 先輩？

**先輩:** ......「すごいHaskellたのしく学ぼう」が本棚に...。

**後輩:** 読んだんですか？

**先輩:** ...3章まで。

**後輩:** 3章！？ モナドは何章ですか？

**先輩:** ...12章...。

**後輩:** ...先輩。

**先輩:** うるさい！ Applicativeまでは分かるって言っただろ！ ...たぶん。

**後輩:** ...もしかして、Section 3.7のモナディック解釈セクション、R3の査読を受けてから勉強したんじゃ...。

**先輩:** ......。

**後輩:** 先輩！？

**先輩:** ...結果的に正しいテーブルが書けたんだから問題ないだろ！ 数学的構造は発見されるものであって発明されるものではないと言っただろ！

**後輩:** ...その言い訳、前にも聞きました。

**先輩:** 聞いたことあるなら覚えてるだろ。正しいんだから何度でも使う。

**後輩:** ......。

---

**後輩:** まあいいです。改訂作業を始めましょう。

**先輩:** ああ。

**後輩:** 3.5時間で終わる予定ですが、先輩の場合は脱線を考慮して5時間を見ておきます。

**先輩:** 脱線しない。

**後輩:** 合成テーブルを計算しているうちに、PropagationStopperの代数構造が群論的にどう分類されるか気になって調べ始める、とか。

**先輩:** ......。

**後輩:** 図星ですか。

**先輩:** ...いや、4要素集合上の自己写像のモノイドは256元で、そのうち7つが生成されるなら、生成される部分モノイドの構造は...

**後輩:** 先輩、それminor revisionの要求には含まれていません。

**先輩:** ......わかった。

**後輩:** 合成テーブル、MatchedTokenParser議論、Table 4注記、テスト環境、pythonian修正。この5つだけです。

**先輩:** 了解。

**後輩:** では始めましょう。

**先輩:** ああ。...ところで後輩。

**後輩:** はい？

**先輩:** ありがとう。

**後輩:** え？

**先輩:** v1の査読対応で、R3の批判をモナディック解釈セクションに変える戦略を提案したのはお前だ。あの戦略がなければ、R3はRejectのままだっただろう。

**後輩:** ...先輩がちゃんと技術的に正確なテーブルを書いたからです。私は翻訳しただけですよ。

**先輩:** ...まあ、チームワークだな。

**後輩:** はい。...ところで先輩、感謝の言葉を言うのは珍しいですね。

**先輩:** 論文が通ったんだ。少しくらい機嫌が良くてもいいだろ。

**後輩:** ...そうですね。

---

**先輩:** よし、改訂作業だ。まず合成テーブルから...

**後輩:** はい。

**先輩:** ...あ、その前に。

**後輩:** はい？

**先輩:** camera-ready の提出期限4週間って言ってたな。

**後輩:** はい。

**先輩:** 3.5時間の作業に4週間。つまり残り27日と20.5時間は...

**後輩:** 先輩、何を考えています？

**先輩:** ...v2の論文を拡張して、MatchedTokenParserの認識能力の形式的特徴づけを完全にやったら、それだけでもう1本書けるんじゃないか？

**後輩:** 先輩！ まずminor revisionを終わらせてください！

**先輩:** ......はい。

**後輩:** ...先輩が「はい」って言うの、初めて聞きました。

**先輩:** 論文が通った日くらい素直になってもいいだろ。

**後輩:** ...そうですね。おめでとうございます、先輩。

**先輩:** ああ。お前もだ。共著者だからな。

**後輩:** ...ありがとうございます。

---

*（静かにminor revision作業が始まる）*

---

---

## Appendix: 査読スコアの変遷

| 査読者 | v1 スコア | v2 スコア | 変化 | 主要因 |
|--------|----------|----------|------|--------|
| R1 | Weak Reject | Borderline Accept | +2 | 操作的意味論、代数的性質、Table 1拡張 |
| R2 | Weak Accept (borderline) | Accept | +1 | 月間10億tx、Spoofax/Xtext比較、回文ケーススタディ |
| R3 | Reject | Weak Accept | +3 | モナディック解釈セクション、対応テーブルの正確さ、「6成果物の統一生成」の反論 |

**決定: Accept with Minor Revisions**

---

## Appendix: 改訂のレッスン

### v1 -> v2 で効果的だった改訂戦略

1. **批判を取り込んで論文を強化する**: R3の「PropagationStopperはReader localの特殊化」という批判を、Section 3.7のモナディック解釈セクションとして論文に取り込んだ。批判を否定するのではなく、認めた上で「だから我々のフレームワークの価値はそこにはない」と位置づける戦略が有効だった。

2. **形式的厳密さの追加は投資効果が高い**: 操作的意味論の5つの推論規則とAppendix Cの代数的性質は、3日間の作業で追加されたが、R1のスコアをWeak RejectからBorderline Acceptに変えた。

3. **実運用の実績は最強の証拠**: 月間10億トランザクションの本番実績は、R2のスコアを即座にAcceptに変え、R3さえも「動くシステムの前では理論的優越性の主張は無力」と認めさせた。

4. **「novel」の主張は控えめに**: 「our contribution」「Among the frameworks we surveyed」への修正は、査読者の反感を軽減した。

5. **追加のケーススタディは必要**: 回文認識のケーススタディは小規模だが、tinyexpressionとは根本的に異なるタスクでフレームワークの能力を示した。N=1問題への部分的な回答として有効だった。

### minor revision で対応すべき残課題

1. Appendix Cの合成テーブルの完全化（5x5表）
2. MatchedTokenParserの認識能力の上界についてのDiscussion追記
3. Table 4のLOC計算の注記
4. テスト環境仕様の追記
5. pythonian APIの位置づけ修正と入力バリデーション

---

*この会話劇は、unlaxer-parser論文 v2 の査読対応プロセス（第2ラウンド）を記録したものである。*

*v1の査読（Weak Reject / Weak Accept / Reject）から、v2の査読（Borderline Accept / Accept / Weak Accept）への変化と、Accept with Minor Revisions の決定に至るPC議論を含む。*

*全ての査読コメントは架空のものであるが、技術的内容は実際の論文の改善点を反映している。*

---

## ナビゲーション

[← インデックスに戻る](../INDEX.md)

| 査読 | 対応する論文 |
|------|-------------|
| [← v1 査読](../v1/review-dialogue-v1.ja.md) | [v1 論文](../v1/from-grammar-to-ide.ja.md) |
| **v2 査読 — 現在** | [v2 論文](./from-grammar-to-ide.ja.md) |
| [v4 査読 →](../v4/review-dialogue-v4.ja.md) | [v4 論文](../v4/from-grammar-to-ide.ja.md) |
