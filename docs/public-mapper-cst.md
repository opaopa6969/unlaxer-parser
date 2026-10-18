# 公開 mapper と可変 CST の境界

この防御は更新後に再生成した mapper に適用する。以前生成した source は再生成が必要。

生成された Java mapper の `mapParsedToken` / `mapSubtreeToken` とその SourceMap・preferred snapshot 入口、および Rust の `map` / `map_parsed_tree` / `map_subtree_tree` は、投影前に CST の構造を検証する。構造不正は Java の `IllegalArgumentException`、Rust の `Err(String)` となり、診断には `E-MAPPING-CST` を含む。資源上限は `E-MAPPING-LIMIT` となる。 Java の null 引数は従来の `rootToken must not be null` / `subtreeToken must not be null` を保持し、その入口の引数検証を先に行う。Rust の存在しない root ID は構造不正として拒否する。通常の型変換・typeof・必須捕捉・recovery 等の失敗契約も引き続き適用する。

検査は公開 CST を改変する consumer の誤りを境界で止めるためのもので、元 parser が実際にこの CST を生成したという認証ではない。Rust の通常 `map` は従来どおり grammar fingerprint を要求せず、preferred 入口は既存の fingerprint と rule metadata を検証する。Java の元 root / alternate-entry parser class 検証も維持する。

## 構造

* Rust: root、node/child/capture ID、rule ID、source と内部 CP→UTF-8 表、node/capture span を検証する。arena は既存契約どおり postorder で、自己参照・forward edge を拒否する。capture の参照先は owner の children から到達できる元 subtree に属する必要がある。共有 descendant と shortcut は許す。
* Java: null root/child、欠落 metadata、不正 CP span、original/filtered の循環を、再帰を使わず検査する。filtered child は original subtree の descendant でなければならない。AST 用に中間 token を除去した平坦化、同じ token を複数回参照する DAG は許す。original graph のうち filtered root から隠れた node も検査する。
* 両言語とも parent consumed span に child span を閉じ込めない。先読みの match-only child は親の consumed span より広くなり得る。Java の detached/generated/projected source をこの一般検査だけで一律拒否しない。AST 捕捉の input-only 契約は対応する捕捉処理で別途検証する。
* Java の mutable parent backpointer は検索対象にしない。CST の読取り中の並行改変は契約外。任意実装の Parser/Source メソッドの非停止や外部副作用を sandbox 化する機能ではない。

## 資源

通常と preferred の両入口で、最大 node 数 1,048,576、edge 数 8,388,608、CST 高さ 256、実投影 work 8,388,608、要求する text の累計 UTF-8 byte 数 268,435,456 を上限とする。Java は到達可能な original/filtered graph、Rust は public arena 全体を検査する。内部 CST 表現の違いにより、同じ文法でも node/edge/work 数は一致するとは限らない。

通常の original tree / forest は、一度の iterative DFS で作る祖先区間を使う。索引構築は O(node + original edge)、各 filtered / capture shortcut の厳密な子孫判定は O(1) であり、平坦化された子ごとに元 subtree を繰り返し探索しない。判定は node identity に基づき、source span の包含を仮定しない。同じ original parent からの重複 edge は索引を利用できるが、実投影の反復訪問は従来どおり計上する。複数の異なる original parent を持つ DAG は従来の上限付き正確な探索を保つ。公開 Token の mutable parent backpointer は索引に使用しない。

構造検査は bounded な table と iterative stack を使う。通常の走査は node/edge 数に比例し、capture/filtered shortcut の到達性検査には独立した 8,388,608 work 上限を適用する。Rust の private offset 検証は入力も単一走査するが、新しい全文座標表や入力のコピーを作らない。preferred snapshot は従来の所有契約に従い、検証成功後に原文と CP 境界表を保持するため、別途 O(input) の出力領域を必要とする。Java は text を materialize する前に CP accessor で UTF-8 長を計数し、同じ token の計数結果をその mapping call 内だけ保持する。

DAG の静的な children/capture union 経路数を投影回数とみなさない。合法な capture shortcut を二重経路として指数的に数えてしまうため、実際の mapper/helper 呼出しと走査を call-local budget に計上する。node の訪問には、その node の child/capture 数も加算する。nested snapshot は既存の局所上限に加えて、累計 snapshot element/text を計上する。

Rust preferred `MappingLimits` の既存既定値（candidate 4,096、projection work 1,000,000）は維持する。`projection_work` は実訪問に適用し、明示値にも通常 mapper の絶対 work 上限を適用する。Java preferred snapshot の候補数は 4,096 まで。

これらは process 全体の RAM 上限ではない。入力と既存 CST、AST/string/object の allocator overhead、consumer が保持する古い snapshot、JVM/Rust 実行系のメモリは含まない。検証 table/stack は node/edge 上限により制限し、投影 work/text は増殖する DAG と text copy の累計を制限する。nested snapshot 一個分の局所割当ては累計計上より先に行うため、その局所上限分の一時領域も必要となる。

## 失敗後と検証

budget と検証 table は呼出し単位。Java は各公開 mapping 入口で memo/span layer を新規化し、投影失敗時に部分 memo/span を破棄する。成功して既に返した source snapshot は保持され、後の失敗や別 parse/map で内容を変えない。Rust の公開 map は呼出しのローカル state を破棄して `Err` を返す。

共通 corpus は `spec-corpus/public-mapper-cst/`。1/64/512/2,048 個の補助文字 Leaf を含む同じ入力について、通常・source snapshot・preferred の各入口で値・全 node の CP 範囲・候補数・retained snapshot を独立期待値と比較する。2,048 個は preferred の既存 4,096 候補上限内であり、検証 work 上限の引上げを必要としない。`PublicMapperCstConformanceTest` は Java consumer と Java/native 両 host の Rust 生成 consumer を実 compile/run し、入口ごとの不正グラフ分類、共有 DAG の実投影上限、失敗後の正常値、補助文字の CP span、retained snapshot を照合する。型の正常系は既存の preferred・typed nested・mapped leaf・family・enum・typeof corpus を併用する。

追加の合成試験は `composition-cases.json`。scalar/flat/node/enum/mixed/nested/family/typeof/recovery の代表9文法で、6入口の不正metadata拒否後の正常CSTによる値・CP span・保持snapshotをJavaと両Rust生成consumerで比較する。Rustのtransparent typeof検査は既存ASTを参照し、参照展開数をworkに計上する。Javaの公開Token入口のrecovery検査はguardで訪問済みのunique original tokenのmarkerを使い、DAGを診断一覧へ全展開しない。
