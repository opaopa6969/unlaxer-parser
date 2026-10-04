# UBNF 学習シナリオ

公開先: [はじめての言語づくり](https://opaopa6969.github.io/unlaxer-parser/)

初めて言語を作る人向けに、説明・問い・文法編集・試験をひとつの流れにする教材です。
各ステップは前段の完成例を土台にした課題から始まり、自分の編集はステップごとに保持します。
問いと実習の両方に合格すると「次へ」が有効になります。目次からの移動は自由です。
解答例を表示しても合格にはなりません。文法・評価方針の変更は実習の合格を取り消し、再検証を要求します。

## 最初の教材

全8ステップ、目安60〜90分。数値の入口、1桁以上の数字、加減算、乗除算と優先順位、括弧と再帰、
変数、条件式、評価器の順です。TinyExpression 全体の再実装ではありません。

数値リテラルは非負の整数、識別子は `[a-z_][a-z0-9_]*`。計算には JavaScript の Number を使います。
`let name = expression;` を0回以上書いて、最後に式を置きます。`if(condition, yes, no)` は0を偽とし、
選ばれた枝だけを評価します。ゼロ除算・未定義変数・二重宣言・予約語（`if`, `let`）・非有限値を評価エラーにします。
`@whitespace: javaStyle` により空白・コメントを規則の境界で処理します。token 内の空白は許しません。
token 宣言は規則より前に書き、Java の生成クラス名と衝突しない名前（教材では `DIGITS` と `Number`）を使います。

## 教材を追加する

正本は `unlaxer-dsl/src/main/resources/learning/` です。

1. `tiny-expression.json` を参考に JSON を追加します。
2. `courses.json` に `{ "id": "your-course", "title": "表示名", "file": "your-course.json" }` を追加します。
3. 文法を Java / Rust / WASM の共通試験で検証し、ブラウザで完走します。
4. `node scripts/build-learning.mjs` で静的サイトへ取り込みます。

シナリオの `schemaVersion` は1、`id` は小文字英数字とハイフン、`version` は正整数です。
`title`、`description`、`duration` と `steps` を持ちます。各ステップの契約は以下です。

| 項目 | 内容 |
| --- | --- |
| `id`, `title`, `subtitle` | 安定した ID と見出し |
| `explanation` | 説明の段落配列（HTML は実行せず文字として表示） |
| `question` | `prompt`、`options: [{text, feedback}]`、正解の0始まり添字 `answer` |
| `task`, `hint` | 編集課題とヒント |
| `starter`, `solution` | 初期文法と解答例の全文 |
| `sample` | 自由入力欄の初期値 |
| `cases` | `{input, accept, value}` または `{input, accept: true, evaluationError}`。拒否なら `{input, accept: false}` |
| `semanticsExercise` | 条件分岐の評価方法を学習者が選ぶ課題なら `true` |

構文の受理に加え、数値または評価エラーを照合します。実行エラー・AST 投影エラーは拒否の正解にしません。
この初版の評価器は教材の `Number / Sum / Product / Variable / Binding / Program / Conditional` AST 契約専用です。
別の意味を持つ言語の教材を追加するときは、`evaluator.js` の固定インタープリタと試験も拡張します。
評価器のソースや任意の JavaScript を JSON に入れて実行する仕組みではありません。

進捗は `ubnf-learning:<id>:v<version>` の localStorage へ保存します。
課題・採点・評価器の意味を変更するときは教材の `version` を上げ、古い合格状態を再利用させないでください。
読み込み失敗・保存拒否・容量不足でも画面は使えますが、保存できないことを表示します。
保存先はこのブラウザだけで、端末間の同期やサーバーへの送信はありません。

## 学習エンジンと対応範囲

`rust/unlaxer-learning` は既存の `unlaxer-ubnf` / `unlaxer-generator::lowering` / `unlaxer-runtime` を使います。
正規化した IR を runtime の式へ変換し、CST の capture から AST を投影します。対応範囲外は拒否します。
文法から Rust のソースコードを生成してブラウザで rustc を起動する方式ではありません。

| 機能 | 学習画面 | Java / Rust の CLI |
| --- | --- | --- |
| リテラル・宣言的 token・参照・選択・optional・repeat・capture | 対応 | 対応 |
| text / node の mapping、javaStyle 空白、ソース位置 | 対応 | 対応 |
| import、host binding、状態付きルール、mixed-value capture、高度な選択・述語 | 対象外として拒否 | 各 backend の対応範囲に従う |
| 教材 AST の計算 | 固定の学習用評価器 | 生成した AST にアプリ側の評価器を実装 |

文法16 KiB、入力8 KiB、1回5秒、結果4 MiB、WASM メモリ256 MiB。
各操作で新しい Worker / WASM instance を作り、終わったら破棄します。
runtime の `&'static str` 用に確保した文法文字列の寿命も instance 内に限定します。
`LessonGrammar` はこの使い捨て instance 用 adapter で、長寿命の native server 向け API ではありません。
タイムアウト・trap・容量超過は構文不一致と区別し、再試行できます。編集中の古い結果は合格判定に使いません。

## ローカル検証

Rust 1.85+、`wasm32-unknown-unknown`、Node 20+、Java 21 を使用します。

```sh
node scripts/build-learning.mjs
python3 -m http.server 8080 --bind 127.0.0.1 --directory build/learning-site
# http://127.0.0.1:8080/ を開く
```

出力先は `build/learning-site/`。教材と Help、および完成文法から生成した通常の playground を同梱します。
WASM は依存 crate をローカルから offline build します。ブラウザ実行時に CDN は使いません。

```sh
cargo test --locked --manifest-path rust/Cargo.toml -p unlaxer-learning
mvn -pl unlaxer-common,unlaxer-dsl -am test \
  -Dtest=PlaygroundCommandTest,DeclarativeTokenConformanceTest \
  -DrustConformance=true -Dsurefire.failIfNoSpecifiedTests=false
cd unlaxer-dsl/ubnf-vscode
npm ci
npx playwright install chromium
node scripts/learning-browser.mjs
```

`DeclarativeTokenConformanceTest#learningScenariosAgreeInJavaRustAndBrowserWasm` は教材の全51入力を使い、
Java / native Rust の生成 parser と学習用 WASM の受理・拒否、消費/一致位置、AST 全体と Unicode span を比較します。
ブラウザ試験は全問の初期状態での失敗と解答での合格、計算結果、エラー、再開、文法保存、変更時の合格取消、
古い結果の破棄、保存拒否、タイムアウトからの再試行、HTML の無害化、狭い画面、完成例の playground を確認します。

## ホスティング

GitHub Pages の公開元を GitHub Actions に設定します。`learning-pages.yml` が master の変更から静的サイトを作り、
ブラウザ試験に通った成果物だけを `github-pages` environment へ deploy します。`workflow_dispatch` で再公開もできます。
ビルド・ブラウザ試験は既存の self-hosted runner、Pages への配置は標準の GitHub-hosted runner を使います。
ローカル runner の DNS 待ちが Pages action の接続制限を超えたため、配置処理の実行環境を分けています。
公開リポジトリの標準 hosted runner は [無料枠の対象](https://docs.github.com/en/actions/reference/runners/github-hosted-runners) です。
公開対象は `build/learning-site/` だけです。学習者の文法・入力はアップロードしません。
手順は [GitHub のカスタム workflow](https://docs.github.com/en/pages/getting-started-with-github-pages/using-custom-workflows-with-github-pages) に従います。
内容を戻す場合は該当 PR を revert して workflow を再実行します。
