# Unlaxer Parser エコシステム

[English](./README.md) | [日本語](./README.ja.md)

Unlaxer は、Java のための包括的なパーサ開発エコシステムです。強力なパーサコンビネータライブラリと、パーサを自動生成するための高レベル DSL で構成されています。

## プロジェクト構成

本リポジトリは、Unlaxer パーサシステムのコアコンポーネントを含むモノレポです：

- **[unlaxer-common](./unlaxer-common/README.ja.md)**: コア・パーサコンビネータライブラリ
- **[unlaxer-dsl](./unlaxer-dsl/README.ja.md)**: UBNF 文法定義からパーサ、AST、ツールを生成するツール

---

## 🧩 unlaxer-common
**コア・パーサコンビネータライブラリ**

`unlaxer-common` は、[RELAX NG](http://relaxng.org/) にインスパイアされた、シンプルかつ強力な Java 用パーサコンビネータライブラリです。小さな再利用可能なパース関数を組み合わせることで、複雑なパーサを構築できます。

### 主な特徴
- **直感的な記述**: `ZeroOrMore` や `Choice` といった記述的な名前によるコードファーストなアプローチ。
- **高度なパース機能**: 無制限の先読み、バックトラッキング、後方参照をサポート。
- **充実したデバッグ**: パースログ、トークンログ、トランザクションログを含む包括的なロギングシステム。
- **依存関係ゼロ**: サードパーティライブラリを必要としない純粋な Java 実装。

👉 [unlaxer-common の詳細を見る](./unlaxer-common/README.ja.md)

---

## 🚀 unlaxer-dsl
**パーサ＆ツールジェネレータ**

`unlaxer-dsl` は、**UBNF (Unlaxer BNF)** 記法で書かれた文法定義から、Java パーサ、型安全な AST、マッパー、エバリュエータを自動生成するツールです。

### 主な特徴
- **UBNF 記法**: 拡張 BNF 構文（グループ化、オプション、繰り返し、キャプチャ）による簡潔な文法記述。
- **4つのクラスを自動生成**: 1つの文法定義から `Parsers`, `AST`, `Mapper`, `Evaluator` クラスを生成。
- **モダン Java サポート**: Sealed interface、Record、パターンマッチングなどの Java 21+ 機能をフル活用。
- **IDE 連携**: Language Server Protocol (LSP) や Debug Adapter Protocol (DAP) ツールの構築基盤を提供。

👉 [unlaxer-dsl の詳細を見る](./unlaxer-dsl/README.ja.md)

---

## インストール方法

各コンポーネントは `org.unlaxer` グループとして Maven Central に公開されています。

### Maven
```xml
<!-- 共通ライブラリ -->
<dependency>
    <groupId>org.unlaxer</groupId>
    <artifactId>unlaxer-common</artifactId>
    <version>2.5.0</version>
</dependency>

<!-- DSL ツール -->
<dependency>
    <groupId>org.unlaxer</groupId>
    <artifactId>unlaxer-dsl</artifactId>
    <version>2.5.0</version>
</dependency>
```

## ライセンス
本プロジェクトは MIT ライセンスの下で公開されています。詳細は [LICENSE.txt](./unlaxer-common/LISENCE.txt) を参照してください。
