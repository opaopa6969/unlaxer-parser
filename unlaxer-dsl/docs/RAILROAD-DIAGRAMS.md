# UBNF → Railroad Diagrams Generator

## Overview

The **Railroad Diagram Generator** is a command-line tool that converts UBNF grammar files into visual railroad diagrams. Railroad diagrams are a clear, visual way to represent grammar rules and are particularly useful for documentation.

**Source files:**
- `src/main/java/org/unlaxer/dsl/tools/railroad/RailroadMain.java` — CLI entry point
- `src/main/java/org/unlaxer/dsl/tools/railroad/RailroadDiagram.java` — SVG rendering engine
- `src/main/java/org/unlaxer/dsl/tools/railroad/UBNFToRailroad.java` — UBNF to Railroad model converter
- `src/main/java/org/unlaxer/dsl/tools/railroad/SvgToPngConverter.java` — SVG→PNG transcoder (Apache Batik)

## Usage

```bash
java RailroadMain <input.ubnf> [output-dir] [--format svg|png|both|markdown]
```

### Arguments

| Argument | Required | Default | Description |
|----------|----------|---------|-------------|
| `<input.ubnf>` | Yes | — | Path to UBNF grammar file |
| `[output-dir]` | No | `./railroad-output/` | Output directory for diagrams |
| `[--format TYPE]` | No | `svg` | Output format(s) |

### Output Formats

#### `svg` (default)
- Generates one SVG file per grammar rule
- Creates `index.html` with embedded SVGs, TOC sidebar, and smooth navigation
- All NonTerminal references are clickable links within the page (`#RuleName` anchors)

**Files:**
```
output-dir/
├── index.html                          # Interactive viewer
├── Grammar_RuleName1.svg
├── Grammar_RuleName2.svg
└── ...
```

#### `png`
- Generates PNG images only (no SVG or HTML)
- Useful for lightweight storage or embedding in non-web documents
- Requires Apache Batik transcoder dependency

**Files:**
```
output-dir/
├── Grammar_RuleName1.png
├── Grammar_RuleName2.png
└── ...
```

#### `both`
- Generates both SVG and PNG formats
- Includes `index.html` for interactive exploration

**Files:**
```
output-dir/
├── index.html
├── Grammar_RuleName1.svg
├── Grammar_RuleName1.png
├── Grammar_RuleName2.svg
├── Grammar_RuleName2.png
└── ...
```

#### `markdown`
- Generates SVG files and a GitHub-compatible `README.md`
- Each rule has a **References:** section with clickable links to related rules
- All diagrams display inline in the markdown file on GitHub
- Perfect for publishing grammar documentation alongside code

**Files:**
```
output-dir/
├── README.md                           # GitHub-compatible markdown
├── Grammar_RuleName1.svg
├── Grammar_RuleName2.svg
└── ...
```

**Markdown structure:**
```markdown
# Railroad Diagrams

## Table of Contents

### Grammar
- [Rule1](#rule1)
- [Rule2](#rule2)

## Grammar

### Rule1

![Rule1 diagram](Grammar_Rule1.svg)

**References:** [Rule2](#rule2), [Rule3](#rule3)

### Rule2
...
```

## Examples

### Generate SVG with interactive HTML viewer
```bash
java RailroadMain tinyexpression.ubnf output/
# → output/index.html (open in browser)
```

### Generate PNG for documentation
```bash
java RailroadMain tinyexpression.ubnf output/ --format png
```

### Generate markdown for GitHub
```bash
java RailroadMain tinyexpression.ubnf docs/railroad/ --format markdown
# Commit docs/railroad/README.md to GitHub
```

### Generate all formats
```bash
java RailroadMain tinyexpression.ubnf output/ --format both
```

## Features

### Railroad Diagram Elements

The tool converts UBNF syntax to visual elements:

| UBNF Syntax | SVG Element | Appearance |
|-------------|-------------|-----------|
| Literal `'text'` | Rounded rectangle (Terminal) | Blue box with single quotes |
| Rule reference `RuleName` | Rectangle (NonTerminal) | Green box, clickable link |
| Sequence `a b c` | Horizontal connection | Elements in sequence |
| Choice `a \| b \| c` | Branching paths | Curved alternatives |
| Optional `[a]` | Bypass path above | Can skip and proceed |
| Repeat `{a}` | Loop back below | Zero-or-more repetition |
| Group `(a b)` | Transparent grouping | Nested structures |

### Interactive HTML Features (SVG mode)

- **Sticky sidebar TOC** — Always-visible rule navigation
- **Smooth scrolling** — Auto-scroll to rule sections
- **Anchor links** — `#RuleName` navigation
- **Target highlighting** — Current section highlighted with blue border
- **Clickable NonTerminals** — Hover & click to navigate between rules

### GitHub Integration (Markdown mode)

- SVG images render directly in markdown preview
- Click **References:** links to jump to related rules
- No external dependencies — pure markdown + SVG
- Works on all GitHub-hosted markdown (README.md, .md files, wiki, etc.)

## Reference Extraction

The markdown generator automatically extracts and lists all **references** (rule dependencies) for each rule:

```
### Expression

![Expression diagram](Expression.svg)

**References:** [BooleanExpression](#booleanexpression), [NumberExpression](#numberexpression), [MethodInvocation](#methodinvocation), ...
```

References are:
- **Sorted alphabetically** for consistency
- **Deduplicated** (each reference appears once)
- **Linked** to the corresponding rule section
- **Extracted recursively** through sequences, choices, groups, optionals, and repeats

## Implementation Details

### Architecture

1. **Parser:** UBNFMapper parses UBNF source into UBNFAST AST
2. **Conversion:** UBNFToRailroad walks UBNFAST and builds RailroadDiagram tree
3. **Rendering:** RailroadDiagram.renderSvg() outputs SVG
4. **Output:** RailroadMain writes files and optionally transcodes to PNG

### RailroadDiagram Model

The tool uses a sealed interface hierarchy:
```java
sealed interface RailroadDiagram {
    record Terminal(String label) ...         // 'text'
    record NonTerminal(String label, String grammarName) ...  // RuleName
    record Sequence(List<RailroadDiagram> elements) ...       // a b c
    record Choice(List<RailroadDiagram> alternatives) ...     // a | b
    record Optional(RailroadDiagram inner) ...                // [a]
    record Repeat(RailroadDiagram inner) ...                  // {a}
    record Group(RailroadDiagram inner) ...                   // (a)
}
```

### Curve Rendering (SVG)

Quarter-circle arcs are rendered using quadratic Bézier curves:
- **H→V curves** (horizontal to vertical): control point `(endX, startY)`
- **V→H curves** (vertical to horizontal): control point `(startX, endY)`

This ensures proper corner geometry for Choice/Optional/Repeat structures.

### PNG Transcoding

PNG output uses **Apache Batik** (`batik-transcoder`):
- Reads SVG content from memory
- Transocdes to PNG with 96 DPI (screen resolution)
- Suitable for documentation, slides, and lightweight storage

## Exit Codes

| Code | Meaning |
|------|---------|
| 0 | Success |
| 1 | No input file provided |
| 2 | I/O error (file read/write) |
| 3 | Parse error (invalid UBNF) |
| 4 | Invalid format flag |
| 5 | PNG transcoding error |

## Limitations & Notes

- **PNG mode:** Requires Apache Batik. If PNG transcoding fails, a warning is printed but generation continues.
- **Markdown references:** Only direct rule references are extracted. Literals are not listed.
- **Large grammars:** SVG rendering is linear in number of rules; no performance issues up to 1000+ rules.
- **GitHub markdown:** SVG links in GitHub markdown are relative file references. Ensure SVG files are in the same directory or use correct relative paths.

## Related Tools

- **[BNF Converter](UBNF-TO-BNF.md)** — Generate EBNF from UBNF
- **[Code Generators](CODEGEN.md)** — Generate Parser, AST, Mapper, LSP, DAP from UBNF

## See Also

- [UBNF Syntax Specification](../grammar/ubnf.ubnf)
- [RailroadDiagram Implementation](../src/main/java/org/unlaxer/dsl/tools/railroad/)
