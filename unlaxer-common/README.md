# Unlaxer

[English](./README.md) | [日本語](./README.ja.md)

A simple and powerful parser combinator library for Java, inspired by [RELAX NG](http://relaxng.org/).

Looking for Japanese documentation? See [`README.ja.md`](./README.ja.md).

[![Maven Central](https://img.shields.io/maven-central/v/org.unlaxer/unlaxer-common.svg?label=Maven%20Central)](https://search.maven.org/search?q=g:%22org.unlaxer%22%20AND%20a:%22unlaxer-common%22)

## Features

- **Easy to Read & Write**: Code-first approach with descriptive names (e.g., `ZeroOrMore` instead of `*`)
- **IDE-Friendly**: Full Java support with excellent debugging capabilities
- **Powerful Combinators**: Support for Optional, Choice, Interleave, ZeroOrMore, OneOrMore, Chain, and more from RELAX NG vocabulary
- **Advanced Parsing**: Infinite lookahead, backtracking, and backward reference support
- **Flexible Architecture**: Functional parser/token reference with context scope tree
- **Zero Dependencies**: No third-party libraries required
- **Rich Debugging**: Comprehensive logging with parse, token, and transaction logs

## Quick Start

### Installation

Add to your `build.gradle`:

```groovy
dependencies {
    implementation 'org.unlaxer:unlaxer-common:VERSION'
}
```

Or `pom.xml`:

```xml
<dependency>
    <groupId>org.unlaxer</groupId>
    <artifactId>unlaxer-common</artifactId>
    <version>VERSION</version>
</dependency>
```

### Basic Example

```java
import org.unlaxer.*;
import org.unlaxer.parser.*;
import org.unlaxer.parser.combinator.*;
import org.unlaxer.parser.posix.*;
import org.unlaxer.context.*;

// Define grammar: [0-9]+([-+*/][0-9]+)*
Parser parser = new Chain(
    new OneOrMore(DigitParser.class),
    new ZeroOrMore(
        new Chain(
            new Choice(
                PlusParser.class,
                MinusParser.class,
                MultipleParser.class,
                DivisionParser.class
            ),
            new OneOrMore(DigitParser.class)
        )
    )
);

// Parse input
ParseContext context = new ParseContext(
    StringSource.createRootSource("1+2+3")
);
Parsed result = parser.parse(context);

// Check result
System.out.println("Status: " + result.status); // succeeded
System.out.println("Token: " + result.getRootToken());
```

## User Guide

### Understanding Parser Combinators

Parser combinators are small parsing functions that can be combined to build complex parsers. Each combinator is a "parser builder" that:

1. Takes simple parsers as input
2. Combines them according to specific rules
3. Returns a new, more complex parser

This composability is the key strength of parser combinators.

### Core Combinators

#### Chain - Sequential Matching

`Chain` matches all child parsers in order (similar to concatenation in regex).

```java
// Matches: "if", whitespace, identifier
Parser ifStatement = new Chain(
    IfKeywordParser.class,
    WhiteSpaceParser.class,
    IdentifierParser.class
);
```

**Grammar notation**: `A B C` or `A , B , C`

#### Choice - Alternative Matching

`Choice` tries each child parser until one succeeds (similar to `|` in regex).

```java
// Matches: number OR string OR boolean
Parser literal = new Choice(
    NumberParser.class,
    StringParser.class,
    BooleanParser.class
);
```

**Grammar notation**: `A | B | C`

#### ZeroOrMore - Repetition (0+)

`ZeroOrMore` matches the child parser zero or more times (similar to `*` in regex).

```java
// Matches: "", "a", "aa", "aaa", ...
Parser manyAs = new ZeroOrMore(new MappedSingleCharacterParser('a'));
```

**Grammar notation**: `A*`

#### OneOrMore - Repetition (1+)

`OneOrMore` matches the child parser one or more times (similar to `+` in regex).

```java
// Matches: "1", "12", "123", ...
Parser digits = new OneOrMore(DigitParser.class);
```

**Grammar notation**: `A+`

#### Optional - Zero or One

`Optional` matches the child parser zero or one time (similar to `?` in regex).

```java
// Matches: "42" or "-42"
Parser signedNumber = new Chain(
    new Optional(MinusParser.class),
    new OneOrMore(DigitParser.class)
);
```

**Grammar notation**: `A?`

#### NonOrdered - Interleaved Matching

`NonOrdered` matches all child parsers but in any order (similar to RELAX NG's `<interleave>`).

```java
// Matches: "abc", "acb", "bac", "bca", "cab", "cba"
Parser anyOrder = new NonOrdered(
    new MappedSingleCharacterParser('a'),
    new MappedSingleCharacterParser('b'),
    new MappedSingleCharacterParser('c')
);
```

### Terminal Parsers

Terminal parsers match actual characters from the input:

#### Character Class Parsers

```java
// POSIX character classes (in org.unlaxer.parser.posix package)
new DigitParser()              // [0-9]
new AlphabetParser()           // [a-zA-Z]
new AlphabetNumericParser()    // [a-zA-Z0-9]
new SpaceParser()              // whitespace
new AlphabetNumericUnderScoreParser()  // [a-zA-Z0-9_]

// ASCII punctuation
new PlusParser()         // +
new MinusParser()        // -
new MultipleParser()     // *
new DivisionParser()     // /
```

#### Custom Character Parsers

```java
// Single character
new MappedSingleCharacterParser('x')

// Character range
new MappedSingleCharacterParser(new Range('a', 'z'))

// Multiple characters
new MappedSingleCharacterParser("abc")

// Punctuation excluding parentheses
PunctuationParser p = new PunctuationParser();
MappedSingleCharacterParser withoutParens = p.newWithout("()");
```

### Advanced Features

#### Recursive Grammars with Lazy Evaluation

For recursive structures, use lazy evaluation to avoid infinite loops during parser construction. Unlaxer provides `LazyChain`, `LazyChoice`, `LazyOneOrMore`, `LazyZeroOrMore`, and other lazy combinators specifically for this purpose.

**Why Lazy Evaluation?**

When you have a recursive grammar like:
```
expr = term | '(' expr ')'
```

You can't write:
```java
// WRONG - causes infinite loop during construction!
Parser expr = new Choice(
    term,
    new Chain(lparen, expr, rparen)  // expr doesn't exist yet!
);
```

**Solution 1: Using LazyChain and LazyChoice**

The recommended approach is to extend the lazy parser classes:

```java
// Define recursive expression parser
public class ExprParser extends LazyChoice {
    @Override
    public Parsers getLazyParsers() {
        // This method is called lazily, avoiding infinite recursion
        return new Parsers(
            Parser.get(NumberParser.class),
            new Chain(
                Parser.get(LParenParser.class),
                Parser.get(ExprParser.class),  // Recursive reference!
                Parser.get(RParenParser.class)
            )
        );
    }
    
    @Override
    public Optional<RecursiveMode> getNotAstNodeSpecifier() {
        return Optional.empty();  // Include in AST
    }
}

// Usage
Parser expr = Parser.get(ExprParser.class);
```

**Solution 2: Using Supplier (legacy approach)**

```java
// Expression grammar with parentheses
Supplier<Parser> exprSupplier = () -> {
    Parser term = /* ... */;
    return new Choice(
        term,
        new Chain(
            new MappedSingleCharacterParser('('),
            Parser.get(exprSupplier),  // Recursive reference via supplier
            new MappedSingleCharacterParser(')')
        )
    );
};

Parser expr = Parser.get(exprSupplier);
```

**Complete Recursive Example**

```java
// Grammar:
// expr   = term (('+'|'-') term)*
// term   = factor (('*'|'/') factor)*  
// factor = number | '(' expr ')'

public class FactorParser extends LazyChoice {
    @Override
    public Parsers getLazyParsers() {
        return new Parsers(
            Parser.get(NumberParser.class),
            new Chain(
                Parser.get(LParenParser.class),
                Parser.get(ExprParser.class),  // Recursive!
                Parser.get(RParenParser.class)
            )
        );
    }
    
    @Override
    public Optional<RecursiveMode> getNotAstNodeSpecifier() {
        return Optional.empty();
    }
}

public class TermParser extends LazyChain {
    @Override
    public Parsers getLazyParsers() {
        return new Parsers(
            Parser.get(FactorParser.class),
            new ZeroOrMore(
                new Chain(
                    new Choice(
                        Parser.get(MultipleParser.class),
                        Parser.get(DivisionParser.class)
                    ),
                    Parser.get(FactorParser.class)
                )
            )
        );
    }
    
    @Override
    public Optional<RecursiveMode> getNotAstNodeSpecifier() {
        return Optional.empty();
    }
}

public class ExprParser extends LazyChain {
    @Override
    public Parsers getLazyParsers() {
        return new Parsers(
            Parser.get(TermParser.class),
            new ZeroOrMore(
                new Chain(
                    new Choice(
                        Parser.get(PlusParser.class),
                        Parser.get(MinusParser.class)
                    ),
                    Parser.get(TermParser.class)
                )
            )
        );
    }
    
    @Override
    public Optional<RecursiveMode> getNotAstNodeSpecifier() {
        return Optional.empty();
    }
}

// Usage
ParseContext context = new ParseContext(
    StringSource.createRootSource("3 + 4 * (2 - 1)")
);
Parser expr = Parser.get(ExprParser.class);
Parsed result = expr.parse(context);
```

**Key Points about Lazy Parsers**:
- Extend `LazyChain`, `LazyChoice`, `LazyOneOrMore`, `LazyZeroOrMore`, etc.
- Implement `getLazyParsers()` method to return child parsers
- Children are constructed only when first needed
- Enables mutual recursion and self-recursion
- Use `Parser.get(YourLazyParser.class)` for singleton instances

#### Named Parsers

Named parsers help identify and reference specific parts of your grammar:

```java
Parser number = new OneOrMore(DigitParser.class);
number.setName(new Name("Number"));

// Use in token tree for easier debugging
```

#### Parse Context Options

```java
// Enable meta token creation (includes combinator nodes in token tree)
ParseContext context = new ParseContext(
    source,
    CreateMetaTokenSpecifier.createMetaOn
);

// Disable meta token creation (only terminal parsers in token tree)
ParseContext context = new ParseContext(
    source,
    CreateMetaTokenSpecifier.createMetaOff
);
```

### Working with Parse Results

#### Parse Status

```java
Parsed result = parser.parse(context);

// Check status
if (result.status == Parsed.Status.succeeded) {
    // Success!
}
else if (result.status == Parsed.Status.failed) {
    // Parse failed
}
else if (result.status == Parsed.Status.stopped) {
    // Parse stopped (e.g., error message parser matched)
}
```

#### Token Tree

The result contains a syntax tree represented as tokens:

```java
Token root = result.getRootToken();

// Token properties
String text = root.getConsumedString();  // Matched text
int start = root.getRange().start;       // Start position
int end = root.getRange().end;           // End position (exclusive)
Parser parser = root.getParser();        // Parser that created this token

// Children
List<Token> children = root.getChildren();
```

#### Pretty Printing

```java
// Print token tree
System.out.println(TokenPrinter.get(result.getRootToken()));

// Example output:
// '1+2+3' : org.unlaxer.combinator.Chain
//  '1' : org.unlaxer.combinator.OneOrMore
//   '1' : org.unlaxer.posix.DigitParser
//  '+2+3' : org.unlaxer.combinator.ZeroOrMore
//   '+2' : org.unlaxer.combinator.Chain
//    '+' : org.unlaxer.ascii.PlusParser
//    '2' : org.unlaxer.combinator.OneOrMore
//     '2' : org.unlaxer.posix.DigitParser
```

### Complete Example: Arithmetic Expression Parser

```java
import org.unlaxer.*;
import org.unlaxer.parser.*;
import org.unlaxer.parser.combinator.*;
import org.unlaxer.parser.posix.*;
import org.unlaxer.parser.ascii.*;
import org.unlaxer.context.*;
import java.util.function.Supplier;

public class Calculator {
    
    // Grammar:
    // expr   = term (('+' | '-') term)*
    // term   = factor (('*' | '/') factor)*
    // factor = number | '(' expr ')'
    
    public static Parser createParser() {
        Supplier<Parser> exprSupplier = () -> {
            Parser factor = new Choice(
                new OneOrMore(DigitParser.class),
                new Chain(
                    new MappedSingleCharacterParser('('),
                    Parser.get(exprSupplier),
                    new MappedSingleCharacterParser(')')
                )
            );
            
            Parser term = new Chain(
                factor,
                new ZeroOrMore(
                    new Chain(
                        new Choice(
                            MultipleParser.class,
                            DivisionParser.class
                        ),
                        factor
                    )
                )
            );
            
            Parser expr = new Chain(
                term,
                new ZeroOrMore(
                    new Chain(
                        new Choice(
                            PlusParser.class,
                            MinusParser.class
                        ),
                        term
                    )
                )
            );
            
            return expr;
        };
        
        return Parser.get(exprSupplier);
    }
    
    public static void main(String[] args) {
        Parser parser = createParser();
        
        String input = "1+2*(3-4)";
        ParseContext context = new ParseContext(
            StringSource.createRootSource(input)
        );
        
        Parsed result = parser.parse(context);
        
        if (result.isSucceeded()) {
            System.out.println("Parse succeeded!");
            System.out.println(TokenPrinter.get(result.getRootToken()));
        } else {
            System.out.println("Parse failed: " + result.getMessage());
        }
        
        context.close();
    }
}
```

## Internal Architecture

Understanding the internal architecture is essential for creating custom parser combinators or extending the library.

### Core Concepts

#### 1. Source and Source Hierarchy

`Source` is the foundation of Unlaxer's position tracking system. It represents input text with precise Unicode handling and supports hierarchical relationships.

**Source Types**

```java
public enum SourceKind {
    root,       // Original input source
    subSource,  // View into parent source (maintains connection)
    detached,   // Independent source (no parent connection)
    attached    // Special case (rarely used)
}
```

**Creating Sources**

```java
// Root source - the original input
Source root = StringSource.createRootSource("Hello World");

// SubSource - a view into the parent (maintains position tracking)
Source sub = root.subSource(
    new CodePointIndex(0),    // Start position (inclusive)
    new CodePointIndex(5)     // End position (exclusive)
);
// sub.sourceAsString() = "Hello"
// sub.offsetFromRoot() = 0
// sub.parent() = Optional.of(root)

// Nested subSource - offsets are composed
Source nested = sub.subSource(
    new CodePointIndex(1),    // Position 1 in sub
    new CodePointIndex(4)     // Position 4 in sub
);
// nested.sourceAsString() = "ell"
// nested.offsetFromParent() = 1 (relative to sub)
// nested.offsetFromRoot() = 1 (relative to root)
// nested.parent() = Optional.of(sub)
```

**SubSource vs Detached**

SubSource maintains connection to parent:
```java
Source root = StringSource.createRootSource("ABCDEFGH");

// SubSource - keeps parent reference and offset
Source sub = root.subSource(new CodePointIndex(2), new CodePointIndex(6));
// sub = "CDEF"
// sub.parent().isPresent() = true
// sub.offsetFromRoot() = 2
// Position tracking works back to root

// Detached - becomes independent root
Source detached = sub.reRoot();
// detached = "CDEF"  
// detached.parent().isEmpty() = true
// detached.offsetFromRoot() = 0
// Loses connection to original root
// Useful when you need new coordinate system
```

**Why SubSource Matters**

SubSource is critical for:

1. **Position Tracking**: Error messages can reference original input
```java
Source root = StringSource.createRootSource("var x = 10;");
Source statement = root.subSource(new CodePointIndex(0), new CodePointIndex(11));

// Parse the statement
Parsed result = parser.parse(new ParseContext(statement));

if (result.isFailed()) {
    // Position in statement
    int localPos = cursor.positionInSub().value();
    
    // Position in original file
    int globalPos = cursor.positionInRoot().value();
    
    System.out.printf(
        "Error at position %d (global: %d) in: %s%n",
        localPos, globalPos, root.sourceAsString()
    );
}
```

2. **Incremental Parsing**: Parse portions without losing context
```java
Source file = StringSource.createRootSource(entireFileContent);

// Parse each function separately but maintain file positions
List<FunctionToken> functions = new ArrayList<>();
for (FunctionLocation loc : functionLocations) {
    Source funcSource = file.subSource(loc.start, loc.end);
    
    Parsed result = functionParser.parse(new ParseContext(funcSource));
    
    // Token positions reference original file
    functions.add(result.getRootToken());
}
```

3. **Multi-pass Parsing**: Parse recursively while tracking positions
```java
// First pass: identify string literals
Source root = StringSource.createRootSource(code);
List<Source> stringLiterals = extractStringLiterals(root);

// Second pass: parse each literal with different rules
for (Source literal : stringLiterals) {
    // literal maintains position in root
    Parsed result = stringContentParser.parse(new ParseContext(literal));
    
    // Report errors with original file positions
    if (result.isFailed()) {
        int line = literal.cursorRange()
            .startIndexInclusive.lineNumber().value;
        System.err.printf("Error at line %d in original file%n", line);
    }
}
```

**Source Operations**

```java
Source source = StringSource.createRootSource("Hello World");

// Create views
Source peek = source.peek(
    new CodePointIndex(0),
    new CodePointLength(5)
);  // "Hello" (temporary view)

Source sub = source.subSource(
    new CodePointIndex(6),
    new CodePointIndex(11)
);  // "World" (maintains parent)

// Transform (creates new detached source)
Source upper = source.toUpperCaseAsStringInterface();  // "HELLO WORLD"
// upper.parent().isEmpty() = true (transformation breaks parent link)

// Re-root with transformation
Source newRoot = source.reRoot(s -> s.replace("World", "Universe"));
// newRoot = "Hello Universe"
// newRoot.isRoot() = true
// newRoot.offsetFromRoot() = 0
```

**Source Hierarchy Example**

```java
// Root: "The quick brown fox jumps"
Source root = StringSource.createRootSource("The quick brown fox jumps");

// Level 1: "quick brown fox"
Source level1 = root.subSource(new CodePointIndex(4), new CodePointIndex(19));

// Level 2: "brown"
Source level2 = level1.subSource(new CodePointIndex(6), new CodePointIndex(11));

// Accessing positions
System.out.println("Text: " + level2.sourceAsString());               // "brown"
System.out.println("Parent: " + level2.parent().get().sourceAsString()); // "quick brown fox"
System.out.println("Offset from parent: " + level2.offsetFromParent());  // 6
System.out.println("Offset from root: " + level2.offsetFromRoot());      // 10

// Walking up the hierarchy
Source current = level2;
while (current.hasParent()) {
    System.out.println("  " + current.sourceAsString());
    current = current.parent().get();
}
System.out.println("Root: " + current.sourceAsString());
```

#### 2. CodePointIndex - Unicode-Aware Position Tracking

`CodePointIndex` represents a position in the source as a **Unicode code point offset**, not a character or byte offset. This is crucial for correct handling of:
- Emoji (😀 = 1 code point, 2 Java chars)
- Surrogate pairs
- Multi-byte UTF-8 sequences
- Combining characters

**Why Code Points Matter**

```java
String text = "A😀B";  // A, emoji (surrogate pair), B

// Wrong: Using character indices
text.charAt(0);  // 'A'
text.charAt(1);  // '\uD83D' (high surrogate - wrong!)
text.charAt(2);  // '\uDE00' (low surrogate - wrong!)
text.charAt(3);  // 'B'

// Correct: Using code point indices
Source source = StringSource.createRootSource(text);
source.getCodePointAt(new CodePointIndex(0));  // 'A' (65)
source.getCodePointAt(new CodePointIndex(1));  // '😀' (128512)
source.getCodePointAt(new CodePointIndex(2));  // 'B' (66)

// SubSource with emoji
Source emoji = source.subSource(
    new CodePointIndex(1),
    new CodePointIndex(2)
);
assertEquals("😀", emoji.sourceAsString());
```

**CodePointIndex Operations**

```java
CodePointIndex index = new CodePointIndex(10);

// Arithmetic
CodePointIndex next = index.newWithIncrements();           // 11
CodePointIndex prev = index.newWithDecrements();           // 9
CodePointIndex plus5 = index.newWithAdd(5);                // 15
CodePointIndex minus3 = index.newWithMinus(3);             // 7

// Comparison
index.eq(new CodePointIndex(10));    // true
index.lt(new CodePointIndex(15));    // true
index.ge(new CodePointIndex(5));     // true

// Conversion
CodePointOffset offset = index.toCodePointOffset();
CodePointLength length = new CodePointLength(index);

// Value access
int value = index.value();  // 10
```

**Related Position Types**

```java
// CodePointIndex - position in code points (main type)
CodePointIndex codePointPos = new CodePointIndex(5);

// CodePointOffset - relative offset
CodePointOffset offset = new CodePointOffset(3);
CodePointIndex newPos = codePointPos.newWithAdd(offset);

// CodePointLength - length in code points
CodePointLength length = new CodePointLength(10);
Source sub = source.peek(codePointPos, length);

// StringIndex - position in Java String (char units)
// Used internally for String operations
StringIndex stringPos = source.toStringIndex(codePointPos);

// LineNumber - line number (1-based)
LineNumber line = source.positionResolver()
    .lineNumberFrom(codePointPos);

// CodePointIndexInLine - column within line
CodePointIndexInLine column = source.positionResolver()
    .codePointIndexInLineFrom(codePointPos);
```

**Position Tracking Example**

```java
Source source = StringSource.createRootSource(
    "line 1\nline 2 with 😀\nline 3"
);

// Find emoji position
CodePointIndex emojiPos = new CodePointIndex(18);  // Position of 😀

// Get line and column
PositionResolver resolver = source.positionResolver();
LineNumber line = resolver.lineNumberFrom(emojiPos);
CodePointIndexInLine column = resolver.codePointIndexInLineFrom(emojiPos);

System.out.printf(
    "Emoji at line %d, column %d%n",
    line.value,      // 2
    column.value     // 11
);

// Convert between coordinate systems
StringIndex stringIdx = source.toStringIndex(emojiPos);
CodePointIndex backToCodePoint = source.toCodePointIndex(stringIdx);

// stringIdx may differ from emojiPos value due to surrogates
// but backToCodePoint == emojiPos
```

#### 3. CursorRange - Representing Text Spans

`CursorRange` represents a span of text with start (inclusive) and end (exclusive) positions. It's used throughout Unlaxer for:
- Token ranges
- Error locations
- Selection ranges
- Source boundaries

**Basic CursorRange**

```java
Source source = StringSource.createRootSource("Hello World");

// Create range for "World"
CursorRange range = CursorRange.of(
    new CodePointIndex(6),     // Start (inclusive)
    new CodePointIndex(11),    // End (exclusive)
    new CodePointOffset(0),    // Offset from root
    SourceKind.root,
    source.positionResolver()
);

// Access boundaries
StartInclusiveCursor start = range.startIndexInclusive;
EndExclusiveCursor end = range.endIndexExclusive;

// Positions
CodePointIndex startPos = start.position();        // 6
CodePointIndex endPos = end.position();            // 11

// Line and column information
LineNumber startLine = start.lineNumber();
CodePointIndexInLine startCol = start.positionInLine();
```

**CursorRange for SubSource**

When working with subSources, CursorRange handles both local and global positions:

```java
Source root = StringSource.createRootSource("0123456789");

// Create subSource "3456"
Source sub = root.subSource(
    new CodePointIndex(3),
    new CodePointIndex(7)
);

CursorRange subRange = sub.cursorRange();

// Position in root coordinate system
CodePointIndex posInRoot = subRange.startIndexInclusive.positionInRoot();
// = 3

// Position in subSource coordinate system  
CodePointIndex posInSub = subRange.startIndexInclusive.positionInSub();
// = 0 (subSource starts at its own 0)

// This is how tokens track positions in both systems
```

**CursorRange Operations**

```java
Source source = StringSource.createRootSource("ABCDEFGH");
PositionResolver resolver = source.positionResolver();

CursorRange range1 = CursorRange.of(
    new CodePointIndex(2),
    new CodePointIndex(5),
    CodePointOffset.ZERO,
    SourceKind.root,
    resolver
);  // "CDE"

CursorRange range2 = CursorRange.of(
    new CodePointIndex(4),
    new CodePointIndex(7),
    CodePointOffset.ZERO,
    SourceKind.root,
    resolver
);  // "EFG"

// Position testing
boolean contains = range1.match(new CodePointIndex(3));  // true
boolean before = range1.lt(new CodePointIndex(6));       // true
boolean after = range1.gt(new CodePointIndex(1));        // true

// Range relationships
RangesRelation rel = range1.relation(range2);
// Returns: crossed (ranges overlap)

// Equal ranges
CursorRange range3 = CursorRange.of(
    new CodePointIndex(2),
    new CodePointIndex(5),
    CodePointOffset.ZERO,
    SourceKind.root,
    resolver
);
range1.relation(range3);  // Returns: equal

// Nested ranges
CursorRange outer = CursorRange.of(
    new CodePointIndex(1),
    new CodePointIndex(7),
    CodePointOffset.ZERO,
    SourceKind.root,
    resolver
);
range1.relation(outer);  // Returns: outer (range1 is inside outer)
```

**Complete Position Tracking Example**

```java
public class PositionTrackingExample {
    
    public static void main(String[] args) {
        // Original file content
        String fileContent = """
            function hello() {
                print("Hello 😀");
            }
            """;
        
        Source root = StringSource.createRootSource(fileContent);
        
        // Extract function body
        int bodyStart = fileContent.indexOf("{") + 1;
        int bodyEnd = fileContent.indexOf("}");
        
        Source functionBody = root.subSource(
            new CodePointIndex(bodyStart),
            new CodePointIndex(bodyEnd)
        );
        
        System.out.println("Function body: " + functionBody.sourceAsString());
        System.out.println("Offset from root: " + functionBody.offsetFromRoot());
        
        // Parse the body
        Parser parser = /* ... */;
        ParseContext context = new ParseContext(functionBody);
        Parsed result = parser.parse(context);
        
        if (result.isSucceeded()) {
            Token token = result.getRootToken();
            CursorRange tokenRange = token.getRange();
            
            // Positions in function body
            int localStart = tokenRange.startIndexInclusive.positionInSub().value();
            
            // Positions in original file
            int globalStart = tokenRange.startIndexInclusive.positionInRoot().value();
            
            // Line and column in original file
            LineNumber line = tokenRange.startIndexInclusive.lineNumber();
            CodePointIndexInLine column = tokenRange.startIndexInclusive.positionInLine();
            
            System.out.printf(
                "Token at local pos %d, global pos %d (line %d, col %d)%n",
                localStart, globalStart, line.value, column.value
            );
            
            // Extract the token text
            Source tokenSource = root.subSource(tokenRange);
            System.out.println("Token text: " + tokenSource.sourceAsString());
        }
        
        context.close();
    }
}
```

**Key Insights**

1. **Source Hierarchy**: SubSources maintain parent relationships, enabling position tracking back to original input

2. **Code Point Indexing**: All positions use Unicode code points, not character or byte indices, ensuring correct handling of emojis and multi-byte characters

3. **Dual Coordinate Systems**: CursorRange supports both:
   - `positionInSub()`: Position within current source (0-based)
   - `positionInRoot()`: Position in root source (original coordinates)

4. **Position Composition**: Nested subSources compose offsets:
   ```
   root -> sub1 (offset 10) -> sub2 (offset 5)
   sub2.offsetFromRoot() = 10 + 5 = 15
   ```

5. **Detached Sources**: When you transform a source (uppercase, replace, etc.), the result is detached from parent, starting a new coordinate system

This architecture enables Unlaxer to:
- Report errors with precise file positions
- Parse incrementally while maintaining context
- Handle Unicode correctly
- Support nested parsing with position tracking
- Build IDE features like go-to-definition

#### 4. ParseContext

`ParseContext` is the state object passed through all parsing operations:

```java
public class ParseContext {
    public final Source source;
    final Deque<TransactionElement> tokenStack;
    Map<ChoiceInterface, Parser> chosenParserByChoice;
    // ... other state
}
```

**Key responsibilities**:
- **Source management**: Holds the input string
- **Position tracking**: Current parsing position via cursor
- **Backtracking support**: Transaction stack for rollback
- **Scope management**: Parser-specific and global scope trees
- **Choice tracking**: Remembers which alternative was chosen

#### 3. Parser Interface

The core interface all parsers must implement:

```java
public interface Parser {
    Parsed parse(ParseContext parseContext, TokenKind tokenKind, boolean invertMatch);
    
    default Parsed parse(ParseContext parseContext) {
        return parse(parseContext, getTokenKind(), false);
    }
}
```

**Key points**:
- Single method: `parse(ParseContext, TokenKind, boolean)`
- Returns `Parsed` object with status and token
- Stateless: all state is in `ParseContext`
- Can be reused across multiple parse operations

#### 4. Parsed Result

```java
public class Parsed {
    public enum Status { succeeded, stopped, failed }
    
    public Status status;
    private Token token;
    private TokenList originalTokens;
}
```

**Fields**:
- `status`: Parse outcome (succeeded/stopped/failed)
- `token`: Root token of matched subtree (if successful)
- `originalTokens`: All tokens created during parsing

#### 5. Token (Syntax Tree Node)

```java
public class Token {
    private final Parser parser;
    private final Range range;
    private final List<Token> children;
}
```

**Key aspects**:
- Represents a matched portion of input
- Forms tree structure via children
- Links to parser that created it
- Contains position range in source

#### 6. Transaction Stack

The transaction stack enables backtracking:

```java
Deque<TransactionElement> tokenStack;

// Begin transaction
TransactionElement element = new TransactionElement(cursor);
tokenStack.push(element);

// On success - commit
tokenStack.pop();
// Tokens are kept, cursor advances

// On failure - rollback
tokenStack.pop();
// Tokens discarded, cursor restored
```

### How Parsing Works

#### Flow Diagram

```
User Code
    ↓
parser.parse(parseContext)
    ↓
Parser.parse() method
    ↓
Check cursor position
    ↓
┌─────────────────────────┐
│  Begin Transaction      │
│  (push to stack)        │
└─────────────────────────┘
    ↓
Try to match input
    ↓
    ├─── Success ─────────┐
    │                     ↓
    │              Create Token
    │                     ↓
    │              Advance Cursor
    │                     ↓
    │              ┌──────────────────┐
    │              │ Commit           │
    │              │ (pop stack)      │
    │              └──────────────────┘
    │                     ↓
    │              Return Parsed{succeeded, token}
    │
    └─── Failure ─────────┐
                          ↓
                   ┌──────────────────┐
                   │ Rollback         │
                   │ (pop stack,      │
                   │  restore cursor) │
                   └──────────────────┘
                          ↓
                   Return Parsed{failed}
```

### Implementing Custom Combinators

#### Pattern 1: Terminal Parser (Leaf Node)

Terminal parsers match actual characters from input:

```java
public class MyCharParser implements Parser {
    private final char expected;
    
    public MyCharParser(char expected) {
        this.expected = expected;
    }
    
    @Override
    public Parsed parse(ParseContext context, TokenKind tokenKind, boolean invertMatch) {
        // Get current cursor
        TransactionElement transaction = context.getTokenStack().peek();
        ParserCursor cursor = transaction.getCursor();
        
        // Check if at end
        if (cursor.isEndOfSource()) {
            return Parsed.FAILED;
        }
        
        // Get current character
        CodePointString str = context.source.getCodePointString();
        int codePoint = str.getCodePointAt(cursor.getCodePointIndex());
        
        // Check match
        if (codePoint == expected) {
            // Create token for matched character
            Range range = new Range(
                cursor.getCodePointIndex(),
                cursor.getCodePointIndex().plus(1)
            );
            Token token = new Token(this, range, context.source);
            
            // Advance cursor
            transaction.setCursor(cursor.advance(1));
            
            return new Parsed(token, Parsed.Status.succeeded);
        } else {
            return Parsed.FAILED;
        }
    }
}
```

**Key steps**:
1. Get cursor from transaction stack
2. Check if we're at end of input
3. Get current character/substring
4. Compare with expected value
5. On match: create token, advance cursor, return success
6. On mismatch: return failure (cursor unchanged)

#### Pattern 2: Sequence Combinator

Matches child parsers sequentially:

```java
public class MyChain implements Parser {
    private final List<Parser> children;
    
    public MyChain(Parser... children) {
        this.children = Arrays.asList(children);
    }
    
    @Override
    public Parsed parse(ParseContext context, TokenKind tokenKind, boolean invertMatch) {
        TransactionElement transaction = context.getTokenStack().peek();
        ParserCursor startCursor = transaction.getCursor();
        
        List<Token> childTokens = new ArrayList<>();
        
        // Try to match each child in order
        for (Parser child : children) {
            Parsed childParsed = child.parse(context);
            
            if (childParsed.isFailed()) {
                // Restore cursor and fail
                transaction.setCursor(startCursor);
                return Parsed.FAILED;
            }
            
            childTokens.add(childParsed.getRootToken());
        }
        
        // All children matched - create parent token
        ParserCursor endCursor = transaction.getCursor();
        Range range = new Range(
            startCursor.getCodePointIndex(),
            endCursor.getCodePointIndex()
        );
        Token token = new Token(this, range, context.source, childTokens);
        
        return new Parsed(token, Parsed.Status.succeeded);
    }
}
```

**Key steps**:
1. Remember starting cursor
2. Try to match each child parser in order
3. On any failure: restore cursor and return failure
4. On all success: create parent token with all child tokens
5. Return success with parent token

**Important**: Cursor is automatically advanced by child parsers, so we don't need to manually advance it.

#### Pattern 3: Choice Combinator

Tries alternatives until one succeeds:

```java
public class MyChoice implements Parser {
    private final List<Parser> alternatives;
    
    public MyChoice(Parser... alternatives) {
        this.alternatives = Arrays.asList(alternatives);
    }
    
    @Override
    public Parsed parse(ParseContext context, TokenKind tokenKind, boolean invertMatch) {
        TransactionElement transaction = context.getTokenStack().peek();
        ParserCursor startCursor = transaction.getCursor();
        
        // Try each alternative
        for (Parser alternative : alternatives) {
            Parsed parsed = alternative.parse(context);
            
            if (parsed.isSucceeded()) {
                // First success wins
                return parsed;
            }
            
            // Restore cursor for next attempt
            transaction.setCursor(startCursor);
        }
        
        // All alternatives failed
        return Parsed.FAILED;
    }
}
```

**Key steps**:
1. Save starting cursor
2. Try each alternative parser
3. On first success: return that result immediately
4. On failure: restore cursor and try next alternative
5. If all fail: return failure

**Critical**: Always restore cursor between attempts! This enables backtracking.

#### Pattern 4: Repetition Combinator

Matches a parser multiple times:

```java
public class MyZeroOrMore implements Parser {
    private final Parser child;
    
    public MyZeroOrMore(Parser child) {
        this.child = child;
    }
    
    @Override
    public Parsed parse(ParseContext context, TokenKind tokenKind, boolean invertMatch) {
        TransactionElement transaction = context.getTokenStack().peek();
        ParserCursor startCursor = transaction.getCursor();
        
        List<Token> matchedTokens = new ArrayList<>();
        
        // Keep matching until failure
        while (true) {
            ParserCursor beforeAttempt = transaction.getCursor();
            Parsed parsed = child.parse(context);
            
            if (parsed.isFailed()) {
                // Restore cursor after failed attempt
                transaction.setCursor(beforeAttempt);
                break;
            }
            
            matchedTokens.add(parsed.getRootToken());
            
            // Infinite loop detection
            ParserCursor afterAttempt = transaction.getCursor();
            if (afterAttempt.equals(beforeAttempt)) {
                // Child matched empty string - stop to avoid infinite loop
                break;
            }
        }
        
        // Create token even if zero matches
        Range range = new Range(
            startCursor.getCodePointIndex(),
            transaction.getCursor().getCodePointIndex()
        );
        Token token = new Token(this, range, context.source, matchedTokens);
        
        return new Parsed(token, Parsed.Status.succeeded);
    }
}
```

**Key steps**:
1. Loop trying to match child parser
2. On each success: collect token and continue
3. On failure: restore cursor and break loop
4. Always return success (even with zero matches)
5. Infinite loop detection: break if cursor doesn't advance

### Meta Tokens vs Terminal Tokens

Unlaxer supports two modes controlled by `CreateMetaTokenSpecifier`:

#### createMetaOff (Compact Tree)

Only terminal parsers create tokens:

```
Input: "1+2"

Token Tree:
'1+2'
 '1' : DigitParser
 '+' : PlusParser
 '2' : DigitParser
```

**Use case**: When you only care about terminals (lexical tokens)

#### createMetaOn (Full Tree)

All parsers create tokens including combinators:

```
Input: "1+2"

Token Tree:
'1+2' : Chain
 '1' : OneOrMore
  '1' : DigitParser
 '+' : Choice
  '+' : PlusParser
 '2' : OneOrMore
  '2' : DigitParser
```

**Use case**: When you need full structural information for AST building

### Advanced: Transaction Management

The transaction stack is key to backtracking:

```java
// Unlaxer manages transactions automatically, but understanding helps:

// 1. Parser starts
Deque<TransactionElement> stack = context.getTokenStack();
TransactionElement current = stack.peek();
ParserCursor savedCursor = current.getCursor();

// 2. Try parsing
Parsed result = childParser.parse(context);

// 3a. On success - cursor is already advanced by child
// Just use the result

// 3b. On failure - restore cursor
if (result.isFailed()) {
    current.setCursor(savedCursor);
}
```

**Transaction guarantees**:
- Failed parsers never advance cursor
- Successful parsers always advance cursor
- Parent parsers can rely on cursor position after child parse

### Debugging Techniques

#### Enable Parse Logging

```java
ParseContext context = new ParseContext(
    source,
    ParserDebugSpecifier.debug,
    TransactionDebugSpecifier.debug
);

// Generates detailed logs:
// - parse.log: Parser invocations and results
// - transaction.log: Transaction push/pop operations
// - token.log: Token creation
// - combined.log: All above combined
```

#### Custom Parser Listeners

```java
public class MyListener implements ParserListener {
    @Override
    public void onBefore(Parser parser, ParseContext context) {
        System.out.println("Trying: " + parser.getClass().getSimpleName());
    }
    
    @Override
    public void onAfter(Parser parser, ParseContext context, Parsed result) {
        System.out.println("Result: " + result.status);
    }
}

// Register listener
context.getParserListenerByName().put(
    new Name("MyListener"),
    new MyListener()
);
```

## Converting Parse Tree to AST

Unlaxer provides a powerful AST (Abstract Syntax Tree) transformation system through the `org.unlaxer.ast` package. This allows you to convert the parse tree into a more semantic tree structure suitable for interpretation or compilation.

### Understanding the Problem

A parse tree directly reflects the grammar structure, which can be verbose:

```
Parse Tree for "1 + 2 + 3":
Chain
 ├─ OneOrMore (Number)
 │   └─ '1'
 ├─ ZeroOrMore
 │   ├─ Chain
 │   │   ├─ Choice (Operator)
 │   │   │   └─ '+'
 │   │   └─ OneOrMore (Number)
 │   │       └─ '2'
 │   └─ Chain
 │       ├─ Choice (Operator)
 │       │   └─ '+'
 │       └─ OneOrMore (Number)
 │           └─ '3'
```

An AST simplifies this to semantic structure:

```
AST for "1 + 2 + 3":
'+'
 ├─ '+'
 │   ├─ '1'
 │   └─ '2'
 └─ '3'
```

### ASTMapper Interface

The core interface for AST transformation:

```java
public interface ASTMapper {
    /**
     * Transform a parse tree token into an AST token
     */
    Token toAST(ASTMapperContext context, Token parsedToken);
    
    /**
     * Check if this mapper can handle the token
     */
    default boolean canASTMapping(Token parsedToken) {
        return parsedToken.parser.getClass() == getClass();
    }
}
```

### AST Node Kinds

Define the semantic role of each node:

```java
public enum ASTNodeKind {
    Operator,                  // Binary/unary operators
    Operand,                   // Values, variables, literals
    ChoicedOperatorRoot,       // Root of operator choice
    ChoicedOperator,           // Individual operator in choice
    ChoicedOperandRoot,        // Root of operand choice
    ChoicedOperand,            // Individual operand in choice
    Space,                     // Whitespace (usually filtered)
    Comment,                   // Comments (usually filtered)
    Annotation,                // Annotations/decorators
    Other,                     // Other node types
    NotSpecified              // Not yet classified
}
```

### Built-in AST Patterns

#### 1. RecursiveZeroOrMoreBinaryOperator

For grammars like: `number (operator number)*`

**Parse Tree**:
```
'1+2+3'
 ├─ '1' (number)
 ├─ '+' (operator)
 ├─ '2' (number)
 ├─ '+' (operator)
 └─ '3' (number)
```

**AST** (left-associative tree):
```
'+'
 ├─ '+'
 │   ├─ '1'
 │   └─ '2'
 └─ '3'
```

**Implementation**:

```java
public class AdditionParser extends Chain 
    implements RecursiveZeroOrMoreBinaryOperator {
    
    public AdditionParser() {
        super(
            Parser.get(NumberParser.class),
            new ZeroOrMore(
                new Chain(
                    Parser.get(PlusParser.class),
                    Parser.get(NumberParser.class)
                )
            )
        );
    }
}

// The toAST method is automatically provided by the interface
```

#### 2. RecursiveZeroOrMoreOperator

For postfix/prefix operators like: `operand operator*`

**Parse Tree**:
```
'array[0][1]'
 ├─ 'array' (operand)
 ├─ '[0]' (operator)
 └─ '[1]' (operator)
```

**AST**:
```
'[1]'
 └─ '[0]'
     └─ 'array'
```

**Implementation**:

```java
public class SubscriptParser extends Chain 
    implements RecursiveZeroOrMoreOperator {
    
    public SubscriptParser() {
        super(
            Parser.get(IdentifierParser.class),
            new ZeroOrMore(
                Parser.get(IndexOperatorParser.class)
            )
        );
    }
}
```

### Complete AST Example

```java
import org.unlaxer.*;
import org.unlaxer.parser.*;
import org.unlaxer.parser.combinator.*;
import org.unlaxer.parser.posix.*;
import org.unlaxer.ast.*;
import org.unlaxer.context.*;

// Step 1: Tag parsers with AST node kinds
public class NumberParser extends OneOrMore implements StaticParser {
    public NumberParser() {
        super(DigitParser.class);
        // Mark as operand
        addTag(ASTNodeKind.Operand.tag());
    }
}

public class PlusParser extends SingleCharacterParser implements StaticParser {
    @Override
    public boolean isMatch(char target) {
        return '+' == target;
    }

    // Mark as operator in constructor or initialization
    // addTag(ASTNodeKind.Operator.tag());
}

public class MinusParser extends SingleCharacterParser implements StaticParser {
    @Override
    public boolean isMatch(char target) {
        return '-' == target;
    }

    // Mark as operator
    // addTag(ASTNodeKind.Operator.tag());
}

// Step 2: Create parser with AST mapper
public class ExpressionParser extends Chain 
    implements RecursiveZeroOrMoreBinaryOperator {
    
    public ExpressionParser() {
        super(
            Parser.get(NumberParser.class),
            new ZeroOrMore(
                new Chain(
                    new Choice(
                        Parser.get(PlusParser.class),
                        Parser.get(MinusParser.class)
                    ),
                    Parser.get(NumberParser.class)
                )
            )
        );
    }
}

// Step 3: Parse and convert to AST
public class ASTExample {
    public static void main(String[] args) {
        // Parse
        Parser parser = Parser.get(ExpressionParser.class);
        ParseContext context = new ParseContext(
            StringSource.createRootSource("1 + 2 - 3")
        );
        Parsed result = parser.parse(context);
        Token parseTree = result.getRootToken();
        
        // Create AST mapper context
        ASTMapperContext astContext = ASTMapperContext.create(
            new ExpressionParser()
            // Add more mappers as needed
        );
        
        // Convert to AST
        Token ast = astContext.toAST(parseTree);
        
        // Print both trees
        System.out.println("Parse Tree:");
        System.out.println(TokenPrinter.get(parseTree));
        
        System.out.println("\nAST:");
        System.out.println(TokenPrinter.get(ast));
        
        context.close();
    }
}
```

**Output**:

```
Parse Tree:
'1 + 2 - 3' : ExpressionParser
 '1' : NumberParser
  '1' : DigitParser
 ' + 2 - 3' : ZeroOrMore
  ' + 2' : Chain
   '+' : Choice
    '+' : PlusParser
   '2' : NumberParser
    '2' : DigitParser
  ' - 3' : Chain
   '-' : Choice
    '-' : MinusParser
   '3' : NumberParser
    '3' : DigitParser

AST:
'-' : MinusParser
 '+' : PlusParser
  '1' : NumberParser
   '1' : DigitParser
  '2' : NumberParser
   '2' : DigitParser
 '3' : NumberParser
  '3' : DigitParser
```

### Custom AST Mappers

For custom AST transformations, implement `ASTMapper`:

```java
public class CustomFunctionCallParser extends Chain implements ASTMapper {
    
    public CustomFunctionCallParser() {
        super(
            Parser.get(IdentifierParser.class),  // function name
            Parser.get(LParenParser.class),
            Parser.get(ArgumentListParser.class),
            Parser.get(RParenParser.class)
        );
    }
    
    @Override
    public Token toAST(ASTMapperContext context, Token parsedToken) {
        TokenList children = parsedToken.getAstNodeChildren();
        
        // Extract semantic parts
        Token functionName = children.get(0);  // identifier
        Token args = children.get(2);          // argument list
        
        // Create new AST node with only semantic children
        return functionName.newCreatesOf(
            context.toAST(functionName),
            context.toAST(args)
        );
    }
}
```

### AST Best Practices

1. **Tag Terminal Parsers**: Mark all terminal parsers with appropriate `ASTNodeKind`
```java
addTag(ASTNodeKind.Operator.tag());
addTag(ASTNodeKind.Operand.tag());
```

2. **Use Built-in Patterns**: Leverage `RecursiveZeroOrMoreBinaryOperator` and `RecursiveZeroOrMoreOperator` for common patterns

3. **Recursive Transformation**: Always use `context.toAST()` when processing child tokens

4. **Filter Noise**: Remove whitespace, comments, and syntactic markers in AST

5. **Semantic Structure**: AST should reflect program meaning, not grammar structure

### Operator Precedence in AST

For proper operator precedence, structure your grammar hierarchically:

```java
// expr   = term (('+' | '-') term)*
// term   = factor (('*' | '/') factor)*
// factor = number | '(' expr ')'

public class ExprParser extends LazyChain 
    implements RecursiveZeroOrMoreBinaryOperator {
    @Override
    public Parsers getLazyParsers() {
        return new Parsers(
            Parser.get(TermParser.class),
            new ZeroOrMore(
                new Chain(
                    new Choice(PlusParser.class, MinusParser.class),
                    Parser.get(TermParser.class)
                )
            )
        );
    }
}

public class TermParser extends Chain 
    implements RecursiveZeroOrMoreBinaryOperator {
    public TermParser() {
        super(
            Parser.get(FactorParser.class),
            new ZeroOrMore(
                new Chain(
                    new Choice(MultipleParser.class, DivisionParser.class),
                    Parser.get(FactorParser.class)
                )
            )
        );
    }
}
```

This ensures multiplication binds tighter than addition in the resulting AST.

## Scope Tree: Context-Dependent Parsing

The Scope Tree feature allows parsers to store and retrieve contextual information during parsing. This is essential for context-dependent languages and advanced parsing scenarios.

### Understanding Scope Trees

Unlaxer provides two types of scopes:

1. **Parser-Scoped Storage**: Data associated with specific parser instances
2. **Global Scope**: Data shared across all parsers in a parse session

Both are accessible through the `ParseContext`.

### Basic Scope Tree Operations

```java
// During parsing, you can store and retrieve data
ParseContext context = new ParseContext(source);

// Store data associated with a parser
Parser myParser = /* ... */;
context.put(myParser, "some data");

// Retrieve data
Optional<String> data = context.get(myParser, String.class);

// Store with named keys
Name variableName = Name.of("myVariable");
context.put(myParser, variableName, "value");
Optional<String> value = context.get(myParser, variableName, String.class);

// Global scope (not tied to a specific parser)
context.put(Name.of("globalVar"), "global value");
Optional<String> globalValue = context.get(Name.of("globalVar"), String.class);
```

### Use Case 1: Variable Declaration and Reference

Track variable declarations and validate references:

```java
public class VariableDeclarationParser extends Chain {
    
    public static final Name DECLARED_VARIABLES = Name.of("declaredVars");
    
    public VariableDeclarationParser() {
        super(
            Parser.get(TypeParser.class),
            Parser.get(IdentifierParser.class),
            Parser.get(SemicolonParser.class)
        );
    }
    
    @Override
    public Parsed parse(ParseContext context, TokenKind tokenKind, boolean invertMatch) {
        Parsed result = super.parse(context, tokenKind, invertMatch);
        
        if (result.isSucceeded()) {
            Token root = result.getRootToken();
            Token identifier = root.getChildren().get(1);
            String varName = identifier.getConsumedString();
            
            // Store in global scope
            Set<String> declaredVars = context.get(DECLARED_VARIABLES, Set.class)
                .orElse(new HashSet<>());
            declaredVars.add(varName);
            context.put(DECLARED_VARIABLES, declaredVars);
        }
        
        return result;
    }
}

public class VariableReferenceParser extends IdentifierParser {
    
    @Override
    public Parsed parse(ParseContext context, TokenKind tokenKind, boolean invertMatch) {
        Parsed result = super.parse(context, tokenKind, invertMatch);
        
        if (result.isSucceeded()) {
            String varName = result.getRootToken().getConsumedString();
            
            // Check if variable was declared
            Set<String> declaredVars = context.get(
                VariableDeclarationParser.DECLARED_VARIABLES, 
                Set.class
            ).orElse(Collections.emptySet());
            
            if (!declaredVars.contains(varName)) {
                // Variable not declared - could return error or warning
                System.err.println("Undeclared variable: " + varName);
            }
        }
        
        return result;
    }
}
```

### Use Case 2: Nested Scope Management

Track scope levels for languages with block scope:

```java
public class BlockParser extends LazyChain {
    
    public static final Name SCOPE_LEVEL = Name.of("scopeLevel");
    public static final Name SCOPE_VARIABLES = Name.of("scopeVariables");
    
    @Override
    public Parsers getLazyParsers() {
        return new Parsers(
            Parser.get(LBraceParser.class),
            Parser.get(StatementsParser.class),
            Parser.get(RBraceParser.class)
        );
    }
    
    @Override
    public Parsed parse(ParseContext context, TokenKind tokenKind, boolean invertMatch) {
        // Enter new scope
        int currentLevel = context.get(SCOPE_LEVEL, Integer.class).orElse(0);
        context.put(SCOPE_LEVEL, currentLevel + 1);
        
        // Create new variable map for this scope
        Map<String, Token> scopeVars = new HashMap<>();
        context.put(this, SCOPE_VARIABLES, scopeVars);
        
        Parsed result = super.parse(context, tokenKind, invertMatch);
        
        // Exit scope
        context.put(SCOPE_LEVEL, currentLevel);
        
        return result;
    }
}
```

### Use Case 3: Symbol Table Construction

Build a complete symbol table during parsing:

```java
public class SymbolTableBuilder {
    
    public static class Symbol {
        String name;
        String type;
        int scopeLevel;
        Token declarationToken;
        
        public Symbol(String name, String type, int scopeLevel, Token token) {
            this.name = name;
            this.type = type;
            this.scopeLevel = scopeLevel;
            this.declarationToken = token;
        }
    }
    
    public static final Name SYMBOL_TABLE = Name.of("symbolTable");
    
    public static void addSymbol(ParseContext context, Symbol symbol) {
        Map<String, Symbol> table = context.get(SYMBOL_TABLE, Map.class)
            .orElse(new HashMap<>());
        table.put(symbol.name, symbol);
        context.put(SYMBOL_TABLE, table);
    }
    
    public static Optional<Symbol> lookupSymbol(ParseContext context, String name) {
        Map<String, Symbol> table = context.get(SYMBOL_TABLE, Map.class)
            .orElse(Collections.emptyMap());
        return Optional.ofNullable(table.get(name));
    }
}

public class FunctionDeclarationParser extends Chain {
    @Override
    public Parsed parse(ParseContext context, TokenKind tokenKind, boolean invertMatch) {
        Parsed result = super.parse(context, tokenKind, invertMatch);
        
        if (result.isSucceeded()) {
            Token root = result.getRootToken();
            String functionName = extractFunctionName(root);
            String returnType = extractReturnType(root);
            int scopeLevel = context.get(BlockParser.SCOPE_LEVEL, Integer.class)
                .orElse(0);
            
            Symbol symbol = new Symbol(functionName, returnType, scopeLevel, root);
            SymbolTableBuilder.addSymbol(context, symbol);
        }
        
        return result;
    }
}
```

### Scope Tree Best Practices

1. **Use Named Keys**: Always use `Name.of()` for better type safety and clarity
2. **Clean Up**: Remove scope data when exiting scopes to prevent memory leaks
3. **Type Safety**: Use generic methods with class parameters for type-safe retrieval
4. **Document Scope Keys**: Use static constants for scope keys
5. **Hierarchical Scopes**: Use parser-specific scopes for hierarchical data

## Backward Reference: Matching Previous Tokens

Backward reference allows you to match against tokens that were parsed earlier in the document. This is crucial for languages with paired constructs like XML tags or pattern matching.

### MatchedTokenParser

The `MatchedTokenParser` searches for previously matched tokens and validates against them:

```java
public class MatchedTokenParser extends AbstractParser {
    
    // Constructor: reference tokens parsed by specific parser
    public MatchedTokenParser(Parser targetParser)
    
    // Constructor: reference tokens matching predicate
    public MatchedTokenParser(Predicate<Token> tokenPredicator)
    
    // With slicing: extract part of matched token
    public MatchedTokenParser(Parser targetParser, RangeSpecifier rangeSpecifier, boolean reverse)
}
```

### Use Case 1: XML-Style Paired Tags

Match opening and closing tags:

```java
// Grammar: <tagname>content</tagname>
// Opening tag must match closing tag

public class XmlElementParser extends Chain {
    
    public XmlElementParser() {
        super(
            Parser.get(OpeningTagParser.class),   // <tagname>
            Parser.get(ContentParser.class),       // content
            Parser.get(ClosingTagParser.class)     // </tagname>
        );
    }
}

public class OpeningTagParser extends Chain {
    public OpeningTagParser() {
        super(
            new MappedSingleCharacterParser('<'),
            Parser.get(IdentifierParser.class),  // Tag name
            new MappedSingleCharacterParser('>')
        );
    }
}

public class ClosingTagParser extends Chain {

    public ClosingTagParser() {
        super(
            new Chain(
                new MappedSingleCharacterParser('<'),
                new MappedSingleCharacterParser('/')
            ),
            // Match the identifier from OpeningTagParser
            new MatchedTokenParser(
                Parser.get(IdentifierParser.class)
            ),
            new MappedSingleCharacterParser('>')
        );
    }
}

// Usage
ParseContext context = new ParseContext(
    StringSource.createRootSource("<div>Hello</div>")
);
Parser parser = Parser.get(XmlElementParser.class);
Parsed result = parser.parse(context);

// Succeeds: <div>Hello</div>
// Fails:    <div>Hello</span>  (mismatched closing tag)
```

### Use Case 2: Here Documents

Match delimiter in heredoc-style syntax:

```java
// Grammar: <<DELIMITER\ncontent\nDELIMITER

public class HereDocParser extends LazyChain {
    
    @Override
    public Parsers getLazyParsers() {
        return new Parsers(
            // Opening
            new Chain(
                new MappedSingleCharacterParser('<'),
                new MappedSingleCharacterParser('<'),
                Parser.get(IdentifierParser.class)  // Delimiter
            ),
            new LineBreakParser(),
            
            // Content (anything until closing delimiter)
            new ZeroOrMore(
                new Chain(
                    new Not(
                        new MatchedTokenParser(
                            Parser.get(IdentifierParser.class)
                        )
                    ),
                    new WildCardCharacterParser()
                )
            ),
            
            // Closing delimiter (must match opening)
            new MatchedTokenParser(
                Parser.get(IdentifierParser.class)
            )
        );
    }
}

// Example input:
// <<END
// This is the content
// Multiple lines
// END
```

### Use Case 3: Quoted String with Custom Delimiter

Match balanced quotes with any delimiter:

```java
// Allow: q{content}, q[content], q(content), etc.

public class CustomQuotedStringParser extends Chain {
    
    static final Map<Character, Character> PAIRS = Map.of(
        '{', '}',
        '[', ']',
        '(', ')',
        '<', '>'
    );
    
    public CustomQuotedStringParser() {
        super(
            new MappedSingleCharacterParser('q'),
            Parser.get(DelimiterParser.class),  // Opening delimiter
            
            // Content
            new ZeroOrMore(
                new Chain(
                    new Not(
                        new MatchedTokenParser(
                            Parser.get(DelimiterParser.class)
                        ).effect(this::getClosingDelimiter)
                    ),
                    new WildCardCharacterParser()
                )
            ),
            
            // Closing delimiter (matched against opening)
            new MatchedTokenParser(
                Parser.get(DelimiterParser.class)
            ).effect(this::getClosingDelimiter)
        );
    }
    
    private String getClosingDelimiter(String opening) {
        char openChar = opening.charAt(0);
        char closeChar = PAIRS.getOrDefault(openChar, openChar);
        return String.valueOf(closeChar);
    }
}

// Matches: q{hello}, q[world], q(foo), q<bar>
```

### Use Case 4: Pattern Matching Variables

Reference captured groups in pattern matching:

```java
// Grammar: pattern = value
// where pattern defines variables that must match in value

public class PatternMatchParser extends Chain {

    public PatternMatchParser() {
        super(
            Parser.get(PatternParser.class),    // Defines variables
            new MappedSingleCharacterParser('='),
            Parser.get(ValueParser.class)       // Must match pattern
        );
    }
}

public class ValueParser extends LazyChoice {
    
    @Override
    public Parsers getLazyParsers() {
        return new Parsers(
            // Match variable from pattern
            new MatchedTokenParser(
                token -> token.getParser() instanceof VariableParser
            ),
            // Or literal value
            Parser.get(LiteralParser.class)
        );
    }
}

// Example:
// point(x, y) = point(10, 20)  // Succeeds, binds x=10, y=20
// point(x, y) = line(10, 20)   // Fails, structure mismatch
```

### Advanced: Slicing Matched Tokens

Extract parts of matched tokens:

```java
// Extract just the tag name without brackets
MatchedTokenParser tagMatcher = new MatchedTokenParser(
    Parser.get(TagParser.class)
).slice(
    new RangeSpecifier(1, -1),  // Skip first and last character
    false
);

// Example: if TagParser matched "<div>", this matches "div"
```

### Backward Reference Best Practices

1. **Use Predicates**: For flexible matching across multiple parser types
2. **Cache Matches**: `MatchedTokenParser` caches results in scope tree for efficiency
3. **Clear Documentation**: Document which tokens are being referenced
4. **Error Handling**: Provide clear errors when matches fail
5. **Order Matters**: Ensure referenced parsers execute before matchers

## Error Reporting with ErrorMessageParser

`ErrorMessageParser` allows you to embed error messages directly in your grammar, providing context-aware error reporting.

### Basic Error Messages

```java
Parser parser = new Chain(
    Parser.get(DigitParser.class),
    Parser.get(PlusParser.class),
    new Choice(
        Parser.get(DigitParser.class),
        new ErrorMessageParser("Expected digit after '+' operator")
    )
);

ParseContext context = new ParseContext(
    StringSource.createRootSource("1+")
);
Parsed result = parser.parse(context);

// Parse succeeds, but carries error message
if (result.isSucceeded()) {
    List<ErrorMessage> errors = TokenPrinter.getErrorMessages(
        result.getRootToken()
    );
    
    for (ErrorMessage error : errors) {
        System.err.printf(
            "Error at position %d: %s%n",
            error.getRange().startIndexInclusive.positionInRoot().value(),
            error.getContent()
        );
    }
}
// Output: Error at position 2: Expected digit after '+' operator
```

### Expected-Hint Mode (Choice Fallback)

Use `ErrorMessageParser.expected(...)` when you want a `Choice` fallback that fails but injects a focused expected hint for diagnostics.

```java
Parser parser = new Chain(
    Parser.get(DigitParser.class),
    Parser.get(PlusParser.class),
    new Choice(
        Parser.get(DigitParser.class),
        ErrorMessageParser.expected("expected: digit after '+'")
    )
);

ParseContext context = new ParseContext(
    StringSource.createRootSource("1+")
);
Parsed result = parser.parse(context);

// Parse fails, diagnostics can read the custom expected hint.
ParseFailureDiagnostics diagnostics = context.getParseFailureDiagnostics();
```

### Use Case 1: Syntax Error Recovery

Continue parsing after errors to find multiple issues:

```java
public class StatementParser extends Choice {
    
    public StatementParser() {
        super(
            Parser.get(IfStatementParser.class),
            Parser.get(WhileStatementParser.class),
            Parser.get(ReturnStatementParser.class),
            // Fallback: report error but continue
            new Chain(
                new ErrorMessageParser("Invalid statement"),
                new ZeroOrMore(
                    new Chain(
                        new Not(Parser.get(SemicolonParser.class)),
                        new WildCardCharacterParser()
                    )
                ),
                new Optional(Parser.get(SemicolonParser.class))
            )
        );
    }
}

// Input: "if (x) { } invalid stuff; while (y) { }"
// Reports error at "invalid stuff" but continues parsing
```

### Use Case 2: Missing Required Elements

```java
public class FunctionCallParser extends Chain {
    
    public FunctionCallParser() {
        super(
            Parser.get(IdentifierParser.class),
            new Choice(
                Parser.get(LParenParser.class),
                new ErrorMessageParser("Missing '(' after function name")
            ),
            new Optional(Parser.get(ArgumentListParser.class)),
            new Choice(
                Parser.get(RParenParser.class),
                new ErrorMessageParser("Missing ')' in function call")
            )
        );
    }
}

// Input: "foo bar"
// Reports: Missing '(' after function name
```

### Use Case 3: Context-Specific Error Messages

Provide different errors based on context:

```java
public class TypeAnnotationParser extends Chain {
    
    public TypeAnnotationParser() {
        super(
            Parser.get(ColonParser.class),
            new Choice(
                Parser.get(TypeNameParser.class),
                new ErrorMessageParser("Expected type name after ':'")
            )
        );
    }
}

public class VariableDeclarationParser extends Chain {
    
    public VariableDeclarationParser() {
        super(
            new Choice(
                new Chain(
                    Parser.get(VarKeywordParser.class),
                    Parser.get(IdentifierParser.class)
                ),
                new ErrorMessageParser("Variable declaration must start with 'var'")
            ),
            new Optional(Parser.get(TypeAnnotationParser.class)),
            new Choice(
                new Chain(
                    Parser.get(EqualsParser.class),
                    Parser.get(ExpressionParser.class)
                ),
                new ErrorMessageParser("Expected '=' and initializer")
            ),
            new Choice(
                Parser.get(SemicolonParser.class),
                new ErrorMessageParser("Missing ';' at end of declaration")
            )
        );
    }
}

// Each error message provides specific context about what went wrong
```

### Use Case 4: Deprecated Syntax Warnings

Use error messages for warnings about deprecated features:

```java
public class OldStyleLoopParser extends Chain {
    
    public OldStyleLoopParser() {
        super(
            new ErrorMessageParser(
                "WARNING: Old-style loop syntax is deprecated. " +
                "Use 'for item in collection' instead."
            ),
            Parser.get(RepeatKeywordParser.class),
            Parser.get(NumberParser.class),
            Parser.get(TimesKeywordParser.class),
            Parser.get(BlockParser.class)
        );
    }
}

// Input: "repeat 5 times { ... }"
// Warning: Old-style loop syntax is deprecated...
// But parse succeeds for backward compatibility
```

### Extracting Error Messages

```java
// Method 1: Using TokenPrinter
List<ErrorMessage> errors = TokenPrinter.getErrorMessages(rootToken);

for (ErrorMessage error : errors) {
    System.err.printf(
        "Line %d, Column %d: %s%n",
        error.getRange().startIndexInclusive.lineNumber().value,
        error.getRange().startIndexInclusive.positionInLine().value,
        error.getContent()
    );
}

// Method 2: Using ErrorMessageParser directly
List<RangedContent<String>> errors = 
    ErrorMessageParser.getRangedContents(rootToken, ErrorMessageParser.class);

for (RangedContent<String> error : errors) {
    // Process error with full range information
    CursorRange range = error.getRange();
    String message = error.getContent();
    // ...
}

// Method 3: Custom traversal
void findErrors(Token token, List<ErrorMessage> errors) {
    if (token.getParser() instanceof ErrorMessageParser) {
        ErrorMessageParser emp = (ErrorMessageParser) token.getParser();
        errors.add(new ErrorMessage(
            token.getSource().cursorRange(),
            emp.get()
        ));
    }
    
    for (Token child : token.getChildren()) {
        findErrors(child, errors);
    }
}
```

### Error Message Best Practices

1. **Specific Messages**: Provide context about what was expected
2. **Position Information**: Error messages include cursor range automatically
3. **Recovery Strategy**: Combine with `Optional` or `ZeroOrMore` to continue parsing
4. **Multiple Errors**: Use error recovery to find all issues in one pass
5. **Error vs Warning**: Use message content to distinguish severity
6. **User-Friendly**: Write messages from the user's perspective

### Complete Error Reporting Example

```java
public class LanguageParserWithErrors {
    
    public static void main(String[] args) {
        String source = """
            var x = 10
            if (x > 5 {
                print(x
            }
            var y
            """;
        
        Parser parser = Parser.get(ProgramParser.class);
        ParseContext context = new ParseContext(
            StringSource.createRootSource(source)
        );
        
        Parsed result = parser.parse(context);
        
        if (result.isSucceeded()) {
            List<ErrorMessage> errors = TokenPrinter.getErrorMessages(
                result.getRootToken()
            );
            
            if (errors.isEmpty()) {
                System.out.println("✓ Parse successful with no errors");
            } else {
                System.out.println("⚠ Parse succeeded with errors:");
                for (ErrorMessage error : errors) {
                    printError(source, error);
                }
            }
        } else {
            System.out.println("✗ Parse failed completely");
        }
        
        context.close();
    }
    
    static void printError(String source, ErrorMessage error) {
        int line = error.getRange().startIndexInclusive.lineNumber().value;
        int col = error.getRange().startIndexInclusive.positionInLine().value;
        
        System.out.printf("%d:%d - %s%n", line, col, error.getContent());
        
        // Show source line with error indicator
        String[] lines = source.split("\n");
        if (line <= lines.length) {
            System.out.println(lines[line - 1]);
            System.out.println(" ".repeat(col) + "^");
        }
        System.out.println();
    }
}

// Output:
// ⚠ Parse succeeded with errors:
// 1:10 - Missing ';' at end of declaration
// var x = 10
//           ^
//
// 2:11 - Missing ')' after condition
// if (x > 5 {
//            ^
//
// 3:15 - Missing ')' in function call
//     print(x
//                ^
//
// 5:6 - Expected '=' and initializer
// var y
//       ^
```



Unlaxer's architecture makes it ideal for building Language Server Protocol (LSP) implementations for custom languages and DSLs.

### Why Unlaxer for LSP?

1. **Incremental Parsing**: Parse tree structure enables efficient re-parsing
2. **Position Tracking**: Built-in line/column tracking for all tokens
3. **Error Recovery**: Graceful handling of incomplete/invalid input
4. **Rich Metadata**: Tokens carry parser information useful for semantic analysis

### Basic LSP Implementation

Here's a foundation for an LSP server using Unlaxer:

```java
import org.unlaxer.*;
import org.unlaxer.parser.*;
import org.unlaxer.context.*;
import org.eclipse.lsp4j.*;
import org.eclipse.lsp4j.services.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public class UnlaxerLanguageServer implements LanguageServer, 
                                               LanguageClientAware {
    
    private LanguageClient client;
    private final TextDocumentService textDocumentService;
    private final WorkspaceService workspaceService;
    
    // Document cache
    private final Map<String, DocumentState> documents = new HashMap<>();
    
    public UnlaxerLanguageServer() {
        this.textDocumentService = new UnlaxerTextDocumentService(this);
        this.workspaceService = new UnlaxerWorkspaceService(this);
    }
    
    @Override
    public CompletableFuture<InitializeResult> initialize(
            InitializeParams params) {
        
        ServerCapabilities capabilities = new ServerCapabilities();
        capabilities.setTextDocumentSync(TextDocumentSyncKind.Full);
        capabilities.setCompletionProvider(new CompletionOptions());
        capabilities.setHoverProvider(true);
        capabilities.setDefinitionProvider(true);
        capabilities.setDocumentSymbolProvider(true);
        capabilities.setDiagnosticProvider(new DiagnosticRegistrationOptions());
        
        return CompletableFuture.completedFuture(
            new InitializeResult(capabilities)
        );
    }
    
    @Override
    public TextDocumentService getTextDocumentService() {
        return textDocumentService;
    }
    
    @Override
    public WorkspaceService getWorkspaceService() {
        return workspaceService;
    }
    
    @Override
    public void connect(LanguageClient client) {
        this.client = client;
    }
    
    // Document state management
    static class DocumentState {
        String uri;
        String content;
        Parsed parsed;
        List<Diagnostic> diagnostics;
        long version;
        
        DocumentState(String uri, String content, long version) {
            this.uri = uri;
            this.content = content;
            this.version = version;
        }
    }
    
    // Parse document and cache results
    void parseDocument(String uri, String content, long version) {
        try {
            Parser parser = createYourLanguageParser();
            ParseContext context = new ParseContext(
                StringSource.createRootSource(content)
            );
            Parsed result = parser.parse(context);
            
            List<Diagnostic> diagnostics = new ArrayList<>();
            
            if (result.isFailed()) {
                // Convert parse errors to LSP diagnostics
                Diagnostic diagnostic = new Diagnostic();
                diagnostic.setSeverity(DiagnosticSeverity.Error);
                diagnostic.setMessage("Parse error");
                // Set range based on cursor position
                diagnostic.setRange(createRange(context));
                diagnostics.add(diagnostic);
            }
            
            DocumentState state = new DocumentState(uri, content, version);
            state.parsed = result;
            state.diagnostics = diagnostics;
            documents.put(uri, state);
            
            // Send diagnostics to client
            client.publishDiagnostics(
                new PublishDiagnosticsParams(uri, diagnostics)
            );
            
            context.close();
        } catch (Exception e) {
            // Handle parsing exceptions
        }
    }
    
    private Parser createYourLanguageParser() {
        // Return your language's root parser
        return Parser.get(YourLanguageParser.class);
    }
    
    private Range createRange(ParseContext context) {
        // Convert Unlaxer position to LSP Range
        ParserCursor cursor = context.getTokenStack().peek().getCursor();
        LineNumber line = cursor.lineNumber();
        CodePointIndexInLine column = cursor.positionInLine();
        
        Position pos = new Position(
            line.value - 1,  // LSP is 0-based
            column.value
        );
        return new Range(pos, pos);
    }
}

// Text Document Service
class UnlaxerTextDocumentService implements TextDocumentService {
    
    private final UnlaxerLanguageServer server;
    
    UnlaxerTextDocumentService(UnlaxerLanguageServer server) {
        this.server = server;
    }
    
    @Override
    public void didOpen(DidOpenTextDocumentParams params) {
        TextDocumentItem doc = params.getTextDocument();
        server.parseDocument(doc.getUri(), doc.getText(), doc.getVersion());
    }
    
    @Override
    public void didChange(DidChangeTextDocumentParams params) {
        String uri = params.getTextDocument().getUri();
        String content = params.getContentChanges().get(0).getText();
        long version = params.getTextDocument().getVersion();
        server.parseDocument(uri, content, version);
    }
    
    @Override
    public CompletableFuture<List<CompletionItem>> completion(
            CompletionParams params) {
        
        String uri = params.getTextDocument().getUri();
        DocumentState doc = server.documents.get(uri);
        
        if (doc == null || doc.parsed == null) {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
        
        // Find token at cursor position
        Position pos = params.getPosition();
        Token tokenAtCursor = findTokenAtPosition(
            doc.parsed.getRootToken(), 
            pos.getLine() + 1,  // Convert to 1-based
            pos.getCharacter()
        );
        
        // Generate completions based on context
        List<CompletionItem> items = generateCompletions(tokenAtCursor);
        
        return CompletableFuture.completedFuture(items);
    }
    
    @Override
    public CompletableFuture<Hover> hover(HoverParams params) {
        String uri = params.getTextDocument().getUri();
        DocumentState doc = server.documents.get(uri);
        
        if (doc == null) {
            return CompletableFuture.completedFuture(null);
        }
        
        Position pos = params.getPosition();
        Token token = findTokenAtPosition(
            doc.parsed.getRootToken(),
            pos.getLine() + 1,
            pos.getCharacter()
        );
        
        if (token != null) {
            // Create hover information
            String content = String.format(
                "Token: %s\nType: %s\nText: %s",
                token.getParser().getClass().getSimpleName(),
                token.getParser().getClass().getName(),
                token.getConsumedString()
            );
            
            Hover hover = new Hover();
            hover.setContents(new MarkupContent("markdown", content));
            return CompletableFuture.completedFuture(hover);
        }
        
        return CompletableFuture.completedFuture(null);
    }
    
    @Override
    public CompletableFuture<List<? extends DocumentSymbol>> documentSymbol(
            DocumentSymbolParams params) {
        
        String uri = params.getTextDocument().getUri();
        DocumentState doc = server.documents.get(uri);
        
        if (doc == null || doc.parsed == null) {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
        
        // Convert token tree to document symbols
        List<DocumentSymbol> symbols = extractSymbols(
            doc.parsed.getRootToken()
        );
        
        return CompletableFuture.completedFuture(symbols);
    }
    
    private Token findTokenAtPosition(Token root, int line, int character) {
        // Traverse token tree to find token at position
        if (root == null) return null;
        
        // Check if position is within this token's range
        // (Implementation depends on your position tracking)
        
        // Recursively search children
        for (Token child : root.getChildren()) {
            Token found = findTokenAtPosition(child, line, character);
            if (found != null) return found;
        }
        
        return null;
    }
    
    private List<CompletionItem> generateCompletions(Token context) {
        List<CompletionItem> items = new ArrayList<>();
        
        // Example: suggest keywords
        CompletionItem item = new CompletionItem("if");
        item.setKind(CompletionItemKind.Keyword);
        item.setDetail("if statement");
        items.add(item);
        
        // Add more completion logic based on context
        
        return items;
    }
    
    private List<DocumentSymbol> extractSymbols(Token token) {
        List<DocumentSymbol> symbols = new ArrayList<>();
        
        // Example: Extract function definitions
        if (token.getParser() instanceof FunctionDefParser) {
            DocumentSymbol symbol = new DocumentSymbol();
            symbol.setName(extractFunctionName(token));
            symbol.setKind(SymbolKind.Function);
            symbol.setRange(tokenToRange(token));
            symbol.setSelectionRange(tokenToRange(token));
            symbols.add(symbol);
        }
        
        // Recursively process children
        for (Token child : token.getChildren()) {
            symbols.addAll(extractSymbols(child));
        }
        
        return symbols;
    }
    
    private Range tokenToRange(Token token) {
        // Convert Unlaxer token range to LSP Range
        // This is a simplified version
        Position start = new Position(0, token.getRange().start.value);
        Position end = new Position(0, token.getRange().end.value);
        return new Range(start, end);
    }
    
    private String extractFunctionName(Token token) {
        // Extract function name from token children
        return token.getConsumedString();
    }
}

// Workspace Service
class UnlaxerWorkspaceService implements WorkspaceService {
    private final UnlaxerLanguageServer server;
    
    UnlaxerWorkspaceService(UnlaxerLanguageServer server) {
        this.server = server;
    }
}
```

### LSP Features with Unlaxer

#### 1. Syntax Highlighting

Use token types for semantic highlighting:

```java
public SemanticTokens getSemanticTokens(String uri) {
    DocumentState doc = documents.get(uri);
    List<SemanticToken> tokens = new ArrayList<>();
    
    traverseTokens(doc.parsed.getRootToken(), (token) -> {
        SemanticTokenType type = mapParserToTokenType(
            token.getParser()
        );
        tokens.add(new SemanticToken(
            token.getRange().start.value,
            token.getRange().end.value - token.getRange().start.value,
            type
        ));
    });
    
    return new SemanticTokens(tokens);
}
```

#### 2. Go to Definition

Track symbol definitions during parsing:

```java
private Map<String, Token> symbolTable = new HashMap<>();

public CompletableFuture<Location> definition(DefinitionParams params) {
    Token token = findTokenAtPosition(...);
    
    if (token.getParser() instanceof IdentifierParser) {
        String name = token.getConsumedString();
        Token definition = symbolTable.get(name);
        
        if (definition != null) {
            return CompletableFuture.completedFuture(
                tokenToLocation(definition)
            );
        }
    }
    
    return CompletableFuture.completedFuture(null);
}
```

#### 3. Code Folding

Use parser hierarchy for folding regions:

```java
public List<FoldingRange> getFoldingRanges(String uri) {
    DocumentState doc = documents.get(uri);
    List<FoldingRange> ranges = new ArrayList<>();
    
    traverseTokens(doc.parsed.getRootToken(), (token) -> {
        // Fold blocks, functions, classes, etc.
        if (isFoldableParser(token.getParser())) {
            ranges.add(tokenToFoldingRange(token));
        }
    });
    
    return ranges;
}
```

### LSP Best Practices

1. **Incremental Updates**: Cache parse results and only re-parse changed regions
2. **Error Recovery**: Use `Optional` and `ZeroOrMore` for robust parsing
3. **Position Mapping**: Leverage Unlaxer's built-in position tracking
4. **Symbol Table**: Build during parsing for efficient lookups
5. **Async Processing**: Run parsing in background threads



### vs. Parser Combinators in Other Languages

#### Haskell Parsec / Megaparsec

**Similarities**:
- Monad-based composition (via method chaining in Java)
- Backtracking support
- Error reporting

**Differences**:
- Unlaxer: Object-oriented, class-based parsers
- Parsec: Functional, higher-order functions
- Unlaxer: Explicit transaction stack
- Parsec: Implicit via State monad

#### Scala Parser Combinators

**Similarities**:
- Operator-based composition (`~`, `|`)
- Rich combinator library

**Differences**:
- Unlaxer: Named methods (`Chain`, `Choice`)
- Scala: Symbolic operators (`~`, `|`, `~>`)
- Unlaxer: Full parse tree by default
- Scala: Can discard intermediate results

#### JavaScript/TypeScript Parsimmon / Arcsecond

**Similarities**:
- Fluent API for chaining
- `.map()` for transformation

**Differences**:
- Unlaxer: Stateful ParseContext
- JS libs: Stateless parsers
- Unlaxer: Java's static typing
- JS libs: Dynamic typing (or TypeScript)

### vs. Parser Generators (ANTLR, Bison, etc.)

**Parser Combinator Advantages** (Unlaxer):
- No separate grammar file needed
- Grammar is executable Java code
- Full IDE support (autocomplete, refactoring, debugging)
- Can use Java logic in grammar
- Easy to extend with custom parsers

**Parser Generator Advantages**:
- Better error messages out-of-box
- More efficient (typically LR/LALR)
- Grammar as documentation
- Better for large, complex grammars

**When to use Unlaxer**:
- Embedded DSLs
- Small to medium grammars
- Prototyping
- When you need tight integration with Java code
- When IDE support for grammar is important

**When to use parser generators**:
- Large, complex grammars
- Need maximum performance
- Standard languages (SQL, JavaScript, etc.)
- When separate grammar documentation is desired

### Unlaxer's Unique Features

1. **RELAX NG-inspired vocabulary**: Familiar to XML developers
2. **Transaction-based backtracking**: Explicit and traceable
3. **Scope tree**: Parser-specific context for complex grammars
4. **Backward reference**: Support for context-dependent parsing
5. **Meta token control**: Choose between compact and full trees
6. **Comprehensive logging**: Detailed trace of parsing process

## Best Practices

### 1. Use Singleton Parsers for Terminals

```java
// Good - reuses instances
Parser digit = Parser.get(DigitParser.class);

// Less efficient - creates new instance each time
Parser digit = new DigitParser();
```

### 2. Named Parsers for Complex Grammars

```java
Parser ifStmt = new Chain(/* ... */);
ifStmt.setName(new Name("IfStatement"));

// Easier to identify in token tree and error messages
```

### 3. Lazy Evaluation for Recursion

```java
// Always use lazy evaluation for recursive grammars
Supplier<Parser> exprSupplier = () -> {
    return new Choice(
        term,
        new Chain(lparen, Parser.get(exprSupplier), rparen)
    );
};
Parser expr = Parser.get(exprSupplier);
```

### 4. Choose Appropriate createMeta Mode

```java
// For lexing/tokenization - use createMetaOff
ParseContext lexContext = new ParseContext(
    source,
    CreateMetaTokenSpecifier.createMetaOff
);

// For AST building - use createMetaOn
ParseContext astContext = new ParseContext(
    source,
    CreateMetaTokenSpecifier.createMetaOn
);
```

### 5. Test Incrementally

```java
// Test each level of grammar separately
@Test
public void testTerm() {
    Parser term = createTermParser();
    // Test term in isolation
}

@Test
public void testExpression() {
    Parser expr = createExpressionParser();
    // Test full expression
}
```

## Requirements

- Java 17 or later

## Building

```bash
./mvnw clean install
```

## Testing

```bash
./mvnw test
```

## License

MIT License

Copyright (c) 2025

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

## Resources

- [Maven Central Repository](https://search.maven.org/search?q=g:org.unlaxer)
- [RELAX NG Specification](http://relaxng.org/)

## Contributing

Contributions are welcome! Please feel free to submit issues and pull requests.

## Author

Created with inspiration from RELAX NG's elegant schema language.
