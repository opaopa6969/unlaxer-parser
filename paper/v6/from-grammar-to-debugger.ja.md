# 文法からデバッガへ：型付きASTとソース位置を保持する評価によるDSLツールチェーンの一貫した生成

From Grammar to Debugger: Generating a Consistent DSL Toolchain with Typed ASTs and Source-Preserving Evaluation

Tool Paper草稿 v6。投稿・採録済みではない。[英語版](from-grammar-to-debugger.en.md) / [再現artifact](artifact.md) / [v5訂正表](errata.md)。旧版の査読会話劇は設計改善のための模擬査読であり、独立した学会査読ではない。

## 概要

DSLの文法変更は、parser、AST、mapper、evaluator、編集支援、debuggerの間に不整合を生む。unlaxer-parserはJava 21のparser combinator基盤とUBNFコード生成器を組み合わせ、注釈付き文法からこれらの構造を生成する。中心となる設計は、文法の`@mapping`からsealed AST、ASTへの変換、網羅的な評価dispatchと抽象メソッドを生成し、手書きの意味処理を別のsubclassに置くことである。ソース位置はASTに対応する別マップとして保持し、評価時の訪問イベントから参照できる。LSP/DAPの生成基盤も同じparserを用いる。

小規模な言語進化実験では、演算子、否定式、条件式を順に追加し、生成された8ファイルのコンパイルと動作を検証した。新AST型に追従しないdispatchと具体evaluatorの欠落はコンパイル時に検出されたが、文字列演算子の追加は検出されなかった。各変更の手書き差分は2ファイル、追加2〜6行・削除1行だった。この値は固定fixtureの変更量であり、他ツールに対する生産性比較ではない。さらに、次の解析で消えないソースマップを生成するAPIを実装した。本稿の貢献は言語進化に対する限定的だが再現可能な整合性検査とツール生成の設計経験であり、新しい形式言語理論や性能上の優位性ではない。

## 1. 問題と研究質問

構文規則が増えたとき、parserだけが新構文を受理してevaluatorは古いままになることがある。ASTの形、変換処理、補完候補、debuggerのソース位置も更新対象になる。既存のlanguage workbenchはこの問題に広く取り組んでおり、統一生成それ自体を新規とは主張しない。本稿はJavaの型検査と再生成を組み合わせた、小さな言語の保守手順を対象とする。

中心主張は、注釈付き文法を構造の仕様源として用いることで、AST型追加に伴うdispatchと評価メソッドの不整合の一部をJavaコンパイル時に検出でき、生成したparserを編集・デバッグ基盤で共有できることである。意味論の仕様全体を文法だけから導くという主張ではない。

- RQ1: 文法から何を生成し、何を手書きする必要があるか。
- RQ2: 言語機能追加時にどの変更が必要で、どの不整合が検出されるか。
- RQ3: parser、mapper、evaluator、LSP、DAPの連携を再現可能なartifactで検証できるか。性能と実運用への一般化には何が不足するか。

## 2. 生成範囲と設計

`unlaxer-common`はToken、Source、ParseContext、combinatorを提供する。`unlaxer-dsl`はUBNFを解析して各generatorへ渡す。実験では`ParserGenerator`、`ASTGenerator`、`MapperGenerator`、`EvaluatorGenerator`、`LSPGenerator`、`DAPGenerator`とLSP/DAPのlauncherを使用する。6種類の主要成果物、launcherを含めて8 Javaファイルとなる。

| 成果物 | 文法から生成する部分 | 手書き・保証外の部分 |
|---|---|---|
| Parser | 規則、選択、capture、token parserへの参照 | 外部token parser、言語固有の制約 |
| AST | `@mapping`に対応するsealed interfaceとrecord | 異種choiceの一部は`Object`になりcastが必要 |
| Mapper | Tokenからrecordへの変換、nodeと位置の対応 | 任意のUBNFでの完全な型正当性、合成nodeの全位置 |
| Evaluator | sealed switch、`evalXxx`契約、訪問イベント | 意味処理、値表現、環境、例外方針 |
| LSP | parserを使う編集支援の基盤、キーワード補完など | 言語固有の型検査・意味的な診断や追加hook |
| DAP | token/AST走査、step、stack frame、位置表示の基盤 | 意味評価の実行・環境との接続、実行意味に沿うstep |

生成されたevaluatorの抽象基底と具体subclassを分けるGeneration Gap Patternを採用する。再生成で手書きの意味処理を上書きしない。新しい`@mapping(Negation, ...)`が追加された場合、二つの失敗形態がある。

1. ASTだけを更新し古いevaluator基底を使うと、sealed switchが非網羅になる。
2. ASTとevaluator基底を再生成するとswitchは追従するが、古い具体subclassに`evalNegation`がなくコンパイルに失敗する。

この保証は同じ新しいソースを再コンパイルする場合の構造的な義務である。古いclassファイルの混在、意味処理の誤り、`Object`の不正なcast、文字列で表す演算子への対応漏れまでは防げない。また、`@eval`で具象メソッドが生成される場合、新しい抽象メソッドの実装義務は生じない。

現行の`@eval`は例えば`@eval(kind='conditional', strategy='default')`という記法で、`binary_arithmetic`、`variable_ref`、`conditional`、`passthrough`、`literal`の生成処理を持つ。型とfieldの形に依存するため、これらを任意の意味論の自動生成とは呼ばない。本実験は意味処理を明示するために手書きevaluatorを使う。

### ソース位置を保持する評価

Tokenはソースを持つが、生成ASTのrecordには位置fieldがない。従来のmapperはnode identityをキーとする静的テーブルで位置を返していた。このテーブルは次の解析でclearされるため、以前の文書のASTを後から評価・表示すると位置が失われる。

v6では`parseWithSourceMap(String)`と`mapParsedTokenWithSourceMap(Token)`を追加した。結果の`SourceMappedAst<T>`はASTと不変の位置スナップショットを所有する。同じ値を持つ別nodeを区別するため、通常のrecord等価性ではなくidentityで対応付ける。位置の配列は保存時と取得時にコピーする。公開mapping処理とsnapshot作成を同期し、一つのmapper内で他のmappingが途中に入ることを防ぐ。既存APIとの互換性を維持するため、従来の最新結果専用lookupも残す。

位置はUnicodeコードポイント単位の半開区間である。開始位置とUTF-16長の混在を修正し、両端の単位を統一した。Java文字列やLSPのUTF-16位置への変換は境界で行う。合成nodeに位置が存在しない場合は`Optional.empty()`を返す。snapshotのメモリ量は記録されたnode数に比例する。性能の改善やparser基盤全体のthread safetyは主張しない。

evaluatorの`DebugStrategy.onEnter/onExit`と`StepCounterStrategy`は実際の評価呼出を観測できる。新しいsnapshotを使えば後続のparse後もそのnodeの位置を引ける。一方、生成DAPの標準動作はtoken/ASTの構造走査であり、evaluatorの意味実行とは別である。例えば条件式で未選択の枝を実行時と同じように飛ばす機能には追加の接続が必要である。

## 3. 言語進化実験

全入力と手書きコードを`src/test/resources/evolution/`に固定した。基準言語は数値と二項加算、段階1で乗算、段階2で`neg(...)`、段階3で`if(condition,then,else)`を加える。数値0を偽とし、他を真とする。小規模な例として二項式の被演算子は数値に限定する。完全な算術言語の構文比較ではない。

各段階で8個のJavaソースを生成・コンパイルし、生成mapperで入力を解析して具体evaluatorで評価する。以前の入力も再実行する。意図的に古い生成dispatchまたは古い手書きevaluatorを組み合わせ、コンパイル診断の種類と欠けたメソッド名を検査する。演算子追加についてはコンパイルが成功しても実行時に失敗する負例を置く。

| 段階 | 手書き変更ファイル | 追加/削除行 | 変化した生成ファイル/生成総数 | 旧コードへの検査結果 |
|---|---:|---:|---:|---|
| 0: 基準 | 2 | 25/0 | 8/8 | コンパイル・評価成功 |
| 1: `*` | 2 | 2/1 | 3/8 | 旧意味処理はコンパイル成功、実行時失敗 |
| 2: Negation | 2 | 6/1 | 5/8 | 非網羅switch／`evalNegation`未実装 |
| 3: Conditional | 2 | 6/1 | 5/8 | 非網羅switch／`evalConditional`未実装 |

空行を含むLCS差分であり、基準は空ファイルからの追加。共通ハーネスやgeneratorの開発行数は含めない。生成ファイルには再生成しても内容が変わらないものがある。LSP/DAPのJavaソースに差分がなくても、参照先の生成parserとmapperを更新して再コンパイルすることで新構文に追従する。

再現コマンドは[artifact](artifact.md)に記載した。テストが出力する`machine_ms`は生成・コンパイル・動作確認の経過時間で、人間の所要時間ではない。作業時間は統制して記録していないため、8週間や13倍削減といった旧版の推計との比較は行わない。

追加したJUnitメソッドは4個で、そのうち言語進化の1メソッドが4段階を検査する。LSPは初期化・文書open・補完、DAPは初期化・AST modeのlaunch・configurationDone・stackTrace・nextを直接呼び出す。各段階でentryとstepが発生し、新AST型がstack frameに現れることを確認する。これはprotocol service APIの試験であり、JSON-RPC輸送やVS Codeでの操作実験ではない。

ソースマップについては、全評価訪問nodeの位置、次の解析後の参照、同値で別identityのnode、取得配列の変更、非BMP文字を含む範囲、既存Tokenからのmappingを検証する。これにより本fixture内の連携を確認できるが、一般的なプログラム意味論の正しさや全node形状の位置精度は証明しない。

## 4. 関連研究

Xtextはparser、linker、compiler/interpreter、編集支援を扱うlanguage engineering frameworkで、LSPの実装支援も提供する。XbaseはJavaとの統合・生成・デバッグを支える。本稿はこれらを「IDEや意味論を作れない」比較対象として扱わない。[Xtext/Xbase](https://eclipse.dev/Xtext/)、[Xtext LSP](https://eclipse.dev/Xtext/documentation/340_lsp_support.html)。

SpoofaxはSDF3、Stratego、静的意味論などのmetalanguageを組み合わせる。DynSemによる動的意味論も提供しており、意味論の記述・生成はunlaxer固有ではない。[SpoofaxのDynSem導入](https://spoofax.dev/release/note/2.1.0/)。Langiumは文法からTypeScriptのASTを生成し、LSPベースの言語基盤を構築する。[Langiumの機能説明](https://langium.org/docs/features/)。

これらとの違いは、Java 21のsealed型と抽象メソッドを使う評価拡張点、combinatorのTokenから保持するソース位置、生成LSP/DAP基盤を同じJavaプロジェクト内に置く構成にある。この比較は設計の位置づけであり、機能網羅性・性能・工数の順位づけではない。生成と手書きの境界は表1に示した。既存workbenchでも型システムや検証規則で類似の整合性検査を実現し得る。

## 5. 妥当性と限界

RQ1に対しては、6種類の基盤を生成できるが、意味処理や言語固有のIDE/debug機能を手書きする必要がある。RQ2に対しては、本fixtureの3変更の差分と、型追加に対する二つのコンパイル検出を再現した。ただし文字列演算子には同じ保証がない。RQ3に対しては、生成物のコンパイル、評価値、位置、補完、DAPサービスの連携を確認した。実用的な性能の主張にはJMH、代表的入力、複数文書の編集workload、実エディタと輸送層の試験がさらに必要である。

本実験は作者が用意した小さな例で、比較実装・第三者参加者・所要時間記録を持たない。数値captureと異種choiceについて実装制約があり、fixtureは`Digits`規則と明示castを使用する。文法やコードの変更量から一般的な生産性を推定しない。ソースマップの同期は同じmapperの処理を直列化するため、並列性との交換条件もある。

tinyexpressionは旧稿で実運用例として報告され、月間10億transaction規模の環境、5実行backend、445件のテストが記述されていた。本artifactはその運用を独立監査せず、backend間の完全な等価性やP4移行完了も主張しない。旧稿の550件超との合計を現在のテスト数として転載しない。

DGEの会話とgap一覧は開発経緯を理解する資料として残す。対照実験なしに「DGEだから201件のgapが見つかった」とは結論しない。模擬査読が回文とモノイドの誤りを見逃したことから、形式主張には独立した列挙や一次資料確認を追加した。方法論の有効性は本稿の研究結果から分離する。

## 6. 結論

unlaxer-parserは、注釈付き文法、生成されたJava型、手書きの意味処理を結び、言語進化時の不整合の一部をコンパイルエラーとして顕在化させる。v6のartifactはこの効果と限界を同じ実験内で示し、ソース位置の寿命を解析結果に結び付けるAPIを追加した。これらはTool Paperとして検証可能な実装上の知見である。大規模な生産性・性能の評価は今後の課題となる。

## 付録A. 回文とコピー言語の訂正

`{ww^R | w∈{a,b}*}`は偶数長回文であり、`S → aSa | bSb | ε`というCFGを持つ。奇数長も含める場合は`S → a | b`を追加する。旧稿の`a`や`abcba`は偶数長だけの定義に一致しなかった。[ColumbiaのCFG講義](https://www.cs.columbia.edu/~aho/cs3261/Lectures/L8-PDA.html)。

補助例を`Lcopy={w#w | w∈{a,b}*}`に置き換えた。非空の`w`をcaptureして`MatchedTokenParser`で同じ順序にreplayし、空語は`#`の明示的な枝で扱う。parserのprefix成功だけでは受理とせず、全入力の消費を要求する。`CopyLanguageTest`は`{a,b,#}`上の長さ0〜7の全3,280文字列を、独立した等値判定と照合する。長い一致・反転・余剰入力・範囲外文字も検査する。

非文脈自由性は有限テストで証明するものではない。CFG言語はhomomorphismで閉じているが、`#`を消す準同型で`Lcopy`は非文脈自由な二文字のコピー言語`{ww}`に写るため、`Lcopy`も非文脈自由である。[コピー言語の非文脈自由性と閉包性](https://www.cs.columbia.edu/~aho/cs4115/Lectures/15-02-11.html)。これは一般PEGの表現力に対する分離結果ではない。PEGをCFGと同一視せず、純粋PEGに対する優位性をここから推論しない。[Ford 2004](https://bford.info/pub/lang/peg/)。

## 付録B. 伝播パラメータの8元モデル

`S={M,C}×{F,T}`上で`Id=(t,b)`、`AllStop=(C,F)`、`DoConsume=(C,b)`、`StopInvert=(t,F)`、`NotProp=(t,!b)`を考える。`f . g`はgの後にfを適用する。第一成分はidかconst C、第二成分はid、not、const F、const Tで、最大8通り。`ConsumeNot=DoConsume . NotProp`、`ForceInvert=NotProp . StopInvert`、`AllTrue=DoConsume . ForceInvert`が残りの写像を生成するため、閉包はちょうど8元になる。

`AllStop . X = AllStop`は左零元の条件。`NotProp . AllStop = AllTrue`なので右零元ではない。`AllTrue`も左零元であり、両側零元はない。完全な合成表は[生成した8×8表](propagation-table.md)に載せる。`PropagationAlgebraTest`は全積、単位元、非可換性、512通りの結合則を検証する。

これは二つのパラメータに関する抽象モデルであり、ParseContextの状態、失敗・rollback、虚tokenを含むparser全体の観測同値性の証明ではない。現行`TokenKind`には虚tokenを含む4値がある。現行APIに`AllPropagationStopper`クラスはなく、`AllStop`は`DoConsumePropagationStopper`と`InvertMatchPropagationStopper`の効果を合成したモデル名である。
