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
でなければならない。manifest / lock / artifact は UTF-8 byte 数で各 8 MiB まで
（ちょうどは受理、1 byte 超過は拒否）。artifact 内 128 ファイル、package graph
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

Java / native Rust の lock bytes と生成物の比較、ローカル・推移的 import、18 の共通
拒否条件、HTTP 接続を監視したオフライン生成を conformance test で検証する。結果は
`target/rust-packages.tsv` と既存 `target/rust-rule-trivia.tsv` に保存し、CI が必須にする。

## 明示した HTTPS 取得と認証

`source` に固定した `https://host/path` を指定できる。ASCII DNS host を使い、HTTP、
userinfo、query、fragment と redirect は拒否する。TLS の証明書と hostname を検証し、
接続5秒・本文を含む全体20秒・artifact 8 MiB の上限を Java/native Rust で適用する。
リモート artifact の推移的依存に `local:` を指定できない。取得後は同じ ID/版/hash
検証を行い、生成時は固定 cache を使う。生成/check/Playground が取得や認証を行うことはない。

HTTPS client と TLS は native target のみに含める。ブラウザー向け WASM での依存取得は
明示エラーとし、native `deps resolve` で準備した package の明示ロードを利用する。

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

## 定義の出典を確認する

`unlaxer deps inspect --grammar file.ubnf` は、同じ module loader と cache の hash 検証を
経て、import alias・source・定義の Unicode scalar 範囲と package の ID/版/hash/file を
JSON で返す。ネットワークや credential を読み込まず、外部ファイルを書かない。
Java/native Rust の JSON は同じ byte 列になる。package identity を公開する resolver API
も、実際の cache artifact を hash 検証してから値を返す。

UBNF LSP は global/rule の whitespace 指定で宣言的 token を補完し、hover に出典・固定版・
hash を表示する。package の定義へ移動すると、読み取り専用 `ubnf-package:` 文書を開く。
文書内容を返す `ubnf/packageSource` request は import の index が検証して保持する source
だけを返し、任意のパスや未知の hash を読み込まない。外部定義は rename しない。

Java/native Rust が生成する language Playground は同じ `vocabulary.json` snapshot を含み、
空白設定と import 定義の出典/版/hash を表示する。定義範囲を開くと元の UBNF を確認できる。
単一文書 authoring Playground は local named token の whitespace 補完も扱う。外部 module
の取得は authoring UI では行わず、プロジェクトの明示 resolver で用意する。

名前付きglobal triviaはtokenStreamの4modeでも対応する（#432）。共通fixtureはstd/layoutをlock/cacheからオフラインロードして検証する。TinyExpression移行の完了確認は親 #368 で追跡する。

## local import の読取り境界（#890）

Java `UBNFPackageResolver.readLocalSource` と Rust `packages::Resolver::read` の disk 経路は、
相対・絶対パスとも symlink をたどった metadata が通常ファイルであることを open 前に確認する。
デバイス（`/dev/stdin`・`/dev/zero` 等）、FIFO、socket、directory は読まず、
`E-MODULE: local import is not a regular file` を返す。

上限は **1ファイル64 MiB（67,108,864バイト）**。既存の package graph 全体の byte budget と同じ値にし、
有限の読取りを保証しつつ、通常の文法ファイルに十分な余裕を持たせる。package artifact 単体の8 MiB制限や
lock/hash/依存/境界検証は変更しない。metadata の size が上限を超える場合は open せず、
`E-MODULE: local import exceeds 64 MiB` を返す。上限ちょうどは受理する。
stat と read の間にファイルが増大・差替えされても、reader は最大 **上限＋1バイト** しか読み取らず、
余分な1バイトを検出したら同じ過大サイズ診断で拒否する。UTF-8 の不正バイトは従来どおり拒否する。
Rust は `O_NONBLOCK` で open し、開いた handle の fstat で通常ファイル・サイズを再確認する。stat の後に FIFO へ差し替えられても
open で止まらず、同じ診断で拒否する。Java は開いた `FileChannel` の `position()` と `size()` を確認し、
writer のある FIFO と過大な通常ファイルへの差替えを読取り前に拒否する。open が失敗したとき、path に socket 等の
非通常ファイルが見えれば同じ対象別の非通常ファイル診断にする。完全な型判定と writer のない FIFO の競合は未解決
（[#907](https://github.com/opaopa6969/unlaxer-parser/issues/907)、[#911](https://github.com/opaopa6969/unlaxer-parser/issues/911)）。
この byte 上限はファイル内容全体の読取り制限であり、文法の解析時間や全import合計の上限ではない。

Java の既存 `SourceReader` 注入経路と Rust の open-buffer overlay は、呼出側が既に持つ仮想/未保存sourceを
引き続き利用できる。Java の disk callback には `UBNFPackageResolver::readLocalSource` を使う。
editor は未保存の local buffer を優先し、package の検証済みsourceを buffer で置換しない。
CLI と authoring server は同じ disk reader を使い、新しい拒否も既存の `E-MODULE` 表示/位置契約を通す。

## manifest / lock の disk 読取り（#908、残り #911）

両版は `ubnf.json` / `ubnf.lock.json` にも local import と同じ bounded reader を使う。
上限は **各8 MiB（8,388,608バイト）**。既存 JSON document の受理上限をそのまま使い、
64 MiB の graph budget まで読んでから拒否する無駄な確保を避ける。symlink の通常ファイル実体は受理する。
path の型・サイズを open 前に検査し、Rust は `O_NONBLOCK` + 開いた handle の fstat、
Java は開いた channel の seek 可否・サイズを再検査する。読取りは上限＋1バイトで打ち切る。
上限ちょうどの UTF-8 JSON は従来どおり受理する。明示 `deps resolve` の manifest も同じ reader を使う。

検査順は **lock の読取り → manifest の読取り・JSON/schema/dependencies検証 → lock のJSON/schema/roots/audit検証**。
両方欠落している場合も既存 Java の missing lock を維持する。新しい I/O 診断は次のとおり両版で共通とする。
`{subject}` は `manifest` または `lock`。位置は既存 import 宣言全体の UTF-16 range、severity は 1。
stdio では code `E-MODULE`、source `ubnf-module`、message `import <alias>: <以下の文言>` を返す。
CLI も以下の同じ原因文字列を表示する（既存の CLI wrapper と終了コードは変更しない）。

| 条件 | 文言 |
| --- | --- |
| 非通常ファイル | `E-PACKAGE: {subject} is not a regular file` |
| 上限超過 | `E-PACKAGE: {subject} exceeds 8 MiB` |
| 欠落 | `E-PACKAGE: missing {subject}; run unlaxer deps resolve --manifest ubnf.json` |
| その他の I/O 失敗 | `E-PACKAGE: cannot read {subject}` |

Java の `SourceReader` と `FileSystemPort` には `readPackage` の既定メソッドを追加した。
既存のカスタム callback は引き続き呼ばれ、disk 実装だけが bounded reader を明示する。
注入された文字列の UTF-8 byte上限も確認するが、信頼された独自 callback 内の I/O は呼出側の責任である。
editor の未保存 buffer は local source にだけ適用し、manifest/lock の disk 読取りを置換しない。

共通 `spec-corpus/ubnf-authoring-lsp/package-documents.json` の25件を、両実 stdio server と
Java-host Rust / native Rust / Java flag CLI に送る。FIFO、device/directory/過大file と symlink、
欠落、上限ちょうど、有効fileへの symlink、複数異常時の順序を検査し、全診断 JSON と後続 completion/hover を照合する。
manifest の拒否は両 `deps resolve` CLI でも検査する。既存 package corpus の期待値は変更しない。

### Java の競合に残る制限と採用しなかった方式

[#908](https://github.com/opaopa6969/unlaxer-parser/issues/908) の「開いた handle が通常ファイルであること」の
完全な保証は Java では未達である。この PR の範囲は上記の通常時の安全化と handle の seek/size 検査まで。
[#907](https://github.com/opaopa6969/unlaxer-parser/issues/907) と新しい
[#911](https://github.com/opaopa6969/unlaxer-parser/issues/911) に残りの受け入れ条件を記録し、#908 も Refs のまま残す。

JDK17 の [StandardOpenOption](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/nio/file/StandardOpenOption.html)
には nonblocking open がなく、[FileChannel](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/nio/channels/FileChannel.html)
は file type の metadata API を提供しない。JDK17/21 の実測で、writer なし FIFO の open は別threadを interrupt しても
戻らず、writer を接続して初めて解放された。`/dev/zero` は `position() == 0` / `size() == 0` なので seek検査だけでは
通常ファイルと区別できない。stat後にこれらへ差し替える競合を、今回の実装が完全に防ぐとは主張しない。

- open/read を有限時間の worker へ移して timeout 後に放棄するだけでは、停止した thread / handle が蓄積する。
  pool を制限すると、攻撃後に正常fileまで読めなくなる。安全で決定的な解決としては採用しない。
- `/proc/self/fd/<fd>` の検査には、保持している正確な fd の取得が必要になる。
  公開 `FileDescriptor` API は番号を公開せず、private field へのアクセスには追加の module開放等が必要。
  fd一覧の差分・pathの再statは、並行open/closeや再差替えで別の競合を作るため代用しない。
- JDK更新、native bridge、OS別の隔離方式は runtime/配布への影響を伴う別単位として #911 で検証する。
  既存 Java の受理集合・注入経路を削ったり parser のホットパスへ処理を追加したりして解決しない。
