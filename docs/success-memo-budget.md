# Success memo snapshot の保持予算 (#652)

Java/Rust の memoization は既定 OFF のままです。safe success replay の対象を広げず、
保存できない結果は通常解析の成功をそのまま返す cache miss とします。
[#644](failure-memo-budget.md) の failure 予算は別で、変更していません。

| 成功用予算 | Java system property | Rust environment variable | 既定 | 設定天井 |
| --- | --- | --- | ---: | ---: |
| 件数 | `unlaxer.memo.success.maxEntries` | `UNLAXER_SUCCESS_MEMO_MAX_ENTRIES` | 65,536 | 1,048,576 |
| 合計 charge | `unlaxer.memo.success.maxBytes` | `UNLAXER_SUCCESS_MEMO_MAX_BYTES` | 16 MiB | 1 GiB |
| 単体 charge | `unlaxer.memo.success.maxEntryBytes` | `UNLAXER_SUCCESS_MEMO_MAX_ENTRY_BYTES` | 2 MiB | 64 MiB |

最初の初期化時に一度だけ読みます。0 は保存停止です。空でないASCII数字列だけを受理し、
空文字、不正、符号付き（`-0` / `+1`を含む）、overflow、天井超過は既定へ戻します。
既存entryが満杯でも置換は古いchargeを差し引いて判定し、不採用では古い結果を残します。
evictionで件数/chargeを減らし、rollback後の再保存にも同じ予算を適用します。
同一epochの元entryが残っていれば、rollback後も実際のsuccess hitが可能です。

## コピー前の判定と所有権

Javaは再帰的・override可能なpublic `Token.deepCopy()`をmemoから呼びません。
既存のpublic deepCopy自体の契約は変更していません。

1. 残る件数・合計・単体予算を確認してから、トポロジーとmetadata参照値の有限planを作ります。
2. planはcollectionによるparent変更の前に、parser/children/filtered projection/metadata mapの値を捕捉します。
3. commit/endParse後の最終diagnosticを含めて再判定し、合格して初めてCSTをコピーします。
4. 保持planをコピー済みTokenへ向け直し、元のTokenや可変child listへの参照を残しません。
   replayも非再帰コピーなので、返却treeの変更が次hitのchild/parent/metadata mapへ漏れません。

visited/pending/metadata projectionも予算に比例する有限量です。cycle、共有node/DAG、null、
不正filtered edge、未知Token/TokenList subclass、未知Name subclassは保存対象外です。
Sourceはexact `StringSource` / `RangedString`と既知のparent/rootだけを許可し、Source ancestryの
確認回数にも上限を置きます。未知Sourceへcallbackして安全性を推測しません。
copy時はSource/Parserのconstructor callbackやタグ判定を再実行しません。
このcache用対象外判定は通常解析結果を変えません。

## Chargeの範囲

**cache-owned構造のchargeであり、RSS上限ではありません。** 両言語の同一byteレイアウトでもありません。

* Java: entry/key/diagnostic、stack、期待値、trialと残存capacity、transaction eventに加え、
  bounded topology、Token/Range、child/filtered list、choice、metadata map/参照projectionを計数します。
  Tokenごとの基礎512 bytes、可視tokenStringのUTF-16長ごと8 bytes、child edge24/filtered edge16 bytes、
  非空metadata map128 bytesと各pair128 bytesを加算します。共有データも保守的に重複計数します。
* Rust: 既存のコピー前Node/child/capture/journal/TokenInfo/owned projected textに、key/diagnosticの基礎、
  expected vectorの保持capacityと期待名UTF-8長のchargeを含めます。件数と索引成長も有限にします。
* immutable Source/root/Parserの共有graph、任意opaque metadata値やrelated-token値の外部graph、
  active/global diagnostics/interner、呼出側が保持する返却tree/Entryは全体上限の対象外です。
  Javaのmetadata値/related値は既存どおり浅い参照であり、任意host objectを深くcloneする保証ではありません。
  Rustのsuccess snapshotは従来どおりportable extra/related metadataを保存せず安全にmissします。

Javaの可視text長の加算は、大きなmaterialized text/投影payloadを固定Token料金だけで保存しないためです。
共有Source全体の再帰走査や、元入力のCP配列をTokenごとに複製する処理ではありません。
共有位置索引はpayload chargeとは別枠で成長を制限します。RSSや共有root graphまで有界とは呼びません。

Java `PackratMemoTable` の `successEntryCount()` / `successRetainedBytes()` / `successBudgetMisses()`、
Rust `ParseContext` の `success_memo_entries()` / `success_memo_retained_bytes()` /
`success_memo_budget_misses()` で観測できます。Rustは現在のgrammar sessionを観測します。
予算による不採用には、有限・安全なplanとして表せない構造も含みます。unsafe/observer gateのmissとは別です。

[共通oracle](../conformance/success-memo-budget/README.md) は実hit、両cursor、原文とfamily emoji/CRLF、
CP/UTF16/UTF8、CST/元token、capture、ID/metadataの隔離、diagnostics、stateとrollbackを固定します。
既存のobserver/trace、unsafe custom gate、資源depthと2MiB再帰条件は維持します。
以前のno-retry約41%/約62% overheadとdefault OFFを撤回する変更ではなく、親 #184/#197 の
production workload等の別条件は未完のままです。
