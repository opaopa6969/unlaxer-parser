# 移行ガイド: unlaxer-parser 2.x → 3.x

[English](./migration-2.x-to-3.x.md)

unlaxer-parser 2.x (2.6.0 – 2.8.0) から 3.x 系へ移行する downstream プロジェクト向けのガイドです。実際の downstream 移行で報告された breaking change ([#27](https://github.com/opaopa6969/unlaxer-parser/issues/27), [#28](https://github.com/opaopa6969/unlaxer-parser/issues/28)) を集約しています。

## チェックリスト

- [ ] `pom.xml` の `unlaxer-common` / `unlaxer-dsl` を最新 3.x へ bump
- [ ] `new StringSource(...)` を `StringSource.createRootSource(...)` に置換
- [ ] 削除された文字列系クラス (`StringBase`, `StringSource2`, `StringIndexAccessor*`) を下表に従い置換
- [ ] タイポクラス `WildCardStringTerninatorParser` → `WildCardStringTerminatorParser` に修正
- [ ] codegen 利用時: CLI エントリポイント `UbnfCodeGenerator` → `CodegenMain` に変更
- [ ] `UBNFAST.TokenDecl` を `instanceof` 判定している場合: sealed interface 化に追従
- [ ] `@mapping(..., params=[...])` 利用時: params が位置順であることを確認
- [ ] `GrammarValidator.validateWithWarnings(grammar)` を実行 (または `CodegenMain` を実行 — 検証警告が stderr に自動報告されます) し、`W-LEFT-RECURSION` / `W-TOKEN-UNRESOLVED` を解消
- [ ] `mvn clean test`

## unlaxer-common の breaking change

| 削除・変更 (2.x) | 置換先 (3.x) | 備考 |
|---|---|---|
| `new StringSource(String)` | `StringSource.createRootSource(String)` | StringSource/StringSource2 統合で削除 |
| `StringSource2` | `StringSource` | リネーム・統合 |
| `StringBase` (1,042行) | Java 21 標準の `String` / `Character` API | コードポイント単位のアクセスは `CodePointAccessor` 参照 |
| `StringIndexAccessor`, `StringIndexAccessorImpl` | `CodePointAccessor` | |
| `WildCardStringTerninatorParser` (タイポ) | `WildCardStringTerminatorParser` | コンストラクタ: `WildCardStringTerminatorParser(boolean, Parser)` |

変更が**ない** API (onigiri-parser の移行で実証, #27): `CodePointIndex`, `Range`, `RangesRelation`, `Specifier`, `FactoryBoundCache`, `Singletons`, `org.unlaxer.util.collection.*`。

## unlaxer-dsl の breaking change (codegen 利用者のみ)

| 変更 (2.x) | 3.x | 備考 |
|---|---|---|
| `org.unlaxer.dsl.UbnfCodeGenerator` (CLI main) | `org.unlaxer.dsl.CodegenMain` | `exec-maven-plugin` の `<mainClass>` を更新 |
| `UBNFAST.TokenDecl` (class) | sealed interface: `Simple` / `Until` / `Negation` / `Lookahead` / `NegativeLookahead` | `instanceof` 判定を更新 |
| `@mapping` params の順序 | 厳密に位置順、codegen 時に検証 | 順序が異なる `params=` はビルド警告となり、誤った Mapper を生成する可能性 |

## 事前検証

アップグレード前に 3.x のバリデータで grammar を検証してください:

```java
import org.unlaxer.dsl.bootstrap.UBNFMapper;
import org.unlaxer.dsl.codegen.GrammarValidator;

var file = UBNFMapper.parse(Files.readString(Path.of("your.ubnf")));
for (var grammar : file.grammars()) {
    GrammarValidator.validate(grammar).forEach(System.err::println);          // エラー + 警告
    GrammarValidator.validateWithWarnings(grammar)                            // 左再帰サイクル
        .ifPresent(warnings -> warnings.forEach(System.err::println));
}
```

または CLI を実行するだけでも、全検証警告が stderr に出力されます (`--strict` / `--fail-on warning` でエラー化も可能):

```bash
java -cp ... org.unlaxer.dsl.CodegenMain --grammar your.ubnf --output out --validate-only
```

代表的な警告と対処:

- `W-TOKEN-UNRESOLVED` — token 宣言が非修飾クラス名を使用。完全修飾名にしてください。hint に候補が列挙されます (例: `Did you mean 'org.unlaxer.parser.elementary.NumberParser'?`)
- `W-LEFT-RECURSION` — 左再帰サイクルを検出。繰り返し (`A ::= B { Op B }`) または右再帰に書き換えてください。警告のみですが、コンビネータパーサーでは左再帰は parse 時に停止しません

## Deprecation ポリシー (3.x 以降)

public API の削除・改名は、削除前に最低 1 マイナーバージョンの `@Deprecated` ブリッジ期間を設けます。上記の削除は本ポリシー制定前 (3.0.0 で直接削除) のものであり、再発防止のためにポリシーを明文化しました。README の「API Deprecation Policy」も参照してください。
