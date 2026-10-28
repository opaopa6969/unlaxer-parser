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
| 不正な UTF-8 | `E-PACKAGE: invalid JSON`（I/O 失敗ではなく内容の誤り。Rust の byte JSON parser と同じ） |

Java の `SourceReader` と `FileSystemPort` には `readPackage` の既定メソッドを追加した。
既存のカスタム callback は引き続き呼ばれ、disk 実装だけが bounded reader を明示する。
注入された文字列の UTF-8 byte上限も確認するが、信頼された独自 callback 内の I/O は呼出側の責任である。
editor の未保存 buffer は local source にだけ適用し、manifest/lock の disk 読取りを置換しない。

共通 `spec-corpus/ubnf-authoring-lsp/package-documents.json` の27件を、両実 stdio server と
Java-host Rust / native Rust / Java flag CLI に送る。FIFO、device/directory/過大file と symlink、
欠落、不正な UTF-8、上限ちょうど、有効fileへの symlink、複数異常時の順序を検査し、全診断 JSON と後続 completion/hover を照合する。
manifest の拒否は両 `deps resolve` CLI でも検査する。既存 package corpus の期待値は変更しない。

lock が指す cache artifact と明示 `deps resolve` の `local:` 依存も、下記 #913 の規則で同じ reader を使う。
先頭 BOM は下記 #914 / #926 の規則で、Java の既存動作と同じく1つだけ無視する。
不正 UTF-8 artifact の内容診断は、下記 #919 の規則で Java の既存文言に統一する。

### Java の競合に残る制限と採用しなかった方式

[#908](https://github.com/opaopa6969/unlaxer-parser/issues/908) は、競合のない通常時の受入条件（両版の
通常ファイル確認・上限付き読取り・共通診断・検査順・共通 fixture）をこの変更で満たしたので閉じる。
Java で stat の後・open の前後に path を差し替える競合（TOCTOU）は local import と同じ
[#907](https://github.com/opaopa6969/unlaxer-parser/issues/907) の範囲であり、manifest/lock の分も含めて
[#911](https://github.com/opaopa6969/unlaxer-parser/issues/911) に受け入れ条件を記録した。

Java の開いた handle の再検査（`position()` / `size()`）は、競合時に次を **防ぐ**。

- writer のある FIFO・pipe への差替え: `position()` が失敗するので、読まずに非通常ファイル診断にする。
- socket への差替え: open が失敗し、path に非通常ファイルが見えれば非通常ファイル診断にする。
- 過大な通常ファイルへの差替え・stat 後の増大: handle の size、または上限＋1の打切りで過大診断にする。

次は **防げない**（Rust は fstat で同じ非通常ファイル診断にする。Java の結果は実測）。

- writer のない FIFO への差替え: open 自体が writer を待ってブロックする。
- seek できる文字デバイスへの差替え: `/dev/zero` は上限＋1バイトで打ち切られて過大診断になる（停止や無制限確保はしない）。
  `/dev/null` は空の内容として読まれ、その後の JSON 検証で拒否される（local import では空の文法として読まれる）。
- directory への差替え: open は成功し、read の `EISDIR` で `cannot read {subject}`（local import では I/O エラー文）になる。

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


## cache / local artifact の disk 読取り（#913）

cache の `.ubnf-cache/packages/<sha256>.json` と、明示 `deps resolve` の `local:` 依存 artifact は、
manifest/lock/local import と同じ reader を共用する。上限は既存定数の **8 MiB（8,388,608バイト）**。
path の通常ファイル・サイズ確認 → open → 開いた handle の再検査 → 上限＋1バイトの順で読み、
ちょうどのサイズと通常ファイルへの symlink は受理する。Rust は既存 `read_source_with` の
`O_NONBLOCK` + fstat、Java は既存 reader の `FileChannel.position()` / `size()` を使う。
Java の reader 内部は raw bytes を返し、文字列を必要とする既存経路だけで strict UTF-8 に復号する。
artifact は復号・再エンコードせず、元の bytes で SHA-256 を検証する。

| 条件 | cache artifact | 明示 `local:` artifact |
| --- | --- | --- |
| 非通常ファイル | `E-PACKAGE: missing artifact; run unlaxer deps resolve --manifest ubnf.json` | `E-PACKAGE: artifact is not a regular file` |
| 欠落・dangling symlink | `E-PACKAGE: missing artifact; run unlaxer deps resolve --manifest ubnf.json` | `E-PACKAGE: missing local artifact` |
| 8 MiB 超過 | `E-PACKAGE: artifact exceeds 8 MiB` | 同左 |
| その他の I/O 失敗 | `E-PACKAGE: cannot read artifact` | `E-PACKAGE: cannot read local artifact` |

cache の非通常ファイルの文言は **既存 Java の表示と既存 corpus の期待値を維持するため** missing artifact のままにした。
過大・読取り失敗は #912 と同じ対象別の文言を使い、Rust の従来の一律 missing artifact への変換を外した。
明示 `local:` の非通常ファイルは原因を示す文言とした。`local:` の欠落・読取り失敗は **Rust の従来の文言**
（`missing local artifact` / `cannot read local artifact`）を維持し、Java もこれに揃えた。
`deps resolve` の実行中に「`deps resolve` を実行せよ」と案内しても直らないため、cache の再 resolve 案内は使わない
（Java の従来は `NoSuchFileException` 等の例外文をそのまま表示していた）。
reader に渡る前の `local:` パスの組み立てと不正パスの診断は、下記 #921 の規則を両版で使う。
同じ経路・入力に対する message/code/severity/range と検査順は両版で共通。
stdio は code `E-MODULE`、severity `1`、source `ubnf-module`、message `import <alias>: E-PACKAGE: ...`、
import 宣言全体の UTF-16 range。CLI は既存 wrapper・終了コードを維持して同じ原因文字列を表示する。

検査順は lock I/O → manifest 検証 → lock JSON/audit → artifact I/O → graph byte budget →
raw hash → strict UTF-8 → artifact JSON/identity/dependencies → 推移的 artifact の検証。
hash 不一致は UTF-8 / JSON 不正より先に報告する。
明示 resolve は manifest → source の読取り → artifact 内容/identity → 推移的依存の順で検証し、
全依存の成功後にのみ lock/cache を書く。通常の生成・server は manifest に危険な `local:` source があっても
その source を取得せず、検証済みの固定 cache を読む。既存の SourceReader 注入・未保存 buffer の経路は維持する。

共通 `spec-corpus/ubnf-authoring-lsp/package-artifacts.json` の **39件**を両実 stdio server と
Java-host Rust / native Rust / Java flag CLI に送り、全診断JSON、診断後の completion と close 後の hover を確認する。
FIFO、`/dev/zero` symlink、directory、過大、欠落、symlink loop、8 MiB ちょうど、raw hash と検査順を含む。
`local:` は両 `deps resolve` にも送り、推移的依存の異常と失敗時の lock/cache 不変、成功時の raw bytes/hash/lock 一致を確認する。
単体テストは opener seam で stat 後の増大・handle 差替えを再現し、mutation を有限 deadline で検出する。
既存 `PackageResolverConformanceTest` / `package-documents.json` の期待値と parser のホットパスは変更しない。

Java の writer なし FIFO への差替えで open が止まる競合、正確な handle 型検査の制限は
上記と同じく **Refs #911**。artifact でも同じ reader を使うため同じ制限があり、今回その競合を解決したとは扱わない。


## artifact の不正 UTF-8 診断（#919）

cache と明示 `local:` artifact は、両版とも **`E-PACKAGE: invalid UTF-8 artifact`** で拒否する。
Java の既存 strict decoder と利用者に見える文言を参照とし、Rust は `serde_json` に渡す前に
`std::str::from_utf8` で全 bytes を検査する。Java の既存経路を維持する最小の変更として、
Java 本体は変更せず、両言語に同じ fixture の回帰テストを追加した。置換文字での復号はしない。
8 MiB 上限 →（cache は graph budget と raw hash）→ strict UTF-8 → JSON の順とし、
不正 bytes の hash が lock と一致する場合だけ UTF-8 診断へ進む。先に JSON の構文エラーが現れる
bytes でも、全体の UTF-8 検査が優先する。UTF-8 として有効な不正 JSON は従来の `invalid JSON` のまま。
manifest/lock の不正 UTF-8 診断、先頭 BOM の扱い（#914）、`local:` のパス解決（#921）は変更しない。

共通 `spec-corpus/ubnf-authoring-lsp/package-artifact-utf8.json` の **29件**に raw bytes を16進表記で保存する。
不正な開始バイト、途切れた多バイト列、JSON 文字列内の不正バイト、過長符号化、サロゲート、
Unicode 上限超過を cache（hash 一致 / 不一致）と明示 local に配置する。JSON 不正との優先順も含む。
レビューで足した8件は、3バイトの過長符号化（`E0 80 80`）、`F5` 以上の開始バイト、
先頭 BOM の後ろの不正バイト（BOM を除く処理を足しても UTF-8 診断が先に出ること）、
有効な JSON の直後でファイル末尾が途切れる多バイト列を、cache（hash 一致）と明示 local に置く。
いずれも Java の strict decoder（`CodingErrorAction.REPORT`）と `std::str::from_utf8` の判定が一致することを実測した。
両実 stdio server の message/code/severity/UTF-16 range 全体と後続 completion/hover、
Java-host Rust / native Rust / Java flag CLI の最終診断行、両 `deps resolve` の拒否と lock/cache 不変を照合する。
通常の生成・server は local source を取得せず正常な固定 cache を利用し、明示 resolve だけが local を拒否する。
既存の package corpus と #913 の reader の条件は維持し、parser のホットパスや公開済み 3.3.0 には手を加えない。


## 明示 `local:` のパス解決（#921）

Java の既存規則を契約として維持し、Rust の `deps resolve` も同じ字句的正規化を行う。
manifest は呼出時の作業ディレクトリから絶対パスにして正規化し、その親を基準ディレクトリにする。
`local:` の値は **`directory.resolve(relative).normalize()`** と同じ規則で、`.`、`..`、
重複する区切りと末尾 `/` をファイルシステムへ問い合わせる前に畳む。絶対パスも引き続き使える。
symlink の実体パスへの変換（`toRealPath` / `canonicalize`）は行わない。

| source の例 | 結果（`artifact.json` は通常ファイル） |
| --- | --- |
| `local:nodir/../artifact.json`（nodir は不存在） | `artifact.json` を読む |
| `local:artifact.json/..` | 親ディレクトリを読み、`E-PACKAGE: artifact is not a regular file` |
| `local:artifact.json/` | `artifact.json` を読む |
| NUL を含む値 | `E-PACKAGE: cannot read local artifact` |
| `local:a/link/../x.json`（link は symlink） | 字句的に `a/x.json` を選ぶ |

JSON として受理した source の不正なパスは、正規化より先に拒否する。NUL が `..` で取り除かれる成分に含まれる場合も同じ文言にする。
Java の `InvalidPathException` の本文や入力パスをそのまま公開せず、既存の local I/O 失敗の文言を使う。
新しい診断を増やさず、既存 Java の受理集合とディレクトリに対する診断を維持するための選択である。
`local:` が空なら基準ディレクトリを指し、同じ非通常ファイル診断になる。

推移的依存の基準は、正規化した **artifact の絶対パスの親**（Java の
`path.toAbsolutePath().getParent()`）。artifact 自体が symlink でもリンクを置いた側を基準とし、
リンク先の親へ切り替えない。残った symlink をたどって通常ファイルを読む経路は従来どおり維持する。
Java の既存経路を狭めず、OS が `..` を解釈する前に対象を決めることで、同じ入力の取得先を両版で揃える。

共通 `spec-corpus/package-local-paths.json` の30件を `PackageLocalPathConformanceTest` が
Java / native Rust の実 `deps resolve` CLI に送る。表の4形、symlink の前後、推移的依存、
相対・絶対 manifest とその正規化、末尾 `/.`、ファイルシステムの root を越える `..`、
`/` 単独、symlink ディレクトリの `/.`、区切りではない `\` を含む。lock/cache の有無の両方で計120回実行し、
成功時は固定した期待値と lock/cache の bytes、失敗時は同じ診断と lock/cache 不変を照合する。
symlink の物理的解決先には異なる bytes の有効 artifact を置き、誤った取得先での成功も検出する。
CI の `cross-language` 契約で両テストメソッドの実行を必須にする。

この正規化は明示 resolve に限る。#913 の bounded reader、#919 の UTF-8 判定、固定 cache の
読取り、既存 package corpus の期待値、parser のホットパスは変更しない。

### package JSON の受理境界（#914 / #926、残り #939）

Java（Gson 2.13.2）を参照とし、Java 本体・既存の注入経路は変更しない。
manifest / lock / artifact の**先頭にある UTF-8 BOM を1つだけ** JSON 解析直前に読み飛ばす。
[RFC 8259 §8.1](https://www.rfc-editor.org/rfc/rfc8259#section-8.1) は解析側の BOM 無視を許容するため、
既存 Java の受理集合を維持するこの規則を選ぶ。生成する lock に BOM は付けない。
artifact の raw bytes は一切変換せず、BOM も含む SHA-256 を lock と cache 名に使用する。
#920 の reader、8 MiB 上限、#924 の **raw hash → strict UTF-8 → JSON**、#927/#933 のパス規則は維持する。

変更前の Java の実 CLI による測定と共通 fixture の結果（3種類の document で共通）:

| 入力 | Java / Rust の結果 |
| --- | --- |
| BOM 1つ + 有効 JSON | 受理 |
| BOM 1つ + JSON 空白 + 有効 JSON | 受理 |
| BOM 2つ + 有効 JSON | `E-PACKAGE: invalid JSON` |
| BOM だけ / BOM + JSON 空白だけ | `E-PACKAGE: expected object: document` |
| BOM + 不正 JSON | `E-PACKAGE: invalid JSON` |
| 空白の後の BOM / JSON token 間の U+FEFF | `E-PACKAGE: invalid JSON` |
| JSON 文字列内の U+FEFF | 文字列の一部として保持 |
| BOM + 不正 UTF-8 | manifest/lock は `invalid JSON`、artifact は `invalid UTF-8 artifact` |
| cache の raw hash 不一致 | BOM・UTF-8・JSON の検証より先に `artifact hash mismatch: <id>` |

BOM のない空文書・空白だけの文書と非 object の top-level（Java は前者が `expected object: document`、後者が `invalid JSON` で、Rust は逆）、
serde_json の再帰上限を超える深い入れ子は、BOM とは別の既存差として
[#941](https://github.com/opaopa6969/unlaxer-parser/issues/941) で扱う。
object の検査順は下記 #942 のとおり Java の記述順に揃える。
HTTPS の credentials ファイルの BOM・単独サロゲート・不正 JSON の差は [#943](https://github.com/opaopa6969/unlaxer-parser/issues/943) で扱う。

単独サロゲート escape は有効な ASCII bytes なので、不正 UTF-8 とは別に扱う。
Gson が受理する `\ud800` / `\udc00` と壊れた組について、次のようにフィールド検証まで進める。
表中の診断はすべて既存の `E-PACKAGE: ` 接頭辞を持つ。

| field / 使用段階 | Java の実測と Rust の対応結果 |
| --- | --- |
| manifest `dependencies.*.source` の `local:` / 固定 cache load | 受理。明示 resolve 以外では source を取得しない |
| 同上 / 明示 resolve | `cannot read local artifact`。正規化で消える `..` 成分でも同じ |
| artifact の推移的 `dependencies.*.source` の `local:` | 固定 cache は受理。明示 resolve は上と同じ診断 |
| manifest / artifact の dependency 名、artifact ID、lock package 名 | `invalid package ID` |
| manifest / artifact / lock の version、lock 推移的 dependency version | `exact package version required` |
| lock の pinned ID | `locked package ID mismatch` |
| lock の SHA-256 | `invalid locked SHA-256` |
| lock の root 名 / version | `stale lock; run unlaxer deps resolve --manifest ubnf.json` |
| schemaVersion の文字列 | `unsupported schemaVersion` |
| 未対応 scheme の source | 固定 cache は受理。明示 resolve は `unsupported artifact source; use HTTPS, local: or builtin:` |
| 未使用の metadata 文字列 | 受理。raw artifact bytes をそのまま cache へ保存 |
| 正常なサロゲート対の `local:`、artifact file/entry、本文 | Unicode scalar として従来どおり受理 |

Rust は通常の `serde_json` 解析が失敗した場合だけ、JSON 文字列中の単独サロゲート escape を検出する。
正常な組、escape された backslash/quote は変えず、JSON の構造・一般の不正 escape・末尾余剰入力は
引き続き `serde_json` が全体を検査する。重複 member は最後の値を使用する。
内部 marker は既存 NUL と区別して符号化し、既存 NUL を復元する。単独サロゲートを含む metadata は
ASCII 検証または不正パス検証を通らない NUL sentinel にし、既存の検証順で診断する。
そのため schema/version エラーが source の取得より先に出て、正常な lock/cache の固定 load も維持される。
新しい JSON parser / 外部 crate は追加せず、UBNF parser のホットパスには処理を追加しない。

実際の UTF-16 文字列を使う以下の field は、置換で受理するとファイル同一性・文法本文・source 位置を変える。
Java の受理集合を狭めず、Rust は従来の `invalid JSON` を維持する意図した暫定差とし、
[#939](https://github.com/opaopa6969/unlaxer-parser/issues/939) の受け入れ条件で追跡する。
これらは `local:` source を扱う #926 の範囲外で、#926 の受け入れ条件（`local:` source の拒否段階・診断と、
無関係な field の既存診断の維持）は上の表で満たす。

| 単独サロゲートを含む field | Java の実測 | Rust の暫定結果 |
| --- | --- | --- |
| artifact.entry（対応 file なし） | `missing UBNF package entry` | `invalid JSON` |
| artifact.files の未使用ファイル名 | resolve / generate / inspect 成功 | `invalid JSON` |
| artifact.files の本文（末尾コメント） | resolve / generate / inspect 成功 | `invalid JSON` |
| manifest / artifact の HTTPS source host | cache load 成功、resolve は `HTTPS artifact URL must omit credentials, query and fragment` | load / resolve とも `invalid JSON` |

共通 `spec-corpus/package-json-acceptance.json` の196件（#914/#926 の127件と #942 の69件、上記暫定差13件）を
両実 stdio と Java-host Rust / native Rust / Java flag CLI、manifest / artifact は両 `deps resolve` に送る。
上位・下位・壊れた組・正常な組・消える成分、NUL、escape された field 名、重複 source、
一般の不正 JSON と schema の優先順を含む。stdio の message/code/severity/UTF-16 range 全体と後続 request、
CLI の最終診断行と既存の終了コード（generate / resolve は3、Java flag CLI は4）、
成功時の lock/cache raw bytes 一致、失敗時の snapshot 不変（既存 snapshot の有無両方）を照合する。
暫定差の13件も実行し、host 別 oracle を台帳に記録する。テストを skip して一致扱いにはしない。
既存 package corpus の期待値は変更しない。


### package JSON の member 順序（#942）

入力の object は Java の Gson `JsonObject` と同じ記述順で検査・走査する。
例えば `dependencies` の `b!`（不正 ID）が `a/x`（不正 version）より前なら
`invalid package ID`、逆なら `exact package version required` を先に報告する。
単独サロゲートを含む不正 ID も同じ順序で既存の検証へ渡す。

Gson 2.13.2 STRICT を JDK17 / JDK21 で実測すると、重複 key は拒否せず、
**最初の記述位置を保持し、最後の値で置き換える**。`"b"` と `"\u0062"` は同じ key、
`"b\ud800"` と `"b\uD800"` も同じ key になる。最初の値が不正でも最後の値が有効なら
その field は有効だが、上書きされた値を含め JSON 全体の構文検査は省かない。
object の等値比較は順序に依存しないため、正常な lock の roots や dependencies の並べ替えで
stale lock / identity conflict にしてはならない。

Rust の `packages/value.rs` は最初に既存 `serde_json::Value` で値と構文を読み、
既存 serde の `Deserialize` / `Visitor` でもう一度 member の位置だけを記録する。
その順序で `Vec<(String, Value)>` を構成し、`BTreeMap<String, usize>` で位置を索引する。
DAP から既に統合される `arbitrary_precision` の数値表現も最初の解析に任せるため、
小数・巨大な数値を object と誤認せず、単独 crate / workspace の既存の数値挙動をそれぞれ維持する。
重複処理と lookup が幅の広い metadata でも二乗にならないようにし、配列は元の順序を保つ。
JSON の文法・数値・再帰上限は既存 serde_json に任せる。#940 の BOM・サロゲート復元は
この木に適用し、raw bytes の reader・hash・UTF-8 の順序は変えない。
`serde_json/preserve_order` は使用せず、新しい crate / license 依存を追加しない。
この表現は package 入力だけに限定し、DAP / LSP の一般 JSON、credentials (#943)、
UBNF parser のホットパスには適用しない。#941 の空文書・非 object・深い入れ子と
#939 の file/entry/HTTPS の単独サロゲートに残る挙動も変更しない。

| object / 経路 | 入力の検査・走査順と観測可能な結果 | 出力の扱い |
| --- | --- | --- |
| manifest / lock / artifact の最上位と各固定 field | JSON 全体を読む。その後は既存の schema → 対象 field の固定順で検査し、最上位 member の並べ替えでは優先順を変えない。未知 metadata は使わない | artifact は元の bytes を保持 |
| manifest `dependencies` の検証 | 記述順に ID → version → source 型を検査。複数の不正 member の最初の診断に影響 | roots の出力は辞書順 |
| manifest `dependencies` の `resolveOne` | 記述順に深さ優先で取得・内容検査。最初の取得失敗、identity conflict、cycle / 上限診断に影響 | 全 graph 成功後だけ lock/cache を書く |
| artifact `files` | 記述順に path → 本文の型を検査。複数の不正 file の最初の診断に影響。その後の entry は key lookup | files を再エンコードしない。cache は raw bytes |
| artifact `dependencies` の検証・明示 resolve | 記述順に field 検証、その後同じ順の深さ優先走査 | pinned dependencies の出力は辞書順 |
| lock `packages` の audit | 記述順に ID、pinned ID/version/SHA、各 dependencies を検査。最初の診断に影響 | 読取りでは lock を変更しない |
| lock `packages.*.dependencies` の audit | 記述順に ID → version → pinned version を検査。最初の診断に影響 | 同上 |
| lock `roots` | manifest から作った参照と順序を無視して比較。不正な追加/値の変更は audit より先に stale lock。その後は記述順で pinned version を確認 | 同上 |
| lock `packages` / `packages.*.dependencies` の cycle audit | 各 object の記述順で DFS。複数 cycle があると報告する ID に影響 | 同上 |
| artifact の dependencies から作る `actual`（固定 cache load） | 記述順を保持して pinned 参照と順序を無視して比較し、同順に子を load。複数 cache の hash 不正などで最初の診断に影響 | 検証済み親は全依存の成功後だけ memo に載せる |
| lock `packages` の `owner` | 記述順で SHA を照合し、最初に一致した所有者を選ぶ。正常な artifact は ID / raw hash 検証も通る必要がある | identity JSON の field 順は既存どおり |
| lock / cache の生成 | Java は `TreeMap` で packages、roots、pinned dependencies と cache hash を整列。Rust は出力時だけ既存の辞書順へ変換 | lock の field 順・末尾 LF と既存 corpus の raw bytes を維持 |
| generator / LSP の利用 | 上記の検証・走査を共用。失敗は最初の原因、CLI の既存 exit code、LSP の `E-MODULE`・severity 1・import 全体の UTF-16 range に現れる | 正常時の生成物・LSP JSON の順序は既存どおり |
| `package_fetch` の credentials | origin / caCertificate / bearerToken の固定 key lookup。object の全走査はない | この変更の対象外 (#943) |

#942 の共通69件は ID/version、単独サロゲート ID、files、roots、重複 key と escape、
package / dependency の cycle、直接・推移的 fetch、固定 cache load の順序を含む。
両実 stdio の全診断と後続 completion / hover、3 CLI、両 `deps resolve` を照合する。
lock 用の新 fixture も resolve へ渡し、入力 lock の不正を読み直さず正常な lock に再生成することを確認する。
成功ケースは記述順を逆にした multi-package graph の lock/cache を Java と固定 byte 期待値に比較し、
失敗ケースは既存 snapshot の有無両方で不変を検証する。既存127件の期待値は維持する。


## grammar / manifest の基準ディレクトリ（#928）

Java の `toAbsolutePath().normalize()` を共通契約とする。grammar / manifest のパスを
まず作業ディレクトリから絶対化し、`.`・`..`・重複区切り・末尾 `/` を字句的に畳んでから、
その親を `ubnf.json` / `ubnf.lock.json` / `.ubnf-cache` の基準にする。
`canonicalize` / `toRealPath` は使わない。例えば `work/a/link/../../g.ubnf` は
`link` の実体によらず `work/g.ubnf` を読み、`work` の lock/cache を使う。
`missing/../g.ubnf` は missing の存在を要求せず、`g.ubnf/` と `g.ubnf/.` も同じ文法になる。
残った symlink は通常の I/O でたどるが、grammar ファイル自体への symlink でも
基準はリンクを指定した側の親であり、実体側の親へ移さない。

| 経路 | 確認と変更 |
| --- | --- |
| `deps inspect --grammar` | Java の既存正規化を維持。Rust の本文読取りと再生成する resolver を同じ正規化済みパスに揃える。 |
| `playground --package --manifest` / `PackagedLanguageProfile.load` / `packaged_profiles::load` | Java の既存規則に Rust の manifest 入口を揃える。ファイル名の検査も正規化後に行う。 |
| `generate --target rust` / `check --target rust` | module loader の基準だけでなく、両 CLI の先行する本文読取り・警告判定も同じ grammar を使う。Java の `checkFile`、Rust の `check_file` も含む。 |
| `UBNFModuleLoader.load` / Rust `modules::load`・`load_for_check` | Rust は既に絶対・字句正規化済み。Java の本文読取りを揃え、Rust の既存 `modules::normalize` は #927 で使った `packages::normalize` に委譲して重複実装をなくす。 |
| `playground --grammar` / 両 file API | 本文 snapshot・portability 判定・生成・vocabulary が同じ正規化済み grammar を使う。 |
| Java flag CLI / `CodegenRunner` | 標準 disk port の入口 grammar の読取り（`readGrammar`）だけを正規化する。`--overwrite if-different` が比較する既存出力の読取りは、`exists` / 書込みと同じ OS のパス解決のままにする。カスタム `FileSystemPort` / `SourceReader` の注入と元の引数契約は維持する。 |
| authoring LSP（通常解析・回復解析） | Java は既に URI のパスを正規化。Rust の `Resolver::with_sources` が呼ぶ `Resolver::new` で、絶対パスを字句正規化してから親を取る。未保存 buffer と検証済み package source の優先順は維持する。 |

変更前の Java を `origin/master` のソースから別のクラス出力先へコンパイルし、同じ14形で70回の実 CLI 測定を行った。inspect と package Playground の28回は既に成功し、generate/check/flag の42回中21回は、先行する本文読取りが symlink の物理側や不存在成分を参照して失敗した。これが Java 側の本文読取りも揃えた理由である。
このため Java の generate / check / grammar Playground / flag CLI で grammar が読めないときの
I/O エラーに出るパスは、`deps inspect` と同じく正規化後の絶対パスになる（例: `nope.ubnf` は
`<作業ディレクトリ>/nope.ubnf`）。#928 時点では exit code と Rust の I/O 表示は維持した。
後述の #932 / #934 で native CLI の終了コード・表示も Java に揃える。

Rust の `Resolver::new` の全呼出元を確認した。module / vocabulary / packaged profile の
入口は絶対化してから渡し、LSP は絶対 file URI を渡す。テスト内の呼出元も絶対パスを使う。
`deps resolve` の manifest / `local:` / 推移的依存は #927 の規則と実装を維持する。
`--profile` の TSV 自体、出力ディレクトリ、credentials、package 内の仮想パス・境界検査は
この grammar / manifest 契約とは別の入口なので変更しない。相対 `.ubnf` import の探索や
cache reader・raw hash・strict UTF-8・JSON の順序にも変更はない。

共通 `spec-corpus/package-grammar-paths.json` の14形を実 Java / native Rust CLI と
両 authoring stdio に渡す。symlink の `..`、不存在成分の `..`、末尾 `/`・`/.`、途中の `.`、
通常ファイルを打ち消す `..`、相対/絶対パス、grammar / manifest 自体への symlink を含む。
字句的な場所に正常な lock/cache、物理的な別の場所には異なる文法を置く。ファイル symlink の
ケースは実体側の lock を除去し、実体パス化による誤りも検出する。
inspect・package Playground・generate/check は成功と lock 欠落の exit/stdout/stderr 最終行を
完全一致で比較し、成功時の生成物も比較する。grammar Playground は成功時の同じ比較と
本文 snapshot の固定期待値を検証する。既存の readiness エラーの描画形式
（Java の Diagnostic リスト / Rust の JSON）は基準ディレクトリとは別である。
#932 ではこのうち `P-MODULE` の描画を Java に揃える。
Java flag CLI と両言語の file API も同じ14形を検証する。LSP は全診断・hover・close 後の
応答と読取り専用性を比較する。parser 実行時のホットパスには処理を追加しない。


## grammar 入力の I/O エラー（#932 / #934）

参照は Java 21 の実 `CodegenMain`。native `unlaxer` は grammar の入口と local import の
I/O 失敗を文法検証エラーと区別し、次の終了コード・stderr 最終行を返す。stdout は空。
出力先は作らず、既存の入力・lock/cache にも書き込まない。
パス `P` は #928 の絶対・字句正規化後の失敗ファイルで、入れ子の import でも入口ではなく
実際に読めなかった import 先を表示する。Java の既存コマンド・file API は変更しない。

| 失敗箇所 | deps inspect | check --target rust | generate --target rust | playground --grammar |
| --- | --- | --- | --- | --- |
| 入口の I/O | 3、原因文 | 4、`I/O error: `＋原因文 | 4、原因文 | 4、原因文 |
| local import の I/O | 3、原因文 | 3、既存の `P-MODULE` JSON（stderr は空） | 4、原因文 | 3、`Playground requires a Rust-portable grammar: [Diagnostic[code=P-MODULE, span=null, subject=UBNF module resolution]]` |

`check` と Playground の import 行は `@ubnf: v2` により module 解決へ進む場合。
それ以外の inventory / JSON・診断順は従来どおり。複数 grammar の generate は従来の
`grammar NAME [start,end): ` を原因文の前に付ける（範囲は元ソースの CP）。
Java の inspect は source snapshot を付けずに解決するため、この接頭辞を付けない。

| I/O 失敗種別 | 原因文への対応 |
| --- | --- |
| `NotFound` | `P`（Java の `NoSuchFileException#getMessage()`） |
| `PermissionDenied` | `P`（Java の `AccessDeniedException#getMessage()`） |
| `IsADirectory`：入口 check / generate / grammar Playground | `Is a directory` |
| 非通常ファイル：入口 inspect / local import | bounded reader の `E-MODULE: local import is not a regular file`。ディレクトリもここで拒否する。 |
| `NotADirectory` | `P: Not a directory` |
| symlink ループ（Unix `ELOOP`） | `P: Too many levels of symbolic links or unable to access attributes of symbolic link`（Java NIO の文） |
| その他の OS I/O | `P: <OS の理由>`。Rust の ` (os error N)` は付けない。Linux の長すぎる成分は `P: File name too long`。 |
| UTF-8 decode（`InvalidData`） | 内容診断の既存契約を維持。上記 OS errno の変換対象ではない。package artifact は #924 の strict UTF-8 / raw hash 順を維持する。 |

通常ファイル・64 MiB・handle 再検査・bounded read と package の `E-PACKAGE` 診断は維持する。
Rust の既存 `Result<_, String>` API は残し、CLI 用の `FileError` で I/O と文法エラーの
区別を module 解決の間も保持する。エラー文字列のパターンや失敗後の再読取りで終了コードを推測しない。
parser のホットパスに変更はない。
local import の bounded reader の失敗（非通常ファイル・64 MiB 超過・不正 UTF-8）も、Java と同じく
generate では I/O として終了4になる（変更前の native は3）。不正 UTF-8 の原因文の差
（Java `Input length = 1` / Rust `stream did not contain valid UTF-8`）は今回の対象外で、#937 に残す。

同じ bounded reader の文言は native authoring LSP の `E-MODULE` 診断にも届く。local import の欠落・権限拒否は
`import ALIAS: P` となり、Java LSP と message まで一致する（変更前の native は `No such file or directory (os error 2)`）。
`scripts/check-ubnf-authoring-lsp.py` の `moduleError` fixture は code・range に加えて message も照合する。

共通 fixture は `spec-corpus/cli-input-errors.json`、実行は `scripts/cli_input_errors.py` と
`CliInputIoConformanceTest`（`-DrustConformance=true`）。26形を LF/CRLF で実行し、
入口の相対/絶対/不存在親/symlink＋`..`/非 ASCII・空白・非 BMP を含むパス、ディレクトリ、権限、import の欠落/入れ子/ディレクトリ/権限、
複数 grammar と正常な生成物を検証する。通常の I/O fixture は4コマンドの exit・stdout・stderr 最終行を
独立期待値に照合し、成功時は両 host の生成 bytes と `--check` も比較する。
追加の `@ubnf` 省略/v1/v3/block の4形は、Playground が I/O より先に capability 判定で停止するため、
inspect・check・generate を対象とし、既存 `P-IMPORT` / `P-SETTING` JSON の全体を固定する。
この4形の Playground の一般的な描画形式は今回変更しない（`P-MODULE` 以外の readiness 描画差と
出力先の書込み失敗の文言差は #937）。

権限 fixture は同じ実行ユーザーで `chmod(000)` の読取りが `PermissionDenied` になることを先に確認する。
root / `CAP_DAC_OVERRIDE` 等で読める環境では入口/import の各1形を組合せから除外し、
証跡 JSON の `excluded` に理由を残す。JUnit を skip したり権限拒否が成功したと数えたりしない。
通常の非 root CI は全400観測（両 host、LF/CRLF）と生成後 `--check` 16回を実行し、必須3メソッドの skip は0とする。
GitHub Actions 上（`GITHUB_ACTIONS` あり）では除外が1件でもあればテストを失敗させ、権限 fixture が黙って抜けないようにする。
