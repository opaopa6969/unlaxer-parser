package org.unlaxer.dsl.tools.railroad;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.unlaxer.dsl.bootstrap.UBNFAST.AnnotatedElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.AtomicElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.ChoiceBody;
import org.unlaxer.dsl.bootstrap.UBNFAST.GrammarDecl;
import org.unlaxer.dsl.bootstrap.UBNFAST.GroupElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.BoundedRepeatElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.SeparatedElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.OneOrMoreElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.OptionalElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.RepeatElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.RuleBody;
import org.unlaxer.dsl.bootstrap.UBNFAST.RuleDecl;
import org.unlaxer.dsl.bootstrap.UBNFAST.RuleRefElement;
import org.unlaxer.dsl.bootstrap.UBNFAST.SequenceBody;
import org.unlaxer.dsl.bootstrap.UBNFAST.UBNFFile;
import org.unlaxer.dsl.bootstrap.UBNFMapper;

/**
 * CLI entry point for the UBNF → Railroad Diagram generator.
 *
 * Usage:
 * <pre>
 *   java RailroadMain &lt;input.ubnf&gt; [output-dir] [--format svg|png|both|markdown]
 * </pre>
 *
 * The tool:
 * <ol>
 *   <li>Reads the given {@code .ubnf} file.</li>
 *   <li>Parses it using {@link UBNFMapper#parse(String)}.</li>
 *   <li>Generates one SVG file per grammar rule in the output directory.</li>
 *   <li>Optionally converts to PNG format (via {@code --format png|both}).</li>
 *   <li>Optionally generates a GitHub-compatible README.md with SVG references (via {@code --format markdown}).</li>
 *   <li>Generates an {@code index.html} that embeds all generated SVGs (when SVG output enabled and not markdown-only).</li>
 * </ol>
 *
 * Output formats:
 * <ul>
 *   <li>{@code svg} - generates SVG files and index.html</li>
 *   <li>{@code png} - generates PNG files only</li>
 *   <li>{@code both} - generates SVG + PNG files and index.html</li>
 *   <li>{@code markdown} - generates SVG files and README.md (GitHub-compatible)</li>
 * </ul>
 *
 * The output directory defaults to {@code ./railroad-output/} if not supplied.
 * The format defaults to {@code svg} if not specified.
 */
public class RailroadMain {

    private static final String DEFAULT_OUTPUT_DIR = "railroad-output";

    private RailroadMain() {}

    /**
     * Application entry point.
     *
     * @param args  command-line arguments
     */
    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: RailroadMain <input.ubnf> [output-dir] [--format svg|png|both|markdown]");
            System.exit(1);
        }

        String inputFilePath = args[0];
        String outputDirPath = (args.length >= 2) ? args[1] : DEFAULT_OUTPUT_DIR;
        String format = "svg"; // default

        // Parse optional --format flag
        for (int i = 2; i < args.length; i++) {
            if ("--format".equals(args[i]) && i + 1 < args.length) {
                format = args[i + 1];
                if (!format.matches("svg|png|both|markdown")) {
                    System.err.println("Invalid format: " + format + ". Must be: svg, png, both, or markdown");
                    System.exit(4);
                }
                break;
            }
        }

        try {
            run(inputFilePath, outputDirPath, format);
        } catch (IOException ioException) {
            System.err.println("I/O error: " + ioException.getMessage());
            System.exit(2);
        } catch (IllegalArgumentException illegalArgumentException) {
            System.err.println("Parse error: " + illegalArgumentException.getMessage());
            System.exit(3);
        } catch (Exception exception) {
            System.err.println("Error: " + exception.getMessage());
            System.exit(5);
        }
    }

    /**
     * Runs the railroad diagram generation pipeline.
     *
     * @param inputFilePath  path to the {@code .ubnf} input file
     * @param outputDirPath  path to the output directory (created if absent)
     * @param format         output format: "svg", "png", or "both"
     * @throws IOException   if reading or writing files fails
     */
    public static void run(String inputFilePath, String outputDirPath, String format) throws IOException {
        boolean outputSvg = "svg".equals(format) || "both".equals(format);
        boolean outputPng = "png".equals(format) || "both".equals(format);
        boolean outputMarkdown = "markdown".equals(format);
        // Markdown requires SVG files to be present
        if (outputMarkdown) {
            outputSvg = true;
        }
        run(inputFilePath, outputDirPath, outputSvg, outputPng, outputMarkdown);
    }


    /**
     * Runs the railroad diagram generation pipeline with SVG, PNG, and markdown options.
     *
     * @param inputFilePath   path to the {@code .ubnf} input file
     * @param outputDirPath   path to the output directory (created if absent)
     * @param outputSvg       whether to output SVG files
     * @param outputPng       whether to output PNG files
     * @param outputMarkdown  whether to output markdown file
     * @throws IOException    if reading or writing files fails
     */
    public static void run(String inputFilePath, String outputDirPath, boolean outputSvg, boolean outputPng, boolean outputMarkdown) throws IOException {
        // Read the input file
        Path inputPath = Paths.get(inputFilePath);
        String ubnfSource = new String(Files.readAllBytes(inputPath), StandardCharsets.UTF_8);

        // Parse the UBNF source
        System.out.println("Parsing: " + inputPath.toAbsolutePath());
        UBNFFile ubnfFile = UBNFMapper.parse(ubnfSource);

        // Prepare the output directory
        Path outputPath = Paths.get(outputDirPath);
        Files.createDirectories(outputPath);

        List<String> generatedFileNames = new ArrayList<>();
        List<MarkdownEntry> markdownEntries = new ArrayList<>();
        // rule name -> inline SVG content (for index.html)
        Map<String, String> inlineSvgByRule = new LinkedHashMap<>();

        // Generate one SVG per rule in every grammar block
        for (GrammarDecl grammarDecl : ubnfFile.grammars()) {
            String grammarName = grammarDecl.name();
            System.out.println("Grammar: " + grammarName + " (" + grammarDecl.rules().size() + " rules)");

            for (RuleDecl ruleDecl : grammarDecl.rules()) {
                String ruleName = ruleDecl.name();
                String svgFileName = grammarName + "_" + ruleName + ".svg";
                Path svgPath = outputPath.resolve(svgFileName);

                RailroadDiagram diagram = UBNFToRailroad.convertRule(ruleDecl, grammarName);
                String svgContent = RailroadDiagram.renderFullDiagram(ruleName, diagram, grammarName);

                // Write SVG if requested
                if (outputSvg) {
                    Files.write(svgPath, svgContent.getBytes(StandardCharsets.UTF_8));
                    System.out.println("  Wrote: " + svgPath.toAbsolutePath());
                }

                generatedFileNames.add(svgFileName);
                inlineSvgByRule.put(ruleName, svgContent);

                // Add to markdown entries
                if (outputMarkdown) {
                    List<String> referencedRules = extractReferencedRules(ruleDecl);
                    markdownEntries.add(new MarkdownEntry(grammarName, ruleName, svgFileName, referencedRules));
                }

                // Convert to PNG if requested
                if (outputPng) {
                    try {
                        String pngFileName = grammarName + "_" + ruleName + ".png";
                        Path pngPath = outputPath.resolve(pngFileName);
                        SvgToPngConverter.convertContent(svgContent, pngPath);
                        System.out.println("  Wrote: " + pngPath.toAbsolutePath());
                    } catch (Exception pngException) {
                        System.err.println("  Warning: Failed to convert to PNG: " + pngException.getMessage());
                    }
                }
            }
        }

        // Generate index.html with inline SVGs (if SVG output enabled and not markdown-only)
        if (outputSvg && !outputMarkdown) {
            Path indexPath = outputPath.resolve("index.html");
            String indexHtml = buildIndexHtml(inputFilePath, ubnfFile, inlineSvgByRule);
            Files.write(indexPath, indexHtml.getBytes(StandardCharsets.UTF_8));
            System.out.println("Index:  " + indexPath.toAbsolutePath());
        }

        // Generate markdown file (if markdown output enabled)
        if (outputMarkdown) {
            Path mdPath = outputPath.resolve("README.md");
            String markdown = buildMarkdown(inputFilePath, ubnfFile, markdownEntries);
            Files.write(mdPath, markdown.getBytes(StandardCharsets.UTF_8));
            System.out.println("Markdown: " + mdPath.toAbsolutePath());
        }

        int svgCount = outputSvg ? generatedFileNames.size() : 0;
        int pngCount = outputPng ? generatedFileNames.size() : 0;
        System.out.println("Done. Generated " + svgCount + " SVG(s), " + pngCount + " PNG(s), " + (outputMarkdown ? "and README.md" : ""));
    }

    /**
     * Runs the railroad diagram generation pipeline with default SVG output.
     *
     * @param inputFilePath  path to the {@code .ubnf} input file
     * @param outputDirPath  path to the output directory (created if absent)
     * @throws IOException   if reading or writing files fails
     */
    public static void run(String inputFilePath, String outputDirPath) throws IOException {
        run(inputFilePath, outputDirPath, true, false, false);
    }

    /**
     * Helper record for markdown generation.
     */
    private record MarkdownEntry(String grammarName, String ruleName, String svgFileName, List<String> referencedRules) {
        MarkdownEntry(String grammarName, String ruleName, String svgFileName) {
            this(grammarName, ruleName, svgFileName, List.of());
        }
    }

    // =========================================================================
    // Rule reference extraction
    // =========================================================================

    /**
     * Extracts all NonTerminal (rule references) from a rule declaration.
     *
     * @param ruleDecl the rule to analyze
     * @return sorted list of unique rule names referenced in this rule
     */
    private static List<String> extractReferencedRules(RuleDecl ruleDecl) {
        Set<String> refs = new java.util.LinkedHashSet<>();
        collectReferences(ruleDecl.body(), refs);
        return new ArrayList<>(refs);
    }

    private static void collectReferences(RuleBody body, Set<String> refs) {
        if (body instanceof ChoiceBody choiceBody) {
            for (SequenceBody alt : choiceBody.alternatives()) {
                collectReferences(alt, refs);
            }
        } else if (body instanceof SequenceBody seqBody) {
            for (AnnotatedElement elem : seqBody.elements()) {
                collectReferences(elem.element(), refs);
            }
        }
    }

    private static void collectReferences(AtomicElement elem, Set<String> refs) {
        if (elem instanceof RuleRefElement refElem) {
            refs.add(refElem.name());
        } else if (elem instanceof OptionalElement optElem) {
            collectReferences(optElem.body(), refs);
        } else if (elem instanceof RepeatElement repElem) {
            collectReferences(repElem.body(), refs);
        } else if (elem instanceof OneOrMoreElement oneElem) {
            collectReferences(oneElem.body(), refs);
        } else if (elem instanceof BoundedRepeatElement boundedElem) {
            collectReferences(boundedElem.body(), refs);
        } else if (elem instanceof SeparatedElement sepElem) {
            collectReferences(sepElem.element(), refs);
            collectReferences(sepElem.separator(), refs);
        } else if (elem instanceof GroupElement grpElem) {
            collectReferences(grpElem.body(), refs);
        }
    }

    // =========================================================================
    // Markdown generation
    // =========================================================================

    /**
     * Builds a GitHub-compatible markdown file with SVG diagram references.
     *
     * @param inputFilePath    the original input file path (for display)
     * @param ubnfFile         the parsed UBNF AST
     * @param markdownEntries  list of grammar/rule/filename entries
     * @return                 the complete markdown string
     */
    static String buildMarkdown(
            String inputFilePath,
            UBNFFile ubnfFile,
            List<MarkdownEntry> markdownEntries) {

        StringBuilder md = new StringBuilder();
        md.append("# Railroad Diagrams\n\n");
        md.append("Auto-generated from: `").append(escapeHtml(inputFilePath)).append("`\n\n");

        // Table of Contents
        md.append("## Table of Contents\n\n");
        for (GrammarDecl grammarDecl : ubnfFile.grammars()) {
            md.append("### ").append(escapeHtml(grammarDecl.name())).append("\n\n");
            for (RuleDecl ruleDecl : grammarDecl.rules()) {
                String ruleName = ruleDecl.name();
                md.append("- [").append(escapeHtml(ruleName)).append("](#").append(ruleName.toLowerCase())
                  .append(")\n");
            }
            md.append("\n");
        }

        // Diagrams grouped by grammar
        String currentGrammar = "";
        for (MarkdownEntry entry : markdownEntries) {
            if (!currentGrammar.equals(entry.grammarName())) {
                currentGrammar = entry.grammarName();
                md.append("## ").append(escapeHtml(currentGrammar)).append("\n\n");
            }

            md.append("### ").append(escapeHtml(entry.ruleName())).append("\n\n");
            md.append("![").append(escapeHtml(entry.ruleName())).append("](")
              .append(entry.svgFileName()).append(")\n\n");

            // Add references to other rules as clickable markdown links
            if (!entry.referencedRules().isEmpty()) {
                md.append("**References:** ");
                List<String> sortedRefs = new ArrayList<>(entry.referencedRules());
                java.util.Collections.sort(sortedRefs);
                for (int i = 0; i < sortedRefs.size(); i++) {
                    String refName = sortedRefs.get(i);
                    if (i > 0) {
                        md.append(", ");
                    }
                    md.append("[").append(escapeHtml(refName)).append("](#")
                      .append(refName.toLowerCase()).append(")");
                }
                md.append("\n\n");
            }
        }

        return md.toString();
    }

    // =========================================================================
    // Index HTML generation
    // =========================================================================

    /**
     * Builds an HTML index page with inline SVGs and a table of contents.
     * NonTerminal elements link to {@code #RuleName} anchors within the page.
     *
     * @param inputFilePath    the original input file path (for display)
     * @param ubnfFile         the parsed UBNF AST
     * @param inlineSvgByRule  map of rule name to SVG content string
     * @return                 the complete HTML string
     */
    static String buildIndexHtml(
            String inputFilePath,
            UBNFFile ubnfFile,
            Map<String, String> inlineSvgByRule) {

        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html>\n");
        html.append("<html lang=\"en\">\n");
        html.append("<head>\n");
        html.append("  <meta charset=\"UTF-8\">\n");
        html.append("  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n");
        html.append("  <title>Railroad Diagrams — ").append(escapeHtml(inputFilePath)).append("</title>\n");
        html.append("  <style>\n");
        html.append("    html { scroll-behavior: smooth; }\n");
        html.append("    body { font-family: sans-serif; margin: 0; background: #fafafa; color: #222; }\n");
        html.append("    .layout { display: flex; }\n");
        html.append("    .toc { position: sticky; top: 0; height: 100vh; width: 240px; min-width: 200px;");
        html.append(" overflow-y: auto; background: #fff; border-right: 1px solid #ddd;");
        html.append(" padding: 16px; font-size: 13px; }\n");
        html.append("    .toc h2 { font-size: 14px; color: #555; margin: 16px 0 4px 0; }\n");
        html.append("    .toc a { display: block; padding: 2px 0; color: #1565C0; text-decoration: none;");
        html.append(" font-family: monospace; }\n");
        html.append("    .toc a:hover { color: #0D47A1; text-decoration: underline; }\n");
        html.append("    .toc a.active { font-weight: bold; color: #0D47A1; }\n");
        html.append("    .content { flex: 1; padding: 24px; overflow-x: auto; }\n");
        html.append("    h1 { font-size: 20px; color: #333; }\n");
        html.append("    .rule-section { margin-bottom: 24px; border: 1px solid #ddd;");
        html.append(" border-radius: 6px; padding: 12px; background: white; scroll-margin-top: 16px; }\n");
        html.append("    .rule-section:target { border-color: #2196F3; box-shadow: 0 0 8px rgba(33,150,243,0.3); }\n");
        html.append("    .rule-header { font-family: monospace; font-weight: bold; color: #1565C0;");
        html.append(" font-size: 15px; margin-bottom: 8px; }\n");
        html.append("    .rule-header a { color: inherit; text-decoration: none; }\n");
        html.append("    .rule-header a:hover { text-decoration: underline; }\n");
        html.append("    svg { max-width: 100%; height: auto; }\n");
        html.append("  </style>\n");
        html.append("</head>\n");
        html.append("<body>\n");
        html.append("<div class=\"layout\">\n");

        // --- Table of Contents (sidebar) ---
        html.append("<nav class=\"toc\">\n");
        html.append("  <h1>Railroad Diagrams</h1>\n");
        html.append("  <p style=\"font-size:12px;color:#888;\">").append(escapeHtml(inputFilePath)).append("</p>\n");
        for (GrammarDecl grammarDecl : ubnfFile.grammars()) {
            html.append("  <h2>").append(escapeHtml(grammarDecl.name())).append("</h2>\n");
            for (RuleDecl ruleDecl : grammarDecl.rules()) {
                String ruleName = ruleDecl.name();
                html.append("  <a href=\"#").append(escapeHtml(ruleName)).append("\">")
                    .append(escapeHtml(ruleName)).append("</a>\n");
            }
        }
        html.append("</nav>\n");

        // --- Main content ---
        html.append("<main class=\"content\">\n");
        html.append("<h1>Railroad Diagrams</h1>\n");

        for (GrammarDecl grammarDecl : ubnfFile.grammars()) {
            html.append("<h2>Grammar: <code>").append(escapeHtml(grammarDecl.name())).append("</code></h2>\n");

            for (RuleDecl ruleDecl : grammarDecl.rules()) {
                String ruleName = ruleDecl.name();
                String svgContent = inlineSvgByRule.get(ruleName);

                html.append("<div class=\"rule-section\" id=\"").append(escapeHtml(ruleName)).append("\">\n");
                html.append("  <div class=\"rule-header\"><a href=\"#")
                    .append(escapeHtml(ruleName)).append("\">").append(escapeHtml(ruleName)).append("</a></div>\n");

                if (svgContent != null) {
                    // Inline the SVG directly (strip the <?xml?> prolog if present)
                    html.append("  ").append(svgContent).append("\n");
                }

                html.append("</div>\n");
            }
        }

        html.append("</main>\n");
        html.append("</div>\n");
        html.append("</body>\n");
        html.append("</html>\n");
        return html.toString();
    }

    /**
     * Escapes special HTML characters in {@code text}.
     */
    static String escapeHtml(String text) {
        String escaped = text;
        escaped = escaped.replace("&", "&amp;");
        escaped = escaped.replace("<", "&lt;");
        escaped = escaped.replace(">", "&gt;");
        escaped = escaped.replace("\"", "&quot;");
        return escaped;
    }
}
