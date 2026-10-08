# 明示した UBNF package 依存と標準空白

追跡: #401、親 #368。UBNF v2 の token と空白・コメント定義を、同梱またはローカルの
版付き artifact から import できる。package の import 先を grammar 自体に埋め込まず、
grammar と同じディレクトリの `ubnf.json` で宣言する。親ディレクトリや個人設定を
暗黙に探索しない。既存の相対 `.ubnf` import と package 名の探索順も作らない。

```json
{
  "schemaVersion": 1,
  "dependencies": {
    "std/layout": {"version": "1.0.0", "source": "builtin:std/layout@1.0.0"},
    "team/layout": {"version": "1.0.0", "source": "local:packages/team-layout.json"}
  }
}
```

```ubnf
grammar Example {
  @import layout from 'pkg:std/layout'
  @ubnf: v2
  @whitespace: layout.SPACES_AND_COMMENTS
  @root @mapping(Root, params=[value])
  Root ::= 'a' @value 'b';
}
```

依存の取得・更新は、Java CLI と native Rust CLI の両方で次の明示コマンドを実行する。

```sh
unlaxer deps resolve --manifest ubnf.json
```

これにより `ubnf.lock.json` と `.ubnf-cache/packages/<sha256>.json` を保存する。
manifest と lock はプロジェクトで版管理する。cache はローカルの生成物として扱い、
private package を含む cache を公開する操作は行わない。parse・生成・check・Playground
生成は lock と cache の検証・読み込みだけを行い、自動取得しない。欠落時は上の
コマンドを促す。source を変更しても lock の版と名前が一致する間は固定された
artifact を使い続け、更新には明示 resolve が必要になる。

lock は canonical ID、exact version、raw artifact bytes の SHA-256、推移的依存を
保存する。同じ ID の版または hash の競合、循環、stale lock、欠落 cache、hash の
不一致、lock と artifact の ID / 版 / 依存の不一致を拒否する。要求版の初版は exact
`major.minor.patch`（任意 prerelease 付き）だけを扱い、浮動 branch や latest を扱わない。

## artifact の形式

archive を展開する代わりに、明示した UBNF ファイル群を JSON envelope に保存する。

```json
{
  "schemaVersion": 1,
  "id": "team/layout",
  "version": "1.0.0",
  "entry": "layout.ubnf",
  "files": {"layout.ubnf": "grammar Layout { @ubnf: v2 token GAP ::= ' ' | '#'; }"},
  "dependencies": {}
}
```

entry は含まれる `.ubnf` ファイルでなければならない。ファイル名に絶対パス、`.`、
`..`、空 path segment、backslash、colon を許さない。package 内の相対 import は
package のファイル群で解決し、境界外へ抜ける参照を拒否する。別 package は自身の
`dependencies` で宣言した ID だけを参照できる。ローカルの推移的 artifact source は、
その依存を宣言した artifact のディレクトリから解決する。

共有する module の entry は従来どおり declarative token だけを持ち、rule / evaluator /
host parser code を import できない。自動 skip の対象は non-nullable な lexical program
でなければならない。上限は artifact 8 MiB、artifact 内 128 ファイル、package graph
128 package / 64 MiB、依存深さ64。生成時は解決した cache のファイルを仮想 source と
して読むため、任意パスへファイルを展開しない。

## std/layout 1.0.0

[同梱 artifact](../unlaxer-dsl/src/main/resources/ubnf-packages/std-layout-1.0.0.json) が
具体的な定義を持つ。空白は ASCII space と TAB / LF / VT / FF / CR、行コメントは
`//` から CR / LF の直前まで、block comment は `/*` から最初の `*/` まで。
未閉鎖 block comment は trivia として成功しない。

export は `SPACES`、`LINE_COMMENT`、`BLOCK_COMMENT`、`SPACES_AND_COMMENTS`。
従来の javaStyle 11文法・24入力を明示 import に置き換えた共通 fixture で、受理/拒否、
consumed/matched cursor、AST 全 field/node span と生成物を比較する。

Java / native Rust の lock bytes と生成物の比較、ローカル・推移的 import、17 の共通
拒否条件、HTTP 接続を監視したオフライン生成を conformance test で検証する。結果は
`target/rust-packages.tsv` と既存 `target/rust-rule-trivia.tsv` に保存し、CI が必須にする。

## 明示した HTTPS 取得と認証

`source` に固定した `https://host/path` を指定できる。ASCII DNS host を使い、HTTP、
userinfo、query、fragment と redirect は拒否する。TLS の証明書と hostname を検証し、
接続5秒・本文を含む全体20秒・artifact 8 MiB の上限を Java/native Rust で適用する。
リモート artifact の推移的依存に `local:` を指定できない。取得後は同じ ID/版/hash
検証を行い、生成時は固定 cache を使う。生成/check/Playground が取得や認証を行うことはない。

認証は `UBNF_CREDENTIALS_FILE`、未指定時は `~/.config/unlaxer/credentials.json` の
ユーザー/CI 設定だけから読む。プロジェクトの grammar・manifest・lock に token を
書かない。credential の key は `https://` + lower-case host + 非443 port の exact origin。
別 origin へ認証を送り直すことはなく、失敗ログに credential や server body を出さない。

```json
{
  "https://packages.example": {"bearerToken": "user-provided-token"}
}
```

必要な場合は同じ entry に `caCertificate`（ユーザー/CI が管理する PEM CA bundle の
絶対パス）を追加できる。明示 CA の trust store を使って TLS/hostname 検証を続ける。
credential / CA 設定ファイルの上限は64 KiB。token は printable bearer 文字に限定する。
設定ファイルと取得済み private artifact はユーザーが管理し、公開操作は行わない。

`HttpsPackageConformanceTest` はローカル TLS server を使い、認証、lock/hash と生成物、
未信頼 TLS/hostname、別 origin、redirect、容量、本文 timeout、remote→local 拒否、
server 停止後のオフライン生成を両 CLI で照合する。CI は `rust-package-https.tsv` を保存する。

出典/版を見せる補完・hover/定義表示、TinyExpression 移行、
名前付き trivia の tokenStream 対応は親 #368 の未完了項目。この段階で親を close しない。
