# unlaxer-parser LLM Reference

> **Purpose**: This is the single reference file an LLM needs to create a parser, evaluator, and VSIX extension with unlaxer-parser. Under 2000 lines.

---

## 1. What unlaxer-parser Does

unlaxer-parser is a Java framework that generates a complete language processing system -- parser, AST, mapper, evaluator skeleton, LSP server, and DAP debugger -- from a single UBNF grammar file. Unlike traditional parser generators (ANTLR, etc.) that only produce a parser, unlaxer generates the entire pipeline from grammar to IDE support. You write a `.ubnf` grammar, run `mvn compile`, and get 4-6 Java source files ready to use.

---

## 2. Quick Start: UBNF Grammar to Running Code

### Step 1: Project Setup

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                             http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>com.example</groupId>
    <artifactId>mylang</artifactId>
    <version>1.0.0-SNAPSHOT</version>

    <properties>
        <maven.compiler.source>21</maven.compiler.source>
        <maven.compiler.target>21</maven.compiler.target>
        <unlaxer.version>2.5.0</unlaxer.version>
    </properties>

    <dependencies>
        <dependency>
            <groupId>org.unlaxer</groupId>
            <artifactId>unlaxer-common</artifactId>
            <version>${unlaxer.version}</version>
        </dependency>
        <dependency>
            <groupId>org.unlaxer</groupId>
            <artifactId>unlaxer-dsl</artifactId>
            <version>${unlaxer.version}</version>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.codehaus.mojo</groupId>
                <artifactId>exec-maven-plugin</artifactId>
                <version>3.1.0</version>
                <executions>
                    <execution>
                        <id>generate-parser</id>
                        <phase>generate-sources</phase>
                        <goals><goal>java</goal></goals>
                        <configuration>
                            <mainClass>org.unlaxer.dsl.UbnfCodeGenerator</mainClass>
                            <arguments>
                                <argument>${project.basedir}/src/main/resources/MyLang.ubnf</argument>
                                <argument>${project.build.directory}/generated-sources/ubnf</argument>
                            </arguments>
                        </configuration>
                    </execution>
                </executions>
            </plugin>
            <plugin>
                <groupId>org.codehaus.mojo</groupId>
                <artifactId>build-helper-maven-plugin</artifactId>
                <version>3.4.0</version>
                <executions>
                    <execution>
                        <id>add-generated-sources</id>
                        <phase>generate-sources</phase>
                        <goals><goal>add-source</goal></goals>
                        <configuration>
                            <sources>
                                <source>${project.build.directory}/generated-sources/ubnf</source>
                            </sources>
                        </configuration>
                    </execution>
                </executions>
            </plugin>
        </plugins>
    </build>
</project>
```

### Step 2: Write the Grammar

Place `src/main/resources/MyLang.ubnf`:

```ubnf
grammar MyLang {
  @package: com.example.mylang
  @whitespace: javaStyle

  token NUMBER     = NumberParser
  token IDENTIFIER = IdentifierParser
  token EOF        = EndOfSourceParser

  @root
  Formula ::= Expression EOF ;

  @mapping(BinaryExpr, params=[left, op, right])
  @leftAssoc
  Expression ::= Term @left { AddOp @op Term @right } ;

  @mapping(BinaryExpr, params=[left, op, right])
  @leftAssoc
  Term ::= Factor @left { MulOp @op Factor @right } ;

  Factor ::= NUMBER | '(' Expression ')' ;

  AddOp ::= '+' | '-' ;
  MulOp ::= '*' | '/' ;
}
```

### Step 3: Generate

```bash
mvn compile
```

### Step 4: Generated Files

```
target/generated-sources/ubnf/com/example/mylang/
  MyLangParsers.java      -- parser combinator classes
  MyLangAST.java          -- sealed interface + record AST nodes
  MyLangMapper.java       -- Token tree -> AST conversion
  MyLangEvaluator.java    -- abstract evaluator base class
  MyLangLanguageServer.java  -- LSP server (if LSP generation is enabled)
  MyLangDebugAdapter.java    -- DAP server (if DAP generation is enabled)
```

### Step 5: Implement the Evaluator

```java
package com.example.mylang;

import com.example.mylang.MyLangAST.BinaryExpr;
import com.example.mylang.MyLangAST.NumberLiteral;

public class CalcEvaluator extends MyLangEvaluator<Double> {

    @Override
    protected Double evalBinaryExpr(BinaryExpr node) {
        Double left = eval(node.left());
        Double right = eval(node.right());
        return switch (node.op()) {
            case "+" -> left + right;
            case "-" -> left - right;
            case "*" -> left * right;
            case "/" -> left / right;
            default -> throw new IllegalArgumentException("Unknown operator: " + node.op());
        };
    }

    @Override
    protected Double evalNumber(NumberLiteral node) {
        return Double.parseDouble(node.value());
    }
}
```

### Step 6: Use It

```java
var parsers = new MyLangParsers();
var parseResult = parsers.parse("1 + 2 * 3");
var ast = new MyLangMapper().map(parseResult);
var result = new CalcEvaluator().eval(ast);
// result = 7.0
```

---

## 3. UBNF Syntax Reference

### Grammar Structure

```ubnf
grammar LanguageName {

  // Global settings
  @package: com.example.pkg
  @whitespace: javaStyle
  @comment: { line: '//' }

  // Token declarations
  token TOKEN_NAME = JavaParserClass

  // Rule declarations
  RuleName ::= rule_body ;
}
```

### Global Settings

| Setting | Syntax | Description |
|---------|--------|-------------|
| `@package` | `@package: com.example.pkg` | Target Java package for generated code |
| `@whitespace` | `@whitespace: javaStyle` | Whitespace handling profile |
| `@comment` | `@comment: { line: '//' }` | Comment syntax definition |

### Built-in Token Parsers

| Token Parser | What It Reads | Example Input |
|-------------|---------------|---------------|
| `NumberParser` | Numeric literals | `123`, `3.14` |
| `IdentifierParser` | Identifiers | `foo`, `myVar` |
| `SingleQuotedParser` | Single-quoted strings | `'hello'` |
| `EndOfSourceParser` | End of input | (nothing) |

### Rule Body Operators

| Notation | Meaning | Example |
|----------|---------|---------|
| `A B` | Sequence (A then B) | `Term AddOp Term` |
| `A \| B` | Ordered choice (A or B; PEG semantics, tries left first) | `'+' \| '-'` |
| `{ A }` | Zero or more (repetition) | `{ AddOp Term }` |
| `A +` | One or more | `Digit +` |
| `[ A ]` | Optional (zero or one) | `[ '-' ]` |
| `( A )` | Grouping | `( '+' \| '-' )` |
| `'+'` | Literal string (keyword or symbol) | `'if'`, `'+'` |
| `@name` | Capture (bind to AST field) | `@left`, `@op` |

### Annotations

#### `@root`

Marks the entry point for parsing. Exactly one rule must have `@root`.

```ubnf
@root
Formula ::= Expression EOF ;
```

#### `@mapping(ClassName, params=[field1, field2, ...])`

Declares that when this rule matches, an AST record named `ClassName` is created with the specified fields.

```ubnf
@mapping(BinaryExpr, params=[left, op, right])
Expression ::= Term @left { AddOp @op Term @right } ;
```

- `ClassName` becomes a Java record inside the generated AST sealed interface
- `params` lists the record field names
- `@fieldName` in the rule body binds matched elements to those fields
- If `params` is omitted, a record with no fields is generated: `@mapping(RandomExpr)`

#### `@leftAssoc`

Declares left-associative evaluation for binary operator rules with repetition.

```ubnf
@mapping(BinaryExpr, params=[left, op, right])
@leftAssoc
Expression ::= Term @left { AddOp @op Term @right } ;
```

Without this: `3 - 2 - 1` would be parsed as `3 - (2 - 1) = 2` (wrong).
With this: `3 - 2 - 1` is parsed as `(3 - 2) - 1 = 0` (correct).

#### `@rightAssoc`

Declares right-associative evaluation. Use for exponentiation or assignment operators.

```ubnf
@mapping(AssignExpr, params=[target, value])
@rightAssoc
Assignment ::= IDENTIFIER @target '=' Expression @value ;
```

#### `@precedence(level=N)`

Declares operator precedence level. Higher numbers bind more tightly.

```ubnf
@mapping(BinaryExpr, params=[left, op, right])
@leftAssoc
@precedence(level=10)
Expression ::= Term @left { AddOp @op Term @right } ;

@mapping(BinaryExpr, params=[left, op, right])
@leftAssoc
@precedence(level=20)
Term ::= Factor @left { MulOp @op Factor @right } ;
```

In `3 + 4 * 2`: `Term` (level=20) captures `4 * 2` first, then `Expression` (level=10) processes `3 + 8`.

#### `@interleave(profile=javaStyle)`

Automatically skip whitespace and comments between elements of this rule.

```ubnf
@interleave(profile=javaStyle)
ImportDeclaration ::= 'import' ClassName 'as' IDENTIFIER ';' ;
```

#### `@scopeTree(mode=lexical)`

Marks rules that create a lexical scope (for variable resolution).

```ubnf
@scopeTree(mode=lexical)
MethodDeclaration ::= ReturnType IDENTIFIER '(' Parameters ')' '{' Expression '}' ;
```

#### `@backref(name=refName)`

Declares a back-reference for resolving names (e.g., method invocation resolving to method declaration).

```ubnf
@backref(name=methodName)
@mapping(MethodInvocationExpr, params=[name])
MethodInvocation ::= 'call' IDENTIFIER @name '(' [ Arguments ] ')' ;
```

#### `@eval(kind=..., strategy=...)`

**Design-phase annotation** for evaluator code generation hints. Tells the generator what kind of evaluation logic this rule needs.

```ubnf
@mapping(BinaryExpr, params=[left, op, right])
@leftAssoc
@eval(kind=binary_arithmetic, strategy=default)
Expression ::= Term @left { AddOp @op Term @right } ;
```

See Section 8 for all `@eval` kinds.

---

## 4. Complete TinyCalc Example

### Grammar: `TinyCalc.ubnf`

```ubnf
grammar TinyCalc {
  @package: com.example.tinycalc
  @whitespace: javaStyle

  token NUMBER     = NumberParser
  token IDENTIFIER = IdentifierParser
  token EOF        = EndOfSourceParser

  @root
  @mapping(TinyCalcProgram, params=[declarations, expression])
  TinyCalc ::=
    { VariableDeclaration } @declarations
    Expression @expression ;

  @mapping(VarDecl, params=[keyword, name, init])
  VariableDeclaration ::=
    ( 'var' | 'variable' ) @keyword
    IDENTIFIER @name
    [ 'set' Expression @init ]
    ';' ;

  @mapping(BinaryExpr, params=[left, op, right])
  @leftAssoc
  Expression ::= Term @left { ( '+' @op | '-' @op ) Term @right } ;

  @mapping(BinaryExpr, params=[left, op, right])
  @leftAssoc
  Term ::= Factor @left { ( '*' @op | '/' @op ) Factor @right } ;

  Factor ::=
      '(' Expression ')'
    | NUMBER
    | IDENTIFIER ;
}
```

### Evaluator: `TinyCalcEvaluator.java`

```java
package com.example.tinycalc;

import com.example.tinycalc.TinyCalcAST.*;
import java.util.HashMap;
import java.util.Map;

public class TinyCalcEvaluator extends TinyCalcGeneratedEvaluator<Double> {

    private final Map<String, Double> variables = new HashMap<>();

    @Override
    protected Double evalTinyCalcProgram(TinyCalcProgram node) {
        // Process variable declarations first
        for (var decl : node.declarations()) {
            evalVarDecl(decl);
        }
        return eval(node.expression());
    }

    @Override
    protected Double evalVarDecl(VarDecl node) {
        Double value = node.init() != null ? eval(node.init()) : 0.0;
        variables.put(node.name(), value);
        return value;
    }

    @Override
    protected Double evalBinaryExpr(BinaryExpr node) {
        Double left = eval(node.left());
        Double right = eval(node.right());
        return switch (node.op()) {
            case "+" -> left + right;
            case "-" -> left - right;
            case "*" -> left * right;
            case "/" -> left / right;
            default -> throw new IllegalArgumentException("Unknown operator: " + node.op());
        };
    }

    @Override
    protected Double evalNumberLiteral(NumberLiteral node) {
        return Double.parseDouble(node.value());
    }

    @Override
    protected Double evalIdentifierRef(IdentifierRef node) {
        return variables.getOrDefault(node.name(), 0.0);
    }
}
```

### Test Results

```
"1 + 2 * 3"       -> 7.0    (precedence correct)
"10 - 3 - 2"      -> 5.0    (left-associative: (10-3)-2)
"(1 + 2) * 3"     -> 9.0    (parentheses)
"100 / 10 / 2"    -> 5.0    (left-associative: (100/10)/2)
```

---

## 5. Common Mistakes and Fixes

### Mistake 1: Expression Alternative Ordering

**Problem**: `$a + $b` causes a parse error.

**Cause**: In PEG (ordered choice), the first matching alternative wins. If `BooleanExpression` is listed before `NumberExpression`, `$a` matches `BooleanExpression` (as a variable reference), consuming only `$a` and leaving `+ $b` unparsed.

**Fix**: Put `NumberExpression` before `BooleanExpression` in Expression choices.

```ubnf
// WRONG: BooleanExpression matches $a, leaves +$b unparsed
Expression ::=
    BooleanExpression @value
  | NumberExpression @value
  | StringExpression @value ;

// CORRECT: NumberExpression consumes $a+$b fully
Expression ::=
    NumberExpression @value
  | BooleanExpression @value
  | StringExpression @value ;
```

**Rule**: In PEG ordered choice, list the alternative that consumes the most input first. Numeric expressions with binary operators consume more than boolean variable references.

### Mistake 2: Multiple Rules Mapping to the Same AST Node

**Problem**: `3 * 4` does not produce a valid AST.

**Cause**: Both `NumberExpression` and `NumberTerm` have `@mapping(BinaryExpr, ...)`, but the mapper only registers `NumberExpression`'s parser class for `BinaryExpr` mapping.

**Fix**: Ensure `allMappingRules` in the mapper includes ALL parser classes that map to `BinaryExpr`.

```java
// Both parser classes must be registered
if (token.parser.getClass() == NumberExpressionParser.class) {
    return toBinaryExpr(token);
}
if (token.parser.getClass() == NumberTermParser.class) {
    return toBinaryExpr(token);  // Do not forget this!
}
```

### Mistake 3: Left Recursion

**Problem**: Grammar causes infinite loop or stack overflow.

**Cause**: Direct left recursion is not supported in PEG/unlaxer.

```ubnf
// WRONG: left-recursive
Expression ::= Expression '+' Term | Term ;

// CORRECT: use repetition
Expression ::= Term { '+' Term } ;
```

### Mistake 4: Missing @mapping params

**Problem**: Generated AST record has no fields.

**Cause**: `params` not specified in `@mapping`, and no `@fieldName` captures in the rule body.

```ubnf
// WRONG: no params, no captures
@mapping(BinaryExpr)
Expression ::= Term { AddOp Term } ;

// CORRECT: params + captures
@mapping(BinaryExpr, params=[left, op, right])
Expression ::= Term @left { AddOp @op Term @right } ;
```

### Mistake 5: Forgetting Semicolons

**Problem**: Parse error in UBNF file.

**Cause**: Every rule in UBNF must end with `;`.

```ubnf
// WRONG
Expression ::= Term { AddOp Term }

// CORRECT
Expression ::= Term { AddOp Term } ;
```

### Mistake 6: Token Name Reuse

**Problem**: Unexpected parsing behavior.

**Cause**: Using the same name for a token and a rule.

```ubnf
// WRONG
token NUMBER = NumberParser
NUMBER ::= '-' NUMBER | NUMBER ;  // Conflicts with token

// CORRECT
token NUMBER = NumberParser
NumericLiteral ::= [ '-' ] NUMBER ;
```

---

## 6. How to Add Features

### Adding a New Operator (e.g., modulo `%`)

1. Add the operator to the relevant Op choice rule:

```ubnf
MulOp ::= '*' | '/' | '%' ;
```

2. Add the case to the evaluator:

```java
case "%" -> left % right;
```

3. Run `mvn compile`. Done.

### Adding a New Function (e.g., `abs()`)

1. Add the mapping and rule to the grammar:

```ubnf
@mapping(AbsExpr, params=[arg])
AbsFunction ::= 'abs' '(' Expression @arg ')' ;
```

2. Add `AbsFunction` to the Factor choice:

```ubnf
Factor ::= AbsFunction | NUMBER | '(' Expression ')' ;
```

3. Run `mvn compile`. A new `evalAbsExpr` abstract method appears in the generated evaluator.

4. Implement in your evaluator:

```java
@Override
protected Double evalAbsExpr(AbsExpr node) {
    return Math.abs(eval(node.arg()));
}
```

### Adding a New Expression Type (e.g., variables with `$`)

1. Add a token (if not already present):

```ubnf
token IDENTIFIER = IdentifierParser
```

2. Add the rule and mapping:

```ubnf
@mapping(VariableRefExpr, params=[name])
VariableRef ::= '$' IDENTIFIER @name ;
```

3. Add to Factor:

```ubnf
Factor ::= NUMBER | VariableRef | '(' Expression ')' ;
```

4. Run `mvn compile`.

5. Implement `evalVariableRefExpr` in your evaluator:

```java
@Override
protected Double evalVariableRefExpr(VariableRefExpr node) {
    String varName = node.name().startsWith("$")
        ? node.name().substring(1) : node.name();
    return context.getVariable(varName);
}
```

### Adding an if-Expression

1. Grammar:

```ubnf
@mapping(IfExpr, params=[condition, thenExpr, elseExpr])
IfExpression ::=
  'if' '(' BooleanExpression @condition ')'
  '{' Expression @thenExpr '}'
  'else'
  '{' Expression @elseExpr '}' ;
```

2. Add to Factor.

3. Implement evaluator:

```java
@Override
protected Object evalIfExpr(IfExpr node) {
    Boolean condition = (Boolean) eval(node.condition());
    return condition ? eval(node.thenExpr()) : eval(node.elseExpr());
}
```

### Adding a Ternary Operator

```ubnf
@mapping(TernaryExpr, params=[condition, thenExpr, elseExpr])
TernaryExpression ::=
  BooleanFactor @condition '?' Expression @thenExpr ':' Expression @elseExpr ;
```

### Adding String Expressions

```ubnf
token STRING = SingleQuotedParser

@mapping(StringConcatExpr, params=[left, op, right])
@leftAssoc
StringExpression ::= StringTerm @left { '+' @op StringTerm @right } ;

StringTerm ::= STRING | VariableRef ;
```

### Adding Comparison/Boolean Expressions

```ubnf
@mapping(ComparisonExpr, params=[left, op, right])
ComparisonExpression ::= Expression @left CompareOp @op Expression @right ;
CompareOp ::= '==' | '!=' | '<=' | '>=' | '<' | '>' ;

@mapping(BooleanAndExpr, params=[left, op, right])
@leftAssoc
BooleanExpression ::= BooleanTerm @left { '&&' @op BooleanTerm @right } ;
```

---

## 7. How to Package as VSIX

A VSIX extension requires: syntax highlighting (TextMate grammar), an LSP client, and optionally a DAP client.

### Directory Structure

```
mylang-vscode/
  package.json              -- Extension manifest
  language-configuration.json
  syntaxes/
    mylang.tmLanguage.json  -- TextMate grammar for syntax highlighting
  grammar/
    MyLang.ubnf             -- UBNF grammar (reference copy)
  src/
    extension.ts            -- LSP/DAP client wiring
  server-dist/
    mylang-lsp-server.jar   -- Fat JAR of LSP/DAP server
  tsconfig.json
```

### package.json

```json
{
  "name": "mylang-lsp",
  "displayName": "MyLang DSL (LSP)",
  "description": "VS Code extension for MyLang DSL.",
  "version": "0.1.0",
  "publisher": "your-name",
  "engines": { "vscode": "^1.85.0" },
  "categories": ["Programming Languages"],
  "activationEvents": ["onLanguage:mylang"],
  "main": "./out/extension.js",
  "contributes": {
    "languages": [{
      "id": "mylang",
      "aliases": ["MyLang"],
      "extensions": [".mylang"],
      "configuration": "./language-configuration.json"
    }],
    "grammars": [{
      "language": "mylang",
      "scopeName": "source.mylang",
      "path": "./syntaxes/mylang.tmLanguage.json"
    }],
    "debuggers": [{
      "type": "mylang",
      "label": "MyLang Debug",
      "languages": ["mylang"],
      "configurationAttributes": {
        "launch": {
          "required": ["program"],
          "properties": {
            "program": {
              "type": "string",
              "description": "Path to the .mylang file.",
              "default": "${file}"
            },
            "stopOnEntry": {
              "type": "boolean",
              "default": false
            }
          }
        }
      }
    }],
    "configuration": {
      "title": "MyLang LSP",
      "properties": {
        "mylangLsp.server.javaPath": {
          "type": "string",
          "default": "java"
        },
        "mylangLsp.server.jarPath": {
          "type": "string",
          "default": ""
        }
      }
    }
  },
  "scripts": {
    "vscode:prepublish": "npm run compile",
    "compile": "tsc -p ./"
  },
  "devDependencies": {
    "@types/node": "^20.11.30",
    "@types/vscode": "^1.85.0",
    "typescript": "^5.4.5",
    "@vscode/vsce": "^2.26.0"
  },
  "dependencies": {
    "vscode-languageclient": "^9.0.1"
  }
}
```

### extension.ts

```typescript
import * as path from "path";
import * as vscode from "vscode";
import {
  LanguageClient,
  LanguageClientOptions,
  ServerOptions
} from "vscode-languageclient/node";

let client: LanguageClient | undefined;

function getBundledJarPath(context: vscode.ExtensionContext): string {
  return context.asAbsolutePath(path.join("server-dist", "mylang-lsp-server.jar"));
}

export async function activate(context: vscode.ExtensionContext): Promise<void> {
  const config = vscode.workspace.getConfiguration("mylangLsp");
  const javaPath = config.get<string>("server.javaPath", "java");
  const configuredJar = config.get<string>("server.jarPath", "");
  const jarPath = configuredJar.trim().length > 0
    ? configuredJar
    : getBundledJarPath(context);

  const serverOptions: ServerOptions = {
    command: javaPath,
    args: ["--enable-preview", "-jar", jarPath]
  };

  const clientOptions: LanguageClientOptions = {
    documentSelector: [{ scheme: "file", language: "mylang" }],
    outputChannel: vscode.window.createOutputChannel("MyLang LSP")
  };

  client = new LanguageClient(
    "mylangLanguageServer",
    "MyLang Language Server",
    serverOptions,
    clientOptions
  );

  client.start();
  context.subscriptions.push({ dispose: () => { void client?.stop(); } });

  // DAP adapter
  const dapFactory: vscode.DebugAdapterDescriptorFactory = {
    createDebugAdapterDescriptor(): vscode.ProviderResult<vscode.DebugAdapterDescriptor> {
      return new vscode.DebugAdapterExecutable(javaPath, [
        "--enable-preview", "-cp", jarPath,
        "com.example.mylang.generated.MyLangDapLauncher"
      ]);
    }
  };
  context.subscriptions.push(
    vscode.debug.registerDebugAdapterDescriptorFactory("mylang", dapFactory)
  );
}

export async function deactivate(): Promise<void> {
  if (client) await client.stop();
}
```

### Build and Package

```bash
# 1. Build the LSP/DAP server JAR
cd mylang-server && mvn package -DskipTests
cp target/mylang-lsp-server.jar ../mylang-vscode/server-dist/

# 2. Build the VSIX
cd mylang-vscode
npm install
npm run compile
npx @vscode/vsce package
# Output: mylang-lsp-0.1.0.vsix

# 3. Install locally
code --install-extension mylang-lsp-0.1.0.vsix
```

---

## 8. Available @eval Kinds

The `@eval` annotation is a design-phase feature for declaring evaluation strategy hints in UBNF. These tell the code generator what kind of `evalXxx()` implementation to produce.

### Kinds

| Kind | Description | Typical Use |
|------|-------------|-------------|
| `binary_arithmetic` | Left-associative binary operation with leaf/wrap/binary detection | `NumberExpression`, `NumberTerm`, `StringExpression` (concat) |
| `variable_ref` | Variable reference lookup (including `$` prefix strip) | `VariableRef` |
| `conditional` | if/else branching -- evaluate condition, then thenExpr or elseExpr | `IfExpression` |
| `match_case` | Pattern matching -- evaluate cases in order, return first match | `NumberMatchExpression` |
| `passthrough` | Return value as-is (unwrap a wrapper node) | `ExpressionExpr`, `ObjectExpression` |
| `literal` | Parse a literal value (number, string, boolean) | `NumberLiteral`, `StringLiteral` |
| `comparison` | Compare two values and return boolean | `ComparisonExpression`, `StringComparisonExpression` |
| `invocation` | Method invocation resolution | `MethodInvocation`, `ExternalInvocation` |

### Strategies

| Strategy | Description |
|----------|-------------|
| `default` | Generator produces standard implementation automatically |
| `template("file.java.tmpl")` | Implementation expanded from an external template file |
| `manual` | Left as abstract; human writes the implementation |

### Example Usage

```ubnf
@mapping(BinaryExpr, params=[left, op, right])
@leftAssoc
@precedence(level=10)
@eval(kind=binary_arithmetic, strategy=default)
NumberExpression ::= NumberTerm @left { AddOp @op NumberTerm @right } ;

@mapping(VariableRefExpr, params=[name])
@eval(kind=variable_ref, strategy=default)
VariableRef ::= '$' IDENTIFIER @name ;

@mapping(IfExpr, params=[condition, thenExpr, elseExpr])
@eval(kind=conditional, strategy=default)
IfExpression ::= 'if' '(' BooleanExpression @condition ')' '{' Expression @thenExpr '}' 'else' '{' Expression @elseExpr '}' ;

@mapping(ExpressionExpr, params=[value])
@eval(kind=passthrough, strategy=default)
Expression ::= NumberExpression @value | StringExpression @value ;

@mapping(MethodInvocationExpr, params=[name])
@eval(kind=invocation, strategy=manual)
MethodInvocation ::= 'call' IDENTIFIER @name '(' [ Arguments ] ')' ;
```

### What `binary_arithmetic` with `strategy=default` Generates

The generated evaluator handles the three BinaryExpr patterns automatically:

```java
@Override
protected Object evalBinaryExpr(BinaryExpr node) {
    BinaryExpr left = node.left();
    List<String> op = node.op();
    List<BinaryExpr> right = node.right();

    // Leaf: left==null, op=[literal], right=[]
    if (left == null && right.isEmpty() && op.size() == 1) {
        return resolveLeafLiteral(op.get(0));
    }
    // Wrap: left!=null, op=[], right=[]
    if (left != null && op.isEmpty() && right.isEmpty()) {
        return eval(left);
    }
    // Binary: process left + (op[i], right[i]) pairs left-to-right
    Object current = eval(left);
    for (int i = 0; i < Math.min(op.size(), right.size()); i++) {
        Object r = eval(right.get(i));
        current = applyBinary(op.get(i), current, r);
    }
    return current;
}
```

### Generation Gap Pattern (GGP)

With `@eval`, the class hierarchy becomes 3 layers:

```
MyLangEvaluator<T>           -- generated (abstract, sealed switch dispatch)
  |
  +-- MyLangDefaultEvaluator  -- generated (@eval default/template implementations)
  |     evalBinaryExpr()      -- auto-generated by @eval(kind=binary_arithmetic)
  |     evalVariableRefExpr() -- auto-generated by @eval(kind=variable_ref)
  |     evalMethodInvocation()-- still abstract (strategy=manual)
  |
  +-- MyCustomEvaluator       -- hand-written (extends MyLangDefaultEvaluator)
        evalMethodInvocation()-- manual implementation
        evalBinaryExpr()      -- override only if custom behavior needed
```

---

## 9. tinyexpression: Full-Featured Example

tinyexpression is a production-grade expression language built with unlaxer. Its UBNF grammar (~300 lines) demonstrates all major features:

| Feature | Grammar Pattern | Example Input |
|---------|----------------|---------------|
| Arithmetic | `@leftAssoc` binary rules at precedence 10/20 | `1 + 2 * 3` |
| Variables | `VariableRef ::= '$' IDENTIFIER @name` | `$price * $tax` |
| Strings | `StringExpression` with `+` concat | `'Hello ' + $name` |
| Comparison | `ComparisonExpression` | `$x > 0` |
| Boolean logic | `BooleanExpression` with `\|`, `&`, `^` | `$a > 0 & $b < 10` |
| Ternary | `TernaryExpression` | `$x > 0 ? $x : -$x` |
| if/else | `IfExpression` | `if ($x > 0) { $x } else { -$x }` |
| match | `NumberMatchExpression` | `match { $x > 0 -> 1, default -> 0 }` |
| Built-in functions | `SinFunction`, `SqrtFunction`, etc. | `sin($angle)`, `sqrt($x)` |
| Method definitions | `MethodDeclaration` with `@scopeTree` | `number abs(x) { x > 0 ? x : -x }` |
| Imports | `ImportDeclaration` | `import java.lang.Math#abs as abs;` |
| String methods | `ToUpperCaseMethod`, `TrimMethod` | `toUpperCase($name)` |
| Type conversion | `ToNumFunction` | `toNum($str, 0)` |
| External calls | `ExternalInvocation` | `external returning as number calc()` |

Repository: [tinyexpression](https://github.com/opaopa6969/tinyexpression)

---

## 10. Architecture Summary

```
.ubnf file
    |
    v
[UbnfCodeGenerator / CodegenMain]
    |
    +-> ParserGenerator   -> XxxParsers.java     (parser combinator classes)
    +-> ASTGenerator      -> XxxAST.java         (sealed interface + records)
    +-> MapperGenerator   -> XxxMapper.java       (Token -> AST conversion)
    +-> EvaluatorGenerator-> XxxEvaluator.java    (abstract eval base class)
    +-> LSPGenerator      -> XxxLanguageServer.java (lsp4j-based LSP server)
    +-> DAPGenerator      -> XxxDebugAdapter.java   (DAP debug adapter)
```

### Key Design Principles

1. **Single source of truth**: The UBNF grammar is the only thing you version-control; all code is regenerated from it.
2. **Generation Gap Pattern**: Generated abstract classes in `target/generated-sources/`; hand-written concrete classes in `src/main/java/`.
3. **Sealed interface safety**: Java 21 sealed interfaces ensure exhaustive `switch` coverage. Missing cases are compile errors.
4. **PEG ordered choice**: Alternatives are tried left-to-right. First match wins. Order matters.
5. **Zero overhead debugging**: `DebugStrategy` interface with NOOP default; no performance cost when not debugging.
