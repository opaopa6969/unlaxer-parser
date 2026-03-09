# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Unlaxer is a parser combinator library for Java, inspired by RELAX NG. It provides composable parsers with infinite lookahead, backtracking, and rich debugging capabilities.

## Build Commands

```bash
# Build and run all tests
mvn -B package

# Run a single test class
mvn -Dtest=ClassName test

# Run a specific test method
mvn -Dtest=ClassName#methodName test

# Skip GPG signing (for local development)
mvn -Dgpg.skip=true package

# Deploy to Maven Central (requires GPG setup)
mvn clean deploy
```

## Testing

- Test framework: JUnit 4.13.2
- Base class: `ParserTestBase` provides helper methods for parser testing
- Test output goes to `build/parserTest/` with parse, transaction, token, and combined logs

### Test Helper Methods

- `testAllMatch(parser, source)` - Verify parser consumes entire input
- `testPartialMatch(parser, source, matched)` - Verify parser matches prefix
- `testUnMatch(parser, source)` - Verify parser fails on input
- `testSucceededOnly(parser, source)` - Verify parse succeeds (ignores consumed length)

### Debug Logging

Enable detailed logs in tests:
```java
ParserTestBase.setLevel(OutputLevel.detail);
```

## Architecture

### Core Concepts

1. **Parser** - Base interface; all parsers implement `parse(ParseContext) -> Parsed`
2. **ParseContext** - Manages source, cursor position, token stack, and debug listeners
3. **Parsed** - Result with status (succeeded/stopped/failed), consumed tokens, and messages
4. **Cursor** - Position tracking with line/column and Unicode code point support

### Package Structure

| Package | Purpose |
|---------|---------|
| `org.unlaxer` | Core types: Cursor, Parsed, Token, Source, CodePoint |
| `org.unlaxer.parser.combinator` | Combinators: Chain, Choice, ZeroOrMore, OneOrMore, Optional, NonOrdered |
| `org.unlaxer.parser.elementary` | Character/string parsers: WordParser, SingleCharacterParser |
| `org.unlaxer.parser.posix` | POSIX character classes: AlphabetParser, DigitParser |
| `org.unlaxer.context` | ParseContext, debug specifiers, transaction management |
| `org.unlaxer.listener` | Debug listeners for parse/transaction logging |

### Parser Combinator Pattern

Build complex parsers from simple ones:
```java
// Grammar: [0-9]+([-+*/][0-9]+)*
Parser expr = new Chain(
    new OneOrMore(DigitParser.class),
    new ZeroOrMore(
        new Chain(
            new Choice(PlusParser.class, MinusParser.class, AsteriskParser.class, SlashParser.class),
            new OneOrMore(DigitParser.class)
        )
    )
);
```

## Java Code Generation Rules

### Execution Environment
- Java: 21 or later (compile target: 11)
- Build Tool: Maven
- Encoding: UTF-8

### Naming Conventions
- No abbreviations: use `count`, `index`, `temporaryValue` (not `cnt`, `idx`, `tmp`)
- Express intent through naming; minimize comments

### Code Style
- Always use braces `{}` for if, for, while, switch
- One statement per line; early returns allowed to reduce nesting
- Do not use `var`; always declare explicit types
- Prefer `false == condition` over `!condition` for boolean checks

### Null Safety
- Be explicit about null safety; use Optional where appropriate
- Do not use Optional as a field type

### for-loop vs Stream
Prefer for-loops when:
- Iteration is simple or involves side effects
- Sequential flow is clearer

Use streams when:
- filter/map/collect/flatMap/groupingBy are natural
- Logic is a transformation pipeline

Stream style: one method call per line, prefer method references

### Returning Multiple Values
- Use `record` by default for public/protected method returns
- Tuples (vavr) allowed only in private methods for short-lived intermediate results
- Destructure tuples immediately into meaningful variable names

### vavr Library Usage
Recommended types: `Try`, `Either`, `Tuple2/Tuple3`, `Function1/Function2`, `CheckedFunction0/1/2`

Avoid: vavr `Option` and collections; prefer Java `Optional` and standard collections

### Exception Handling
- Use `Try` or `Either` inside streams
- Handle failures explicitly with `recover`/`recoverWith`
- Checked exceptions must be handled meaningfully
