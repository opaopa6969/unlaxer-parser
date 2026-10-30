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

HTTPS source は ASCII・Unicode scalar とも Java の `URI.create`（JDK17/21）を構文の参照にする（#962・#960）。
Rust は `packages/java_uri.rs` の `classify_str(&str)` から同じ UTF-16 parser と origin 判定を使う。URL 全体の構文検査後、scheme →
`getHost()`（null・ASCII DNS/IPv4 文字種）→ userinfo → query → fragment → port の順で検査し、
受理後だけ HTTP 用 URL を組み立てる（ASCII は元の綴り、Unicode は下記の NFC/percent encoding）。
Java の製品コード・診断と parser のホットパスは変更しない。
ただし接続先は java.net.http の扱いに合わせる（レビューで追加）。4つの数字 octet の host は
`InetAddress` が10進で読む literal なので、先頭0を含む `127.0.0.010` は 127.0.0.10 へ接続する
（getaddrinfo は8進で 127.0.0.8 と読む）。Rust はこの場合だけ 10進正規形の literal を接続・TLS（IP 照合、SNI なし）に使い、
`Host` header は Java と同じ `getHost()` の表記と既定以外の port を送る。request target は変えない。
ただし Java の literal 判定（`IPAddressUtil.textToNumericFormatV4`）は15文字までで、`00127.000.000.010` のような
長い数字 host は literal にならず `InetAddress` が曖昧な表記として拒否する（接続しない）。
それ以外の host は Java が `SNIHostName` を作り、末尾の `.` や63文字を超える label では接続前に例外となるため、
これらは Rust も credentials → CA → token の検査後に接続せず `HTTPS artifact retrieval failed (TLS, timeout or size limit)` とする。
この UTF-16 化は明示 package fetch の URL だけで、package JSON 全体には行わない。
JDK17/21 の独立 oracle と ASCII fuzz 8万件で、構文受理・scheme・getHost/port・
userinfo/query/fragment の有無・exact origin が一致した。

**HTTP request target の渡し方（#977・#979）**: ureq 3.4.2 / http 1.5.0 / ureq-proto 0.6.4 の
公開 API を試作した。`Uri::from_maybe_shared` / parse は全長65534 bytesまでで、
`Uri::builder` / `from_parts` は成分ごとの検査なので65535/65536 bytesの URL を組み立てられるが、
`PathAndQuery` 自体の65534 bytes上限と backtick 拒否は残る。ureq-proto は request の `Uri` から
request line を作り、target だけを直接指定する API はない。

native は公開 `Agent::with_parts` と `DefaultConnector::chain` を使う取得専用の
`package_fetch/request_target.rs` で揃える。検証済みの同じ接続先の URI に placeholder `/` を渡し、
ureq が作った `GET / HTTP/1.1` の `/` だけを **TLS transport の外側**で元の request target に置換する。
ASCII の元の綴り、Unicode の既存 NFC/percent encoding をそのまま送り、切詰めや追加 escape はしない。
Host・認証を含む後続 header bytes は移動するだけで、再構築しない。先頭0の IPv4 の10進接続先と元の Host も維持する。
接続・hostname/trust 検証・TLS・request header 作成・応答の framing/header 上限・本文読取りは引き続き ureq が担う。
独自 HTTP client、TLS 実装、応答 parser、新しい crate、unsafe は追加しない。

送信 buffer は既定128 KiBに target の長さを足し、既存8 MiBの入力文書上限と encoding の伸びの範囲で確保する。
置換に使った経過時間を元の timeout から引き、1回の underlying transmit に渡す。期限切れでは書き込まず、分割ごとに期限を延長しない。
ただし ureq は1回の TLS transmit の中でも socket write ごとに残り期限を設定し直すため、受信を止めた peer に
socket buffer を超える target を送ると、全体20秒を数倍超えて待ち得る（6 MB の target で約62秒を実測）。
Java は `future.get(20秒)` で交換全体を打ち切るので、native も取得全体を worker で実行し、20秒で同じ取得失敗を返す。
打ち切った worker は自身の接続だけを保持し、process 終了で消える。共通 fixture の `request-stall` 形
（7000000 bytes、server は先頭256 KiBだけ読んで止まる）で両版とも19.5秒以上30秒未満を要求する。
agent は1回の fetch 専用で接続を pool に保持せず、redirect は0回のまま。
この公開 API は ureq の `unversioned` 名前空間にあり、依存更新時は共通 transport fixture を再実行する。
TLS 未確立、想定外の prelude、buffer 不足、ASCII origin-form 外の target は書込み前に拒否する。
全 URL の構文・origin の検査、credentials → CA → token の順と既存診断、非 scalar URL の送信禁止は維持する。
encoding の計算だけは buffer 設定のため credentials より前に行うが、I/O・診断・送信は行わない。

共通 fixture は絶対 URL 全長65534 / 65535 / 65536 / 100000 / 200000 bytesを生成し、
Oracle JDK17.0.12 / 21.0.9 と native の両 `deps resolve` で、直接/推移的依存・snapshot 有無の全組合せを比較する。
この5つの長さでは両 Java と native が成功し、target の実 bytes・Host・lock/cache raw bytes が一致する。
これは server が受理するこの範囲の実測であり、Java の有限な最大長が200000という意味ではない。
Unicode の encoding 後に65534 bytesを超える形も同じ wire に揃える。
大きな URL は JSON に展開せず `urlBytes`（ASCII prefix の絶対 URL byte 長）と任意の `urlSuffix` で表す。
検査順の対照は長い URL / U+1FEF と不正 credentials・欠落 CA・不正 token を組み合わせ、
両版の既存診断・接続0・snapshot 不変を固定する。

| ASCII source の形 | credentials より前の結果 |
| --- | --- |
| path の `[` / `]`、不正 percent escape、2つ目の `#`、不正 IPv6 | `invalid HTTPS artifact URL` |
| query / fragment の `[` / `]`、userinfo（空も含む） | 構文は受理し `HTTPS artifact URL must omit credentials, query and fragment` |
| port 0 / 65536以上、非数字・`+`・`;`・整数overflow | 同じ omit 診断。DNS・TLS に進まない |
| hostname の先頭/末尾 `-`、空 label、数字だけの最終 label、percent authority | registry authority に fallback して `getHost()` が null となり、同じ omit 診断 |
| IPv6 literal（構文は有効） | Java の既存 ASCII host 文字種制約で omit。IPv6 の取得を新たに許可しない |
| IPv4、末尾の dot、63/64文字の label | URI と host の検査を通過。URI は DNS label の63文字上限を課さない。取得時は上記の接続先規則（15文字までの先頭0の IPv4 は10進 literal、それより長い数字 host・末尾 dot・64文字以上の label は接続前に取得失敗） |
| 空 port、65535、先頭0付きの port、path の `%5B%5D` | URI と port の検査を通過。空 port は既定443、origin の port は数値化して443を省略 |

構文の拒否は host / query 等の拒否より先になる。たとえば不正 hostname と path の `[` を
同時に含む URL は invalid になる。既存の credentials → CA → token と transport の順は維持する。
Unicode を含む scalar URL も同じ順で検査する（#960）。Unicode host / IDN は punycode に変換せず、
Java と同じ registry authority（`getHost() == null`）として omit 診断にする。
Unicode userinfo / query / fragment は URI が構文受理しても omit、不正 percent escape と Unicode の
混在や非 ASCII の空白（NBSP・U+1680・U+2009・U+2028・U+3000 等）は先に invalid になる。
全角 `／？＃＠：％` は ASCII の区切りへ変換しない。
非 scalar URL は従来どおり origin の検査だけに parser を使い、HTTP request を作らず送信しない（#939）。

共通 `spec-corpus/package-https-transport.json` の ASCII62形・Unicode88形を、
既存 `HttpsPackageConformanceTest#transportDiagnosticsMatchJavaAcrossClis` で実行する。
各形は直接依存とローカル親 artifact 経由の推移的依存を両 `deps resolve` に与え、
snapshot 有無の両方で診断・exit・成功時 lock/cache raw bytes・失敗時 snapshot 不変を照合する。
拒否した URL はローカル TLS server の request / 接続とも0を要求する。
65535・空 port・任意 hostname の構文受理は不正なダミー token で通信前に止め、固定ポートや外部DNSを利用しない。
末尾 dot の localhost は正しい token と CA で送り、両方とも接続0の取得失敗になることを確かめる。
先頭0の IPv4 は 127.0.0.10 にも同じ port の listener（証明書に IP SAN）を置き、受信先・`Host`・request target を照合し、16文字以上の数字 host は接続0の取得失敗を確かめる。
成功する localhost URL はローカル TLS で認証・request target bytes も照合する。
3生成 CLI・両 stdio の固定 cache 読取りは既存の共通試験で通信しないことを検証し、
Unicode の成功 path と不正 percent source でも3生成 CLIの接続0・snapshot不変を検査する。
Java の順次呼出しは既存 `CodegenBatchHost` の1 JVM方式を使い、CIの新クラス・メソッド・shardは増やさない。

Unicode の送信表現は Oracle JDK17.0.12 / 21.0.9 のローカル TLS 実測を参照する。
HttpClient の `Http1Request.getPathAndQuery` → `Utils.encode` は raw path を **NFC** に正規化し、
非 ASCII の UTF-8 bytes だけを大文字 `%XX` にする（`URI.toASCIIString()` と同じ方式）。
既存の percent escape は復号せず、小文字 hex も保持する。dot segment / 重複 slash も畳まない。
`Host` は元の ASCII `getHost()` の大文字小文字を保ち、既定以外の port を数値化して付ける。
IPv4 の10進接続先・末尾 dot 等の接続前失敗は #962 の規則をそのまま使う。

| raw path の一部 | 送信する request target の対応部分（JDK17/21・native） |
| --- | --- |
| `é` / `e` + U+0301 | `%C3%A9`（NFC/NFD で同じ wire） |
| `😀`（正常な UTF-16 pair） | `%F0%9F%98%80` |
| `é%2f😀%41%25%ff` | `%C3%A9%2f%F0%9F%98%80%41%25%ff` |
| `e%CC%81` | `e%CC%81`（escape 越しの合成はしない） |
| `%65` + U+0301 | `%65%CC%81`（ASCII escape の末尾 `5` は合成されない） |
| `%4a` + U+0301 | `%4%C3%A1`（末尾の hex 文字 `a` は合成され、送信する escape が崩れる。Java と同じ） |
| U+212B / `A` + U+030A | `%C3%85` |
| Hangul U+1100 U+1161 U+11A8 | `%EA%B0%81` |
| U+1D15E（非 BMP・合成除外） | `%F0%9D%85%97%F0%9D%85%A5` |

native の取得専用 NFC は JDK21 / Unicode15.0 の canonical decomposition・結合クラス・合成対と
algorithmic Hangul を使う。`scripts/generate-package-nfc.py --java-home <JDK21> --check` で
JDK から offline 再生成・hash 照合でき（CI の core shard が実行）、新しい依存 crate や外部プロセスは製品に追加しない。
結合文字列は安定ソートで並べ、長い結合 run に二乗の処理を持ち込まない。
この表と処理は native の明示取得だけで、WASM / parser / package 本文には適用しない。

**JDK 自身の版差**: JDK17（Unicode13）と21（Unicode15）は canonical decomposition / 合成対が同じだが、
JDK21 では50文字に新しい結合クラスが付く。たとえば `a` U+1AC1 U+0323 は21/nativeでは
`%E1%BA%A1%E1%AB%81`、17では `a%E1%AB%81%CC%A3` となる。
U+1E4EC を含む非 BMP の対照も共通 fixture の `javaJdk17TargetSuffix` に固定し、CI の JDK17 job も同じ transport メソッドを実行する。
native は主 CI の JDK21 を参照する。入力だけでは実行する JDK の版を選べないための契約であり、
JDK17 の URI 受理・診断を変更したり、この2形を skip したりしない。

**NFC が作る ASCII の target（#979）**: U+1FEF（GREEK VARIA）は NFC で backtick（U+0060）になり、
Java/native とも byte `60` をそのまま送る。単独、BMP、非 BMP、正常 percent との混在、すべての混在を
共通 fixture で照合する。`%60` への追加 escape はしない。U+037E → ASCII `;` は一致の対照とする。
raw ASCII backtick は従来どおり URI 構文エラーであり、credentials を読む前に拒否する。

共通 fixture は JSON の raw UTF-8 と BMP escape / 正常な surrogate pair escape の両方を与える。
encoding は送信専用。manifest は書き換えず、取得した cache の raw bytes も変えない。
lock の現行 schema は source URL を記録せず ID/版/hash/dependencies を記録する。
推移的依存を含む親 artifact の cache は元の Unicode/JSON escape の綴りをそのまま保持する。
NFC と NFD の source は同じ wire でも別の raw source であり、この差を親 artifact の hash まで照合する。

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

credentials の場所は Java の `UBNFPackageFetcher` を参照する（#958）。Unix の通常の
passwd entry では JDK17 / JDK21 と native Rust が次の規則で一致する。

| 指定 | 選択する場所・診断 |
| --- | --- |
| `UBNF_CREDENTIALS_FILE` 未設定 | 実 UID の passwd home + `.config/unlaxer/credentials.json`。`HOME` が未設定・空・別ディレクトリでも通常の passwd home を優先する |
| override が空文字列 | 作業ディレクトリ。通常は `cannot read artifact credentials`（サイズ検査は従来どおり先行） |
| 空白だけ・相対パス・`~` 始まり | 文字列をそのままパスにする。trim・tilde 展開をしない。相対パスの基準はプロセスの cwd で、manifest の親ではない |
| override が存在しない・親が非ディレクトリ・dangling symlink | `artifact credentials file is missing` |
| override がディレクトリ・ディレクトリへの symlink | `cannot read artifact credentials`（サイズ検査が先行） |
| override が通常ファイルへの symlink | リンク先を読む |
| override の末尾の `/`（`file.json/`・`link//`） | Java の `UnixPath` と同じく末尾の `/` を取り除いた場所を読む（`/` だけならルート）。途中の `//` はそのまま |
| Java の `-Duser.home=...` | override 未指定時だけ property を使う。空 property は cwd からの相対パス。native に JVM property はなく、同じ場所を指定するには両 CLI で `UBNF_CREDENTIALS_FILE` を明示する |

Rust は既存依存の `libc::getpwuid_r(getuid(), ...)` を明示 HTTPS 取得時だけ呼ぶ。
ERANGE は1 KiBから倍増して1 MiBまで再試行し、結果を所有する PathBuf へコピーする。
FFI は専用の小さな関数だけで unsafe を許可し、generator の他のコードは deny、
parser/runtime の workspace forbid は維持する。新しい crate と parser hot path の処理は追加しない。
Unix 以外（Windows 等）の Rust は従来の `HOME` と override の挙動を維持し、passwd との一致は保証しない。

passwd lookup の不在・エラー・2 バイト未満の home（空、systemd DynamicUser の `/` など）は JDK 間で規則が異なる。
JDK21 は非空の `HOME`、なければ `?` を使う。JDK17 は不在・エラー時に `?`、見つかった home は短くてもそのまま使う。
native の互換対象をリポジトリの既定 JDK21 に固定する（#964）。Java の製品コードを
変えずに native が JDK17 と JDK21 の異なる場所を同時に選ぶことはできない。
既定 JDK・CI の主 job と一致し、非空 HOME があるとき cwd や `?` 配下を誤って読む危険を
小さくするため JDK21 を選ぶ。HOME もない場合の `?` は JDK21 の既存規則として維持する。
この条件では同じ `UBNF_CREDENTIALS_FILE` の明示で差を避けられる。

| passwd lookup / pw_dir | HOME | JDK17 の基点 | JDK21 / native の基点 |
| --- | --- | --- | --- |
| 不在 | 未設定 / 空 | `?` | `?` |
| 不在 | 非空 | `?` | HOME |
| エラー | 未設定 / 空 | `?` | `?` |
| エラー | 非空 | `?` | HOME |
| 空 pw_dir | 未設定 / 空 | 空文字列（cwd） | `?` |
| 空 pw_dir | 非空 | 空文字列（cwd） | HOME |
| 1バイトの pw_dir（`/`・`s`） | 未設定 / 空 | pw_dir | `?` |
| 1バイトの pw_dir（`/`・`s`） | 非空 | pw_dir | HOME |
| 2バイト以上の pw_dir | 任意 | pw_dir | pw_dir |

各基点から `.config/unlaxer/credentials.json` を選ぶ。空 home はこの相対パスを cwd で解決する。
共通 `spec-corpus/package-credentials-location.json` の41形を既存の `HttpsPackageConformanceTest` から実行する。
不在・エラー・空 pw_dir × HOME 未設定・空・非空の9組合せは、正常なファイルと不正 JSON の
両方を検証する。各形で両 `deps resolve`、Java-host Rust / native Rust / Java flag CLI と
lock/cache の有無を検査する（410呼出し）。成功時は Authorization のラベルで選択ファイルを確認し、
raw lock/cache と生成 bytes を照合する。不正 JSON の形は選ばれるべきファイルだけを壊し、
他の候補は正常なままにする（別の場所を選ぶと成功して検出される）。両 resolve が
`E-PACKAGE: cannot read artifact credentials` / exit 3、lock/cache は既存の有無とも不変で、
同じ不正 credentials があっても固定 cache からの3 CLI生成は成功し通信しない。
Java の独立 home probe と実 CLI の診断・exit を使い、同じ子環境内の呼出しは
既存 `CodegenBatchHost` の1 JVM にまとめる（`--unbatched` で実 CLI と照合可能）。
`-Duser.home` の2形は native の明示ファイル指定へ対応付けた検査であり、同じ環境だけで一致したとは扱わない。
JDK17 の差は `javaSelected17` として記録し、skip しない。TLS の最終診断は #956 で共通化した。
CI の cross-language job（cli shard）は JDK21、JDK17 も既存の jdk17-parity step の
`credentialsLocationsMatchJavaHomeAndPaths` で全形を実行する（#967 / #975）。
非 UTF-8 ロケールと生バイト名の恒久差は、下記「CLI のロケールとファイル名」（#966）の契約に従う。
Linux のテスト専用 `LD_PRELOAD` shim は子プロセスだけへ仮の passwd home を返し、実 passwd lookup へ fallback しない。
実 credentials は開かず、前後の存在・inode・size・mtime/ctime（symlink とリンク先）で不変を検査する。
結果は `target/rust-package-credentials-location.json` に、値を含めず選択ファイルのラベルと所要時間で記録する。

必要な場合は同じ entry に `caCertificate`（ユーザー/CI が管理する PEM CA bundle の
絶対パス）を追加できる。明示 CA の trust store を使って TLS/hostname 検証を続ける。
credential / CA 設定ファイルの上限は64 KiB。token は printable bearer 文字に限定する。
設定ファイルと取得済み private artifact はユーザーが管理し、公開操作は行わない。

資格情報 JSON は Java の `Files.readString` → Gson 2.13.2 STRICT の
`fromJson(..., JsonElement.class)` を参照とする（#943）。JDK17 / JDK21 で同じ結果を確認した。
検査順はファイルの有無 → 64 KiB 上限 → UTF-8 → JSON 全体 → 最上位 object →
exact origin の object → `caCertificate` の型・CA 読取り → `bearerToken` の型・文字種。
member の記述順にかかわらず CA の失敗が token の失敗より先になる。
以下の診断にはすべて `E-PACKAGE: ` が付き、`deps resolve` の失敗は exit 3。

| 入力 | 受理または診断 |
| --- | --- |
| 明示したファイルが存在しない | `artifact credentials file is missing` |
| 既定ファイルが存在しない | 空の資格情報として続行 |
| ディレクトリ・読取り権限なし・不正 UTF-8 | `cannot read artifact credentials` |
| 65536 bytes | 上限内。UTF-8 / JSON の検査を続ける |
| 65537 bytes 以上（8 MiB 超も同じ） | 内容を解析する前に `artifact credentials file exceeds limit` |
| byte 0 の BOM 1つ + 有効 object | 受理 |
| 空文書・JSON 空白だけ（BOM の有無を問わない） | `invalid artifact credentials` |
| 有効な非 object（`null`・配列・文字列・数値・真偽値） | `invalid artifact credentials` |
| 不正 JSON、BOM 2つ、空白の後の BOM、末尾余剰入力、深さ256以上 | `cannot read artifact credentials` |
| exact origin がない / 別 origin の値が非 object | 空の資格情報として続行 / 別 origin は無視 |
| exact origin の値が非 object | `invalid artifact credentials` |
| `caCertificate` / `bearerToken` の値が文字列でない | その field の検査順で `invalid artifact credentials` |
| 単独サロゲートを含む `bearerToken`（上位・下位・壊れた組） | JSON は受理し、token 検査で `invalid artifact credentials`。送信しない |
| 単独サロゲートを含む `caCertificate` | JSON は受理し、パスの読取りで `cannot read artifact CA certificate` |
| 単独サロゲートを含む origin key・field 名・未使用 member | JSON は受理。ASCII の exact origin / 固定 field と一致しない key は使わない |

JSON 空白は space / tab / CR / LF だけ。重複 key は最初の位置・最後の値を保持するが、
上書きされた値も含め構文を検査する。未使用の範囲外数値は表記のまま受理し、数値は1023文字まで、
object / array の深さは最上位を含め255まで。Rust は package 専用 reader と BOM・サロゲート escape の
処理を再利用し、package の `JsonObject` 用診断とは分ける。新しい crate / serde_json feature は使わない。
ASCII の固定 key だけを取得し、使用する文字列に限って単独サロゲートを既存の不正パス / ASCII 検査へ渡す。
未使用 member に `files` / `entry` / `dependencies` があっても package の schema や #939 の制限は適用しない。

任意の HTTP header を指定する機能はない。`headers` object、header 名やその値は未使用 member であり、
非文字列や単独サロゲートを含んでも送信されない。資格情報から作る header は
`Authorization: Bearer <token>` だけで、token は空でない `[A-Za-z0-9._~+/=-]+` に限定する。
client 既定の `Host` / `User-Agent` と、Rust ureq だけが付ける `Accept: */*` は資格情報と無関係で、従来どおり。
正常なサロゲート対もこの ASCII 制限では拒否する。JDK17 / JDK21 の `HttpRequest.Builder.header` を
独立に測定すると、header 名・値の単独サロゲートは `IllegalArgumentException` になる。
製品ではその前の token 検査で固定診断を返すため、JDK の値を含み得る例外文は出さない。

資格情報を受理した後の HTTPS 失敗分類は Java `UBNFPackageFetcher.fetch` を参照する（#956）。
Java は `sendAsync` の上限付き本文が完了してから status を調べる。Rust も ureq の
`http_status_as_error(false)` で応答を受け、本文読取り → status の順に判定する。
redirect は従来どおり0回で、Location の先には接続しない。下表の診断には `E-PACKAGE: ` が付く。

| 条件 | 最終診断 | `deps resolve` exit |
| --- | --- | --- |
| TLS trust 失敗・hostname 不一致・接続拒否 | `HTTPS artifact retrieval failed (TLS, timeout or size limit)` | 3 |
| 接続 timeout（5秒）・本文 read timeout（全体20秒） | `HTTPS artifact retrieval failed (TLS, timeout or size limit)` | 3 |
| 要求していない `101`、本文を宣言した `204`（`Content-Length` が `0` 以外、または `Transfer-Encoding` あり） | Java の exchange 失敗と同じ `HTTPS artifact retrieval failed (TLS, timeout or size limit)` | 3 |
| 本文が8 MiB超（Content-Length・chunked・close-delimited） | `HTTPS artifact retrieval failed (TLS, timeout or size limit)` | 3 |
| Content-Length より短い本文で切断・chunk の途中切断・応答 header 前の切断 | `HTTPS artifact retrieval failed (TLS, timeout or size limit)` | 3 |
| 本文が完了した200以外（201/204/206、300/301/302/303/304/307/308、401/403/404/500。204 は本文なし） | `HTTPS artifact requires status 200; redirects are forbidden` | 3 |
| 302/404/500 で本文が上限超過・途中切断 | status より先に `HTTPS artifact retrieval failed (TLS, timeout or size limit)` | 3 |
| 200・空 body（Content-Length: 0） | 取得後の JSON 検査で `expected object: document` | 3 |
| 200・有効 artifact・8 MiBちょうど（末尾JSON空白を含む） | 成功。chunked・close-delimited でも同じ | 0 |
| Content-Length が送信本文より小さい（宣言長までが有効 artifact） | 宣言長までを取得して成功。後続 bytes は artifact に含めない | 0 |

Content-Length の値だけで上限超過と判定せず、HTTP framing に従って読んだ実 bytes を制限する。
ureq の `limit` は指定量を読み切るとエラーになるため、上限＋1バイトを probe し、8 MiBまでは受理する。
接続5秒・全体20秒の製品設定は変更しない。本文が未完了なら status の種類によらず全体期限が適用される。
`101` と本文を宣言した `204` は、java.net.http が status 判定より前に exchange を失敗させる
（`Exchange` の "Unexpected 101 response"、`MultiExchange.bodyIsPresent`）。Rust も応答 header の時点で同じ規則を適用する。
期限の fixture は製品の値をそのまま測る。接続 timeout は accept しない listen socket（backlog を先に埋める）への接続で
5秒以上19秒未満（全体20秒と区別）、本文 read timeout は header 後に止まる応答で19.5秒以上45秒未満、
送信途中で受信を止める `request-stall` は19.5秒以上30秒未満を要求し、期限を外すと落ちる。
待つだけの3形の呼出し（両 resolve × snapshot 有無）は別 folder で並行に実行し、Java も実 CLI を起動する。

共通 `spec-corpus/package-https-transport.json` の transport43形と URL150形を `HttpsPackageConformanceTest` から実行する。
JDK17/21 の独立した Java 実測を期待値とし、両 `deps resolve` を snapshot 有無の両方で照合する。
待たない形の Java `deps resolve` は、テスト専用の `CodegenBatchHost` が1つの JVM で `CodegenMain.run` を順に呼ぶ
（JVM 起動を1回にするため。呼出しごとに credentials override を読み直す）。両 JDK で、1呼出し1 JVM の実 CLI と
診断・exit・snapshot が全76呼出しで一致することを確認した。
生成は fetch しないため、3生成 CLI（Java-host Rust / native Rust / Java flag CLI）は `generate: true` の代表4形
（成功・302・Unicode path・Unicode と不正 percent）で snapshot 有無の両方を照合する。
失敗時の lock/cache 不変、成功時の raw bytes、認証の exact origin、TLS境界、redirect 先の接続0、
生成時の接続0と固定cache使用を検査する。空bodyの診断は transport ではなく後続 JSON 検査である。
43形はすべてローカル TLS/socket fixture の実測で、JDK17/21の診断に差はない。
HTTP/1.1 の境界・不正 framing は追加38形を同じメソッドで実行し、host 別期待値も skip しない（#972）。
Java 製品コード・診断は維持し、native は以下の安全側の契約を使う。
表の「取得失敗」は `HTTPS artifact retrieval failed (TLS, timeout or size limit)`（exit 3）、
「status」は `HTTPS artifact requires status 200; redirects are forbidden`（exit 3）を指す。

| 応答の形 | Java 17.0.12 / 21.0.9 | native（ureq 3.4.2）と判断理由 |
| --- | --- | --- |
| header 70 / 200 KiB | 両方成功 | 揃える。`max_response_header_size` を64から384 KiBへ変更し、入力 buffer も上限＋1 bytes にする。既定128 KiB buffer のままでは200 KiBを受け取れない |
| header 500 KiB / 2 MiB | 17成功、21取得失敗 | 公称384 KiBの厳しい21を参照し、native は取得失敗。17の parser はこの上限を持たず、2 MiBまで成功を実測（有限の上限が2 MiBという意味ではない） |
| header 上限の直前・直後 | この fixture の wire 393074 bytesまで21成功、393075以上は取得失敗。17は全境界成功 | native は status 行から空行までの wire 393216 bytesを包含的に受理、393217以上で取得失敗。会計方式の狭い差は恒久契約 |
| header 127 / 128 / 129 / 200行 | 両方成功 | 128行まで成功、129行以上は取得失敗。ureq-protoの固定 `MAX_RESPONSE_HEADERS = 128` に公開設定がなく、恒久差 |
| Content-Length と Transfer-Encoding の併記 | CLで読み、このfixtureは `invalid JSON` | 取得失敗。ureqがchunkedとして読むことを確認後、自前検査で拒否。RFC 9112 §6.3(3) の曖昧なframingをcacheへ入れない。拒否はstatus 200だけで、404/500等は本文を読み捨ててJavaと同じstatus診断 |
| `gzip, chunked` / `chunked, gzip` / TEの複数行 | close-delimited等で読み、このfixtureは `invalid JSON` | 取得失敗。ureqはgzipを復号せずchunkedと解釈するので、単独のchunked以外を拒否。RFC 9112 §6.1・§6.3(4)。gzip転送のラベルだけを除去してartifactにしない。大文字小文字違い・前後の空白付きの単独 `chunked` は成功。非200は上と同じくstatus診断 |
| 同値の重複 Content-Length | 両方成功 | 成功を維持。RFC 9112 §6.3(5) が同値のリストを許容 |
| 異値の重複 Content-Length | 最初のCLで成功 | 取得失敗を維持。RFC 9112 §6.3(5) の回復不能なframingエラー |
| Content-Length の負数 / 正符号 | `-1`はclose-delimited、正符号は数値として成功 | 取得失敗を維持。RFC 9112 §6.2 の `1*DIGIT` を満たさない |
| `:` のない行 | 無視して成功 | 取得失敗を維持。RFC 9112 §5 のfield構文に反する行を独自に補修しない |
| obs-fold | 展開して成功 | 取得失敗を維持するbackend制約。RFC 9112 §5.2は送信を禁止するが、user agentには受信時の空白への展開を要求しており、nativeの拒否をRFC必須とは扱わない。独自parserでureqを緩めない |
| 404 等 + ureq が `.call()` で拒否する形（負のCL等） | status診断 | 取得失敗の恒久差。理由は次の行と同じで、`.call()` のエラーにはstatusが残らない |
| 204 + Content-Length: +0 | 両方status診断 | nativeは取得失敗という恒久差。ureq-protoはbody mode決定中にCLを拒否し、`.call()`のエラーにはstatus/headerが残らない。取得後の検査だけで安全にstatus診断へ写せない |

JDK21の `jdk.http.maxHeaderSize` の既定値は384 KiBだが、status長＋32、各fieldの名前長＋値長＋32と、
解析途中の行長でも検査する。ureqの上限はwire bytesなので完全な同一境界ではない。
fixtureの4 headerの場合はJDK21の会計がwireより140 bytes大きく、最後の行の途中検査がさらに2 bytesを要する。
reason phraseや生の空白はureqの公開Responseに保持されず、受信後に厳密なJDK会計を復元できない。
公称384 KiBの有限上限を設定し、この差を `error`（上限のあるJDK）・`javaJdk17Error`（旧17）・`rustError` に固定する。
更新版JDK17にも上限がbackportされる。CIで使用可能なTemurin 17.0.20-101にも同じfieldがあるため、
既存のテスト専用 `CodegenBatchHost --http-header-limit` でparserの `MAX_HTTP_HEADER_SIZE` fieldの有無だけを調べる
（値の読取り・module opensは不要）。`javaJdk17Error` はこのfieldがない17だけに適用し、更新版17は上限のある期待値を使う。
NFCのJDK17/21別期待値は従来どおりmajor versionで選び、このheader判定と混同しない。
証跡の `javaHeaderAccounting` は `limited` / `unlimited` だけを記録する。
JDKのsystem propertyによる上限変更の模倣はしない。
CL+TEと`gzip, chunked`は、修正前nativeがserverのchunk内のartifactと同じlock/cache raw bytesを保存することも確認する。
成功扱いで曖昧な応答をcacheに入れる経路を残さず、Java側の寛容さを変更しない。
仕様根拠: [RFC 9112 §5](https://www.rfc-editor.org/rfc/rfc9112.html#section-5)、
[§5.2](https://www.rfc-editor.org/rfc/rfc9112.html#section-5.2)、
[§6.1–6.3](https://www.rfc-editor.org/rfc/rfc9112.html#section-6.1)。
証跡 `target/rust-package-transport.json` には既知の診断・exit・件数・hash・所要時間だけを記録し、
資格情報・header値・server body・任意の例外文を残さない。

共通 `spec-corpus/package-credentials.json` の65件を、両 `deps resolve` に既存 lock/cache の有無両方で与える。
Java を独立の期待値として、最終診断・終了コード・成功時の lock/cache raw bytes・失敗時の snapshot 不変・
ローカル TLS server が受け取る Authorization を比較する。生成は資格情報を読まず、固定 cache だけを使用する。
Java-host Rust / native Rust / Java flag CLI の生成は、`generate: true` の代表5形（受理・既定パス・
明示ファイルなし・読取り権限なし・不正 JSON）で同じ2状態を照合し、ネットワーク不使用と資格情報非依存を確かめる。
生成を全形で繰り返さないため、CLI 起動は650回から290回（Java は390回から160回）になる（#954）。
読取り権限なしの形は、root など chmod(000) を読める実行ユーザーでは除外を記録し、CI（非 root）では除外を許さない。
stdio の authoring も同じ読取り専用経路を使い、明示 resolve を持たないため、credentials 用の stdio ケースは追加しない。
検証記録には header 値を残さず一致/欠落だけを記録する。TLS失敗の2形も #956 の共通診断で照合する。

`HttpsPackageConformanceTest` はローカル TLS server を使い、認証、lock/hash と生成物、
未信頼 TLS/hostname、別 origin、redirect、容量、本文 timeout、remote→local 拒否、
server 停止後のオフライン生成を両 CLI で照合する。CI は `rust-package-https.tsv` と
`rust-package-credentials.json`（件数・所要時間・値を含まない比較結果）を保存する。

## CLI のロケールとファイル名（#966）

Linux の Java CLI は OS の argv・環境変数・passwd home を JVM の
`sun.jnu.encoding` で復号し、`Path.of` は同じ文字コードへ再変換する。
Oracle JDK17.0.12 / 21.0.9 では `LC_ALL` が未設定なら `LANG`、両方未設定または
有効値が `C` なら US-ASCII、`C.UTF-8` なら UTF-8 になる。`LC_ALL` は `LANG` に優先する。
CI 自身のロケールは変更せず、子だけで全9組合せを検証する。

この制約による Java の失敗を native に移植しない。native は OS のパス bytes を保持し、
利用可能なファイルを読み書きできる契約を維持する。利用者の成功する操作を意図的に
失敗させる利益がなく、言語仕様・parser の機能差でもないための恒久差である。
Java 製品コード・診断・公開済み3.3.0・parser ホットパスは変更しない。

| パスの形 | Java（JDK17 / 21） | native Rust | 判断 |
| --- | --- | --- | --- |
| UTF-8 の非 ASCII manifest / grammar / 出力先 / import / credentials override / passwd home、有効ロケール `C` または未設定 | exit 3、最終行 `Malformed input or input contains unmappable characters: ...`。lock/cache・出力は不変 | exit 0、指定したファイルを使用 | JVM のパス変換による恒久差 |
| 同じ6形、有効ロケール `C.UTF-8` | exit 0 | exit 0、同じ lock/cache・生成 bytes | 一致を固定 |
| 非 UTF-8 の `raw-FF`（FF は単一 byte）の manifest、`C.UTF-8` | exit 3、`E-PACKAGE: missing manifest; run unlaxer deps resolve --manifest ubnf.json` | exit 0、生 bytes の名前を使用 | JVM が FF を U+FFFD に復号する恒久差 |
| 同じ生バイト名の grammar / import を含む親ディレクトリ | exit 4、置換後の絶対パス（`raw-�`） | exit 0、生 bytes の名前を使用 | 同上 |
| 同じ生バイト名の出力先 | exit 0、`raw-�` という **別の名前** を作る | exit 0、元の FF を含む名前を作る | ファイル名は恒久差。両方の生成内容 bytes は一致 |
| 同じ生バイト名の credentials override | exit 3、`E-PACKAGE: artifact credentials file is missing` | exit 0、指定ファイルでローカル TLS 取得 | JVM の復号による恒久差 |
| 同じ生バイト名の passwd home | 置換後の home に既定 credentials がなく、fixture の私有 CA を得られない。exit 3、`E-PACKAGE: HTTPS artifact retrieval failed (TLS, timeout or size limit)` | exit 0、元の home の credentials を使用 | 同上。Java の診断は TLS fixture の条件下の値 |
| 生バイト名、有効ロケール `C` または未設定（ASCII 名の symlink を経る import を除く） | exit 3、`Malformed input ...` | exit 0 | JVM のパス変換による恒久差 |
| ASCII 名の import symlink の target に生バイト名がある | 両ロケールで exit 0。target の bytes は OS が辿る | exit 0 | 同じ生成 bytes |
| ASCII の manifest 本文の `local:` source、ASCII の credentials 本文の `caCertificate` が非 ASCII のパス、有効ロケール `C` または未設定 | exit 3、`E-PACKAGE: cannot read local artifact` / `E-PACKAGE: cannot read artifact CA certificate` | exit 0、指定したファイルを使用 | 本文から読んだパスも JVM の変換による恒久差。`C.UTF-8` では両 host が同じ lock/cache で成功 |
| 存在しない非 ASCII の grammar、有効ロケール `C` または未設定 | exit 3、`Malformed input ...` | exit 4、そのパス | 両方失敗するが、Java は I/O の前にパス変換で失敗する恒久差。native はロケールに依らず本来の I/O 診断を返し、`C.UTF-8` では両 host とも exit 4 で一致 |

UTF-8 の grammar 本文には FF のファイル名を直接書けない。共通 fixture は任意の
置換文字をその名前の代用にせず、ASCII import → 生バイト target の symlink と、
生バイト親ディレクトリ → ASCII の相対 import の両境界を測る。
UTF-16 の単独 surrogate を文法に通す #959 は扱わない。
argv は OS bytes から復号するため、`é😀` は C で6個の置換文字になる。
UTF-8 本文から読む import の `é😀` は2コードポイントのままで、最終 stderr は
ASCII encoder により `??` と表示する。この区別も独立した実 CLI の期待値で固定する。
非 UTF-8 ロケールの Java は本文由来の文字を含む stdout/stderr も同じ encoder で `?` にする
（例: 構文エラーの `UBNF パース失敗: ... é😀` は `UBNF ?????: ... ??`、exit 3 は一致）。
native は端末の文字コードを推測せず、ロケールに依らず UTF-8 で出力する。これも JVM の
コンソール文字コードによる表示の恒久差で、構文エラー文面自体の差は #937 の意図した差である。

**本文 bytes はファイル名と別の契約**とする。JDK17 の `file.encoding` は C で
US-ASCII、JDK21 は UTF-8 だが、対象 CLI の grammar / manifest / credentials reader と
生成 writer は UTF-8 を使う。fixture の grammar・manifest・credentials・artifact の本文は
`\u` escape ではなく生の UTF-8 bytes で書き、その性質も setup で検査する。ASCII パス上の非 ASCII grammar・manifest・credentials・
取得 cache・生成物・Playground/profile の bytes は両 JDK/native とも一致する。
不正 UTF-8 grammar/import は `Input length = 1` / exit 4、manifest は
`E-PACKAGE: invalid JSON` / exit 3、credentials は
`E-PACKAGE: cannot read artifact credentials` / exit 3。
既存の手書き出力は `refusing to overwrite non-generated file ...` / exit 4。
これらの両方失敗する形は診断・exit・全入力/lock/cache/出力不変を一致させる。
今回の実測では本文 I/O や共通失敗条件に実装修正を要する差はなかった。

共通 `spec-corpus/cli-locale-paths.json` の27形を既存
`CliStderrConformanceTest#localePathsRetainHostLimitsAndUtf8FileBytes` で実行する。
9環境 × 両 host × 既存 lock/cache 有無の972観測を、host 別の固定期待値と照合する。
生バイト名は証跡のツリーで hex にして lossless に記録し、生成ファイルは全内容の
SHA-256 を固定する。成功時も意図しない別名・追加ファイルを検出する。
Java は同じ環境内を `CodegenBatchHost --locale-paths` の1 JVMにまとめ、OS argv の
復号と実 CLI の stdout/stderr encoder を再現する。`--unbatched` は実 CLI の独立照合用。
JVM の起動と `sun.jnu.encoding` / default charset を毎環境で確認し、fixture を skip しない。
credentials は child-only の passwd/HOME shim、ローカル TLS、ダミー値だけを使う。
実ユーザーの credentials は開かず、前後の metadata 不変も検査する。
結果と runner のロケールは `target/cli-stderr-locale.json` に記録する。
既存 cli shard と jdk17-parity の必須メソッド登録で両 JDK が実行する。

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
Java の既存 strict decoder と利用者に見える文言を参照とし、Rust は package JSON reader に渡す前に
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

### package JSON の受理境界（#914 / #926 / #941、残り #939）

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
| 空文書 / JSON 空白だけ（BOM の有無を問わない） | `E-PACKAGE: expected object: document` |
| 非 object の最上位（`null` / 配列 / 数値 / 文字列 / `true` / `false`、BOM の有無を問わない） | `E-PACKAGE: invalid JSON` |
| 文書全体の object / array の深さが255以下 | 受理後に既存の field 検査。無視される metadata も同じ |
| 深さ256以上（上書きされる重複 member の値も含む） | `E-PACKAGE: invalid JSON` |
| BOM + 不正 JSON | `E-PACKAGE: invalid JSON` |
| 空白の後の BOM / JSON token 間の U+FEFF | `E-PACKAGE: invalid JSON` |
| JSON 文字列内の U+FEFF | 文字列の一部として保持 |
| BOM + 不正 UTF-8 | manifest/lock は `invalid JSON`、artifact は `invalid UTF-8 artifact` |
| cache の raw hash 不一致 | BOM・UTF-8・JSON の検証より先に `artifact hash mismatch: <id>` |

JSON 空白は space / tab / CR / LF の4種類だけで、form feed / vertical tab は `invalid JSON`。
Gson の `fromJson(String, JsonObject.class)` は空文書で Java の `null` を返す一方、JSON の `null` は
`JsonSyntaxException` になるため、両者の診断を分ける（#941）。
深さは最上位 object を1と数える。例えば `{"meta":` に配列254段を続けた文書は受理し、255段は拒否する。
Gson 2.13.2 STRICT の既定 nesting limit を実 JDK17 / JDK21 で測定し、旧 Rust 境界127/128と
Gson 境界255/256の前後、8 MiB までの入れ子を確認した。後者も `JsonSyntaxException` で拒否され、
`StackOverflowError` は観測しなかった。Java の設定・実装は変えず、Rust も255を超えて受理しない。
object の検査順は下記 #942 のとおり Java の記述順に揃える。
HTTPS の credentials ファイルは上記の資格情報節の規則を使う（#943）。空文書と非 object の診断は package 文書と異なる。

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

Rust は package JSON 解析が失敗した場合だけ、JSON 文字列中の単独サロゲート escape を検出する。
正常な組、escape された backslash/quote は変えない。下記の package 専用 reader が JSON 全体の構造・数値・
末尾余剰入力を、既存 `serde_json` が各文字列と一般の escape を検査する。重複 member は最後の値を使用する。
内部 marker は既存 NUL と区別して符号化し、既存 NUL を復元する。単独サロゲートを含む metadata は
ASCII 検証または不正パス検証を通らない NUL sentinel にし、既存の検証順で診断する。
そのため schema/version エラーが source の取得より先に出て、正常な lock/cache の固定 load も維持される。
外部 JSON parser / crate は追加せず、UBNF parser のホットパスには処理を追加しない。

### package の UTF-16 文字列境界（#939）

Java の Gson / SourceReader / FileSystemPort と受理集合は変更しない。Rust は package 入力の
`Text` に通常の `Box<str>` と、単独サロゲートを含む `Box<[u16]>` を持たせる。
後者を使うのは artifact の entry / files key・本文、manifest / artifact の HTTPS source だけ。
既存の rewrite marker をこの型へ復元し、上位・下位・壊れた組と本来の NUL を区別する。
正常な対は従来の Unicode scalar。文字列の照合は lossless で、異なるサロゲート、U+FFFD、
利用者が書いた marker 風の文字列は同じ key にならない。重複 key は最初の位置・最後の値を維持する。
`Text` は24 bytes、入力 `Value` は32 bytesを保ち、通常入力を UTF-16 に変換しない。
新しい crate / serde_json feature は追加せず、parser のホットパスと公開済み3.3.0は変更しない。

| 境界 | Java を参照した結果 |
| --- | --- |
| JSON に単独サロゲート escape がある | JSON の構文・深さを最後まで検査後、schemaVersion → ID/version → files → entry → dependencies の既存順へ進む |
| files key / entry の path | UTF-16 units にも空・ISO制御文字・`\`・先頭 `/`・`:` を先に検査し、その後で空成分・`.`・`..` を検査する |
| 対応 file がない entry | `E-PACKAGE: missing UBNF package entry` |
| 未使用の files key / 本文 | 受理。取得・変換せず、resolve は元の artifact bytes と同じ lock を保存する |
| 対応 file がある非 scalar entry | resolve は成功。Linux の読取りでは Java UnixPath と同じ `Malformed input or input contains unmappable characters: <name>` で失敗する。診断を wire に出す時だけ JDK と同じ `?` 表記になり、別名のファイルを開かない |
| HTTPS source / 固定 cache load | source の URI 化・credentials 読取り・ネットワークを行わない |
| HTTPS source の host / query / fragment / userinfo / port | Java の `URI.create` が受理した後、host が null（registry authority）・userinfo・query・fragment・port 0 / 65536以上なら `HTTPS artifact URL must omit credentials, query and fragment`。`URI.create` が拒否する構文（space・NBSP 等の空白文字、不正 percent escape、path の `[` `]`、2つ目の `#` など）は authority の位置によらず `invalid HTTPS artifact URL` |
| HTTPS source の path | Java は URI を受理し、credentials → CA → token 検査の後で HTTP encoding が失敗する。Rust も同じ順で検査し `HTTPS artifact retrieval failed (TLS, timeout or size limit)` を返す |

非 scalar URL は、`rust/unlaxer-generator/src/packages/java_uri.rs` が JDK17/21 の `URI.create`（RFC 2396 parser）を
UTF-16 units 上にそのまま移植した判定で分類し、credentials の origin（小文字 host、443 以外の port）だけを得る。
置換した URL や percent escape した URL は作らず、HTTP request も組み立てない。credentials → CA → token の検査後、必ず transport の前で拒否する。
移植した判定は JDK17/21 の `URI.create` と、単独サロゲートを含む fuzz 8万件・ユニットテスト58形で一致を確認した。
ASCII scalar URL は #962、Unicode scalar URL は #960 で同じ parser を使う。
Unicode scalar の送信だけは上記の NFC/percent encoding を行う。非 scalar URL には適用しない。
未使用 credential の型、CA と token の優先順位、既存の exact origin・TLS・redirect・timeout・上限は維持する。
#956 の transport 診断契約と #958 の credentials 場所選択も変更しない。
この単位の path 診断は self-hosted Linux / JDK17・21 の境界で検証する。

本文を実際に UBNF として読む場合は、package 型だけでは Rust の `&str` frontend、String の AST、
source snapshot と生成出力まで UTF-16 units を保持できない。Java の成功する comment や未使用 literal を
U+FFFD / `?` / 私用文字に置換して解析する方法は採らず、**この残差は #959 に分離する**。
Rust はその読取り境界で `E-PACKAGE: package source contains unpaired UTF-16 surrogate` を返す。
resolve と未使用本文はこの拒否の対象ではない。Java では使用する literal が解析を通っても生成ファイルの
UTF-8 書込みで `Input length = 1`（exit 4、部分生成物あり）になるため、JSON 拒否と同一視しない。
Java の `ubnf/packageSource` も、内部 UTF-16 String と stdio encoder が `?` を出す実 wire を区別して測る。
元の13 gap のうち10件は一致し、3 comment の resolve も一致する。残る3件の load と追加した実読取りの
計18件は #959 の host 別期待値で実行し、完了扱いにしない。

正常な対の HTTPS host / userinfo の診断と path の request target は #960 で上記の共通契約へ揃えた。
package JSON corpus にあった4診断差と2 request target差の host 別期待値は削除し、共通期待値で実行する。

共通 `spec-corpus/package-json-acceptance.json` は481件（既存400＋#939 の81）。両実 stdio では全件を実行し、
本文の代表では definition の UTF-16 range・packageSource・後続 request も照合する。
既存400件の生成は維持し、追加81件は `generate: true` の13形だけで Java-host Rust / native Rust / Java flag CLI の
生成を比較する（失敗段階・正常対・未使用本文・read-only HTTPS を含む）。manifest / artifact と resolve 対象 lock は
両 `deps resolve` に既存 snapshot の有無両方で与え、成功時の raw lock/cache bytes と失敗時の snapshot 不変を比較する。
HTTPS の新規ケースはローカル TLS server のみを使い、固定 cache の生成中は request 0、非 scalar URL の resolve も
request 0を必須とする。資格情報・header 値は出力しない。生成失敗の新規ケースでは Java の部分出力も観測する。

#941 は空文書・非 object・BOM・配列/object の深さ境界、深い単独サロゲート・不正 escape・末尾余剰入力・
上書きされる深い値・files の非文字列を含む。8 MiB の入れ子はテスト内で生成し、巨大な fixture を保存しない。
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

Rust の `packages/value.rs` は入力だけの木を持つ。member は記述順の `Vec`、key 順の索引は
同じ `Vec` の要素に置き、各 key は一度だけ保持する。lookup は二分探索、object の等値比較は key 順で行う。
#947 で数値トークンをそのまま保持するため、#941 の `packages/value/depth.rs` の明示スタックを
浅い入力にも共用する。object / array / 区切り / 数値の構文を検査し、key・文字列・真偽値・null の復号は
既存 serde_json に任せる。`serde_json::Value` や numeric map protocol を入力に使わないため、
DAP の `arbitrary_precision` が workspace で統合されても、package の受理と診断は単独 build と同じになる。
重複 key は一時 BTreeMap で逐次置換し、最初の位置と最後の値を保持する。深さは最上位を含め最大255 frame。
既存の surrogate 復元・木の解放も255段以下に制限し、debug / release の2 MiB thread stackで検証する。

数値は変換せず表記を保持する。22 bytes 以下は inline、それより長い表記だけを heap に置くため、
小さい数値の配列で個別の割当てを増やさず、`Value` は32 bytes（従来の serde_json::Value 以下）を維持する。
lock の出力には検証済みの文字列参照だけを既存の辞書順 JSON object へ移す。
未使用 metadata とその数値を再エンコードせず、cache は元の artifact bytes のままとする。

新しい crate / feature / license 依存は追加しない。#940 の BOM・サロゲート復元はこの木に適用し、
raw bytes の reader・hash・UTF-8 の順序は変えない。この表現は package 入力だけに限定し、
DAP / LSP の一般 JSON、UBNF parser のホットパスには適用しない。credentials は #943 で
この reader を共用するが、JsonElement の受理と固定 field の検査を別経路で行う。
#939 は上記 Text により file/entry/HTTPS を復元する。実読取りの未修正差は #959、通常 Unicode URL は #960 の上記契約で検査する。

#941 の再測定（Linux x86_64、Rust 1.85.0、`deps resolve`、各3回の中央値、時間 s / peak RSS MiB）。
基点は #946 merge 後の `a40c7513`。比較には同じ入力と固定した standalone binary を使う。
共有マシンなので時間には実行負荷による変動があるが、通常入力のメモリ水準を維持する。
性能作業の凍結（#197）に従い、性能改善や CI の速度判定は追加していない。

| 入力 | 基点 release | #941 release |
| --- | --- | --- |
| 数値400万個の配列 | 0.25 / 133.4 | 0.25 / 133.8 |
| `{"a":{"b":0}}` 50万個の配列 | 1.98 / 849.4 | 1.68 / 849.4 |
| 60万 key の object | 0.63 / 208.3 | 0.66 / 207.9 |
| 55万 key の逆順 object（値は配列） | 0.76 / 265.4 | 0.84 / 265.1 |
| 10 key を計110万回書く重複 object | 0.17 / 10.1 | 0.18 / 9.9 |
| 配列120段 | <0.01 / 3.9 | <0.01 / 3.9 |

深い入力でも reader の8 MiB上限を維持する。最大深さ255の数値配列を横に伸ばす場合と、
入力全体を入れ子にして256段で拒否する場合の測定は次のとおり。

| 入力サイズ | 深さ255で受理 release | 同 debug | 深さ超過で拒否 release | 同 debug |
| --- | --- | --- | --- | --- |
| 約1 MiB | 0.05 / 20.8 | 0.19 / 25.4 | <0.01 / 5.7 | 0.01 / 10.1 |
| 約2 MiB | 0.09 / 37.7 | 0.39 / 42.1 | <0.01 / 7.8 | 0.02 / 12.2 |
| 約4 MiB | 0.18 / 71.8 | 0.88 / 76.2 | 0.01 / 11.6 | 0.04 / 16.3 |
| 8 MiB | 0.45 / 139.5 | 1.76 / 143.0 | 0.05 / 19.7 | 0.09 / 24.0 |

深さ255の object で重複 key を増やす入力も1/2/4/8 MiBで測定した。
release は0.02/0.04/0.08/0.14 s、debug は0.18/0.30/0.86/1.43 sで、
8 MiB時の peak RSS は11.7/16.0 MiBだった。入力サイズに対する二乗の増加は観測しなかった。
深さ255/256・単独サロゲートとの組合せ・8 MiBの入れ子は、明示的に2 MiB stackを指定した
Rust threadでも debug / release の両方で検証し、stack overflow は起きなかった。
巨大入力は生成して測り、repository に実体は置かない。

| object / 経路 | 入力の検査・走査順と観測可能な結果 | 出力の扱い |
| --- | --- | --- |
| manifest / lock / artifact の最上位と各固定 field | JSON 全体を読む。その後は既存の schema → 対象 field の固定順で検査し、最上位 member の並べ替えでは優先順を変えない。未知 metadata は使わない | artifact は元の bytes を保持 |
| manifest `dependencies` の検証 | 記述順に ID → dependency の object 型 → version → source 型を検査。複数の不正 member の最初の診断に影響 | roots の出力は辞書順 |
| manifest `dependencies` の `resolveOne` | 記述順に深さ優先で取得・内容検査。最初の取得失敗、identity conflict、cycle / 上限診断に影響 | 全 graph 成功後だけ lock/cache を書く |
| artifact `files` | 記述順に path → 本文の型を検査。複数の不正 file の最初の診断に影響。その後の entry は key lookup。path は空・制御文字・backslash・先頭 `/`・`:`、次に各成分の空・`.`・`..` の順で検査 (#947) | files を再エンコードしない。cache は raw bytes |
| artifact `dependencies` の検証・明示 resolve | 記述順に field 検証、その後同じ順の深さ優先走査 | pinned dependencies の出力は辞書順 |
| lock `packages` の audit | 記述順に ID、locked package の object 型、pinned ID/version/SHA、各 dependencies を検査。最初の診断に影響 | 読取りでは lock を変更しない |
| lock `packages.*.dependencies` の audit | 記述順に ID → version → locked dependency の object 型 → pinned version の文字列型・一致を検査。最初の診断に影響 | 同上 |
| lock `roots` | object 型を確認し、manifest から作った参照と順序を無視して比較。不正な追加/値の変更は audit より先に stale lock。その後は記述順で pinned version を確認 | 同上 |
| lock `packages` / `packages.*.dependencies` の cycle audit | 各 object の記述順で DFS。複数 cycle があると報告する ID に影響 | 同上 |
| artifact の dependencies から作る `actual`（固定 cache load） | 記述順を保持して pinned 参照と順序を無視して比較し、同順に子を load。複数 cache の hash 不正などで最初の診断に影響 | 検証済み親は全依存の成功後だけ memo に載せる |
| lock `packages` の `owner` | 記述順で SHA を照合し、最初に一致した所有者を選ぶ。正常な artifact は ID / raw hash 検証も通る必要がある | identity JSON の field 順は既存どおり |
| lock / cache の生成 | Java は `TreeMap` で packages、roots、pinned dependencies と cache hash を整列。Rust は出力時だけ既存の辞書順へ変換 | lock の field 順・末尾 LF と既存 corpus の raw bytes を維持 |
| generator / LSP の利用 | 上記の検証・走査を共用。失敗は最初の原因、CLI の既存 exit code、LSP の `E-MODULE`・severity 1・import 全体の UTF-16 range に現れる | 正常時の生成物・LSP JSON の順序は既存どおり |
| `package_fetch` の credentials | JSON 全体を検査した後、origin / caCertificate / bearerToken の固定 key lookup。未知 member の schema 検査はしない (#943) | 資格情報は lock/cache に書かない |

#942 の共通69件は ID/version、単独サロゲート ID、files、roots、重複 key と escape、
package / dependency の cycle、直接・推移的 fetch、固定 cache load の順序を含む。
両実 stdio の全診断と後続 completion / hover、3 CLI、両 `deps resolve` を照合する。
lock 用の新 fixture も resolve へ渡し、入力 lock の不正を読み直さず正常な lock に再生成することを確認する。
成功ケースは記述順を逆にした multi-package graph の lock/cache を Java と固定 byte 期待値に比較し、
失敗ケースは既存 snapshot の有無両方で不変を検証する。既存127件の期待値は維持する。


### package JSON の schema と数値（#947）

Java（Gson 2.13.2 STRICT、UBNFPackageResolver）を参照し、Java の経路・受理集合・診断文は変更しない。
文書全体の JSON 検査後、既存の固定 field 順と上表の記述順で次を検査する。

| 入力 | 診断または受理 |
| --- | --- |
| manifest / artifact の `dependencies` の値が非 object（文字列・配列・重複 key の最後の値を含む） | ID の検査後に `expected object: dependency` |
| artifact の files key / entry が空、ISO制御文字（U+0000–001F / U+007F–009F）、backslash、先頭 `/`、`:` を含む | `invalid package file path`。本文型や成分の検査より先 |
| path 成分が空・`.`・`..` | `package file path escapes boundary` |
| lock の roots が欠落・非 object | stale lock 比較より先に `expected object: roots` |
| lock の packages に root がない | `expected object: locked root` |
| lock の packages の値が非 object | `expected object: locked package` |
| locked dependency の参照先が欠落・非 object | `expected object: locked dependency` |
| 参照先の version が非文字列 | 一致比較より先に `expected string: version` |
| 未使用 metadata の `1e999` / `-1e999` / 巨大整数 / `1e-999` | 数値に変換せず受理 |
| `schemaVersion` | JSON 表記が `1` の数値だけを受理。`1.0` / `1e0` / `1E+0` / `1e999` / 文字列 / object は `unsupported schemaVersion` |
| 数値を version / source / file 本文などの文字列用 field に置く | その field の既存の型診断。数値の範囲による `invalid JSON` にしない |
| 数値トークンの長さ | Gson の reader buffer に合わせ1023文字まで受理、1024文字以上は `invalid JSON`。未使用でも同じ |
| `{"$serde_json::private::Number":"1.5"}` など | 通常の object。key の位置・値によらず数値と解釈しない。単独 / workspace build 共通 |

追加38件は各 schema 行・優先順・使用/未使用の数値・1023/1024境界・深さ255と単独サロゲートの
組合せを含む。数値の代表は各文書で同じ metadata 配列にまとめ、同型のケース数を増やさない。
既存362件を保持し、両実 stdio・3 CLI・両 deps resolve を実行する。新 lock ケースの resolve は
入力 lock を検査せず再生成する既存仕様も確認する。Java の独立 oracle は JDK17 / JDK21 で同一。

#947 のメモリ退行確認（基点 `1f9cdace`、同一入力・固定 standalone release binary、Rust 1.85.0、
Linux x86_64、3回の中央値。時間 s / peak RSS MiB）。性能改善や CI 時間の判定は追加しない (#197)。

| 入力 | 基点 | #947 |
| --- | --- | --- |
| 数値400万個の配列 | 0.26 / 133.8 | 0.17 / 133.0 |
| `{"a":{"b":0}}` 50万個の配列 | 1.55 / 850.0 | 0.23 / 131.9 |
| 60万 key の object | 0.82 / 208.8 | 0.46 / 155.0 |
| 55万 key の逆順 object（値は `[0]`） | 0.51 / 265.9 | 0.35 / 218.0 |
| 10 key を計110万回書く重複 object | 0.10 / 11.3 | 0.13 / 10.5 |
| 配列120段 | <0.01 / 4.2 | <0.01 / 3.4 |


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
は #937 の下記 UTF-8 表で解消する。

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
出力先の書込み失敗の文言差は下記 #937 で解消する）。

権限 fixture は同じ実行ユーザーで `chmod(000)` の読取りが `PermissionDenied` になることを先に確認する。
root / `CAP_DAC_OVERRIDE` 等で読める環境では入口/import の各1形を組合せから除外し、
証跡 JSON の `excluded` に理由を残す。JUnit を skip したり権限拒否が成功したと数えたりしない。
通常の非 root CI は全400観測（両 host、LF/CRLF）と生成後 `--check` 16回を実行し、必須3メソッドの skip は0とする。
GitHub Actions 上（`GITHUB_ACTIONS` あり）では除外が1件でもあればテストを失敗させ、権限 fixture が黙って抜けないようにする。


## CLI の書込み・UTF-8・readiness 診断（#937）

参照は変更していない Java `CodegenMain`（Oracle JDK 17.0.12 / 21.0.9）。
`spec-corpus/cli-stderr.json` は実 Java CLI から確認して固定した独立期待値で、実行時に
片方の出力から期待値を作らない。両 CLI に LF/CRLF を送り、終了コード・stdout 全文・stderr 最終行と
実行前後のファイルツリー（既存ファイルの bytes・mode を含む）を照合する。

### 出力先 I/O

`W` は作業ディレクトリ、`D` は生成物の出力先、`P` は失敗したパス。
`generate` の書込み段階は `cannot write D: <原因>`、Playground は原因文を直接表示する。
Rust の数値 errno 接尾辞は付けない。失敗時の終了コード4と空 stdout は従来どおり。

| OS / 失敗箇所 | Java 実 CLI の最終行（Rust も同じ） |
| --- | --- |
| EEXIST: `out` が通常ファイル、generate | `cannot write out/mod.rs: out`（createDirectories の最初の mkdir） |
| ENOTDIR: `file/out` の `file` が通常ファイル、generate | `cannot write file/out/mod.rs: W/file/out: Not a directory`（絶対パスで親から再試行） |
| EACCES: `ro` が555、未作成 `ro/out`、generate | `cannot write ro/out/mod.rs: W/ro/out` |
| EACCES: `ro` が555、出力先 `ro`、generate | `cannot write ro/mod.rs: ro/mod.rs` |
| EACCES: `ro` が555、Playground `ro/out` | `W/ro/.ubnf-playground-<乱数>` |
| EEXIST: Playground `file/out` の親 `file` が通常ファイル | `W/file` |
| 既存出力先: Playground `out`（通常ファイル / directory） | `output already exists; choose a new directory: W/out`。mkdir 前に拒否する。 |
| EISDIR 相当: generate の `out/mod.rs` が directory | `refusing to overwrite non-generated file out/mod.rs`。実 CLI は書込み前の preflight で拒否するため、EISDIR 自体は発生しない。書込み中の EISDIR は `cannot write D: P: Is a directory`。 |
| その他: ENAMETOOLONG（256文字の成分 X）、generate `X/out` | `cannot write X/out/mod.rs: W/X: File name too long` |
| 同じ ENAMETOOLONG、Playground `X/out` | `W/X: File name too long` |
| 書込み失敗（EFBIG。ENOSPC / EIO と同じ経路）、generate | `cannot write out/mod.rs: File too large`。開いた channel への write の失敗は Java ではパスを付けない |
| 同じ書込み失敗、Playground | `Generation failed; partial staging retained at W/.ubnf-playground-<乱数>: File too large` |
| EACCES: 既存 `out` が555で `out/grammar_a` などの子 directory は書込み可、複数 grammar の generate | `cannot write out/mod.rs: out/mod.rs`。Java の書込み順どおり子 directory の生成物を書いた後、root の `mod.rs` で失敗する |
| `--check`: 生成物が無い・directory・出力先が通常ファイル | generate は `generated file differs: out/mod.rs`、Playground は `generated file differs: W/out/src/generated/mod.rs`（`Files.isRegularFile` が偽） |
| `--check` / 上書き前検査: 既存生成物が不正 UTF-8（例 `E2 82 20`） | `Input length = 3`（`Files.readString` の長さ。下記 UTF-8 表） |

Java の `Files.createDirectories` に合わせ、最初に指定パスを mkdir し、EEXIST 以外の失敗は
絶対パスの既存親から作成を試みる。symlink 拒否と native の排他的な一時ファイル作成・置換は維持する。
一時ファイルは各生成物と同じ directory に作る。Java が生成物ごとにその directory へ直接書く順序と
権限の判定を保つためである。意図した差として、書込み失敗時に Java は途中まで書いた
（`RLIMIT_FSIZE` 0 では空の）生成物を残すが、native の通常の rename 経路では一時ファイルを消して既存の生成物を切り詰めない。
#971 の hard link と一時ファイル作成権限のない directory の2経路だけは、下記のとおり Java と同じその場書込みに退避する。
fixture はこの違いを host ごとの作成物一覧として固定する。
Playground の乱数/PID部分だけを `.ubnf-playground-${TEMP}` に正規化し、親パスを比較から除かない。
対象 fixture では staging が作られる前に失敗し、一時ディレクトリも残らない。
生成途中の失敗で task-owned staging を保持する既存の両版の契約は変更しない。
権限 probe は同じ実行ユーザーで555の directory に書けないことを確かめる。書ける環境では
該当 fixture を理由付きで除外するが、GitHub Actions では除外を失敗とし、JUnit skip は許さない。

### 不正 UTF-8

入口の generate/check/Playground は `Files.readString`、inspect の入口と全 local import は
bounded reader の `CharsetDecoder` が Java の参照であり、両者の malformed length は同一ではない。
Rust は通常入力を一度だけ strict decode し、失敗した場合だけ invalid suffix から同じ長さを求める。

| 不正 bytes（先頭の正常な bytes / LF/CRLF は長さに影響しない） | Files.readString | strict decoder |
| --- | --- | --- |
| `FF`, `80`, `C2`, `C2 20`, `C0 80` | `Input length = 1` | `Input length = 1` |
| `E2 82`（末尾途切れ） | `Input length = 1` | `Input length = 2` |
| `E2 20` | `Input length = 2` | `Input length = 1` |
| `E2 82 20` | `Input length = 3` | `Input length = 2` |
| `E0 80 80`（過長） | `Input length = 3` | `Input length = 1` |
| `ED A0 80`（surrogate） | `Input length = 3` | `Input length = 3` |
| `ED A0`, `ED A0 20` | `Input length = 1`, `3` | `Input length = 2`, `2` |
| `F0 9F 98`（末尾途切れ） | `Input length = 1` | `Input length = 3` |
| `F0 9F 98 20` | `Input length = 4` | `Input length = 3` |
| `F0 80 80 80`, `F4 90 80 80`, `F5 80 80 80` | `Input length = 4` | `Input length = 1` |

入口は inspect が終了3、他は4（check のみ `I/O error: ` 接頭辞）。import は inspect が3、
generate が4、check/Playground は3と従来の `P-MODULE`。check の JSON は変更しない。
同じ local reader を使う LSP の import message にもこの文が届く。package artifact の
`E-PACKAGE: invalid UTF-8 artifact`、raw hash → strict UTF-8 → JSON と reader 上限は #924 のまま。

### Playground readiness と構文エラー

Playground は `P-MODULE` だけの特別扱いをやめ、全診断を Java record と同じ
`[Diagnostic[code=..., span=Span[start=..., end=...], subject=...], ...]` に描画する。
位置が無ければ `span=null`。順序・重複・code point 範囲・subject の改行や引用符をそのまま保ち、
JSON の escape や severity を混ぜない。grammar / profile / package は同じ描画を使う。
構文、構造、inventory、module、parameterized rule の各失敗を共通 fixture で固定し、
check は従来の JSON 全体を独立期待値と照合する。

意図した差として、generate / deps inspect（と `playground --profile` の参照 grammar、#953）の構文エラーは Java の
`UBNF パース失敗: <ソースの先頭80文字（UTF-16 単位）>`（未消費の入力がある場合は
`UBNF パース失敗: <行:列> に未消費の入力があります: <続く80文字>`）と、native の行・列・原因付き診断を維持する。
Java の文は複数行のソースを含むため、stderr 最終行がソースの途中の行になることがある。
利用者が誤りの位置を特定できる既存の情報を削らず、parser ホットパスを変更しない最小の選択である。
単一行と複数行・非BMP・LF/CRLF の両 host の最終行を固定し、終了3・空 stdout・無出力を検証する。
この差は parity ledger の `cli-stderr-presentation` / #937 に記録する。


#937 の調査で見つけた旧 token 宣言の余分なセミコロンの診断選択差は #949 で修正した。
`grammar Main { token X = REGEX('x'); @root Start ::= X; }` は、Java 17/21 と native の
check がともに `P-SYNTAX`（span=null、subject=`UBNF syntax`）を選ぶ。
セミコロンのない旧宣言は `E-REGEX-RETIRED` のまま拒否する。ファイル全体の構文認識を
移行診断より優先し、Playground の record 表示にも同じ選択を反映する。
両版・LF/CRLF・import・非BMP・独立期待値・mutation の詳細は
[REGEX 移行診断](regex-retirement.md)を参照。#983 では CHAR_RANGE・escape・字句 range・
重複 annotation 引数も保留し、後続の構文不正を優先する。規則本体の native 入れ子上限で
先を読めなくなる残差は #996 で追跡する。共通 `value` suite の79形・2,528観測は、
既存 `regex` / `qualified` suite と同じ1 JVMバッチ・出力先/staging不在の検査を使う。
CLI から到達する readiness の50 code は本変更の48入力で固定する。
手組みASTだけの `P-GRAMMAR-COUNT` / `P-GRAMMAR-NAME` / `P-TOKEN-KIND` も同じ分岐のない描画を使うが、
実 CLI の corpus 件数には含めない。

### 名前空間参照の空白（#986）

Java `RuleRefElementParser` を基準に、native frontend も `(IDENTIFIER '.')*` の
**接頭辞だけ**を連続した字句として読む。設定・import・adapter 等の別の dotted name 構文は変えない。

| 参照 | 構文認識 |
| --- | --- |
| `a.N` / `a.b.N` | 受理 |
| `a .N` / `a.b .N` / `a. b.N` | `P-SYNTAX` |
| `a// comment` 改行 `.N` / `a.// comment` 改行 `b.N` | `P-SYNTAX` |
| `a. N` / `a.b. N` / `a.// comment` 改行 `N` | 受理（最後の名前の前には grammar trivia を許す） |

最後の名前の前まで禁止すると Java の既存の受理から退行するため、この境界を維持する。
構文不正の入口 check は `P-SYNTAX` / span=null / subject=`UBNF syntax`、
local import の入口 check / Playground は既存の `P-MODULE` ラップを保つ。
旧 REGEX 宣言が先行しても、全体の構文不正を移行診断より優先する（#949）。
generate / deps inspect は出力の作成前に終了3となり、エラー本文は上記 #937 の host 別表示を保つ。

`spec-corpus/cli-stderr.json` の `qualified` suite は18形×入口/local import と
正常な token import 2形の38ケースを固定する。LF/CRLF・非BMPを含む両実CLIの608観測と
成功後の `--check` 16回で、check JSON全文・全stderr・失敗段階・出力/staging不在、
成功時の全生成物の名前/type/mode/SHA-256 を比較する。生成物の固定値は変更前 Java の出力を
変更前/後 native とも比較して確定し、試験中に他方の host から期待値を作らない。
同じ表の frontend 36入力は Java の通常/snapshot API と native の AST または全位置付きエラーに照合する。
既存の authoring LSP `spaced-namespace-*` の編集拒否は維持する。

構文として正しい未解決参照の generate は、既存の Java validator の詳細と native の
`E-RULE-UNDEFINED: unsupported qualified rule reference` という説明を維持する（#843）。
rule を含む module の拒否の元パスは [#988](https://github.com/opaopa6969/unlaxer-parser/issues/988) で
共通期待値へ統合し、下記の4形を加えた42ケース・672観測に拡張する。
この名前空間参照の修正では値検証の順序を変えない（別途 #983）。Java の製品コード・診断文、公開3.3.0 は変更しない。

## CLI の出力先パス（#952）

参照は Java `CodegenMain`。Java 製品コード、上記 #932/#934 の入力 I/O 表、#937 の出力 I/O 表、
check JSON と正常な生成物の内容は変更しない。`W` は作業ディレクトリ。

| 操作・前提 | 契約（空 stdout・終了4と書かれた失敗以外は終了0） |
| --- | --- |
| generate `link/out`、`link` が directory symlink | 意図した差: Java は実体先へ生成。native は空 stdout・終了4、`output directory must not have a symbolic link ancestor: link`。意図しない実体先への書込みを防ぐ既存方針を維持する。`link/.`・`link/../out`・`--check` も同じ方針。 |
| generate の祖先が dangling / ループ / file への symlink（`dang/out` など） | 両版とも空 stdout・終了4。最終行だけが意図した差で、Java は `cannot write dang/out/mod.rs: W/dang` などの作成失敗、native は上と同じ祖先拒否文。 |
| generate `out/l` が symlink、または `out` が dangling symlink | 両版とも空 stdout・終了4、`output directory must not be a symbolic link: P`。 |
| generate `out/../out2`、`out` が無い | 両版とも `out2/` だけを作り、空 stdout・終了4、`cannot write out/../out2/mod.rs: out/../out2/mod.rs`。`out` が既存 directory なら成功し、stdout は `in out/../out2` のまま。 |
| generate の既存生成物 `out/mod.rs` が mode 444 | 書込み権限の無いユーザーでは空 stdout・終了4、`cannot write out/mod.rs: out/mod.rs`。既存ファイルの bytes/mode は不変。一時ファイルや置換後のファイルを残さない。 |
| generate `file/./out` / `file/.`、`file` が通常ファイル | 両版とも空 stdout・終了4、`cannot write file/./out/mod.rs: W/file/.: Not a directory` / `cannot write file/./mod.rs: W/file`。Java の `toAbsolutePath`・`getParent` と同じく `.` を残したまま既存の親を探す。 |
| generate の生成先 directory `m/grammar_a` が symlink で、その中の `mod.rs` も symlink | 両版とも空 stdout・終了4、`refusing symbolic link m/grammar_a`。Java の `validateGeneratedParents` と同じく近い親から検査し、ファイル自体は最後。 |
| generate `out/` / `out//x` | stdout は `Generated 5 Rust module files in out` / `in out/x`（末尾 LF）。`--check` は `Verified`。`.`・`..` はここで畳まない。 |
| Playground `./pg` / `pg/` / `pg/.` / `pg//x` / `pg/../pg2` | stdout は絶対・字句正規化した `W/pg` / `W/pg/x` / `W/pg2`。`Generated` / `Verified` と続く `Next:` 行の他の内容は維持。 |
| Playground `missing/../pg2`、`pg2` が既存 file / directory | 空 stdout・終了4、`output already exists; choose a new directory: W/pg2`。`missing/` や staging を作らない。`pg2` が無ければ `W/pg2` への生成が成功し、やはり `missing/` は作らない。 |
| Playground `missing/../pg2 --check`、生成物なし | 空 stdout・終了4、`generated file differs: W/pg2/src/generated/mod.rs`。書込みは無い。 |

generate の directory 作成は Java `Files.createDirectories` と同じく、指定されたパスへの作成を
まず試み、失敗時だけ既存の親から `relativize` した成分を順に作る。この相対化では `..` の対を畳むが、
その後のファイル open は元のパスで行う。Playground は正規化前後の両方で symlink を検査するので、
`link/../pg2` の `link` を正規化で隠すことはできない。

generate の祖先拒否は引数の綴りに現れる成分だけを `lstat` で検査する。相対パスでは作業ディレクトリより上を見ないが、
絶対パスでは `/` まで見るので、macOS の `/tmp` のように symlink を含む綴りは拒否される（Java の Playground と同じ性質）。
検査は書込み前の1回で、検査後に祖先が symlink に差し替わる競合は防がない（Java も同じ）。

mode 444 の判定は各生成物の書込み順で、既存ファイルを truncate せず書込み用に open する。
Unix では `O_NOFOLLOW | O_NONBLOCK` を付け、事前検査後に末尾成分が symlink へ差し替わっても追わない。
失敗時にそれ以前の生成物だけが残る順序も Java と一致する。
#937 の各生成物と同じ directory への排他的な一時ファイル作成・rename と、書込み失敗時に
既存生成物を切り詰めない契約は、下記 #971 の2つの退避経路を除いて維持する。

`spec-corpus/cli-stderr.json` の追加 `paths` suite は33形、LF/CRLF・両実 CLI の244観測と、
成功後の `--check` 114回。終了コード・stdout全文・stderr最終行・全ツリーの名前/type/mode/内容ハッシュを
固定期待値で検証する。正常な5生成ファイルと67 Playgroundファイルの期待値は変更前の Java と
native の bytes 一致を確認して固定した。Playground root の既存 mode 差（Java の一時 directory は700、
native は umask 022 で755）は今回変更せず host 別の期待値に記録する。
chmod(444) の file と chmod(555) の directory は実行ユーザーで別々に probe し、効かない場合だけ
理由付きで除外する。CI は除外0・skip0を必須とする。
既存 `CliStderrConformanceTest` に必須メソッドを追加し、cli shard と JDK17 契約の両方で実行する。
成功 stdout の変更に伴う移行手順は [3.3.0 以降の移行](migration-after-3.3.0.md#native-cli-の出力先パス952)を参照。


## 既存生成物の mode・hard link・directory 権限（#971）

Java `CodegenMain` の `Files.writeString` が参照実装で、Java 製品コードは変更しない。
native の `generate` は通常、既存ファイルを truncate せず書込み用に open して権限を確認し、
同じ directory の一時ファイルへ書き、既存の permission bits を写してから rename する。
既存ファイルがあるとき一時ファイルは mode 600 で作り、内容を書き始める前に既存の permission bits を写す。
書込み後には写し直さないので、Java のその場書込みと同じく非 root の書込みで set-user-ID と
group execute 付きの set-group-ID が OS により解除される。
新規ファイルには既存 mode を写さず、`0666 & ~umask` を用いる。

| 形 | Java / native の結果 |
| --- | --- |
| 既存生成物が mode 600 / 640 | 成功し、既存の mode を保つ。native は rename 前に mode を写す。 |
| 既存生成物が mode 4755 / 2755 / 2645、hard link 付き 4755 | 成功し、非 root では 755 / 755 / 2645 / 755 になる（書込みによる解除）。 |
| 既存生成物の nlink > 1 | 成功し、全 hard link の内容と nlink を保つ。native は既に開いた同じ handle へのその場書込みに切り替える。 |
| 出力 directory が555、既存生成物は書込み可能 | 成功し、mode・nlink を保つ。一時ファイル作成が PermissionDenied になったときだけ、既存の書込み用 handle へその場書込みする。 |
| 既存生成物が444、非 root | 両方とも終了4。既存内容は変えず、後続ファイルを書かない（#952 のまま）。 |
| 新規ファイル、umask 022 / 002 / 077 | 両方とも mode 644 / 664 / 600。新規 Playground の生成物も同じ。Playground root の Java 700 / native umask 由来の既存差は #952 のまま。 |
| root で既存生成物が444（未実測の推定） | 書込み用 open が成功すれば両方とも成功して444を保持する見込み。runner は非 root、sudo 不可なので root の実測とは扱わない。 |

その場書込みへの退避は **hard link と既存ファイルの sibling 一時ファイルを作成できない権限拒否の2経路だけ**。
既に開いた handle を使い、truncate のためにパスを開き直さない。Unix の末尾 symlink 拒否
（`O_NOFOLLOW | O_NONBLOCK`）と既存の preflight は維持する。
衝突（AlreadyExists）・容量不足等の別の一時ファイル作成失敗では退避せず、元の失敗を返す。
新規出力しかない555 directory の失敗文・順序は #937 の表から変わらない。
Playground は既存出力先を拒否し新しい staging に書くので、その場書込みへの退避は発生しない。

この2経路では #937 の「書込み失敗時に既存生成物を切り詰めない」保証が Java と同じ水準へ下がる。
truncate 後の write に失敗すると空または部分内容を残し、hard link の別名にも反映される。
通常の rename 経路では失敗時に既存 bytes を保持する。ファイル群全体の transaction は保証しない。

**意図した差**: rename 経路は inode を置き換えるため、mode 以外の所有者・ACL・xattr は保存しない。
新しいファイルの所有者と directory 由来の ACL 等になる。Java のその場書込みは同じ inode の属性を保持する。
#937 の失敗時の bytes 保護を維持し、新しい依存 crate や特権による属性移植を加えない最小の選択である。
その場書込みの2経路では、同じ inode を保つため所有者・ACL・xattr も Java と同じく保たれる
（OS が書込みで更新・解除する属性は OS の規則による）。台帳 `cli-output-replacement-metadata` に記録する。

共通 `replacement` suite は15形、LF/CRLF・両 CLI の60観測と成功後 check 44回。
exit・stdout 全文・stderr 最終行と全ツリーの mode・nlink・内容ハッシュを独立期待値に照合する。
各 host の前に古い生成物を作り直し、一方の成功を他方の入力にしない。
通常成功ケースは既存 `CodegenBatchHost` から実 `CodegenMain.run` を1 JVMで呼び、
umask と RLIMIT_FSIZE を変えるケースは実 CLI process を起動する。
hard link / 555 directory の途中書込み失敗と通常 rename の既存 bytes 保護も対照検証する。
chmod(555/444) を迂回できる実行ユーザーと、set-ID を解除しない root では該当の非 root ケースだけを理由付き除外し、
root 専用の行は推定として残す。CI は非 root で除外0・JUnit skip0を必須とする。
cli shard と JDK17 の既存クラス登録を使い、両契約に新メソッドを必須登録する。

## Playground profile 入力の I/O エラー（#953）

`playground --profile` は Java `CodegenMain`（JDK17 / JDK21）を参照する。
profile 自体は `Path.of` 相当で連続・末尾 separator を整理した指定パスで読み、
参照 grammar は profile の親から解決した絶対・字句正規化パスで読む。
既存の `input::read_string` と module loader の `FileError` を使い、I/O の分類を CLI まで保持する。
Java 製品コード、既存の String を返す Rust API、`--grammar` の上記 I/O 表と `check` JSON は変更しない。

| 入力の形 | 終了コード・stderr 最終行 |
| --- | --- |
| profile の欠落・権限拒否 | 4、指定された profile パス（絶対化しない） |
| 参照 grammar の欠落・権限拒否 | 4、絶対・字句正規化した grammar パス |
| profile / 参照 grammar が directory | 4、`Is a directory` |
| profile / 参照 grammar の不正 UTF-8 | 4、`Input length = N`（既存の `Files.readString` の長さ規則） |
| 空 profile・BOM のみ・BOM 付き profile・末尾 LF なし・空行・NUL・CRLF | 3、`invalid language profile` |
| 参照 grammar の構文エラー（BOM 付き・空を含む） | 3、Java は `UBNF パース失敗: <ソースの先頭80文字>`、native は行・列・原因付き診断（意図した差） |
| profile の参照 grammar が local import を読めない | 4、module loader の原因文。欠落・権限は絶対パス、不正 UTF-8 は strict decoder の長さ、directory は `E-MODULE: local import is not a regular file` |

最後の行は profile の検証が readiness より前に module を解決する Java の順序による。
同じ import が `--grammar` で終了3の readiness 診断になる既存の契約とは入口が異なる。
参照 grammar の構文エラーも同じ順序で readiness より前に module loader が報告する。
`--grammar` の Playground は両 host とも `P-SYNTAX` の readiness 表示で一致するが、`--profile` では
generate / deps inspect と同じ「Playground readiness と構文エラー」の意図した差（#937）になる。
終了3・空 stdout・無出力は一致し、両 host の最終行を fixture の `nativeSyntax` で固定する。
上記失敗の stdout は空で、出力先・staging は作らず、入力の mode / bytes も変えない。
空白だけの option 値の引数検査は [Playground の空白値](#playground-の空白値974) を参照。

共通 `spec-corpus/cli-stderr.json` の `profile` suite は74形、LF/CRLF・両実 CLI の296観測。
不正 UTF-8 は profile / grammar ごとに24形（1〜4の長さ、末尾途切れ、過長、surrogate、
上限超過、BOM / 非BMP前置文字を含む）。TSV の LF 制約が grammar の失敗を隠さないよう、
`fixedFiles` の profile は LF のまま grammar を LF/CRLF にし、profile 自体を CRLF にした形も別途検証する。
正常な profile は68生成ファイルの全 bytes を固定ハッシュと照合し、生成後の `--check` 4回も検証する。
権限3形は実行ユーザーで `chmod(000)` の読取り拒否を probe し、効かない環境では理由付きで除外する。
CI では除外0・skip0を必須とする。新クラスは追加せず、既存 `CliStderrConformanceTest` の
必須メソッドを cli shard / JDK17 の両契約へ追加する。


## Playground の空白値（#974）

`--grammar` / `--profile` / `--package` / `--manifest` / `--output` は、空文字または
Java `String.isBlank()` と同じ空白だけの値を usage 誤り（終了2）として拒否する。
stdout は空で、出力先・staging を作らない。値を trim せず、空白で囲んだ非空白もそのまま渡す。
JDK17 / JDK21 の `Character.isWhitespace` を全 code point で実測した集合は同じ25文字:
`U+0009..U+000D`、`U+001C..U+0020`、`U+1680`、`U+2000..U+2006`、
`U+2008..U+200A`、`U+2028`、`U+2029`、`U+205F`、`U+3000`。
NBSP（`U+00A0`）・`U+2007`・`U+202F`・NEL（`U+0085`）は空白ではない。
Rust の `char::is_whitespace` とは集合が異なるため、native はこの集合を明示して判定する。

usage の `unlaxer ` 接頭辞は意図した差として維持する。Java は `Usage: playground ...`、
native は `Usage: unlaxer playground ...`。既存 native の generate / check / impact と同じく
実行ファイル名を含む案内を保ち、共通 fixture には両方の全文を固定する。
既存 docs / ledger に接頭辞を統一する規約はなかったため、この表示契約を台帳に明記した。
他サブコマンドの Java はこの値に `isBlank` を使わないので、空白拒否を一律には広げない。

既存 `CliStderrConformanceTest` の `blank` suite は、25空白文字・空文字・混合空白と、
上記非空白の境界（`U+180E` / `U+200B` / BOM / 空白で囲んだ文字列も含む）を5 optionへ渡す
175形・両実 CLI の350観測。JDK17 / JDK21 の両方で、exit・全 stdout・stderr 最終行・
全ツリー不変を独立期待値へ照合する。argv の制御文字は shell で展開せず subprocess へ直接渡す。
入力ファイルのない検査なので LF/CRLF の重複実行は行わない。既存のテスト専用
`CodegenBatchHost` から実際の `CodegenMain.run` を呼び、Java の起動を175回から1回にする。
全呼出しの前後で空の作業ツリーを検査し、他の suite は従来の実 CLI 起動を維持する。
既存 cli shard と JDK17 の必須メソッド契約へ追加し、新クラスや依存 crate は追加しない。

## rule を含む local module の拒否（#988）

Java の既存診断を基準に、native も `generate --target rust` / `deps inspect` で
`E-MODULE: only declarative token modules can be imported: <元ファイルの絶対パス>` を返す。
パスは入口や途中の bridge ではなく、拒否された module 自体の絶対・字句正規化したパスである。
構文認識後、module の import を辿る前に拒否し、終了3、入力不変、出力と staging 不在を維持する。
`check --format json` と Playground の `P-MODULE` は変えない。
authoring LSP も同じ loader を使うため、import 宣言の range と `import <alias>: ` を保ち、
診断 message の元パスが Java に揃う。失敗後の文書更新・要求も引き続き処理する。

`spec-corpus/cli-stderr.json` の qualified suite は、入口・推移的 import、
`é😀 space/part.ubnf`、LF/CRLF の4形を手書きの独立期待値で固定する。
追加ケースは Java の実 launcher と JVM 不要の native CLI、同じ入力の両 authoring stdio に送る。
既存の正常 declarative token import の生成 bytes と `--check` は維持する。
JVM がパスへ変換できない文字・生 bytes、および C ロケールの表示については、
上記「JVM のファイル名変換制約」（#966 / PR #991）と同じ恒久差とする。
native の UTF-8 表示や利用可能なパスの読取りを故意に失敗させない。
この非 ASCII module パスも C ロケールでは、両 JDK が module の読取り前に
`Malformed input or input contains unmappable characters: ?? space/part.ubnf`
（推移的 import の記述は `./?? space/part.ubnf`）で終了3となる。
native は同じ終了3で UTF-8 の絶対元パス付き `E-MODULE` を返す。

隣接する拒否のうち、`import requires exactly one grammar` の絶対元パスと
`network imports are unsupported` の URL が native に付かない表示差は #999 で追跡する。
両方とも generate / deps inspect の終了3と無出力は一致する。#988 は rule を含む module の
拒否だけを扱い、この2つの既存診断は変更しない。
