# 査読会話劇: ["From Grammar to IDE"](./from-grammar-to-ide.ja.md) v1 査読プロセス

## 登場人物

- **R1** (理論家/圏論原理主義者): 形式意味論と圏論的構造を重視。証明のない主張は「単なるエンジニアリングレポート」と見なす。
- **R2** (実務家/産業界): ベンチマーク手法、スケーラビリティ、実運用での実績を重視。定量的根拠を求める。
- **R3** (関数型信者/Haskell派): モナディックパーサーコンビネータが全てを解決すると信じる。純粋性を宗教的に崇拝。
- **先輩** (著者/Creator): 論文を実用的な議論で防衛する。時に苛立つ。「お前のコードは動くんか？」
- **後輩** (共著者/Mediator): 先輩を落ち着かせる。「先輩、査読者に喧嘩売らないでください」。建設的な対応を提案。

---

## Round 1: 査読コメント

---

### R1 の査読 (Score: Weak Reject)

**Summary:**

本論文は、単一のUBNF文法仕様からパーサー、AST型定義、マッパー、評価器、LSPサーバー、DAPサーバーの6つの成果物を生成するJava 21フレームワーク「unlaxer-parser」を提示している。3つの貢献を主張しているが、いずれも形式的な裏付けが欠如している。

**Detailed Comments:**

(1) Section 3.3の「PropagationStopper」は本論文の核心的貢献とされているが、形式的な意味論が一切与えられていない。`TokenKind`と`invertMatch`の2次元伝搬を制御すると述べているが、この「2次元制御フロー」は何の圏における射なのか？ パーサー状態の圏を定義し、PropagationStopperをその圏間の関手（functor）として特徴づけるべきである。現状では「4つのクラスがある」としか述べておらず、これは設計ドキュメントであって学術論文ではない。

具体的に言えば、パーサーの状態空間を `S = TokenKind x Bool` とし、PropagationStopperを `S -> S` の写像として定義すべきである。AllPropagationStopperは定数関手 `const (consumed, false)`、DoConsumePropagationStopperは射影の部分的オーバーライド `(_, b) -> (consumed, b)` である。この構造は明らかに `S` 上のモノイド作用であり、PropagationStopper階層はこのモノイドの生成元として特徴づけられる。このレベルの形式化がなければ、「no equivalent in existing frameworks」という主張は検証不能である。

(2) 「novel contribution」の主張が過度に強い。Section 3.3末尾の「To our knowledge, no existing parser combinator framework provides this level of control over parsing mode propagation」は、形式的な対応関係の証明なしには受け入れられない。Parsecの`try`と`lookAhead`が「異なる次元に沿って合成しない」という主張は、具体的にどの意味で合成しないのか？ 圏論的には、Parsecのコンビネータはクライスリ圏における射の合成であり、PropagationStopperとの形式的比較が必要である。

(3) Section 3.4の`ContainerParser<T>`について、表示的意味論（denotational semantics）が必要である。「パースツリーがコミュニケーションチャネルとして機能する」という記述は直観的だが、これをside-effectful computationとしてどう形式化するのか？ `ContainerParser`は明らかにWriter monadの一種であるが、著者はこの対応関係に言及していない。

(4) Table 1の比較は表面的すぎる。「Yes/No」の二値比較ではなく、各ツールの生成能力の質的・量的比較が必要である。ANTLRの「Partial」がSpoofax [Erdweg et al. 2013]やMPS [Volter et al. 2006]との比較を欠いているのは看過できない。Language Workbenchの文献 [Erdweg et al. 2013] への言及があるにもかかわらず、SpoofaxやJetBrains MPSとの比較が全くないのは重大な欠落である。

**Questions for Authors:**

- PropagationStopperの結合性（associativity）と冪等性（idempotence）について、ネストした場合の振る舞いを形式的に特徴づけられるか？
- `ContainerParser<T>`の`T`に対する関手性（functoriality）は成立するか？ すなわち、`ContainerParser<A>` と `f: A -> B` から `ContainerParser<B>` を得ることは可能か？

**Recommendation: Weak Reject**

形式的な意味論の追加と、既存のパーサーコンビネータとの圏論的対応関係の明示がなければ、この論文はSLEの水準に達しない。

---

### R2 の査読 (Score: Weak Accept — borderline)

**Summary:**

単一文法からIDE統合を含む6つの成果物を生成するという実用的なアプローチは評価に値する。特に、Generation Gap Patternとsealed interfaceの組み合わせは実務的に有用である。しかし、評価の方法論に重大な問題がある。

**Detailed Comments:**

(1) ベンチマーク手法が疑問。Section 5.2の性能測定は`BackendSpeedComparisonTest`を用いているが、JMH（Java Microbenchmark Harness）を使用していない。JVMベンチマークにおいてJMHを使わないのは、2024年以降では受け入れがたい。warmup 5,000回、測定50,000回と述べているが、信頼区間（confidence interval）は？ 標準偏差は？ GCの影響の制御は？ JITコンパイルのtiered compilationによる影響は？ `-XX:+PrintCompilation`の結果は？ ベンチマーク結果の「~0.10 us/call」という近似値表記は、科学的な測定結果として不十分である。

具体的に求められるのは：
- JMH `@Benchmark`メソッドによる測定
- `@Warmup(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)`
- `@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)`
- `@Fork(3)` による独立JVMでの繰り返し
- 結果テーブルに平均、標準偏差、99パーセンタイルを含める
- GCログとJITコンパイルログの提示

(2) N=1のケーススタディでは一般化可能性の主張は成立しない。tinyexpressionは著者自身が開発した式言語であり、unlaxer-parserに最適化されている可能性がある。最低でも、以下のいずれかが必要：
- 第三者が開発したDSLへの適用事例
- 合成文法ベンチマーク（文法の複雑さを段階的に変化させた場合の生成コード品質と性能の測定）
- 他の言語ワークベンチ（Spoofax、Xtext、JetBrains MPS）との同一文法に対する定量比較

(3) 「10x effort reduction」の主張（Section 5.3）は自己申告に基づいている。Table 3の「Time estimate」列は「8 weeks」「5 weeks」「3 days」と記載されているが、これは実測値ではなく推定値である。制御実験（同一のDSLを異なるアプローチで実装し、工数を測定する）なしには、この主張は検証不能である。少なくとも、LOC比較は客観的指標であるが、LOCの質（保守性、テスト容易性）の議論が欠如している。

(4) エラー回復のベンチマークがない。Section 6.1で「error recovery」を制限事項として認めているが、実際のLSPサーバーにとってエラー回復は最も重要な機能の一つである。不完全な入力（ユーザーが入力中のコード）に対するパース成功率、エラー報告の精度、リカバリー後のパースツリー品質について定量的評価が必要。これなしに「LSP server」を生成していると主張するのは過大評価。

(5) Section 5.4「LLM-Assisted Development」は興味深いが、「10x reduction in token cost」「eliminates approximately 95% of debugging round-trips」はエビデンスがない。LLM支援開発のメリットを主張するなら、具体的な実験設計（タスク定義、被験者数、トークン使用量の測定）が必要。現状ではアネクドートに過ぎない。

**Strengths:**

- 6成果物の統一生成という問題設定は明確で実務的に重要
- Generation Gap Pattern + sealed interfaceの組み合わせは新規性がある
- Appendix AのTinyCalcの例は、フレームワークの使いやすさを効果的に示している
- 1,400x性能改善（reflection -> sealed switch）は印象的

**Questions for Authors:**

- JMHベンチマークを追加する意思はあるか？
- tinyexpression以外の文法で実験を行った経験はあるか？
- エラー回復の改善計画は具体的にあるか？

**Recommendation: Weak Accept (borderline)**

実用的な貢献は認めるが、評価の厳密さが不足している。ベンチマーク手法の改善と追加のケーススタディがあれば、Accept に引き上げる用意がある。

---

### R3 の査読 (Score: Reject)

**Summary:**

本論文の根本的な問題は、モナディックパーサーコンビネータが既に解決している問題を、Javaで車輪の再発明をしていることに著者が気づいていない点である。

**Detailed Comments:**

(1) 本論文の動機自体が誤りである。Section 1で「Parser combinator libraries such as Parsec offer compositional parser construction but stop at parsing」と述べているが、これはParsecの能力の過小評価である。Parsecはmonadicであるがゆえに、パース結果を任意の型に変換でき、パース中に意味情報を蓄積できる。HaskellのType Classを使えば、同一のパーサーから複数の解釈（evaluator, pretty-printer, type-checker）を導出できる。Swierstra [2009]のcombinator parsing tutorialが示すように、これは1990年代から知られていた技術である。

(2) PropagationStopperはReader monadの`local`関数そのものである。著者は「To our knowledge, no existing parser combinator framework provides this level of control」と述べているが、これは単に著者がHaskellのMTL（Monad Transformer Library）を知らないだけである。

具体的な対応関係を示す：

```haskell
type ParserEnv = (TokenKind, InvertMatch)
type Parser a = ReaderT ParserEnv (StateT ParseState (ExceptT ParseError Identity)) a

-- AllPropagationStopper = local (const (Consumed, False))
allStop :: Parser a -> Parser a
allStop = local (const (Consumed, False))

-- DoConsumePropagationStopper = local (\(_, inv) -> (Consumed, inv))
doConsume :: Parser a -> Parser a
doConsume = local (\(_, inv) -> (Consumed, inv))

-- InvertMatchPropagationStopper = local (\(tk, _) -> (tk, False))
stopInvert :: Parser a -> Parser a
stopInvert = local (\(tk, _) -> (tk, False))

-- NotPropagatableSource = local (\(tk, inv) -> (tk, not inv))
notProp :: Parser a -> Parser a
notProp = local (\(tk, inv) -> (tk, not inv))
```

これは`ReaderT`の`local`の4つの特殊化に過ぎない。「novel contribution」ではなく、「Haskellでは10行で書けることをJavaで200行かけて再発明した」というのが正確な記述である。

(3) `ContainerParser<T>`はWriter monadである。パースツリーにメタデータを「注入」するという説明は、Writer monadの`tell`操作と完全に一致する：

```haskell
type MetadataParser a = WriterT [Metadata] Parser a

errorMessage :: String -> MetadataParser ()
errorMessage msg = tell [ErrorMsg msg]

suggestCompletion :: [Suggest] -> MetadataParser ()
suggestCompletion suggests = tell [Suggestions suggests]
```

入力を消費せずにメタデータをログに追加する。これは関数型プログラミングの基本パターンであり、新規性は皆無である。

(4) `TagWrapper`はdecorated parser functorである。パーサーにタグを付与して結果を変換する操作は、関手（functor）の`fmap`に他ならない。著者はfunctor則（identity law, composition law）を検証していないので、正しく実装されているかさえ不明である。

(5) Generation Gap Patternについて、Haskellでは`DeriveFunctor`、`DeriveTraversable`、`GHC.Generics`による自動導出、Template Haskellによるコード生成、そしてsyb（Scrap Your Boilerplate）による汎用プログラミングがある。sealed interfaceによる網羅性検査は、Haskellのパターンマッチ網羅性検査（`-Wincomplete-patterns`）と同等である。Java 21がようやくHaskell 98に追いついたことを「novel contribution」と呼ぶのは知的誠実さを欠く。

(6) Section 3.3の末尾にある「Parsec handles lookahead through `try` and `lookAhead` combinators, but these do not compose along independent dimensions」は、端的に間違いである。Parsecの`try`はバックトラッキング境界を制御し、`lookAhead`はzero-width assertionを提供する。これらは独立して合成可能であり、monadの結合法則（associativity）により、任意のネスティングが正しく動作する。著者のフレームワークでは、PropagationStopperのネスティングの結合性すら示されていない。

(7) 全体的なアーキテクチャについて、モナドトランスフォーマースタック `ReaderT ParserEnv (WriterT [Metadata] (StateT ParseState (ExceptT ParseError Identity)))` は、本論文が提案する全ての機能を統一的に提供する。環境の伝搬制御（Reader）、メタデータの蓄積（Writer）、パーサー状態の管理（State）、失敗のハンドリング（Except）。これを「multiple interrelated artifacts that must remain consistent with each other」と呼ぶ必要はない。型クラスの coherence がそれを保証する。

**Questions for Authors:**

- PropagationStopperがReader monadの`local`と異なる点を具体的に示せるか？
- `ContainerParser<T>`がWriter monadの`tell`と異なる点を具体的に示せるか？
- モナドトランスフォーマースタックによる統一的な定式化を検討したか？

**Recommendation: Reject**

既知のモナディック抽象化の再発明を「novel contribution」として提示しており、関連研究の調査が不十分。MonadにNOVELはない。

---

## Round 2: 著者の議論

---

### 査読結果を読む先輩と後輩

**後輩:** 先輩、査読結果が返ってきました。3人中、Weak RejectとRejectとWeak Accept（borderline）です。

**先輩:** ......3人の平均取ったら落ちとるやないか。

**後輩:** はい、現状だとRejectです。でも、R2はborderlineなので、revision次第でAcceptに上がると言っています。

**先輩:** まずR2から見るか。R2はまともなこと言うとる。JMHベンチマークがない、N=1、10xの根拠が弱い。全部正しい。

**後輩:** ですね。ベンチマークの信頼区間がないのは確かに弱いです。

**先輩:** JMH足せばいいだけやろ。2日でできる。やる。

**後輩:** エラー回復のベンチマークも求められていますが...

**先輩:** あー...エラー回復は確かに弱い。PEGの構造的にパニックモードリカバリーが難しいのは事実で、それは制限事項として書いてある。でも「LSPサーバーを生成している」と主張しておいて、不完全な入力でのパース成功率を示さないのは...確かにフェアじゃないな。

**後輩:** 不完全な入力に対する定量評価、追加しましょうか。tinyexpressionの式を途中で切って、パースエラーの位置精度を測定するとか。

**先輩:** うん、それはやるべきだ。具体的には、tinyexpressionの主要な式パターン50個を準備して、各式を末尾1トークン、2トークン、3トークン削除した不完全入力を作る。計150テストケース。各ケースについて、(a) パースがどの位置で失敗するか、(b) ErrorMessageParserが報告するメッセージが適切か、(c) 失敗位置は本来の削除位置と何トークンずれているか、を計測する。

**後輩:** それだけあれば統計的にも意味のある評価になりますね。

**先輩:** あとR2はLLMの話にもツッコんでるな。「95%のデバッグラウンドトリップを排除」に根拠がないと。

**後輩:** それは...正直、我々の体感値ですからね。

**先輩:** 体感値を論文に書いたのは反省する。削除するか、控えめな表現に直す。

---

### R1 への対応

**後輩:** 次、R1です。圏論的な定式化を求めています。

**先輩:** 読んだ。PropagationStopperを関手として定義しろ、パーサー状態の圏を定義しろ、表示的意味論を書け...

**後輩:** 先輩、実際のところ、R1の指摘で一番重要なのはどれだと思います？

**先輩:** 操作的意味論（operational semantics）は書くべきだと思う。PropagationStopperの4つのクラスのセマンティクスは、小さなステップの推論規則で書ける。それは正当な要求だ。

**後輩:** R1はTable 1の比較がSpoofaxやMPSを含んでいないとも指摘しています。

**先輩:** これは痛い。SpoofaxとMPS、あとXtextは確かに比較すべきだった。特にSpoofaxは[Erdweg et al. 2013]を参照しているのにTable 1に入っていないのはおかしい。追加する。

**後輩:** あと、R1の最後の質問...「ContainerParser<T>の関手性は成立するか」。

**先輩:** ContainerParser<T>はTに対して共変ではあるが、map操作を明示的に提供していない。つまり、関手として設計していない。

**後輩:** それは正直に書けばいいのでは？ 現状の実装はfmap操作を提供していないが、原理的には可能であり、将来の拡張として検討する、と。

**先輩:** そうする。ただし圏論で全体を書き直すのは断る。操作的意味論までは書くが、圏論的意味論は今回のスコープ外だ。SLEはプログラミング言語理論の会議じゃなくてソフトウェア言語工学の会議だからな。

**後輩:** まあ、R1は理論家ですから、その主張は通ると思いますが...「お前のコードは動くんか？」は返答に書かないでくださいね。

**先輩:** ......。

**後輩:** あと、R1が挙げた具体的な形式化の提案、つまりパーサー状態空間を `S = TokenKind x Bool` として定義する、というのは実際にやる価値があると思います。

**先輩:** それは操作的意味論の一部としてやる。推論規則の形式は以下のようになる：

```
                    s = (tokenKind, invertMatch)
  ─────────────────────────────────────────────────── [AllStop]
  AllPropagationStopper(p).parse(ctx, s)
    = p.parse(ctx, (consumed, false))
```

**後輩:** 読みやすいですね。4つのStopperと、デフォルト伝搬の5つの規則ですべてカバーできます。

**先輩:** そうだ。あとはTransactionの意味論も書くべきか？ `begin`, `commit`, `rollback`の3つの操作。

**後輩:** R1はそこまで求めていませんが...書いておけば論文の形式的厳密さは上がります。

**先輩:** Transaction意味論は時間があればAppendixに入れる。優先度は低い。

**後輩:** あと、R1がSpoofaxとの比較を求めている件。Spoofaxの能力を正確に把握しています？

**先輩:** SpoofaxはSDF3で文法を定義し、StrategoでAST変換を記述し、ESVでエディターサポートを定義する。パーサーはSGLR（Scannerless GLR）で、PEGではなくGLR。曖昧な文法を扱えるのはSpoofaxの強みだが、我々のPEGベースのアプローチとは根本的に異なる。

**後輩:** LSPサポートは？

**先輩:** Spoofax 3（2023年時点）はLSP対応を進めているが、完全ではない。特にDAPサポートは我々の知る限り提供されていない。MPSはプロジェクショナルエディターなのでそもそもテキストベースのLSPとは異なるパラダイム。Xtextは最もLSPに近いが、DAPは手動実装が必要。

**後輩:** それを表にまとめれば、我々のフレームワークの位置づけが明確になりますね。

---

### R3 への対応

**先輩:** さて、R3。

**後輩:** 先輩、深呼吸してから読んでください。

**先輩:** ......読んだ。

**後輩:** ......先輩？

**先輩:** 5段重ねのモナドトランスフォーマースタックを「統一的」「エレガント」と言うのは宗教だよ。

**後輩:** 先輩、査読者に喧嘩売らないでください。

**先輩:** いや、聞いてくれ。R3は `ReaderT ParserEnv (WriterT [Metadata] (StateT ParseState (ExceptT ParseError Identity)))` が全てを解決すると言っている。型を見ろ。この型シグネチャだけで150文字ある。これが「エレガント」か？

**後輩:** でも、R3の指摘で重要な点があります。PropagationStopperがReader monadの`local`の特殊化であるという対応関係は、技術的に正しいです。

**先輩:** ......。

**後輩:** 先輩？

**先輩:** ぐっ...。

**後輩:** 認めましょう。R3は正しい。PropagationStopperの4つのクラスは、`local`の4つのインスタンス化と正確に対応しています。

**先輩:** 対応しているのは認める。しかし、「reader monadのlocalの4つの特殊化に過ぎない」という表現は不公平だ。我々の貢献は、パーサーコンビネータにおいて`TokenKind`と`invertMatch`という2つの独立した次元を明示的に識別し、それぞれの伝搬を独立に制御できるAPIを設計したことにある。Parsecでは`try`と`lookAhead`はあるが、この2次元の独立制御を明示的に提供するAPIは存在しない。

**後輩:** そこですよ。R3への反論ポイントは「Haskellでは10行で書ける」に対して「ではなぜ誰もやっていないのか」です。

**先輩:** 正確に言えば、「なぜ既存のHaskellパーサーコンビネータライブラリはこの2次元制御を明示的なAPIとして提供していないのか」だ。megaparsecにもattoparsecにもこのAPIはない。

**後輩:** それを反論に書きましょう。「PropagationStopperはReader monadのlocalと対応する」ことを認めた上で、「しかし、この特定のモナディック構造がパーサーコンビネータにおいて有用であることを認識し、明示的なAPIとして設計・実装したことが我々の貢献である」と。

**先輩:** ...うん、それは書ける。それにContainerParserがWriter monadのtellであるという指摘も認めるべきだな。

**後輩:** はい。むしろ、R3の指摘を活かして「Monadic Interpretation」セクションを追加したら、論文が強くなりますよ。

**先輩:** どういう意味だ？

**後輩:** Section 3に「3.6 Monadic Interpretation of unlaxer Abstractions」を追加するんです。PropagationStopperがReader monadのlocalに対応し、ContainerParserがWriter monadのtellに対応することを明示的に述べる。その上で、「我々のフレームワークの価値は、これらの抽象化をJava 21の型システムで実現し、コード生成パイプラインと統合したことにある」と位置づける。

**先輩:** つまり、R3の批判を取り込んで論文を強化する、と。

**後輩:** はい。R3が「著者はMonadを知らない」と思っているなら、「知っている上でJavaで設計した」ことを示すのが最善の反論です。

**先輩:** ......悔しいが、それは正しい戦略だ。

**後輩:** 具体的には、以下のような対応テーブルをSection 3.6に入れましょう：

```
| unlaxer概念                  | モナディック対応              | 説明                           |
|------------------------------|------------------------------|-------------------------------|
| PropagationStopper           | Reader monadの local         | 環境パラメータの局所的変更      |
| AllPropagationStopper        | local (const (C,F))         | 定数環境への置換               |
| DoConsumePropagationStopper  | local (\(_,i)->(C,i))       | 第1成分のみ固定                |
| InvertMatchPropagationStopper| local (\(t,_)->(t,F))       | 第2成分のみ固定                |
| NotPropagatableSource        | local (\(t,i)->(t,not i))   | 第2成分の反転                  |
| ContainerParser<T>           | Writer monadの tell          | 副作用なしにメタデータを蓄積   |
| ErrorMessageParser           | tell [ErrorMsg msg]          | エラーメッセージの蓄積         |
| SuggestsCollectorParser      | tell [Suggestions xs]        | 補完候補の蓄積                 |
| ParseContext.begin/commit    | State monadの get/put        | パーサー状態の保存・復元        |
| Parsed.FAILED                | ExceptT の throwError        | パース失敗の伝搬               |
```

**先輩:** ......よくまとまっている。

**後輩:** このテーブルがあれば、R3の「著者はモナドを知らない」という批判は完全に無効化されます。知っていることを示した上で、Javaでの設計判断を説明する。

**先輩:** だが、この対応テーブルを書いた瞬間に、R3は「ほら、全部既知じゃないか」と言うぞ。

**後輩:** そこで重要なのは、テーブルの後に「しかし、この対応関係の認識だけでは、文法から6つの成果物を統一生成するフレームワークは実現しない」と明記することです。モナディック構造は個々のコンポーネントの設計を説明するが、コード生成パイプライン、UBNF文法言語の設計、Generation Gap Patternとsealed interfaceの統合、LSP/DAPサーバーの自動生成は、モナドの知識からは導出されない。

**先輩:** そうだ。モナドは「how to parse」を説明するが、「how to generate all six artifacts from a single grammar」は説明しない。

---

### R3 への具体的反論の検討

**先輩:** ただし、R3の(6)は間違っている。

**後輩:** Parsecの`try`と`lookAhead`が独立に合成可能だという主張ですか？

**先輩:** そうだ。Parsecの`try`はバックトラック境界を制御する。`lookAhead`はzero-width assertionを提供する。これらは確かに独立に合成可能だが、我々が言っている「2次元」とは違う。我々の2次元は`TokenKind`（消費モード）と`invertMatch`（反転フラグ）であり、Parsecの`try`/`lookAhead`は消費/非消費と成功/失敗のペアを制御する。似ているが同一ではない。

**後輩:** その微妙な違いを明確に記述する必要がありますね。「Parsecの`try`は committed/uncommitted choiceを制御し、`lookAhead`は消費/非消費を制御する。我々のPropagationStopperは、TokenKind（consumed/matchOnly）とinvertMatch（normal/inverted）を独立に制御する。この2つの制御空間は類似しているが同型ではなく、特にinvertMatchの伝搬制御はParsecの標準コンビネータには直接対応するものがない」と。

**先輩:** よし、そう書く。ただし、Reader monadのlocalとして定式化できることは認める。形式的には同じ構造だ。APIとしての設計と、それがパーサーコンビネータ固有の問題をどう解決するかが我々の貢献だ。

---

### DoConsume のスペルについて

**後輩:** あ、先輩、一つ。

**先輩:** 何だ。

**後輩:** 圏論の話の前に、もっと基本的な問題が...

**先輩:** ...何だ。

**後輩:** `DoConsumePropagationStopper`のクラス名なんですが。

**先輩:** ...ちゃんと`DoConsume`だが？

**後輩:** ソースコードではそうなんですが、以前`DoCounsume`というtypoがありませんでしたっけ。

**先輩:** ぐっ...それは直した...今日...。

**後輩:** 圏論の前にスペルチェックでは...。

**先輩:** うるさい。次行くぞ。

---

### R3 の「Why not Haskell?」について

**後輩:** R3の根本的な主張は「なぜHaskellで書かないのか」ですよね。

**先輩:** ああ。これは答えなきゃいけない。

**後輩:** どう答えます？

**先輩:** まず、我々のターゲットユーザーはJava開発者だ。Haskellで書いたパーサーコンビネータフレームワークは、Haskellプログラマーにしか使えない。Javaプログラマーは世界に900万人いる。Haskellプログラマーは...

**後輩:** ...先輩、そのマウントの取り方は査読で使えません。

**先輩:** わかっている。真面目に答えると、3つある。第一に、Java 21のsealed interfaceとrecordがHaskellのADT（代数的データ型）と実質的に同等の表現力を持つことを示す。これは形式的に示せる。sealed interfaceのpermitsリストが直和型に対応し、recordが直積型に対応する。exhaustive switch expressionがパターンマッチの網羅性検査に対応する。

**後輩:** それは論文にも書いてありますね。

**先輩:** 第二に、LSP/DAP生成はHaskellエコシステムには存在しない。Haskell Language Server（HLS）はHaskell自体のためのLSPサーバーであり、ユーザー定義DSLのためのLSPサーバーを文法から生成する機能はない。我々のフレームワークはここが決定的に異なる。

**後輩:** それは強い反論ですね。

**先輩:** 第三に、JVMエコシステムとの統合。Maven/Gradleビルドシステム、IntelliJ/Eclipse IDE、Spring Boot、etc.。Haskellのcabalやstackは優れたビルドツールだが、エンタープライズJava環境での採用障壁は高い。我々のフレームワークは`mvn generate-sources`で動く。

**後輩:** でもR3は「なぜJavaの制約の中でモナドを再発明するのか」と聞いているんです。

**先輩:** それに対しては「我々はモナドを再発明したのではなく、モナディック抽象化をJavaのイディオムで実現した」と答える。HaskellのWriter monadの`tell`をJavaの`ContainerParser`として、Reader monadの`local`をJavaの`PropagationStopper`として、それぞれのホスト言語に適した形で実装した。これはdesign decisionであり、知識の欠如ではない。

**後輩:** ...それ、本当ですか？ 最初から`local`と`tell`を意識して設計したんですか？

**先輩:** ......結果的にそうなったんだよ。

**後輩:** 先輩...。

**先輩:** いいだろ！ 結果的に正しい構造に到達したなら、それは設計が正しかったということだ。数学的構造は発見されるものであって発明されるものではない。

**後輩:** ...その弁明はR3には通じないと思いますが、revision noteには「モナディック対応関係を明示的にセクションに追加した」と書けば十分です。

**先輩:** そうする。

---

### ベンチマークの再考

**後輩:** R2に戻りましょう。ベンチマークの問題は深刻です。

**先輩:** JMHを使う。これは異論ない。`@Benchmark`、`@Warmup`、`@Measurement`、`@Fork(3)`、全部やる。

**後輩:** 現在の結果と大きく変わる可能性は？

**先輩:** 1,400x改善（reflection -> sealed switch）は、JMHで測っても同程度の結果が出ると確信している。反射APIのオーバーヘッドはJMHでも消えない。ただし、2.8x（sealed switch vs. JIT compiled code）の数値は、JMHの方が厳密な条件で測るから、多少変動する可能性はある。

**後輩:** 変動しても、orderは変わらないと。

**先輩:** そうだ。sealed switchがJIT compiledの3倍以内、reflectionが1000倍以上遅い、この結論は変わらない。

**後輩:** あと、R2はエラー回復のベンチマークも求めています。不完全な入力に対するパース成功率ですね。

**先輩:** うーん...これは厳しい。PEGベースのパーサーでエラー回復は本質的に難しい。ANTLRのようなトークン挿入/削除による回復戦略はPEGには馴染まない。

**後輩:** でも、`ErrorMessageParser`があるじゃないですか。少なくとも「どこでパースが失敗したか」の報告精度は測定できます。

**先輩:** パース失敗位置の精度は高いと思う。PEGのordered choiceで最も深くマッチした位置を報告するので、「どの代替が最も近かったか」は分かる。でもANTLRの「トークンを挿入して続行」のような回復はできない。

**後輩:** であれば、正直に：(a) パース失敗位置の精度測定を追加、(b) エラー回復は将来課題として明記、(c) 現状のLSP機能は「完全なパースが成功した場合の補完と診断」に限定される、と書きましょう。

**先輩:** うん、それが誠実だ。

---

### N=1 問題

**後輩:** もう一つ、R2のN=1問題。tinyexpression以外のケーススタディがないという指摘。

**先輩:** これは認めざるを得ない。ただし、短期間でもう一つのケーススタディを追加するのは現実的ではない。

**後輩:** 代替案として、合成文法ベンチマーク（synthetic grammar benchmark）はどうですか？ 文法の複雑さを段階的に変化させた合成文法（5ルール、10ルール、20ルール、50ルール、100ルール）を作成し、各段階での生成コード量、パース性能、生成時間を測定する。

**先輩:** それは面白い。文法サイズに対するスケーラビリティを示せる。tinyexpressionの520行文法は「中規模」に位置づけられる。

**後輩:** さらに、AppendixにTinyCalcの例があるので、これを「小規模文法」のデータポイントとして使えます。合成文法で「大規模」のデータポイントを追加すれば、3点のスケーラビリティ曲線が描けます。

**先輩:** よし、合成文法ベンチマークを追加する。100ルール規模の文法で生成が破綻しないことを示す。

**後輩:** 合成文法のデザインについてもう少し議論しましょう。単に「ルール数を増やす」だけだと、文法の構造的複雑さ（再帰の深さ、選択肢の数、@mappingの多さ）が反映されません。

**先輩:** そうだな。3つの軸で変化させよう。(a) ルール数（5, 10, 20, 50, 100）、(b) 再帰の深さ（直接再帰のみ, 相互再帰2段, 相互再帰4段）、(c) @mappingアノテーションの密度（全ルールに@mapping, 50%に@mapping, 20%に@mapping）。

**後輩:** それだと組み合わせが多くなりますが...5 x 3 x 3 = 45パターン。

**先輩:** 主要な組み合わせだけでいい。全組み合わせは不要。ルール数5つの文法で再帰4段は不自然だし。10パターン程度に絞る。

**後輩:** 了解です。

**先輩:** それとR2は、Spoofax、Xtext、JetBrains MPSとの定量比較も求めていますが...

**先輩:** 同一文法に対する定量比較は、各ツールの学習コストだけで1ヶ月かかる。revisionの期間では無理だ。

**後輩:** では、qualitative comparison（定性的比較）に留めましょう。Table 1を拡張して、Spoofax、Xtext、MPSを含め、各ツールの「Parser / AST / Mapper / Evaluator / LSP / DAP」のサポート状況を記載する。定量比較は将来課題として明記する。

**先輩:** そうする。SpoofaxはSDF3 + Stratego + ESVで、パーサーとAST型とエディターサポートを生成する。LSPは部分的にサポート。DAPは...たぶんない。MPSはプロジェクショナルエディターなのでLSPとは異なるパラダイム。XtextはEMFベースでLSPを生成するが、DAPは手動。

**後輩:** その知識があるなら、Table 1を正確に拡張できますね。

**先輩:** ああ、やる。

---

### 「novel」の主張について

**後輩:** 全査読者に共通する指摘として、「novel contribution」の主張が強すぎる、というのがあります。

**先輩:** ......具体的にどう弱めればいい？

**後輩:** 提案です。「novel」を「our contribution」に変更する。「To our knowledge, no existing framework」を「Among the parser combinator frameworks we surveyed」に変更する。「no equivalent in existing frameworks」を「this specific combination of controls is not provided as a first-class API in existing frameworks」に変更する。

**先輩:** つまり、「世界初」から「我々の調査範囲では新しい」に後退する、と。

**後輩:** 後退ではなく、正確化です。R3が示したように、数学的構造としては既知です。APIとしての設計が我々の貢献であることを明確にすれば、正当な主張になります。

**先輩:** ......わかった。

---

### R1 の関手性の質問への回答

**後輩:** あ、R1の質問に戻りますが、「PropagationStopperのネストした場合の結合性と冪等性」について。

**先輩:** 結合性は成立する。PropagationStopperは `S -> S` の写像で、写像の合成は結合的だ。冪等性は...

**後輩:** AllPropagationStopperは冪等ですね。2回適用しても結果は同じ。

**先輩:** そうだ。AllPropagationStopper, DoConsumePropagationStopper, InvertMatchPropagationStopperは全て冪等。NotPropagatableSourceは非冪等で、2回適用すると元に戻る。つまり自己逆（involution）だ。

**後輩:** それ、論文に書いたら面白くないですか？ PropagationStopper階層の代数的性質として。

**先輩:** ......R1を喜ばせるために書くのは癪だが、論文の質が上がるのは確かだ。

**後輩:** 先輩、査読プロセスとはそういうものです。

**先輩:** 言われなくても分かっとる。

---

### 代数的性質の検討

**先輩:** せっかくだから整理するか。PropagationStopperを`S = {consumed, matchOnly} x {true, false}`上の写像として考える。

**後輩:** はい。各StopperをS -> Sの写像として列挙すると：

```
All:       (tk, inv) -> (consumed, false)     -- 定数写像
DoCons:    (tk, inv) -> (consumed, inv)        -- 第1成分を固定
StopInv:   (tk, inv) -> (tk, false)            -- 第2成分を固定
NotProp:   (tk, inv) -> (tk, !inv)             -- 第2成分を反転
Identity:  (tk, inv) -> (tk, inv)              -- 恒等写像（Stopper無し）
```

**先輩:** 合成テーブルを書くと...

```
DoCons . StopInv = All          -- (tk,inv) -> (tk,false) -> (consumed,false)
StopInv . DoCons = All          -- (tk,inv) -> (consumed,inv) -> (consumed,false)
DoCons . NotProp = DoCons'      -- (tk,inv) -> (tk,!inv) -> (consumed,!inv)
NotProp . NotProp = Identity    -- 自己逆
All . X = All (任意のXに対して)  -- All は右零元
```

**後輩:** あ、面白い。`DoCons . StopInv = StopInv . DoCons = All` なので、DoConsとStopInvは可換ですね。

**先輩:** いや、正確には「DoConsとStopInvの合成は可換」だ。一般には可換ではない。`DoCons . NotProp ≠ NotProp . DoCons` だ。

**後輩:** なるほど。`DoCons . NotProp = (tk,inv) -> (consumed, !inv)` で、`NotProp . DoCons = (tk,inv) -> (consumed, !inv)`...あれ、これも同じですね。

**先輩:** ......本当か？ 計算してみろ。`NotProp . DoCons`: まずDoCons適用で`(consumed, inv)`、次にNotProp適用で`(consumed, !inv)`。`DoCons . NotProp`: まずNotProp適用で`(tk, !inv)`、次にDoCons適用で`(consumed, !inv)`。...同じだ。

**後輩:** じゃあ、4つのStopperは全て可換ですか？

**先輩:** 待て。StopInvとNotPropは？ `StopInv . NotProp = (tk,inv) -> (tk,!inv) -> (tk,false)` = StopInv。`NotProp . StopInv = (tk,inv) -> (tk,false) -> (tk,true)` ...いや、`NotProp`は`(tk,inv) -> (tk, !inv)`だから、`StopInv`の結果`(tk, false)`に対して`NotProp`を適用すると`(tk, !false)` = `(tk, true)`。つまり`(tk,inv) -> (tk, true)`になる。

**後輩:** これは新しいStopperですね。第2成分を常にtrueにする。

**先輩:** つまり`StopInv . NotProp ≠ NotProp . StopInv`だ。非可換だ。

**後輩:** 面白い！ これは論文に入れる価値がありますよ。PropagationStopper階層が非可換モノイドを形成するという結果は。

**先輩:** R1はこういうの好きだろうな...。

**後輩:** 間違いなく。

**先輩:** ......よし、Appendixに代数的性質をまとめる。合成テーブル、冪等性、可換性、自己逆性。これでR1の「形式的な特徴づけ」の要求に部分的に応える。

---

### 先輩の本音

**先輩:** しかしなあ。

**後輩:** はい？

**先輩:** R3の「Haskellでは10行で書ける」は、まあ正しい。Reader monadの`local`で4つの特殊化を書けば確かに同等のことは10行でできる。

**後輩:** はい。

**先輩:** でもな。その10行を書いた後に、そのパーサーからAST型を生成して、マッパーを生成して、LSPサーバーを生成して、DAPサーバーを生成して、sealed interfaceの網羅性検査で型安全な評価器スケルトンを生成して、Generation Gap Patternで再生成可能にして、全部が`@mapping`アノテーション一つで連動するようにするのに、Haskellで何行かかるんだ？

**後輩:** ......。

**先輩:** お前のコードは動くんか？ 「elegantに合成できます」って言うのは簡単だ。でもLSPサーバーが動いて、DAPサーバーが動いて、VS Codeで補完が出て、ステップ実行ができて、ブレークポイントが効いて、文法を変更したら全部が再生成されて、コンパイルエラーが未実装の評価メソッドを教えてくれる...そこまで動くHaskellのフレームワークを見せてくれよ。

**後輩:** 先輩、気持ちは分かりますが、その口調は査読返答には使えません。

**先輩:** 分かっている。

**後輩:** でも、技術的には正しい反論です。丁寧な言葉で書きましょう。「我々の主な貢献はモナディック抽象化そのものではなく、これらの抽象化を統一的なコード生成パイプラインに統合し、6つの成果物の一貫性を保証するエンドツーエンドのフレームワークを実現したことにある。既存のモナディックパーサーコンビネータフレームワークは、パーサー構築においては優れた合成性を提供するが、AST型生成、マッパー生成、評価器スケルトン生成、LSPサーバー生成、DAPサーバー生成を文法から自動的に導出するパイプラインは提供していない。」

**先輩:** ......それでいい。

---

## Round 3: 改訂計画

---

**後輩:** 改訂計画を整理しましょう。優先度順に。

### 改訂項目一覧

**1. 操作的意味論の追加 (R1対応)**

- PropagationStopperの4クラスに対する小ステップ操作的意味論を記述
- 推論規則の形式で、`(TokenKind, invertMatch)` ペアの伝搬を定義
- Appendixに代数的性質（合成テーブル、冪等性、自己逆性、非可換性）を追加

**先輩:** 3日。推論規則は5つ（4つのStopper + デフォルト伝搬）。

**後輩:** 代数的性質のAppendixも含めて3日で？

**先輩:** 合成テーブルはさっき計算したから、あとはLaTeXで書くだけだ。

---

**2. モナディック解釈セクションの追加 (R3対応)**

- Section 3.6「Monadic Interpretation of unlaxer Abstractions」を新設
- PropagationStopper = Reader monadの`local`の特殊化であることを明示
- ContainerParser = Writer monadの`tell`操作であることを明示
- その上で、「API設計とコード生成パイプラインへの統合が我々の貢献」と位置づける
- Parsecとの形式的対応関係を記述（`try` vs. `TokenKind`, `lookAhead` vs. PropagationStopper）

**後輩:** これ、R3を説得できる確率はどのくらいですか？

**先輩:** 50/50だ。R3がモナド原理主義を貫くなら、Javaで何を書いてもRejectだろう。でも、モナディック対応関係を明示的に認めた上で「それでもJava 21で6成果物を統一生成するフレームワークには価値がある」と主張すれば、少なくとも「著者はモナドを知らない」という批判は消える。

**後輩:** それで十分です。R3がRejectを維持しても、R1とR2がAcceptに回れば通ります。

**先輩:** 2日。Haskellのコード例をちゃんと書く。型が合っているか確認するためにGHCでコンパイルまでやる。

**後輩:** え、先輩Haskell書けるんですか？

**先輩:** ......学生時代に少し。

**後輩:** 少し？

**先輩:** ...Applicativeまでは分かる。Monad Transformerは...頑張ればいける。

**後輩:** R3に型エラーのあるHaskellコードを見せたら炎上しますからね。GHCiで確認してくださいよ。

**先輩:** 分かってる！ `stack ghci`でチェックする。...stackまだ入ってたかな。

**後輩:** 入ってなかったらinstallしてください。今回ばかりはHaskellから逃げられません。

**先輩:** ぐぬぬ...。

---

**3. JMHベンチマークの追加 (R2対応)**

- 既存のBackendSpeedComparisonTestをJMH化
- `@Benchmark`, `@Warmup(iterations=10)`, `@Measurement(iterations=10)`, `@Fork(3)`
- 結果テーブルに平均、標準偏差、99パーセンタイルを含める
- GCログとJITコンパイルログの解析結果をAppendixに追加

**先輩:** 2日。JMHのセットアップは既にやったことがある。

**後輩:** 結果が現在の値と大きく異なる場合は？

**先輩:** orderが変わることはない。数値が変わったらrevisionで正直に報告する。

---

**4. 合成文法ベンチマーク (R2対応)**

- 5, 10, 20, 50, 100ルール規模の合成文法を作成
- 各段階で：生成コード行数、パース性能、生成時間を測定
- スケーラビリティ曲線をプロット
- TinyCalc（5ルール）とtinyexpression（~50ルール）を実データポイントとして含める

**先輩:** 3日。合成文法のテンプレートから自動生成するスクリプトを書く。

---

**5. Table 1の拡張 (R1/R2対応)**

- Spoofax (SDF3 + Stratego + ESV) を追加
- Xtext (EMF + LSP) を追加
- JetBrains MPS (Projectional) を追加
- 各ツールのParser / AST / Mapper / Evaluator / LSP / DAP対応状況を記載
- 「Partial」の場合は脚注で何が手動かを説明

**先輩:** 1日。文献確認して表を更新するだけ。

---

**6. エラー回復の定量評価 (R2対応)**

- tinyexpressionの式を途中で切断した入力に対する：
  - パース失敗位置の精度（正しい位置 ± Nトークン）
  - ErrorMessageParserによるエラーメッセージの適切性
- ANTLRのエラー回復戦略との定性的比較
- 将来課題としてPEGにおけるエラー回復戦略の研究方向を示す

**後輩:** これは新しい実験が必要ですね。

**先輩:** 2日。テストケースの作成と結果分析。

---

**7. 「novel」表現の修正 (全査読者対応)**

- 「novel contribution」を「our contribution」に変更
- 「To our knowledge, no existing framework」を「Among the parser combinator frameworks we surveyed」に変更
- 「no equivalent」を「this specific combination is not provided as a first-class API」に変更

**先輩:** 30分。grep & replace。

---

**8. LLM-Assisted Development セクションの修正 (R2対応)**

- 「10x reduction in token cost」「eliminates approximately 95% of debugging round-trips」の定量的主張を削除
- 「our experience suggests」レベルの定性的記述に留める
- または、LLM実験の設計を追加（タスク定義、トークン使用量の実測値）

**先輩:** 削除する方が早い。具体的な数値を出すなら実験設計が必要で、それは別論文のテーマだ。

**後輩:** であれば、「定性的な観察として」と前置きして、具体的な数値は削除しましょう。

**先輩:** そうする。30分。

---

### 工数見積もり

**後輩:** 合計すると...

| 項目 | 工数 |
|------|------|
| 操作的意味論 | 3日 |
| モナディック解釈セクション | 2日 |
| JMHベンチマーク | 2日 |
| 合成文法ベンチマーク | 3日 |
| Table 1拡張 | 1日 |
| エラー回復評価 | 2日 |
| 表現修正 | 0.5日 |
| LLMセクション修正 | 0.5日 |
| **合計** | **14日** |

**先輩:** 2週間か。revisionの期限は？

**後輩:** 通常4-6週間です。余裕はあります。

**先輩:** よし。並行してやれるものもある。JMHとTable 1拡張は独立だし、合成文法ベンチマークも並行できる。実質10日で終わるだろう。

---

## Round 4: 改訂しないもの

---

**後輩:** 最後に、「やらないこと」を明確にしましょう。

### やらないこと 1: 圏論的意味論

**先輩:** 圏論で書き直すのは断る。操作的意味論までは書くけど、表示的意味論や圏論的モデルは今回のスコープ外だ。

**後輩:** R1は「パーサー状態の圏を定義し、PropagationStopperを関手として特徴づけよ」と言っていますが。

**先輩:** 関手じゃなくて写像だと何度言えば。S -> Sの自己写像であって、圏間の関手ではない。R1は何でも圏論にしたがるが、この場合は集合と写像で十分記述できる。圏論が必要な複雑さではない。

**後輩:** 反論として「操作的意味論で十分に形式化できるため、圏論的モデルは過剰な抽象化であり、本論文の対象読者（ソフトウェア言語工学の研究者と実務者）にとって有益ではないと判断した」と書きますか。

**先輩:** ああ、そう書く。ただしR1の面子を潰さないように「将来の理論的発展として、PropagationStopper階層の圏論的定式化は興味深い方向である」と付記する。

**後輩:** 大人の対応ですね。

**先輩:** 何年もやっとるんだよ、査読対応は。

---

### やらないこと 2: Haskellでの再実装

**後輩:** R3の「Why not Haskell?」にはどう答えます？

**先輩:** Java 21のsealed interfaceがHaskellのADTと同等であることを示す。そしてLSP/DAP生成はHaskellエコシステムにない。以上。

**後輩:** ...それで通りますかね。

**先輩:** 通らなかったらそこまでの会議だよ。

**後輩:** ちょっと待ってください。もう少し具体的に「HaskellのADTとの同等性」を示しましょう。

**先輩:** いいぞ。

**後輩:** Haskellでは：

```haskell
data Expr
  = BinaryExpr { left :: Expr, op :: String, right :: Expr }
  | IfExpr { cond :: Expr, thenBranch :: Expr, elseBranch :: Expr }
  | LiteralExpr { value :: Double }
```

Java 21では：

```java
public sealed interface Expr permits BinaryExpr, IfExpr, LiteralExpr {
    record BinaryExpr(Expr left, String op, Expr right) implements Expr {}
    record IfExpr(Expr cond, Expr thenBranch, Expr elseBranch) implements Expr {}
    record LiteralExpr(double value) implements Expr {}
}
```

**先輩:** パターンマッチも同等だ。

```haskell
eval :: Expr -> Double
eval (BinaryExpr l "+" r) = eval l + eval r
eval (IfExpr c t e) = if eval c /= 0 then eval t else eval e
eval (LiteralExpr v) = v
```

```java
Double eval(Expr expr) {
    return switch (expr) {
        case BinaryExpr(var l, "+", var r) -> eval(l) + eval(r);
        case IfExpr(var c, var t, var e) -> eval(c) != 0 ? eval(t) : eval(e);
        case LiteralExpr(var v) -> v;
    };
}
```

**後輩:** 網羅性検査もHaskellの`-Wincomplete-patterns`とJavaのsealed exhaustive switchで同等ですね。

**先輩:** そうだ。Java 21は型システムの表現力においてHaskell 98に追いついた。高カインド多相型やtype classはないが、ADTとパターンマッチに関しては同等だ。

**後輩:** R3は「Java 21がようやくHaskell 98に追いついたことをnovel contributionと呼ぶのは知的誠実さを欠く」と言っていますが...

**先輩:** 我々はsealed interface自体をnovel contributionとは呼んでいない。sealed interfaceをGeneration Gap Patternと組み合わせて、文法変更時にコンパイルエラーで未実装メソッドを検出する仕組みが貢献だ。Haskellにはこの「文法からの再生成 + 網羅性検査による変更検出」のパイプラインがない。

**後輩:** 正確ですね。Template Haskellで似たことはできるかもしれませんが、LSP/DAPまで含めた統一パイプラインは存在しない。

**後輩:** もう少し丁寧に書きませんか？ 「本フレームワークはJVMエコシステムを対象としている。Haskellによる再実装は技術的には可能であるが、(a) 対象ユーザーの大部分はJava開発者であり、(b) JVMエコシステムとの統合（Maven/Gradle, IDE support, enterprise frameworks）がフレームワークの実用的価値の重要な部分を構成しており、(c) LSP/DAP生成を含む文法からIDE統合までの完全なパイプラインは、我々の知る限りHaskellエコシステムには存在しない。」

**先輩:** ...うん、それでいい。

**後輩:** あと、「モナドトランスフォーマースタックによる再定式化」も明示的に断るべきです。

**先輩:** 「我々はPropagationStopperとContainerParserがそれぞれReader monadのlocalとWriter monadのtellに対応することを認識している（Section 3.6で明示）。しかし、モナドトランスフォーマースタックによるフレームワーク全体の再定式化は、Javaユーザーにとっての可読性を損ない、かつ生成パイプラインの設計には影響しないため、実施しない。」

**後輩:** 完璧です。

---

### やらないこと 3: 追加のケーススタディ（実DSL）

**先輩:** R2は第三者のDSLでの適用事例を求めているが、revisionの期間内に外部ユーザーを見つけてDSLを実装してもらうのは不可能だ。

**後輩:** 合成文法ベンチマークで代替しましょう。それと、論文のDiscussionに「external validation through third-party DSL implementations is the primary target for future evaluation」と明記します。

**先輩:** そうする。

---

### やらないこと 4: エラー回復の実装

**後輩:** R2はエラー回復のベンチマークを求めていますが、エラー回復自体の実装は？

**先輩:** しない。PEGベースのパーサーでのエラー回復は研究課題レベルの難しさがある。Ford [2004]もPEGのエラー回復については触れていない。これは正直にLimitationsに書く。

**後輩:** 「エラー回復はPEGベースのパーサーにおける既知の困難な問題であり、本フレームワークの将来の研究方向の一つである。現時点では、ErrorMessageParserによるポイントエラー報告と、PEGの最深マッチ位置報告に基づく診断を提供している。」

**先輩:** そう。あとは、パース失敗位置の精度を定量的に示すことで、「エラー回復はできないが、エラー報告は正確」であることをアピールする。

---

### やらないこと 5: ContainerParser<T> の関手インターフェース追加

**先輩:** R1が`ContainerParser<T>`の関手性について聞いてきたが、fmap操作を追加するのは今回はやらない。

**後輩:** 理由は？

**先輩:** 現状の実装では`ContainerParser<T>`のTは生成時に固定されるため、実行時にTを変換する必要がない。関手性は理論的には面白いが、実用上の必要性がない。「will consider in future work」で十分。

**後輩:** 了解です。

---

### 反論書のまとめ

**後輩:** では、反論書（author response）のドラフトを作りましょう。

**先輩:** 構成は？

**後輩:** 以下の構成でどうですか：

```
1. 全査読者への感謝
2. 主要な改訂点の概要
3. R1への個別回答
   - 操作的意味論の追加（受容）
   - 代数的性質の追加（受容）
   - Table 1の拡張（受容）
   - 圏論的モデル（丁重にお断り）
   - ContainerParserの関手性（将来課題）
4. R2への個別回答
   - JMHベンチマーク（受容）
   - 合成文法ベンチマーク（受容）
   - エラー回復の定量評価（部分的に受容）
   - LLMセクションの修正（受容）
   - 第三者DSL（将来課題）
5. R3への個別回答
   - モナディック解釈セクションの追加（受容）
   - Reader/Writer monad対応の明示（受容）
   - 「novel」表現の修正（受容）
   - Haskell再実装（丁重にお断り）
   - MTLスタック再定式化（丁重にお断り）
```

**先輩:** それでいい。

**後輩:** 先輩、最後に一つ。

**先輩:** 何だ。

**後輩:** 反論書に「お前のコードは動くんか？」は書かないでくださいね。

**先輩:** ......書かんよ。

**後輩:** ...本当ですか？

**先輩:** 書かん！ ......たぶん。

**後輩:** 先輩...。

---

## Epilogue: 査読プロセスの教訓

**後輩:** 今回の査読で学んだことをまとめると。

**先輩:** 一つ目。形式的な裏付けのない「novel」は危険。操作的意味論くらいは最初から書いておくべきだった。

**後輩:** 二つ目。モナディック対応関係は認めた方が強い。隠すと「知らない」と思われる。認めた上で「だから何？ 我々のフレームワークの価値はそこにはない」と言う方が説得力がある。

**先輩:** 三つ目。JMHを使わないベンチマークは2024年以降は通用しない。これは言い訳が効かない。最初からJMHで測っておくべきだった。

**後輩:** 四つ目。N=1でも論文は書けるが、合成ベンチマークでスケーラビリティを補強すべき。tinyexpressionだけだと「このフレームワークのために作った言語でこのフレームワークが上手く動いた」に見えてしまう。

**先輩:** 五つ目。比較対象はパーサージェネレータだけじゃなく、言語ワークベンチまで含めるべきだった。SpoofaxとXtextを比較対象から外したのは手抜きだった。

**後輩:** 六つ目。関連研究は自分の都合の良いところだけ引用しない。[Erdweg et al. 2013]を参照しておきながらSpoofaxを比較表に含めなかったのは、査読者から見ると不誠実に映る。

**先輩:** 七つ目。エビデンスのない定量的主張は書かない。「10x token cost reduction」「95% of debugging round-trips」は体感値を書いただけだが、論文に数字が出てきたら読者は定量的エビデンスを期待する。

**後輩:** 八つ目。制限事項を正直に書く。エラー回復ができないことは書いたが、その影響（LSPサーバーの実用性への影響）まで踏み込むべきだった。

**先輩:** そして最後。

**後輩:** はい？

**先輩:** 査読者はそれぞれの信念体系（圏論、産業実用性、モナド原理主義）から論文を読む。全ての査読者を100%満足させることは不可能だ。できるのは、各査読者の正当な指摘を取り込んで論文を強化しつつ、不当な要求は丁重にかつ論理的に断ることだ。

**後輩:** R1の圏論的モデルは断る。R3のHaskell再実装は断る。でもR1の操作的意味論は受け入れる。R3のモナディック対応関係は受け入れる。R2の指摘はほぼ全部受け入れる。

**先輩:** R2が一番まともだったからな。

**後輩:** R2は実務家ですからね。動くものを評価する。

**先輩:** 「お前のコードは動くんか？」をもっと丁寧に言うとR2になる、ということだな。

**後輩:** ...そうかもしれません。

**先輩:** よし。改訂作業に入るか。まずJMHのセットアップからだ。

**後輩:** 先輩、その前に一つお願いがあります。

**先輩:** 何だ。

**後輩:** 反論書を書くとき、絶対に私に見せてから送ってください。

**先輩:** ...信用がないのか。

**後輩:** 先輩の技術的判断は完全に信頼しています。先輩の外交能力だけが心配なんです。

**先輩:** ......。

**後輩:** 先輩？

**先輩:** ......わかった。

**後輩:** ありがとうございます。では、改訂作業を始めましょう！

**先輩:** ああ。今度はR3にも認めさせてやる。

**後輩:** （...その意気で反論書を書かれると困るんですが...）

---

---

## Appendix: 査読スコアの推定変化

**後輩:** 改訂後のスコア予測をしておきましょう。

| 査読者 | v1 スコア | v2 予測 | 根拠 |
|--------|----------|---------|------|
| R1 | Weak Reject | Borderline Accept | 操作的意味論と代数的性質の追加で形式的厳密さが大幅改善。圏論モデルの不在はマイナスだが、SLEのスコープとして妥当。 |
| R2 | Weak Accept (borderline) | Accept | JMHベンチマーク、合成文法評価、Table 1拡張で全主要指摘に対応。エラー回復は部分的だが誠実な議論で許容範囲。 |
| R3 | Reject | Weak Reject | モナディック解釈セクションで「知らなかった」批判は解消。しかしR3の根本的な「Why not Haskell」には完全には答えられない。Reject -> Weak Rejectへの移動が限界。 |

**先輩:** R3がWeak Rejectに上がれば、平均でBorderline Acceptになる。ACとの議論次第で通る可能性がある。

**後輩:** ACがR2寄りの考え方なら通る。R3寄りなら厳しい。

**先輩:** SLEはソフトウェア言語工学の会議だ。ACはR2寄りの可能性が高いと読んでいる。

**後輩:** 楽観的すぎません？

**先輩:** 楽観的でなければ論文など書けん。

**後輩:** ...先輩がたまに良いこと言うの、ずるいです。

**先輩:** うるさい。draft始めるぞ。

**後輩:** はい！

---

*この会話劇は、unlaxer-parser論文 v1 の査読対応プロセスを記録したものであり、v2 改訂の方針書として機能する。*

*全ての査読コメントは架空のものであるが、技術的内容は実際の論文の問題点を反映している。*

---

## ナビゲーション

[← インデックスに戻る](../INDEX.md)

| 査読 | 対応する論文 |
|------|-------------|
| **v1 査読 — 現在** | [v1 論文](./from-grammar-to-ide.ja.md) |
| [v2 査読 →](../v2/review-dialogue-v2.ja.md) | [v2 論文](../v2/from-grammar-to-ide.ja.md) |
