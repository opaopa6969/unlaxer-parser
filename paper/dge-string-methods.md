# DGE Session: String メソッドの設計 — 関数形式とドット形式の共存

## テーマ
関数形式とドット形式を直交的に共存させることは可能か？ 後方互換性を維持しつつ、ユーザーが驚かない String メソッド設計を発見する。

## キャラクター
- ☕ ヤン・ウェンリー — 「要らなくない？」「最もシンプルな解は？」
- 🎩 千石武 — 「品質基準を示す」「ユーザーのために」
- ⚔ リヴァイ兵長 — 「汚い。動くもの見せろ。」
- 👤 今泉慶太 — 「そもそも」「誰が困るの」

## 前提条件
- 後方互換性は最重要前提（月10億トランザクション本番稼働中）
- 既存のドット記法: `$name.startsWith('he')`, `$name.contains('ell')`, `$name.in('a','b')`
- 既存の関数形式: `toUpperCase($name)`, `toLowerCase($name)`, `trim($name)`
- math 関数は全部関数形式: `sin($x)`, `abs($x)`, `min($a,$b,$c)`
- 直交性: ユーザーが驚かない設計

## 先輩 (ナレーション)
tinyexpression の String メソッドは歴史的経緯により2つの記法が混在している。述語系メソッド（startsWith, contains, in）はドット記法で、変換系メソッド（toUpperCase, toLowerCase, trim）は関数形式で実装された。これは「Boolean を返すものはドット記法、String を返すものは関数形式」という暗黙のルールに基づくが、このルールは明文化されておらず、ユーザーにとって予測可能かどうかが問われている。月10億トランザクションの本番環境が稼働しており、既存の記法を壊すことは許されない。

---

## Scene 1: ユーザーの混乱（今泉が問う）

今泉: 「そもそも、ユーザーはどっちの書き方をしたいんですか？ ユーザーに聞いたんですか？」

ヤン: 「聞いてないだろうね。ユーザーは自分が書きたい記法を試して、動いたらそれを使う。動かなかったらドキュメントを見る。」

今泉: 「要するに、ユーザーが最初に試す記法が通らないと、混乱するってことですよね。じゃあユーザーは最初にどっちを試すんですか？」

千石: 「Java や JavaScript の経験があるユーザーは `$name.toUpperCase()` と書くでしょう。関数形式の `toUpperCase($name)` は Excel や SQL の経験者が書く記法です。」

今泉: 「他にないの？ 例えば Python なら `$name.upper()` だし、Ruby なら `$name.upcase` だし。言語によってバラバラですよね。そもそも tinyexpression のユーザーってどの言語に慣れてる人なんですか？」

  → **Gap 発見: ユーザーペルソナが未定義。Java/JS 系か SQL/Excel 系かでメンタルモデルが全く異なる。どちらの記法を「自然」と感じるかはユーザー属性に依存するが、その属性調査がない。**

ヤン: 「実際に書きそうな式を並べてみようか。」

```
// ユーザーが書きそうな式の例
$name.toUpperCase() == 'HELLO'           // Java/JS 経験者
toUpperCase($name) == 'HELLO'            // SQL/Excel 経験者
$name.startsWith('he') & $name.length() > 3   // ドット記法チェーン
startsWith($name, 'he') & length($name) > 3   // 関数形式チェーン
trim($name).startsWith('he')             // 混在 — これが一番厄介
$name.trim().startsWith('he')            // ドットチェーン — 自然だが今はない
```

今泉: 「`trim($name).startsWith('he')` って、関数の戻り値にドットメソッドを呼ぶんですか？ これ動くんですか？」

  → **Gap 発見: 関数形式の戻り値に対するドットメソッド呼び出し（`trim($name).startsWith('he')`）のパース可否が未定義。関数形式とドット形式の混在使用時のパーサー動作が不明。**

リヴァイ: 「動かない。そして動かす必要もない。混ぜるな。」

---

## Scene 2: 後方互換の壁

千石: 「既存の `.startsWith` は絶対に壊せません。月10億トランザクションです。1つでも既存の式が parse できなくなったら、本番障害です。」

今泉: 「そもそも、本番で使われている記法の一覧ってあるんですか？ 何種類の式がデプロイされてるんですか？」

  → **Gap 発見: 本番で使われている式テンプレートのインベントリがない。どの記法が実際に使われているか不明。新機能追加の影響範囲を正確に判定できない。**

千石: 「少なくとも以下は確実に使われています。」

```
// 既存のドット記法（本番稼働中）
$name.startsWith('he')
$name.contains('ell')
$name.in('a', 'b', 'c')

// 既存の関数形式（本番稼働中）
toUpperCase($name)
toLowerCase($name)
trim($name)
```

ヤン: 「つまり、ドット記法は Boolean を返す述語メソッド、関数形式は String を返す変換メソッド。きれいに分かれてるね。」

今泉: 「前もそうだったっけ？ 最初からこのルールで設計したんですか？ それとも偶然こうなったんですか？」

ヤン: 「偶然だろうね。startsWith は Java の String.startsWith() に似せたからドット記法。toUpperCase は...なぜ関数形式にしたんだろう。」

  → **Gap 発見: 既存の記法分離（述語=ドット、変換=関数）が意図的設計なのか偶然なのかが不明。偶然なら「設計ルール」として昇格させるべきか、それとも直交性のために統一すべきか。**

千石: 「偶然であろうと、既に数千の式が本番で動いている以上、それが事実上の仕様です。変えてはなりません。」

リヴァイ: 「既存を壊すな。新しいものを足すだけだ。足す場合も、既存の parse に影響がないことをテストで証明しろ。」

  → **Gap 発見: 新しい記法を追加した場合に既存の式の parse 結果が変わらないことを保証する回帰テストスイートが必要。既存の式パターンを網羅したテストがあるか不明。**

---

## Scene 3: 直交性の検証

ヤン: 「紅茶を飲みながら整理しよう。両方サポートするとして、全ての String メソッドで両方が等価に動くか？」

```
toUpperCase:
  関数形式: toUpperCase($name) → 既存 ✅
  ドット形式: $name.toUpperCase() → 今はない。追加する？

startsWith:
  ドット形式: $name.startsWith('he') → 既存 ✅
  関数形式: startsWith($name, 'he') → 今はない。追加する？

trim:
  関数形式: trim($name) → 既存 ✅
  ドット形式: $name.trim() → 今はない。追加する？

contains:
  ドット形式: $name.contains('ell') → 既存 ✅
  関数形式: contains($name, 'ell') → 今はない。追加する？

in:
  ドット形式: $name.in('a','b','c') → 既存 ✅
  関数形式: in($name, 'a','b','c') → 今はない。追加する？
```

ヤン: 「完全直交にすると、全メソッドに対して2つのパーサーパスが必要になる。実装量は単純に2倍。」

今泉: 「誰が困るの？ 完全直交じゃないと。」

ヤン: 「ユーザーが困る...かもしれない。`$name.toUpperCase()` を試して動かなかったユーザーが、ドキュメントを引いて `toUpperCase($name)` と書き直す。その手間がどの程度の痛みかだね。」

千石: 「ドキュメントを引く手間を『軽い痛み』と片付けるのはユーザーへの侮辱です。プロなら、ユーザーが最初に試す記法で動くべきです。」

  → **Gap 発見: 完全直交にすると実装量が2倍になる。しかし完全直交にしないと「なぜ startsWith はドット記法で書けるのに toUpperCase はドット記法で書けないのか」というユーザーの疑問に答えられない。直交性の不在はドキュメントのコストも上げる。**

リヴァイ: 「全部やるな。ユーザーの頻度で決めろ。100人中90人が使う記法だけサポートしろ。1人が書く変な式のために全部直交にするのは無駄だ。」

今泉: 「他にないの？ 完全直交か、現状維持か、の二択じゃなくて。例えば『ドット記法を正にして、関数形式はレガシーとして残す』とか。」

  → **Gap 発見: 直交性のレベルに段階がある。(A) 完全直交、(B) 現状維持、(C) ドット記法を正・関数はレガシー、(D) 関数を正・ドットはレガシー、(E) 返り値型で分ける（String→関数、Boolean→ドット）。どのレベルを選ぶかの判断基準が未定義。**

---

## Scene 4: ドット記法のパーサー問題

リヴァイ: 「ドット記法は PEG で面倒だぞ。パーサーの構造を見てから議論しろ。」

今泉: 「そもそも、ドット記法って文法的にどういう構造なんですか？」

リヴァイ: 「`$name.startsWith('he')` をパースするには、まず `$name` を VariableRef として認識し、次に `.` を見て、次に `startsWith` をメソッド名として認識し、`('he')` を引数としてパースする。問題は、VariableRef をパースした後に `.` が来るかどうかを先読みしなきゃならないことだ。」

```
// 現在の文法（推定）
StringPredicateMethod ::= VariableRef '.' MethodName '(' Arguments ')'

// ドットチェーンを追加すると
DotChain ::= Primary { '.' MethodName '(' Arguments ')' }
Primary  ::= VariableRef | FunctionCall | '(' Expression ')'
```

千石: 「`$name.startsWith('he').length` のようなチェーンはどうしますか？ `startsWith` は Boolean を返しますから、`.length` は呼べないはずです。」

今泉: 「要するに、ドットチェーンは型によって呼べるメソッドが変わるってことですよね。でもパーサーは型を知らない。パーサーはどうやって判断するんですか？」

  → **Gap 発見: ドットチェーンの型安全性をパーサーレベルで保証するか、Evaluator レベルで保証するか。PEG パーサーは型情報を持たないため、文法的には `$name.startsWith('he').length()` を受理してしまう。型チェックを Evaluator に委ねるなら、パーサーは緩く受理する必要がある。**

リヴァイ: 「チェーンは要らない。`$name.toUpperCase()` の1段で十分だ。チェーンしたければ変数に入れろ。」

ヤン: 「僕もそう思う。チェーンは便利だけど、パーサーの複雑さが指数的に上がる。1段のドットメソッドだけにしよう。」

今泉: 「ちょっと待ってください。そもそもの質問なんですけど、左から右にパースしていったら左辺の型って確定しませんか？」

ヤン: 「...どういうこと？」

今泉: 「`$name.toUpperCase().startsWith('HE')` をパースするとき:
1. `$name` → String 型が確定
2. `.toUpperCase()` → String を受けて String を返す。型が確定
3. `.startsWith('HE')` → String を受けて Boolean を返す。型が確定
各ステップで左辺の型が分かってますよね？」

ヤン: 「......ああ。PEG の `{ }` (ZeroOrMore) で書けばいいのか。」

```ubnf
// 任意深度チェーン — 左再帰にならない！
StringExpression ::= StringAtom { StringMethodChain } ;
StringMethodChain ::= '.' StringMethodName '(' Arguments ')' ;
```

今泉: 「要するに、チェーンを『サポートするかどうか』という設計判断じゃなくて、左から型が確定するなら自動的にチェーンが可能になるってことですよね？ 制限する方がむしろ不自然じゃないですか？」

千石: 「今泉さんの言う通りです。PEG の反復構文で自然にチェーンが書ける。1段制限は人為的な制約であって、パーサーの都合ではありません。」

リヴァイ: 「...確かに。反復構文なら実装も1段と変わらない。」

ヤン: 「ただし型遷移は考える必要がある。String→String のメソッド（toUpperCase, trim）はチェーン可能だけど、String→Number（length）や String→Boolean（startsWith）はチェーンの終端になる。」

```
$name              : String
  .toUpperCase()   : String → String  (チェーン可能)
  .trim()          : String → String  (チェーン可能)
  .length()        : String → Number  (チェーン終端)
  .startsWith('x') : String → Boolean (チェーン終端)
```

今泉: 「パーサーレベルで型チェックするんですか？」

ヤン: 「文法で分ければいい。StringChainable と StringTerminal を分ける。」

```ubnf
// String→String メソッド（チェーン可能）
StringChainableMethod ::= '.toUpperCase' '(' ')' | '.toLowerCase' '(' ')' | '.trim' '(' ')' ;

// String→Other メソッド（チェーン終端）
StringTerminalToNumber  ::= '.length' '(' ')' ;
StringTerminalToBoolean ::= '.startsWith' '(' Args ')' | '.contains' '(' Args ')' ;

// チェーン構文
StringChainExpr ::= StringAtom { StringChainableMethod } ;
// 終端メソッドはチェーンの最後にだけ来る
NumberFromString  ::= StringChainExpr StringTerminalToNumber ;
BooleanFromString ::= StringChainExpr StringTerminalToBoolean ;
```

千石: 「これなら型安全がパーサーレベルで保証されます。`$name.startsWith('he').length()` は文法的に受理されない。正しい設計です。」

  → **Gap 発見（修正）: ドットチェーンは制限不要。ZeroOrMore で自然に実現できる。ただし StringChainable（String→String）と StringTerminal（String→Other）の区別が文法レベルで必要。**

---

## Scene 5: AST の統一

千石: 「どちらの記法でも同じ AST になるべきです。パーサーが2通り、Evaluator が1通り。これが正しい設計です。」

ヤン: 「そうだね。例を出そう。」

```
// 記法1（ドット形式）
$name.startsWith('he')
  → StartsWithExpr(value=$name, candidates=['he'])

// 記法2（関数形式）
startsWith($name, 'he')
  → StartsWithExpr(value=$name, candidates=['he'])

// 記法1（関数形式）
toUpperCase($name)
  → ToUpperCaseExpr(value=$name)

// 記法2（ドット形式）
$name.toUpperCase()
  → ToUpperCaseExpr(value=$name)
```

千石: 「AST が同じなら、Evaluator のコードは完全に共有できます。`evalStartsWithExpr` は1つ。`evalToUpperCaseExpr` も1つ。パーサーだけが2通りのパスを持つ。」

今泉: 「そもそも、引数の順序が違いますよね。ドット形式は `receiver.method(args)` で、関数形式は `method(receiver, args)` で。パーサーはどうやって同じ AST に変換するんですか？」

  → **Gap 発見: ドット形式と関数形式で引数の意味的役割が異なる。ドット形式の receiver はドットの左側、関数形式の receiver は第1引数。パーサーの @mapping でこの変換を記述する必要がある。@mapping のルールが2通り必要。**

千石: 「具体的に書きましょう。」

```
// ドット形式の @mapping
StringPredicateMethod ::= VariableRef '.' 'startsWith' '(' StringLiteral ')'
  @mapping(StartsWithExpr, params=[value=VariableRef, candidates=[StringLiteral]])

// 関数形式の @mapping
StartsWithFunction ::= 'startsWith' '(' VariableRef ',' StringLiteral ')'
  @mapping(StartsWithExpr, params=[value=VariableRef, candidates=[StringLiteral]])
```

ヤン: 「@mapping の出力側は同じ。入力側のパーサーが違うだけ。これなら既存の Evaluator を変更せずに新しいパーサーパスを追加できる。」

リヴァイ: 「@mapping が2つあると、GGP の MapperGenerator がどう処理するか確認しろ。同じ AST クラスに対して2つの @mapping があるケースをテストしたことがあるのか？」

  → **Gap 発見: GGP の MapperGenerator が同一 AST クラスへの複数 @mapping をサポートしているか未検証。1つの AST クラスに対してドット形式と関数形式の2つの文法ルールからマッピングする場合、MapperGenerator の挙動が不明。**

今泉: 「誰が困るの？ サポートしてなかったら。」

リヴァイ: 「俺が困る。手書きでマッパーを書く羽目になる。」

---

## Scene 6: 新メソッド追加時の原則

今泉: 「将来、新しい String メソッドを追加するとき、毎回両形式作るんですか？ 例えば `replace` を追加するとしたら、`replace($s, 'old', 'new')` と `$s.replace('old', 'new')` の両方を文法に追加して、テストを書いて、ドキュメントを書いて。工数2倍ですよね。」

ヤン: 「2つのアプローチがある。(A) 関数形式を正（canonical）として、ドット形式を互換エイリアスとする。(B) 両方を対等に扱う。」

千石: 「(A) の場合、ドット形式はドキュメントに『Legacy syntax, prefer function form』と書かれることになります。それは既存のドット形式ユーザーへの侮辱です。彼らの書き方を否定することになる。」

今泉: 「(B) の場合、毎回2倍の作業が必要なんですよね。誰がその作業をするんですか？ メンテナーが1人しかいなかったら持続可能なんですか？」

  → **Gap 発見: 新メソッド追加時のガイドラインが未定義。両形式を毎回作るのか、どちらか一方をデフォルトとするのか。メンテナンスコストの持続可能性が考慮されていない。**

ヤン: 「第3のアプローチ (C) がある。返り値の型で決める。Boolean を返すメソッドはドット形式が自然（`$name.startsWith('he')` は `if` の条件式で読みやすい）。String を返すメソッドは関数形式が自然（`toUpperCase($name)` は Excel の `UPPER(A1)` に似てる）。Number を返すメソッドも関数形式（`length($name)` は `LEN(A1)` に似てる）。」

千石: 「ルールは明確ですが、ユーザーがそのルールを知らなければ意味がありません。」

今泉: 「要するに、どのアプローチでもドキュメントは必要ってことですよね。ドキュメントが良ければどのアプローチでもユーザーは学べる。ドキュメントが悪ければどのアプローチでも混乱する。」

  → **Gap 発見: String メソッドの記法ルールを説明するドキュメント / エラーメッセージの設計が未定義。`$name.toUpperCase()` と書いて parse error になった場合、「`toUpperCase($name)` と書いてください」というサジェストを出せるか？**

リヴァイ: 「エラーメッセージでサジェストを出すのは追加工数だ。パーサーエラー時に『もしかして？』を出すには、失敗した parse パスを分析する必要がある。PEG パーサーでこれは面倒だ。」

  → **Gap 発見: PEG パーサーのエラーリカバリとサジェスト機能。parse 失敗時に代替記法を提案する仕組みがない。ユーザビリティ向上のためには有用だが、PEG パーサーのエラーリカバリは一般的に難しい。**

---

## Scene 7: 具体的な API 一覧

リヴァイ: 「議論はいい。全メソッドの一覧を出せ。各々どの形式をサポートするか決めろ。」

| メソッド | 関数形式 | ドット形式 | 引数 | 返り値 | 既存実装 |
|----------|---------|-----------|------|--------|---------|
| toUpperCase | `toUpperCase($s)` | `$s.toUpperCase()` | 1 | String | 関数 ✅ |
| toLowerCase | `toLowerCase($s)` | `$s.toLowerCase()` | 1 | String | 関数 ✅ |
| trim | `trim($s)` | `$s.trim()` | 1 | String | 関数 ✅ |
| length | `length($s)` | `$s.length()` | 1 | Number | なし |
| substring | `substring($s, start, end)` | `$s.substring(start, end)` | 3 | String | なし |
| startsWith | `startsWith($s, 'prefix')` | `$s.startsWith('prefix')` | 2 | Boolean | ドット ✅ |
| endsWith | `endsWith($s, 'suffix')` | `$s.endsWith('suffix')` | 2 | Boolean | ドット ✅ |
| contains | `contains($s, 'sub')` | `$s.contains('sub')` | 2 | Boolean | ドット ✅ |
| indexOf | `indexOf($s, 'sub')` | `$s.indexOf('sub')` | 2 | Number | なし |
| in | `in($s, 'a', 'b', 'c')` | `$s.in('a', 'b', 'c')` | varargs | Boolean | ドット ✅ |
| replace | `replace($s, 'old', 'new')` | `$s.replace('old', 'new')` | 3 | String | なし |

今泉: 「全部必要ですか？ MVP はどれですか？」

ヤン: 「既存のものは既に動いてる。新規追加の候補は length, substring, indexOf, replace, endsWith の5つ。全部いるかね。」

リヴァイ: 「length は要る。substring は要る。replace は要る。indexOf は...使う場面が思い浮かばない。endsWith は startsWith があるなら対称性として入れろ。」

千石: 「endsWith が既にドット形式で存在するとおっしゃいましたが、本当ですか？ 私はコードを確認していません。」

  → **Gap 発見: endsWith の実装状況が不明確。startsWith と contains は確実に存在するが、endsWith が既に実装されているかどうかの確認が必要。**

今泉: 「`in()` の関数形式って `in($name, 'a', 'b', 'c')` ですよね。これ、第1引数が検索対象で第2引数以降が候補リスト。でも英語として読むと `in(name, a, b, c)` って『name は a, b, c の中にある？』で、`$name.in('a', 'b', 'c')` と同じ意味ですけど...」

ヤン: 「何が引っかかる？」

今泉: 「`contains($name, 'ell')` は『name は ell を含むか？』で、`in($name, 'a', 'b')` は『name は a, b の中にあるか？』で。contains は文字列包含、in は集合所属。意味が違うのに関数形式にすると `contains($name, 'ell')` と `in($name, 'a', 'b')` が似た形になって、紛らわしくないですか？」

  → **Gap 発見: `contains` と `in` の意味的区別が関数形式では曖昧になる。`contains($s, 'sub')` は部分文字列検索、`in($s, 'a', 'b')` は集合所属検査。ドット形式では `$s.contains('sub')` vs `$s.in('a','b')` で読みやすいが、関数形式では引数パターンが似すぎて混乱を招く可能性がある。**

リヴァイ: 「`in` を関数形式にする必要はない。ドット形式だけでいい。`$name.in('a','b','c')` は直感的だ。`in($name, 'a', 'b', 'c')` は誰も書かない。」

---

## Scene 8: MVP 決定

ヤン: 「全部やるな。紅茶が冷める前に決めよう。」

リヴァイ: 「動くものを出せ。完全直交は幻想だ。現実的な線引きをしろ。」

ヤン: 「僕の提案はこうだ。」

```
方針: 既存は壊さない。新規追加は返り値型ルールに従う。

ルール:
  1. 既存の記法は全て維持（後方互換）
  2. Boolean を返すメソッド → ドット形式が正（canonical）
  3. String/Number を返すメソッド → 関数形式が正（canonical）
  4. 正でない形式は「追加サポート」として段階的に足す
  5. 追加サポートの優先度はユーザーリクエストで決める
```

千石: 「ルールは理解しましたが、『段階的に足す』の段階が不明確です。何をもって追加するのですか？」

今泉: 「そもそも、このルールだと `$name.toUpperCase()` は『追加サポート』扱いですよね。でも Java/JS ユーザーにとっては `$name.toUpperCase()` こそが『正しい記法』ですよ。正（canonical）が関数形式だと言われたら怒りませんか？」

  → **Gap 発見: canonical/alias の区別がユーザーに見えるかどうか。内部的に canonical を決めることとユーザーに見せることは別問題。ドキュメントで両方を対等に記載し、内部実装だけ canonical を持つアプローチもありうる。**

ヤン: 「いい指摘だ。canonical はあくまで『実装の優先順位』であって、ユーザーに見せるものじゃない。ドキュメントでは両方を対等に書く。」

リヴァイ: 「で、MVP は何だ。具体的に言え。」

ヤン: 「Phase 1（MVP）は以下。」

```
Phase 1 (MVP):
  新規追加:
    - $s.toUpperCase()      ← ドット形式を追加（関数形式は既存）
    - $s.toLowerCase()      ← ドット形式を追加（関数形式は既存）
    - $s.trim()             ← ドット形式を追加（関数形式は既存）
    - length($s)            ← 関数形式を新規追加
    - $s.length()           ← ドット形式も同時に追加

  既存維持:
    - $name.startsWith('he')  ← そのまま
    - $name.contains('ell')   ← そのまま
    - $name.in('a','b')       ← そのまま
    - toUpperCase($name)      ← そのまま
    - toLowerCase($name)      ← そのまま
    - trim($name)             ← そのまま

Phase 2:
    - endsWith (ドット形式)
    - substring (関数形式 + ドット形式)
    - replace (関数形式 + ドット形式)
    - startsWith / contains の関数形式

Phase 3 (要望次第):
    - indexOf
    - in の関数形式
```

千石: 「Phase 1 の範囲は妥当です。toUpperCase, toLowerCase, trim のドット形式追加は、Java/JS ユーザーの期待に応えます。length の両形式同時追加は、新規メソッドのモデルケースになります。」

今泉: 「length って、`$name.length()` と `length($name)` と、あと `$name.length` （括弧なし）もありますよね。Java だと String.length() はメソッドだけど、JavaScript だと string.length はプロパティですよ。」

  → **Gap 発見: `length` をメソッド（括弧あり）にするかプロパティ（括弧なし）にするか。`$name.length()` vs `$name.length`。PEG パーサー的には括弧なしのプロパティアクセスはメソッド呼び出しとは別の文法ルールが必要。**

リヴァイ: 「括弧あり。統一しろ。プロパティアクセスは新しい文法カテゴリを作ることになる。メソッド呼び出しの括弧なし版は許容するな。」

ヤン: 「賛成。`$name.length()` で統一。シンプルが正義。」

---

## Gap リスト

| # | Gap | 発見 Scene | 深刻度 | カテゴリ |
|---|-----|-----------|--------|---------|
| G-01 | ユーザーペルソナが未定義。Java/JS 系か SQL/Excel 系かでメンタルモデルが異なる | Scene 1 | 中 | 設計前提 |
| G-02 | 関数形式の戻り値に対するドットメソッド呼び出しの parse 可否が未定義 | Scene 1 | 高 | パーサー |
| G-03 | 本番で使われている式テンプレートのインベントリがない | Scene 2 | 高 | 運用 |
| G-04 | 既存の記法分離（述語=ドット、変換=関数）が意図的設計か偶然かが不明 | Scene 2 | 低 | 設計履歴 |
| G-05 | 新記法追加時の既存式 parse 回帰テストスイートが必要 | Scene 2 | 高 | テスト |
| G-06 | 完全直交にすると実装量2倍。直交性の不在はドキュメントコストを上げる | Scene 3 | 中 | 設計判断 |
| G-07 | 直交性レベルの選択肢と判断基準が未定義 | Scene 3 | 高 | 設計判断 |
| G-08 | ドットチェーンの型安全性をパーサーかEvaluatorのどちらで保証するか | Scene 4 | 中 | アーキテクチャ |
| G-09 | ドット記法のチェーン深度制限（1段 vs 任意深度） | Scene 4 | 高 | パーサー |
| G-10 | PEG での左再帰的ドットチェーンの反復構文書き換えと AST 構造 | Scene 4 | 中 | パーサー |
| G-11 | ドット形式と関数形式の @mapping 変換ルール（receiver の位置が異なる） | Scene 5 | 中 | パーサー |
| G-12 | GGP MapperGenerator が同一 AST クラスへの複数 @mapping をサポートするか未検証 | Scene 5 | 高 | ツール |
| G-13 | 新メソッド追加時のガイドライン（両形式を毎回作るか）が未定義 | Scene 6 | 中 | プロセス |
| G-14 | parse error 時の代替記法サジェスト機能がない | Scene 6 | 低 | UX |
| G-15 | PEG パーサーのエラーリカバリとサジェスト機能の実装難易度 | Scene 6 | 低 | パーサー |
| G-16 | endsWith の実装状況が未確認 | Scene 7 | 中 | 実装 |
| G-17 | `contains` と `in` の意味的区別が関数形式では曖昧になる | Scene 7 | 低 | API 設計 |
| G-18 | canonical/alias の区別をユーザーに見せるかどうか | Scene 8 | 中 | ドキュメント |
| G-19 | `length` をメソッド（括弧あり）にするかプロパティ（括弧なし）にするか | Scene 8 | 中 | API 設計 |

---

## Decision Record

| # | 決定事項 | 根拠 | Scene |
|---|---------|------|-------|
| D-01 | ドットチェーンは段数制限なし。StringChainable (String→String) と StringTerminal (String→Other) を文法で区別 | 左から型が確定するため ZeroOrMore で自然に実現可能。制限する方が不自然（今泉の指摘）。型安全はパーサーレベルで保証 | Scene 4 |
| D-02 | どちらの記法でも同じ AST を生成する | Evaluator を1つに保つ。パーサーだけ2通り | Scene 5 |
| D-03 | canonical は内部概念。ユーザーには両方を対等に提示する | ユーザー体験を損なわない | Scene 8 |
| D-04 | `length` は括弧ありメソッド形式 `$s.length()` で統一する | プロパティアクセスは新しい文法カテゴリが必要で複雑さが増す | Scene 8 |
| D-05 | 返り値型ルール: Boolean→ドット形式が自然、String/Number→関数形式が自然 | 既存の暗黙ルールを明文化 | Scene 3, 8 |
| D-06 | `in` の関数形式は Phase 3 送り | `contains` との混同リスクがあり、ドット形式で十分 | Scene 7 |
| D-07 | Phase 1 MVP: 既存ドット記法の関数形式追加ではなく、既存関数形式のドット形式追加を優先する | Java/JS ユーザーの期待に応える方がインパクト大 | Scene 8 |

---

## Observe → Suggest → Act

### O-S-A 1: ドット形式の追加（toUpperCase, toLowerCase, trim）

- **Observe**: 既存の toUpperCase, toLowerCase, trim は関数形式のみ。Java/JS 経験者は `$name.toUpperCase()` を期待する。
- **Suggest**: ドット形式を追加し、同じ AST（ToUpperCaseExpr 等）を生成するようにする。
- **Act**: 文法に `VariableRef '.' 'toUpperCase' '(' ')'` を追加。@mapping で ToUpperCaseExpr にマッピング。テストで関数形式と同じ結果になることを検証。

### O-S-A 2: length メソッドの新規追加（両形式同時）

- **Observe**: length メソッドが存在しない。文字列長の取得は基本機能。
- **Suggest**: 関数形式 `length($s)` とドット形式 `$s.length()` を同時に追加し、新メソッド追加のモデルケースとする。
- **Act**: LengthExpr AST クラスを定義。文法に2つのルールを追加。両方が LengthExpr を生成することを検証。

### O-S-A 3: 回帰テストスイートの構築

- **Observe**: 新記法追加時に既存式の parse が壊れないことを保証するテストがない。
- **Suggest**: 既存の全記法パターンを網羅した回帰テストスイートを作成する。
- **Act**: `StringMethodRegressionTest` を作成。既存の6パターン（startsWith, contains, in, toUpperCase, toLowerCase, trim）全てをテストケースとして含める。新記法追加前後で全テストが通ることを確認するフローを CI に追加。

### O-S-A 4: GGP MapperGenerator の複数 @mapping 対応確認

- **Observe**: 同一 AST クラスに対して2つの @mapping（ドット形式と関数形式）が必要だが、MapperGenerator がこれをサポートするか未検証。
- **Suggest**: 小さなテストケースで検証する。既存の AST クラスに対して2つの文法ルールから @mapping を設定し、生成されるコードを確認する。
- **Act**: テスト用の .ubnf ファイルを作成。同一 @mapping ターゲットに2ルールを設定。MapperGenerator を実行して出力を検査。

### O-S-A 5: 本番式テンプレートのインベントリ作成

- **Observe**: 本番環境でどの記法パターンが使われているか不明。影響範囲の判定ができない。
- **Suggest**: 本番の式テンプレートをエクスポートし、使用されている記法パターンを集計する。
- **Act**: 本番 DB から式テンプレートを抽出し、使用メソッド・記法パターンを分類。結果を回帰テストスイートの入力として使用。

---

## Action Items

| 優先度 | アクション | 依存 | 対応 Gap |
|--------|----------|------|---------|
| P0 | 本番式テンプレートのインベントリ作成 | なし | G-03 |
| P0 | 既存6パターンの回帰テストスイート構築 | なし | G-05 |
| P1 | GGP MapperGenerator の複数 @mapping サポート検証 | なし | G-12 |
| P1 | endsWith の実装状況確認 | なし | G-16 |
| P1 | ドット形式の文法ルール追加（toUpperCase, toLowerCase, trim） | G-12 の検証完了 | G-06, G-07 |
| P1 | length メソッド追加（関数形式 + ドット形式） | G-12 の検証完了 | G-19 |
| P2 | ドットチェーン1段制限の文法設計 | なし | G-09, G-10 |
| P2 | ドット形式と関数形式の @mapping 変換ルール定義 | G-12 の検証完了 | G-11 |
| P2 | 新メソッド追加ガイドライン文書化 | D-05, D-07 の確定 | G-13 |
| P3 | Phase 2 メソッド追加（endsWith, substring, replace） | Phase 1 完了 | — |
| P3 | parse error 時の代替記法サジェスト検討 | Phase 1 完了 | G-14, G-15 |
| P3 | ユーザーペルソナ調査 | なし | G-01 |
