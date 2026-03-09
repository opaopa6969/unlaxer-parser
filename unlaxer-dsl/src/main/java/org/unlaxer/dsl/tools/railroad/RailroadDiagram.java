package org.unlaxer.dsl.tools.railroad;

import java.util.ArrayList;
import java.util.List;

/**
 * Railroad diagram model and SVG renderer.
 */
public sealed interface RailroadDiagram permits
    RailroadDiagram.Terminal,
    RailroadDiagram.NonTerminal,
    RailroadDiagram.Sequence,
    RailroadDiagram.Choice,
    RailroadDiagram.Optional,
    RailroadDiagram.Repeat,
    RailroadDiagram.Group {

    int width();
    int height();
    int entryY();
    void renderSvg(StringBuilder builder, int offsetX, int offsetY);
    void renderRtlSvg(StringBuilder builder, int offsetX, int offsetY);

    // Styling constants
    int FONT_SIZE = 12;
    int BOX_PADDING_H = 10;
    int BOX_PADDING_V = 8;
    int MIN_BOX_WIDTH = 30;
    int SEQUENCE_GAP = 20;
    int CHOICE_GAP = 10;
    int CURVE_RADIUS = 8;
    int BYPASS_MARGIN = 10;
    int MARKER_SPACE = 20;
    int ENTRY_CIRCLE_RADIUS = 4;
    int END_CIRCLE_OUTER_RADIUS = 6;
    int END_CIRCLE_INNER_RADIUS = 3;

    // =========================================================================
    // SVG Helpers
    // =========================================================================

    static void appendHLine(StringBuilder b, int x1, int y, int x2) {
        b.append("<line x1=\"").append(x1).append("\" y1=\"").append(y)
         .append("\" x2=\"").append(x2).append("\" y2=\"").append(y)
         .append("\" class=\"rail\"/>\n");
    }

    static void appendArrow(StringBuilder b, int x, int y) {
        b.append("<polygon points=\"").append(x).append(",").append(y - 3)
         .append(" ").append(x + 6).append(",").append(y)
         .append(" ").append(x).append(",").append(y + 3)
         .append("\" class=\"arrow\"/>\n");
    }

    static void appendLeftArrow(StringBuilder b, int x, int y) {
        b.append("<polygon points=\"").append(x + 6).append(",").append(y - 3)
         .append(" ").append(x).append(",").append(y)
         .append(" ").append(x + 6).append(",").append(y + 3)
         .append("\" class=\"arrow\"/>\n");
    }

    static void appendCurveHtoV(StringBuilder b, int x1, int y1, int x2, int y2) {
        b.append("<path d=\"M").append(x1).append(",").append(y1)
         .append(" Q").append(x2).append(",").append(y1)
         .append(" ").append(x2).append(",").append(y2)
         .append("\" class=\"rail\" fill=\"none\"/>\n");
    }

    static void appendCurveVtoH(StringBuilder b, int x1, int y1, int x2, int y2) {
        b.append("<path d=\"M").append(x1).append(",").append(y1)
         .append(" Q").append(x1).append(",").append(y2)
         .append(" ").append(x2).append(",").append(y2)
         .append("\" class=\"rail\" fill=\"none\"/>\n");
    }

    static String escapeSvg(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    static int estimateTextWidth(String text) {
        return text.length() * 7;
    }

    static String buildCss() {
        return "<style>\n"
            + "  .rail { stroke: #333; stroke-width: 2; fill: none; }\n"
            + "  .arrow { stroke: #333; stroke-width: 2; fill: none; }\n"
            + "  .terminal-box { fill: #e8f5e9; stroke: #2e7d32; stroke-width: 2; }\n"
            + "  .terminal-text { font-family: monospace; font-size: 12px; fill: #1b5e20; }\n"
            + "  .nonterminal-box { fill: #e3f2fd; stroke: #1565c0; stroke-width: 2; }\n"
            + "  .nonterminal-text { font-family: sans-serif; font-size: 12px; fill: #0d47a1; }\n"
            + "  .start-circle { fill: #333; stroke: none; }\n"
            + "  .end-circle-outer { fill: none; stroke: #333; stroke-width: 2; }\n"
            + "  .end-circle-inner { fill: #333; stroke: none; }\n"
            + "  rect, g { transition: all 0.3s; }\n"
            + "</style>\n";
    }

    static String renderFullDiagram(String ruleName, RailroadDiagram element, String grammarName) {
        int padding = 20; int labelHeight = 24;
        int innerWidth = MARKER_SPACE + element.width() + MARKER_SPACE;
        int innerHeight = element.height(); int railY = element.entryY();
        int svgWidth = innerWidth + padding * 2; int svgHeight = innerHeight + padding * 2 + labelHeight;
        StringBuilder svg = new StringBuilder();
        svg.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"").append(svgWidth).append("\" height=\"").append(svgHeight).append("\">\n");
        svg.append(buildCss());
        svg.append("<text x=\"").append(padding).append("\" y=\"").append(padding + labelHeight - 6).append("\" font-size=\"13\" fill=\"#555\" font-family=\"sans-serif\">").append(escapeSvg(ruleName)).append("</text>\n");
        int originY = padding + labelHeight; int entryAbsY = originY + railY;
        int startX = padding + ENTRY_CIRCLE_RADIUS;
        svg.append("<circle cx=\"").append(startX).append("\" cy=\"").append(entryAbsY).append("\" r=\"").append(ENTRY_CIRCLE_RADIUS).append("\" class=\"start-circle\"/>\n");
        int elX = padding + MARKER_SPACE;
        appendHLine(svg, startX + ENTRY_CIRCLE_RADIUS, entryAbsY, elX);
        element.renderSvg(svg, elX, originY);
        int endX = elX + element.width();
        int markerX = endX + MARKER_SPACE - END_CIRCLE_OUTER_RADIUS;
        appendHLine(svg, endX, entryAbsY, markerX - END_CIRCLE_OUTER_RADIUS);
        svg.append("<circle cx=\"").append(markerX).append("\" cy=\"").append(entryAbsY).append("\" r=\"").append(END_CIRCLE_OUTER_RADIUS).append("\" class=\"end-circle-outer\"/>\n");
        svg.append("<circle cx=\"").append(markerX).append("\" cy=\"").append(entryAbsY).append("\" r=\"").append(END_CIRCLE_INNER_RADIUS).append("\" class=\"end-circle-inner\"/>\n");
        svg.append("</svg>\n");
        return svg.toString();
    }

    static String renderFullDiagram(String ruleName, RailroadDiagram element) { return renderFullDiagram(ruleName, element, ""); }

    // =========================================================================
    // Element implementations
    // =========================================================================

    record Terminal(String id, String label) implements RailroadDiagram {
        @Override public int width() { return Math.max(MIN_BOX_WIDTH, estimateTextWidth(label) + BOX_PADDING_H * 2); }
        @Override public int height() { return FONT_SIZE + BOX_PADDING_V * 2; }
        @Override public int entryY() { return height() / 2; }
        @Override public void renderSvg(StringBuilder b, int ox, int oy) {
            b.append("<g id=\"").append(id).append("\">\n")
             .append("<rect x=\"").append(ox).append("\" y=\"").append(oy).append("\" width=\"").append(width()).append("\" height=\"").append(height()).append("\" rx=\"10\" ry=\"10\" class=\"terminal-box\"/>\n")
             .append("<text x=\"").append(ox + width()/2).append("\" y=\"").append(oy + BOX_PADDING_V + FONT_SIZE - 2).append("\" text-anchor=\"middle\" class=\"terminal-text\">").append(escapeSvg(label)).append("</text>\n")
             .append("</g>\n");
        }
        @Override public void renderRtlSvg(StringBuilder b, int ox, int oy) { renderSvg(b, ox, oy); }
    }

    record NonTerminal(String id, String label, String grammarName) implements RailroadDiagram {
        NonTerminal(String id, String label) { this(id, label, ""); }
        @Override public int width() { return Math.max(MIN_BOX_WIDTH, estimateTextWidth(label) + BOX_PADDING_H * 2); }
        @Override public int height() { return FONT_SIZE + BOX_PADDING_V * 2; }
        @Override public int entryY() { return height() / 2; }
        @Override public void renderSvg(StringBuilder b, int ox, int oy) {
            b.append("<g id=\"").append(id).append("\">\n")
             .append("<rect x=\"").append(ox).append("\" y=\"").append(oy).append("\" width=\"").append(width()).append("\" height=\"").append(height()).append("\" class=\"nonterminal-box\"/>\n")
             .append("<text x=\"").append(ox + width()/2).append("\" y=\"").append(oy + BOX_PADDING_V + FONT_SIZE - 2).append("\" text-anchor=\"middle\" class=\"nonterminal-text\">").append(escapeSvg(label)).append("</text>\n")
             .append("</g>\n");
        }
        @Override public void renderRtlSvg(StringBuilder b, int ox, int oy) { renderSvg(b, ox, oy); }
    }

    record Sequence(String id, List<RailroadDiagram> elements) implements RailroadDiagram {
        @Override public int width() {
            int w = 0; for (RailroadDiagram e : elements) w += e.width();
            if (!elements.isEmpty()) w += SEQUENCE_GAP * (elements.size() - 1);
            return w;
        }
        @Override public int height() { int h = 0; for (RailroadDiagram e : elements) h = Math.max(h, e.height()); return h; }
        @Override public int entryY() { int y = 0; for (RailroadDiagram e : elements) y = Math.max(y, e.entryY()); return y; }
        @Override public void renderSvg(StringBuilder b, int ox, int oy) {
            b.append("<g id=\"").append(id).append("\">\n");
            int railY = entryY(); int curX = ox;
            for (int i = 0; i < elements.size(); i++) {
                RailroadDiagram e = elements.get(i);
                e.renderSvg(b, curX, oy + railY - e.entryY());
                curX += e.width();
                if (i < elements.size() - 1) {
                    appendHLine(b, curX, oy + railY, curX + SEQUENCE_GAP);
                    appendArrow(b, curX + SEQUENCE_GAP / 2 - 3, oy + railY);
                    curX += SEQUENCE_GAP;
                }
            }
            b.append("</g>\n");
        }
        @Override public void renderRtlSvg(StringBuilder b, int ox, int oy) {
            b.append("<g id=\"").append(id).append("\">\n");
            int railY = entryY(); int curX = ox;
            for (int i = 0; i < elements.size(); i++) {
                RailroadDiagram e = elements.get(i);
                int elementX = ox + width() - (curX - ox) - e.width();
                e.renderRtlSvg(b, elementX, oy + railY - e.entryY());
                if (i < elements.size() - 1) {
                    int lineEndX = elementX;
                    int lineStartX = elementX - SEQUENCE_GAP;
                    appendHLine(b, lineStartX, oy + railY, lineEndX);
                    appendLeftArrow(b, lineStartX + SEQUENCE_GAP / 2 - 3, oy + railY);
                }
                curX += e.width() + SEQUENCE_GAP;
            }
            b.append("</g>\n");
        }
    }

    record Choice(String id, List<RailroadDiagram> alternatives) implements RailroadDiagram {
        @Override public int width() { int w = 0; for (RailroadDiagram a : alternatives) w = Math.max(w, a.width()); return w + CURVE_RADIUS * 4; }
        @Override public int height() { int h = 0; for (RailroadDiagram a : alternatives) h += a.height(); if (!alternatives.isEmpty()) h += CHOICE_GAP * (alternatives.size() - 1); return h; }
        @Override public int entryY() { return alternatives.isEmpty() ? 0 : alternatives.get(0).entryY(); }
        @Override public void renderSvg(StringBuilder b, int ox, int oy) {
            if (alternatives.isEmpty()) return;
            b.append("<g id=\"").append(id).append("\">\n");
            int innerLeftX = ox + CURVE_RADIUS * 2; int mainRailY = oy + entryY();
            int curY = oy;
            for (int i = 0; i < alternatives.size(); i++) {
                RailroadDiagram a = alternatives.get(i);
                int altRailY = curY + a.entryY();
                int elX = innerLeftX + (width() - CURVE_RADIUS * 4 - a.width()) / 2;
                a.renderSvg(b, elX, curY);
                appendHLine(b, innerLeftX, altRailY, elX);
                appendHLine(b, elX + a.width(), altRailY, ox + width() - CURVE_RADIUS * 2);
                if (i == 0) {
                    appendHLine(b, ox, mainRailY, innerLeftX);
                    appendHLine(b, ox + width() - CURVE_RADIUS * 2, mainRailY, ox + width());
                } else {
                    appendCurveHtoV(b, ox, mainRailY, ox + CURVE_RADIUS, mainRailY + CURVE_RADIUS);
                    appendCurveVtoH(b, ox + CURVE_RADIUS, altRailY - CURVE_RADIUS, innerLeftX, altRailY);
                    appendCurveHtoV(b, ox + width() - CURVE_RADIUS * 2, altRailY, ox + width() - CURVE_RADIUS, altRailY - CURVE_RADIUS);
                    appendCurveVtoH(b, ox + width() - CURVE_RADIUS, mainRailY + CURVE_RADIUS, ox + width(), mainRailY);
                }
                curY += a.height() + CHOICE_GAP;
            }
            b.append("</g>\n");
        }
        @Override public void renderRtlSvg(StringBuilder b, int ox, int oy) {
            if (alternatives.isEmpty()) return;
            b.append("<g id=\"").append(id).append("\">\n");
            int mainRailY = oy + entryY();
            int curY = oy;
            for (int i = 0; i < alternatives.size(); i++) {
                RailroadDiagram a = alternatives.get(i);
                int altRailY = curY + a.entryY();
                int elX = ox + CURVE_RADIUS * 2 + (width() - CURVE_RADIUS * 4 - a.width()) / 2;
                a.renderRtlSvg(b, elX, curY);
                appendHLine(b, ox + CURVE_RADIUS * 2, altRailY, elX);
                appendHLine(b, elX + a.width(), altRailY, ox + width() - CURVE_RADIUS * 2);
                if (i == 0) {
                    appendHLine(b, ox, mainRailY, ox + CURVE_RADIUS * 2);
                    appendHLine(b, ox + width() - CURVE_RADIUS * 2, mainRailY, ox + width());
                } else {
                    appendCurveHtoV(b, ox + width(), mainRailY, ox + width() - CURVE_RADIUS, mainRailY + CURVE_RADIUS);
                    appendCurveVtoH(b, ox + width() - CURVE_RADIUS, altRailY - CURVE_RADIUS, ox + width() - CURVE_RADIUS * 2, altRailY);
                    appendCurveHtoV(b, ox + CURVE_RADIUS * 2, altRailY, ox + CURVE_RADIUS, altRailY - CURVE_RADIUS);
                    appendCurveVtoH(b, ox + CURVE_RADIUS, mainRailY + CURVE_RADIUS, ox, mainRailY);
                }
                curY += a.height() + CHOICE_GAP;
            }
            b.append("</g>\n");
        }
    }

    record Optional(String id, RailroadDiagram inner) implements RailroadDiagram {
        @Override public int width() { return inner.width() + CURVE_RADIUS * 4; }
        @Override public int height() { return inner.height() + BYPASS_MARGIN + CURVE_RADIUS; }
        @Override public int entryY() { return BYPASS_MARGIN + CURVE_RADIUS + inner.entryY(); }
        @Override public void renderSvg(StringBuilder b, int ox, int oy) {
            b.append("<g id=\"").append(id).append("\">\n");
            int innerX = ox + CURVE_RADIUS * 2; int innerY = oy + BYPASS_MARGIN + CURVE_RADIUS;
            inner.renderSvg(b, innerX, innerY);
            int railY = oy + entryY(); int bypassY = oy + CURVE_RADIUS;
            appendHLine(b, ox, railY, innerX); appendHLine(b, innerX + inner.width(), railY, ox + width());
            appendCurveHtoV(b, ox, railY, ox + CURVE_RADIUS, railY - CURVE_RADIUS);
            appendCurveVtoH(b, ox + CURVE_RADIUS, bypassY + CURVE_RADIUS, ox + CURVE_RADIUS * 2, bypassY);
            appendHLine(b, ox + CURVE_RADIUS * 2, bypassY, ox + width() - CURVE_RADIUS * 2);
            appendCurveHtoV(b, ox + width() - CURVE_RADIUS * 2, bypassY, ox + width() - CURVE_RADIUS, bypassY + CURVE_RADIUS);
            appendCurveVtoH(b, ox + width() - CURVE_RADIUS, railY - CURVE_RADIUS, ox + width(), railY);
            b.append("</g>\n");
        }
        @Override public void renderRtlSvg(StringBuilder b, int ox, int oy) {
            b.append("<g id=\"").append(id).append("\">\n");
            int innerX = ox + CURVE_RADIUS * 2; int innerY = oy + BYPASS_MARGIN + CURVE_RADIUS;
            inner.renderRtlSvg(b, innerX, innerY);
            int railY = oy + entryY(); int bypassY = oy + CURVE_RADIUS;
            appendHLine(b, ox, railY, innerX); appendHLine(b, innerX + inner.width(), railY, ox + width());
            appendCurveHtoV(b, ox + width(), railY, ox + width() - CURVE_RADIUS, railY - CURVE_RADIUS);
            appendCurveVtoH(b, ox + width() - CURVE_RADIUS, bypassY + CURVE_RADIUS, ox + width() - CURVE_RADIUS * 2, bypassY);
            appendHLine(b, ox + CURVE_RADIUS * 2, bypassY, ox + width() - CURVE_RADIUS * 2);
            appendCurveHtoV(b, ox + CURVE_RADIUS * 2, bypassY, ox + CURVE_RADIUS, bypassY + CURVE_RADIUS);
            appendCurveVtoH(b, ox + CURVE_RADIUS, railY - CURVE_RADIUS, ox, railY);
            b.append("</g>\n");
        }
    }

    record Repeat(String id, RailroadDiagram inner) implements RailroadDiagram {
        @Override public int width() { return inner.width() + CURVE_RADIUS * 4; }
        @Override public int height() { return inner.height() + BYPASS_MARGIN * 2 + CURVE_RADIUS; }
        @Override public int entryY() { return CURVE_RADIUS; }
        @Override public void renderSvg(StringBuilder b, int ox, int oy) {
            b.append("<g id=\"").append(id).append("\">\n");
            int innerX = ox + CURVE_RADIUS * 2;
            int innerY = oy + BYPASS_MARGIN + CURVE_RADIUS;
            inner.renderSvg(b, innerX, innerY);
            int railY = oy + entryY();
            int innerRailY = innerY + inner.entryY();
            int loopY = innerY + inner.height() + BYPASS_MARGIN;

            // zero-times bypass line
            appendHLine(b, ox, railY, ox + width());

            // branch from bypass to inner path (left side)
            appendCurveHtoV(b, ox + CURVE_RADIUS * 2, railY, ox + CURVE_RADIUS, railY + CURVE_RADIUS);
            appendCurveVtoH(b, ox + CURVE_RADIUS, innerRailY - CURVE_RADIUS, ox, innerRailY);
            appendHLine(b, ox, innerRailY, innerX);

            // merge from inner path back to bypass (right side)
            appendHLine(b, innerX + inner.width(), innerRailY, ox + width());
            appendCurveHtoV(b, ox + width(), innerRailY, ox + width() - CURVE_RADIUS, innerRailY - CURVE_RADIUS);
            appendCurveVtoH(b, ox + width() - CURVE_RADIUS, railY + CURVE_RADIUS, ox + width() - CURVE_RADIUS * 2, railY);

            // loop-back for additional iterations
            appendCurveHtoV(b, ox + width() - CURVE_RADIUS, innerRailY, ox + width(), innerRailY + CURVE_RADIUS);
            appendCurveVtoH(b, ox + width(), loopY - CURVE_RADIUS, ox + width() - CURVE_RADIUS, loopY);
            appendHLine(b, ox + CURVE_RADIUS, loopY, ox + width() - CURVE_RADIUS);
            appendLeftArrow(b, ox + width()/2, loopY);
            appendCurveHtoV(b, ox + CURVE_RADIUS, loopY, ox, loopY - CURVE_RADIUS);
            appendCurveVtoH(b, ox, innerRailY + CURVE_RADIUS, ox + CURVE_RADIUS, innerRailY);
            b.append("</g>\n");
        }
        @Override public void renderRtlSvg(StringBuilder b, int ox, int oy) {
            b.append("<g id=\"").append(id).append("\">\n");
            int innerX = ox + CURVE_RADIUS * 2;
            int innerY = oy + BYPASS_MARGIN + CURVE_RADIUS;
            inner.renderRtlSvg(b, innerX, innerY);
            int railY = oy + entryY();
            int innerRailY = innerY + inner.entryY();
            int loopY = innerY + inner.height() + BYPASS_MARGIN;

            // zero-times bypass line
            appendHLine(b, ox, railY, ox + width());

            // branch from bypass to inner path (right side in RTL)
            appendCurveHtoV(b, ox + width() - CURVE_RADIUS * 2, railY, ox + width() - CURVE_RADIUS, railY + CURVE_RADIUS);
            appendCurveVtoH(b, ox + width() - CURVE_RADIUS, innerRailY - CURVE_RADIUS, ox + width(), innerRailY);
            appendHLine(b, innerX + inner.width(), innerRailY, ox + width());

            // merge from inner path back to bypass (left side)
            appendHLine(b, ox, innerRailY, innerX);
            appendCurveHtoV(b, ox, innerRailY, ox + CURVE_RADIUS, innerRailY - CURVE_RADIUS);
            appendCurveVtoH(b, ox + CURVE_RADIUS, railY + CURVE_RADIUS, ox + CURVE_RADIUS * 2, railY);

            // loop-back for additional iterations
            appendCurveHtoV(b, ox + CURVE_RADIUS, innerRailY, ox, innerRailY + CURVE_RADIUS);
            appendCurveVtoH(b, ox, loopY - CURVE_RADIUS, ox + CURVE_RADIUS, loopY);
            appendHLine(b, ox + CURVE_RADIUS, loopY, ox + width() - CURVE_RADIUS);
            appendLeftArrow(b, ox + width()/2, loopY);
            appendCurveHtoV(b, ox + width() - CURVE_RADIUS, loopY, ox + width(), loopY - CURVE_RADIUS);
            appendCurveVtoH(b, ox + width(), innerRailY + CURVE_RADIUS, ox + width() - CURVE_RADIUS, innerRailY);
            b.append("</g>\n");
        }
    }

    record Group(String id, RailroadDiagram inner) implements RailroadDiagram {
        @Override public int width() { return inner.width(); }
        @Override public int height() { return inner.height(); }
        @Override public int entryY() { return inner.entryY(); }
        @Override public void renderSvg(StringBuilder b, int ox, int oy) {
            inner.renderSvg(b, ox, oy);
        }
        @Override public void renderRtlSvg(StringBuilder b, int ox, int oy) {
            inner.renderRtlSvg(b, ox, oy);
        }
    }
}
