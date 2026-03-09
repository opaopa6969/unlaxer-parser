# Unlaxer Parser Ecosystem

[English](./README.md) | [日本語](./README.ja.md)

Unlaxer is a comprehensive parser development ecosystem for Java, consisting of a powerful parser combinator library and a high-level DSL for automatic parser generation.

## Project Structure

This repository is a monorepo containing the core components of the Unlaxer parser system:

- **[unlaxer-common](./unlaxer-common/README.md)**: The core parser combinator library.
- **[unlaxer-dsl](./unlaxer-dsl/README.md)**: A tool that generates parsers, ASTs, and tools from UBNF grammar definitions.

---

## 🧩 unlaxer-common
**Core Parser Combinator Library**

`unlaxer-common` is a simple yet powerful parser combinator library for Java, inspired by [RELAX NG](http://relaxng.org/). It allows you to build complex parsers by combining small, reusable parsing functions.

### Key Features
- **Easy to Read & Write**: Code-first approach with descriptive names (e.g., `ZeroOrMore`, `Choice`).
- **Advanced Parsing**: Support for infinite lookahead, backtracking, and backward references.
- **Rich Debugging**: Comprehensive logging system including parse, token, and transaction logs.
- **Zero Dependencies**: Pure Java implementation with no third-party requirements.

👉 [Learn more about unlaxer-common](./unlaxer-common/README.md)

---

## 🚀 unlaxer-dsl
**Parser & Tool Generator**

`unlaxer-dsl` is a high-level tool that automatically generates Java parsers, type-safe ASTs, mappers, and evaluators from grammar definitions written in **UBNF (Unlaxer BNF)** notation.

### Key Features
- **UBNF Notation**: Concise grammar description with extended BNF syntax (groups, optionality, repetition, capture).
- **Four-in-One Generation**: Automatically generates `Parsers`, `AST`, `Mapper`, and `Evaluator` classes from a single grammar file.
- **Modern Java Support**: Leverages Java 21+ features like sealed interfaces, records, and pattern matching.
- **IDE Support**: Infrastructure for building Language Server Protocol (LSP) and Debug Adapter Protocol (DAP) tools.

👉 [Learn more about unlaxer-dsl](./unlaxer-dsl/README.md)

---

## Installation

Both components are published to Maven Central under the `org.unlaxer` group.

### Maven
```xml
<!-- Common Library -->
<dependency>
    <groupId>org.unlaxer</groupId>
    <artifactId>unlaxer-common</artifactId>
    <version>2.5.0</version>
</dependency>

<!-- DSL Tooling -->
<dependency>
    <groupId>org.unlaxer</groupId>
    <artifactId>unlaxer-dsl</artifactId>
    <version>2.5.0</version>
</dependency>
```

## License
This project is licensed under the MIT License. See [LICENSE.txt](./unlaxer-common/LISENCE.txt) for details.
