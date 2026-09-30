# Token adapter contract corpus (schema 1)

Java / Rust の登録・解決・位置付き診断・生成・実行を共通入力で検証する。
構文と API は [token adapter 契約](../../docs/token-adapters.md) を参照。

- `cases.tsv`: UBNF ファイル名、`check` の structure、順序付き診断 code（`-` はなし）。
  両 host で LF/CRLF の JSON 全体・終了コードを照合する。診断の範囲は登録 setting または
  参照 token 全体であり、重複は後出の登録位置を指す。
  nullability / memo-safety の構造拒否は既存の `P-STRUCTURE` と grammar 全体の範囲を使う。
- `runtime.json`: custom ID `example.word` の独立した Java / Rust 実装をコンパイルし、
  prefix success / consumed / matched、全入力受理、capture、AST の code-point span を比較する。
  実装は `😀` → `x` の順に読むため、途中失敗と、その後の choice の rollback も検査できる。
- built-in の意味は既存の `tiny-string-token` / `tiny-code-fence` corpus を使う。
  実 TinyExpression の固定クラスを Java oracle とし、legacy FQN と新 ADAPTER 構文の
  両方で同じ期待値を検査する。同名 test double を oracle にしない。

## 登録 block の schema

`@tokenAdapter: { ... }` は、次の四つの key をそれぞれ一度だけ持つ。
値は UBNF の single-quoted string。schema 1 はこの文書での契約名であり、
未実装の JSON manifest 読み込みや CLI option を示すものではない。

| key | 値 |
|---|---|
| `id` | case-sensitive な `[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*` |
| `version` | ASCII 十進数字、正規化した整数は `1..2147483647` |
| `java` | ASCII identifier を `.` で連結した2要素以上の Java source class 名 |
| `rust` | ASCII identifier を `::` で連結した2要素以上の Rust function path |

class/path の識別子は `[_A-Za-z][_A-Za-z0-9]*` の限定集合で、予約語等を除く。
Rust の先頭 `crate/self/super` は許可する。完全な Java/Rust 識別子構文を取り込まず、
raw identifier、型引数、マクロ、式、任意のコード断片は受け付けない。

同じ `(id, version)` の重複登録は拒否する。同じ ID の別 version の登録、複数 token alias から
同じ登録を使うことは可能。無効な登録は追加せず、独立した参照エラーも収集する。
version の raw 字句を syntax AST に保持し、0 / overflow を AST 成功後に位置付きで診断する。

登録内容は意味的な同値性の証明ではない。外部 class/function の実在・署名は target compiler が
検査し、受理範囲や副作用の一致は実装者が共通 corpus で検証する。
