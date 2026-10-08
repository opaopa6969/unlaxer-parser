UBNF generated playground
=========================

1. Rust >= 1.85 と Node >= 20 を用意します。
2. rustup target add wasm32-unknown-unknown
3. npm run build
4. npm start
5. 表示された http://127.0.0.1:.../ を開きます。

npm install は不要です。parser と Rust runtime source を同梱しています。
初回の toolchain/target 準備以外、build は --offline で行います。
公開したい場合は public/ 全体を静的ホストに置けます（この生成コマンドは公開しません）。
serve.mjs はローカル試験専用です。127.0.0.1 にのみ bind します。

文法を書く人向け: 画面上の「UBNF の書き方」から入門、構文 catalog、修正方法を開けます。
生成した言語を使う人向け: 「この言語の catalog」に @doc の説明を表示します。
入力例は @doc の指示や、元の文法の仕様に従ってください。

文法は public/grammar.ubnf にスナップショットを記録します。編集しても既存 WASM は変わりません。
元の .ubnf を変更したら生成コマンドを新しい出力ディレクトリに実行し、再 build します。
import は生成時に解決済みです。元の import ファイルも元の場所に保持してください。

対応範囲: 宣言的 token と Rust backend が対応するルール・AST mapping。
通常は @root から 1 つの mapped AST node になる文法が必要です。
既存 Java parser class binding、任意の host code、未対応 annotation は生成時に拒否します。
この playground は認識・CST・AST を確認するものです。計算や外部コードの実行はしません。

位置は Unicode code point、先頭を 0、範囲は [start,end) です。
1 回の入力は最大 64 KiB (UTF-8)、Worker は 3 秒で打ち切ります。
打ち切りや WASM trap は文法の不一致とは区別して画面に表示します。
ブラウザでは生成した Rust parser を実行します。Java parser の実行環境ではありません。
Java / native Rust の両 compiler が同じ project を生成する契約を共通 fixture で検証しています。

runtime/LICENSE: vendored unlaxer-runtime のライセンス。
