# memo key の matched cursor reset policy

Javaのsafe failure/success memo keyには `resetMatchedWithConsumed` が含まれます (#621)。
同じparser・consumed/matched位置・TokenKind・invert・stateVersionでも、transactionの開始時に
matchedをconsumedへ戻すかどうかで受理とtokenの原文範囲が変わるためです。

例えば `input="ba"`, consumed0/matched1 でpure Choice('a')をmatchOnlyとして呼ぶと、
reset=falseなら原文'a'（CP1..2）に成功します。外側をrollbackしてreset=trueに変えた次の呼出しは
'b'を先頭に見るため失敗します。policy変更前の成功entryは利用できません。
入力'xa'/Choice('x')では逆に、古い失敗entryの再利用も誤りになります。

現在policyを含むkeyは `PackratMemoTable.positionKeyOf(context, kind, invert)` から取得できます。
既存public `PositionKey(consumed, matched, kind, invert, stateVersion)` constructorはsource/binary互換のため
残し、歴史的な既定値reset=trueを表します。policy=falseを明示構築する場合は6引数constructor、
通常のcontext利用ではpositionKeyOfを使ってください。recordのcomponent一覧には新booleanが加わるため、
reflectionやrecord patternでcomponent一覧を固定している利用者は更新が必要です。

Chainがlookup前にpolicy=falseへ正規化する既存挙動は維持します。異なるpolicyで偶然同じ結果となる入力でも
keyは分離します。内部helper/反復へpolicyが伝播するため、開始時にconsumed==matchedであることだけから
policyを捨てる最適化は行いません。保持窓のtestはpolicyを固定し、windowの再保存と別の問題を混ぜません。

Rustのfailure keyはreset/nonOrderedを保持します。後続の [#628 Classic memo](classic-memo.md) は
kind/invertも区別して実failure/success hitを提供します。この14ケースのRust warm/nextは別grammar sessionで
あるためentryを共有せず、ON/OFF一致は意味検証です。同一grammar session内の別probeでは
通常/Classic双方でreset切替時のmissと同policyへ戻したときの実hitを独立検証します。

[共通の14ケースと再現手順](../conformance/memo-reset-policy/)を参照してください。
Java/Rustのpure success/stateful failure全体は引き続き#184/#197で追跡します。
