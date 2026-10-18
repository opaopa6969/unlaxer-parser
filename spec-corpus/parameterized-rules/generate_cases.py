#!/usr/bin/env python3
"""Writes spec-corpus/parameterized-rules/cases.json (usage: python3 -I generate_cases.py cases.json).

Expected values are written by hand. Instance names are computed here from hand-written
canonical keys with an independent FNV-1a-64 implementation; error spans are located by
substring search in the grammar text. Nothing is read back from the implementations.
"""
import json, sys

def fnv(key):
    h = 0xcbf29ce484222325
    for b in key.encode("utf-8"):
        h ^= b
        h = (h * 0x100000001b3) & 0xFFFFFFFFFFFFFFFF
    return "%016x" % h

def inst(key):
    return key[: key.index("(")] + "_" + fnv(key)

def t(text):
    return "t(" + ".".join("%x" % ord(c) for c in text) + ")"

def r(name):
    return "r(" + name + ")"

def line_col(source, cp):
    line, col, cr = 1, 1, False
    for c in source[:cp]:
        if c == "\r":
            line += 1; col = 1
        elif c == "\n" and cr:
            pass
        elif c == "\n":
            line += 1; col = 1
        else:
            col += 1
        cr = c == "\r"
    return line, col

def at(source, needle, occurrence=0, length=None):
    start = -1
    for _ in range(occurrence + 1):
        start = source.index(needle, start + 1)
    end = start + (len(needle) if length is None else length)
    return start, end

def node(type_, span, **fields):
    return {"type": type_, "span": list(span), "fields": fields}

HEAD = "grammar Param {\n  @package: paramprobe\n  @whitespace: none\n"
cases = []

def fill(template, keys):
    for n, k in enumerate(keys):
        template = template.replace("{i%d}" % n, inst(k))
    return template

def accept(name, description, grammar, keys, expanded, spans, inputs):
    names = {k: inst(k) for k in keys}
    exp = fill(expanded, keys)
    cases.append({
        "name": name,
        "description": description,
        "grammar": grammar,
        "status": "accept",
        "instances": [{"key": k, "name": names[k]} for k in keys],
        "expanded": exp,
        "spans": [{"rule": names[keys[n]] if isinstance(n, int) else n, "span": list(at(grammar, s))} for n, s in spans],
        "inputs": inputs,
    })

# 1. Token / keyword helpers, shared spacing, a call inside a body, code points.
g1 = HEAD + """  @root @mapping(Root, params=[name, values])
  Root ::= Kw('let') Ident @name Token('=') Num @values { Token(',') Num @values } ( Token(';') | Token('😀') ) ;
  @mapping(Ident, params=[text])
  Ident ::= Letters @text Spacing ;
  @mapping(Num, params=[text])
  Num ::= Digits @text Spacing ;
  Letters ::= Letter { Letter } ;
  Letter ::= 'a' | 'b' | 'x' | 'y' ;
  Digits ::= Digit { Digit } ;
  Digit ::= '0' | '1' | '2' ;
  Spacing ::= { ' ' } ;
  Token(p) ::= p Spacing ;
  Kw(n) ::= Token(n) ;
}
"""
k1 = ["Kw(" + t("let") + ")", "Token(" + t("let") + ")", "Token(" + t("=") + ")", "Token(" + t(",") + ")",
      "Token(" + t(";") + ")", "Token(" + t("😀") + ")"]
e1 = HEAD + """  @root @mapping(Root, params=[name, values])
  Root ::= {i0} Ident @name {i2} Num @values { {i3} Num @values } ( {i4} | {i5} ) ;
  @mapping(Ident, params=[text])
  Ident ::= Letters @text Spacing ;
  @mapping(Num, params=[text])
  Num ::= Digits @text Spacing ;
  Letters ::= Letter { Letter } ;
  Letter ::= 'a' | 'b' | 'x' | 'y' ;
  Digits ::= Digit { Digit } ;
  Digit ::= '0' | '1' | '2' ;
  Spacing ::= { ' ' } ;
  {i0} ::= {i1} ;
  {i1} ::= 'let' Spacing ;
  {i2} ::= '=' Spacing ;
  {i3} ::= ',' Spacing ;
  {i4} ::= ';' Spacing ;
  {i5} ::= '😀' Spacing ;
}
"""
accept("token-keyword", "Token/Kw helpers; one instance per distinct argument; call nested in a body",
    g1, k1, e1,
    [(0, "Kw(n) ::= Token(n) ;"), (1, "Token(p) ::= p Spacing ;"), (5, "Token(p) ::= p Spacing ;")],
    [
        {"input": "let x = 1, 2;", "expected": {"status": "ok", "value": node("Root", (0, 13),
            name=node("Ident", (4, 6), text="x"),
            values=[node("Num", (8, 9), text="1"), node("Num", (11, 12), text="2")])}},
        {"input": "letx=1;", "expected": {"status": "ok", "value": node("Root", (0, 7),
            name=node("Ident", (3, 4), text="x"), values=[node("Num", (5, 6), text="1")])}},
        {"input": "let y = 2 😀", "expected": {"status": "ok", "value": node("Root", (0, 11),
            name=node("Ident", (4, 6), text="y"), values=[node("Num", (8, 10), text="2")])}},
        {"input": "let = 1;", "expected": {"status": "syntax", "offset": 4}},
    ])

# 2. Multi-element and choice arguments, postfix on calls, bounded repeat, separator.
g2 = HEAD + """  @root @mapping(Root, params=[items])
  Root ::= Item @items { Item @items } Tail ;
  @mapping(Item, params=[text])
  Item ::= Either('a' 'b', 'c') @text ;
  Tail ::= Twice('!' | '?')* Opt('.') List('x', ',') ;
  Either(a, b) ::= a | b ;
  Twice(x) ::= x{2} ;
  Opt(x) ::= [ x ] ;
  List(x, s) ::= x % s ;
}
"""
k2 = ["Either(" + t("a") + " " + t("b") + "," + t("c") + ")", "Twice(" + t("!") + "|" + t("?") + ")",
      "Opt(" + t(".") + ")", "List(" + t("x") + "," + t(",") + ")"]
e2 = HEAD + """  @root @mapping(Root, params=[items])
  Root ::= Item @items { Item @items } Tail ;
  @mapping(Item, params=[text])
  Item ::= {i0} @text ;
  Tail ::= {i1}* {i2} {i3} ;
  {i0} ::= ( 'a' 'b' ) | 'c' ;
  {i1} ::= ( '!' | '?' ){2} ;
  {i2} ::= [ '.' ] ;
  {i3} ::= 'x' % ',' ;
}
"""
accept("arguments-and-postfix", "grouped and choice arguments, postfix on a call, bounded repeat and separator",
    g2, k2, e2,
    [(0, "Either(a, b) ::= a | b ;"), (3, "List(x, s) ::= x % s ;")],
    [
        {"input": "abc!!?!.x,x", "expected": {"status": "ok", "value": node("Root", (0, 11),
            items=[node("Item", (0, 2), text="ab"), node("Item", (2, 3), text="c")])}},
        {"input": "cx", "expected": {"status": "ok", "value": node("Root", (0, 2),
            items=[node("Item", (0, 1), text="c")])}},
        {"input": "ab!x", "expected": {"status": "syntax", "offset": 3}},
    ])

# 3. Calls in arguments and parameters passed through (pre-order instance numbering).
g3 = HEAD + """  @root @mapping(Root, params=[text])
  Root ::= Outer('a') @text ;
  Outer(x) ::= Inner(Inner(x)) ;
  Inner(y) ::= '<' y '>' ;
}
"""
inner_a = inst("Inner(" + t("a") + ")")
k3 = ["Outer(" + t("a") + ")", "Inner(" + t("a") + ")", "Inner(" + r(inner_a) + ")"]
e3 = HEAD + """  @root @mapping(Root, params=[text])
  Root ::= {i0} @text ;
  {i0} ::= {i2} ;
  {i1} ::= '<' 'a' '>' ;
  {i2} ::= '<' {i1} '>' ;
}
"""
accept("nested-calls", "a call as an argument; the outer instance takes its slot before its callees",
    g3, k3, e3,
    [(0, "Outer(x) ::= Inner(Inner(x)) ;"), (2, "Inner(y) ::= '<' y '>' ;")],
    [
        {"input": "<<a>>", "expected": {"status": "ok", "value": node("Root", (0, 5), text="<<a>>")}},
        {"input": "<a>", "expected": {"status": "syntax", "offset": 1}},
    ])

# 4. Recursion through an ordinary rule passed as an argument (not a cycle).
g4 = HEAD + """  @root @mapping(Root, params=[text])
  Root ::= Expr @text ;
  Expr ::= Paren(Expr) | 'n' ;
  Paren(x) ::= '(' x ')' ;
}
"""
k4 = ["Paren(" + r("Expr") + ")"]
e4 = HEAD + """  @root @mapping(Root, params=[text])
  Root ::= Expr @text ;
  Expr ::= {i0} | 'n' ;
  {i0} ::= '(' Expr ')' ;
}
"""
accept("recursion-through-argument", "zero-arity recursion via an argument is not a parameterized cycle",
    g4, k4, e4, [(0, "Paren(x) ::= '(' x ')' ;")],
    [
        {"input": "((n))", "expected": {"status": "ok", "value": node("Root", (0, 5), text="((n))")}},
        {"input": "n", "expected": {"status": "ok", "value": node("Root", (0, 1), text="n")}},
        {"input": "((n)", "expected": {"status": "syntax", "offset": 4}},
    ])

# 5. Equal arguments share one instance, including across nesting levels.
g5 = HEAD + """  @root @mapping(Root, params=[text])
  Root ::= Pair(Token('a'), Token('a')) @text Token('a') ;
  Gap ::= [ ' ' ] ;
  Token(p) ::= p Gap ;
  Pair(l, r) ::= l r ;
}
"""
tok_a = inst("Token(" + t("a") + ")")
k5 = ["Token(" + t("a") + ")", "Pair(" + r(tok_a) + "," + r(tok_a) + ")"]
e5 = HEAD + """  @root @mapping(Root, params=[text])
  Root ::= {i1} @text {i0} ;
  Gap ::= [ ' ' ] ;
  {i0} ::= 'a' Gap ;
  {i1} ::= {i0} {i0} ;
}
"""
accept("shared-instances", "three Token('a') calls share one instance",
    g5, k5, e5, [(0, "Token(p) ::= p Gap ;"), (1, "Pair(l, r) ::= l r ;")],
    [
        {"input": "a a a", "expected": {"status": "ok", "value": node("Root", (0, 5), text="a a")}},
        {"input": "aaa", "expected": {"status": "ok", "value": node("Root", (0, 3), text="aa")}},
        {"input": "a a", "expected": {"status": "syntax", "offset": 3}},
    ])

# 6. A nullable repetition that only exists after expansion keeps the runtime progress guard.
g6 = HEAD + """  @root @mapping(Root, params=[text])
  Root ::= Many(Opt('a')) @text 'b' ;
  Many(x) ::= { x } ;
  Opt(x) ::= [ x ] ;
}
"""
opt_a = inst("Opt(" + t("a") + ")")
k6 = ["Opt(" + t("a") + ")", "Many(" + r(opt_a) + ")"]
e6 = HEAD + """  @root @mapping(Root, params=[text])
  Root ::= {i1} @text 'b' ;
  {i0} ::= [ 'a' ] ;
  {i1} ::= { {i0} } ;
}
"""
accept("nullable-repeat-after-expansion", "a nullable repeat body created by expansion behaves as written by hand",
    g6, k6, e6, [(1, "Many(x) ::= { x } ;")],
    [
        {"input": "aab", "expected": {"status": "ok", "value": node("Root", (0, 3), text="aa")}},
        {"input": "b", "expected": {"status": "ok", "value": node("Root", (0, 1), text="")}},
        {"input": "aa", "expected": {"status": "syntax", "offset": 2}},
    ])

# 7. Grammars without parameterized definitions keep NAME( as a reference and a group.
g7 = HEAD + """  @root @mapping(Root, params=[text])
  Root ::= Foo('a') @text ;
  Foo ::= 'b' ;
}
"""
cases.append({"name": "compatibility-reference-and-group",
    "description": "without a Name(params) definition, an adjacent Foo( keeps its previous meaning",
    "grammar": g7, "status": "accept", "instances": [], "expanded": g7, "spans": [],
    "inputs": [
        {"input": "ba", "expected": {"status": "ok", "value": node("Root", (0, 2), text="a")}},
        {"input": "b", "expected": {"status": "syntax", "offset": 1}},
    ]})

# 8. #699: @nestedCaptures reaches its container through transparent instances, exactly as
# through the hand-written aliases of nested-alias-captures/AliasOptionalList.
g8a = HEAD + """  @root @nestedCaptures @mapping(Root, params=[value])
  Root ::= '[' Opt(Many(Glyph)) @value ']' ;
  Glyph ::= '😀' | 'a' ;
  Opt(x) ::= [ x ] ;
  Many(x) ::= { x } ;
}
"""
many = inst("Many(" + r("Glyph") + ")")
k8a = ["Many(" + r("Glyph") + ")", "Opt(" + r(many) + ")"]
e8a = HEAD + """  @root @nestedCaptures @mapping(Root, params=[value])
  Root ::= '[' {i1} @value ']' ;
  Glyph ::= '😀' | 'a' ;
  {i0} ::= { Glyph } ;
  {i1} ::= [ {i0} ] ;
}
"""
accept("nested-captures-through-instances", "#699 alias transparency for @nestedCaptures through expanded instances",
    g8a, k8a, e8a, [(0, "Many(x) ::= { x } ;"), (1, "Opt(x) ::= [ x ] ;")],
    [
        {"input": "[]", "expected": {"status": "ok", "value": node("Root", (0, 2), value=[])}},
        {"input": "[😀a😀]", "expected": {"status": "ok", "value": node("Root", (0, 5), value=["😀", "a", "😀"])}},
    ])

# Post-expansion validation (CE-002 and a nullable prefix that only expansion reveals).
def validate_reject(name, description, grammar, keys, expanded):
    names = {k: inst(k) for k in keys}
    cases.append({"name": name, "description": description, "grammar": grammar, "status": "reject",
        "error": {"stage": "validate", "code": "E-LEFT-RECURSION"},
        "instances": [{"key": k, "name": names[k]} for k in keys],
        "expanded": fill(expanded, keys)})

g8 = "grammar LeftRec { @package: paramprobe @root @mapping(Node) Root ::= F(Root); F(x) ::= x; }"
validate_reject("ce002-self-argument", "lean4-peg CE-002: F(x)=x; S=F(S) passes pre-expansion checks but diverges",
    g8, ["F(" + r("Root") + ")"],
    "grammar LeftRec { @package: paramprobe @root @mapping(Node) Root ::= {i0}; {i0} ::= Root; }")
g9 = "grammar LeftRec { @package: paramprobe @root @mapping(Node) Root ::= Seq(Opt('n'), Root) | 'x'; Seq(a, b) ::= a b; Opt(x) ::= [ x ]; }"
opt_n = inst("Opt(" + t("n") + ")")
validate_reject("nullable-prefix-after-expansion", "left recursion behind a nullable prefix exists only after expansion",
    g9, ["Opt(" + t("n") + ")", "Seq(" + r(opt_n) + "," + r("Root") + ")"],
    "grammar LeftRec { @package: paramprobe @root @mapping(Node) Root ::= {i1} | 'x'; {i0} ::= [ 'n' ]; {i1} ::= {i0} Root; }")

# Positioned frontend rejections.
def reject(name, grammar, code, needle, message, occurrence=0, length=None):
    start, end = at(grammar, needle, occurrence, length)
    line, column = line_col(grammar, start)
    cases.append({"name": name, "grammar": grammar, "status": "reject",
        "error": {"stage": "frontend", "code": code, "start": start, "end": end, "line": line, "column": column,
                  "message": code + ": " + message}})

def defined(grammar, needle):
    line, column = line_col(grammar, grammar.index(needle))
    return f"defined at line {line}, column {column}"

g = "grammar Bad { Root ::= F('a', 'b'); F(x) ::= x; }"
reject("arity-too-many", g, "E-PARAM-ARITY", "F('a', 'b')", "rule F expects 1 argument(s), got 2; " + defined(g, "F(x) ::="))
g = "grammar Bad { Root ::= F 'a'; F(x) ::= x; }"
reject("arity-bare-reference", g, "E-PARAM-ARITY", "F 'a'", "rule F expects 1 argument(s), got 0; " + defined(g, "F(x) ::="), length=1)
g = "grammar Bad { Root ::= F ('a'); F(x) ::= x; }"
reject("arity-trivia-before-parenthesis", g, "E-PARAM-ARITY", "F ('a')", "rule F expects 1 argument(s), got 0; " + defined(g, "F(x) ::="), length=1)
g = "grammar Bad { Root ::= F('a'); F(x) ::= y; }"
reject("undefined-parameter", g, "E-PARAM-UNDEFINED", "y;", "rule F references undefined parameter, rule or token 'y'", length=1)
g = "grammar Bad { Root ::= Copy('a'); Copy(w) ::= Copy(w 'a') | w; }"
reject("cycle-self", g, "E-PARAM-CYCLE", "Copy(w 'a')", "parameterized call cycle Copy -> Copy; expansion would not terminate")
g = "grammar Bad {\r\n  Root ::= F('a');\r\n  F(x) ::= G(x);\r\n  G(y) ::= 'b' F(y);\r\n}"
reject("cycle-mutual-crlf", g, "E-PARAM-CYCLE", "F(y)", "parameterized call cycle F -> G -> F; expansion would not terminate")
g = "grammar Bad { Root ::= F('a'); F(x) ::= F(x); }"
reject("cycle-identity", g, "E-PARAM-CYCLE", "F(x);", "parameterized call cycle F -> F; expansion would not terminate", length=4)
g = "grammar Bad { @mapping(Node) F(x) ::= x; Root ::= F('a'); }"
reject("annotation-mapping", g, "E-PARAM-ANNOTATION", "@mapping(Node) F(x) ::= x;", "parameterized rule F cannot have annotations; only transparent rules take parameters")
g = "grammar Bad { @root F(x) ::= x; }"
reject("annotation-root", g, "E-PARAM-ANNOTATION", "@root F(x) ::= x;", "parameterized rule F cannot have annotations; only transparent rules take parameters")
g = "grammar Bad { @root @mapping(Node, params=[t]) Root ::= F('a' @t); F(x) ::= x; }"
reject("capture-in-argument", g, "E-PARAM-CAPTURE", "F('a' @t)", "captures and @typeof are not allowed in parameterized rule bodies or call arguments")
g = "grammar Bad { Root ::= F('a'); F(x) ::= x @v; }"
reject("capture-in-body", g, "E-PARAM-CAPTURE", "F(x) ::= x @v;", "captures and @typeof are not allowed in parameterized rule bodies or call arguments")
g = "grammar Bad { Root ::= F('a'); F(Root) ::= Root; }"
reject("parameter-shadows-rule", g, "E-PARAM-SHADOW", "F(Root) ::= Root;", "parameter Root of rule F has the same name as a rule or token")
g = "grammar Bad { Root ::= F('a', 'b'); F(x, x) ::= x; }"
reject("duplicate-parameter", g, "E-PARAM-DUPLICATE", "F(x, x) ::= x;", "parameter x is declared more than once in rule F")
g = "grammar Bad { Root ::= F('a'); F ::= 'b'; F(x) ::= x; }"
reject("duplicate-rule", g, "E-PARAM-DUPLICATE", "F(x) ::= x;", "rule F is defined more than once")
g = "grammar Bad { Root ::= 'a'; CAPTURE(x) ::= x; }"
reject("reserved-name", g, "E-PARAM-RESERVED", "CAPTURE(x) ::= x;", "parameterized rule name CAPTURE is reserved by a builtin call")
collision = inst("F(" + t("a") + ")")
g = "grammar Bad { Root ::= F('a'); F(x) ::= x; " + collision + " ::= 'b'; }"
reject("name-collision", g, "E-PARAM-NAME-COLLISION", "F('a')", "expanded rule name " + collision + " collides with another rule, token or expansion")
LIMIT, NESTING = 65536, 128
# NESTING bounds the inline nesting: the rule bodies open at a call (the calling rule's
# body, every enclosing group, and the bodies of the instances being expanded around it)
# plus the nesting of the instance body or argument created at that call.

def doubling_chain(n, base):
    """Root ::= Dn('a'); D0(x) ::= base; Dk(x) ::= D(k-1)(x x).

    Hand evaluation of the documented accounting: each call charges its arguments
    (calls count as one element) before they are built, each new instance charges
    its body after substitution. Returns the grammar, the failing call and message.
    """
    g = "grammar Bad { Root ::= D%d('a'); D0(x) ::= %s; " % (n, base) + " ".join(
        "D%d(x) ::= D%d(x x);" % (k, k - 1) for k in range(1, n + 1)) + " }"
    total = 1 + 1                       # Root's argument 'a', then Dn's body (one call)
    level = 1                           # Root's body; each Dk body adds one level
    rep_size, rep_depth = 1, 0          # 'a' is substituted as itself
    for k in range(n - 1, -1, -1):      # the call D{k}(x x) inside D{k+1}
        call = "D%d(x x)" % k
        site = "D%d(x) ::= D%d(x x);" % (k + 1, k)
        start = g.index(site) + len("D%d(x) ::= " % (k + 1))
        level += 1                       # inside D{k+1}'s body
        arg_size, arg_depth = 2 * rep_size, 1 + rep_depth
        assert level + arg_depth <= NESTING
        total += arg_size
        if total > LIMIT:
            return g, start, call
        rep_size, rep_depth = 1 + arg_size, arg_depth   # two elements: substituted as a group
        body = 1 if k > 0 else (1 if base == "'a'" else 2 * rep_size)
        assert level + (1 if k > 0 or base == "'a'" else 1 + rep_depth) <= NESTING
        total += body
        if total > LIMIT:
            return g, start, call
    raise AssertionError("within the limit")

def reject_at(name, grammar, start, needle, code, message):
    line, column = line_col(grammar, start)
    assert grammar[start:start + len(needle)] == needle
    cases.append({"name": name, "grammar": grammar, "status": "reject",
        "error": {"stage": "frontend", "code": code, "start": start, "end": start + len(needle),
                  "line": line, "column": column, "message": code + ": " + message}})

g, start, call = doubling_chain(15, "x x")
reject_at("limit-elements", g, start, call, "E-PARAM-LIMIT", "expanded parameterized rules exceed 65536 elements")
# The callee ignores its argument, so instance bodies stay tiny; only the arguments grow.
g, start, call = doubling_chain(20, "'a'")
reject_at("limit-unused-argument-growth", g, start, call, "E-PARAM-LIMIT", "expanded parameterized rules exceed 65536 elements")
def chain_defs(n, g, d):
    """Ck(x) ::= g groups around C{k+1}(x) for k < n; Cn(x) ::= d groups around x."""
    defs = ["C%d(x) ::= %sC%d(x)%s ;" % (k, "( " * g, k + 1, " )" * g) for k in range(n)]
    return defs + ["C%d(x) ::= %sx%s ;" % (n, "( " * d, " )" * d)]

def chain_inline(n, g, d):
    """Inline nesting of each instance C0..Cn created from Root ::= C0('a').

    The call of Ck sits inside Root's body and k instance bodies of 1 + g levels each;
    the argument x (one level) never exceeds the instance created at the same call.
    """
    return [1 + k * (1 + g) + ((1 + g) if k < n else (1 + d)) for k in range(n + 1)]

def chain_accept(name, description, n, g, d):
    assert max(chain_inline(n, g, d)) == NESTING and 1 + n * (1 + g) + 1 <= NESTING
    defs = chain_defs(n, g, d)
    grammar = HEAD + "  @root @mapping(Root, params=[text])\n  Root ::= C0('a') @text ;\n" + "".join("  " + x + "\n" for x in defs) + "}\n"
    keys = ["C%d(%s)" % (k, t("a")) for k in range(n + 1)]
    names = [inst(k) for k in keys]
    body = ["%s ::= %s%s%s ;" % (names[k], "( " * g, names[k + 1], " )" * g) for k in range(n)]
    body.append("%s ::= %s'a'%s ;" % (names[n], "( " * d, " )" * d))
    expanded = HEAD + "  @root @mapping(Root, params=[text])\n  Root ::= %s @text ;\n" % names[0] + "".join("  " + x + "\n" for x in body) + "}\n"
    cases.append({"name": name, "description": description, "grammar": grammar, "status": "accept",
        "instances": [{"key": k, "name": inst(k)} for k in keys], "expanded": expanded,
        "spans": [{"rule": names[0], "span": list(at(grammar, defs[0]))}, {"rule": names[n], "span": list(at(grammar, defs[n]))}],
        "inputs": [
            {"input": "a", "expected": {"status": "ok", "value": node("Root", (0, 1), text="a")}},
            {"input": "b", "expected": {"status": "syntax", "offset": 0}},
        ]})

def chain_reject(name, n, g, d):
    """The first call whose argument (checked first) or new instance exceeds the limit."""
    inline = chain_inline(n, g, d)
    for k in range(n + 1):
        argument = 1 + k * (1 + g) + 1      # open bodies at the call + the one-level argument
        if argument > NESTING:
            message = "argument of rule C%d nests %d levels; the limit is %d" % (k, argument, NESTING)
            break
        if inline[k] > NESTING:
            message = "expansion of rule C%d nests %d levels; the limit is %d" % (k, inline[k], NESTING)
            break
    defs = chain_defs(n, g, d)
    grammar = "grammar Bad { Root ::= C0('a'); " + " ".join(defs) + " }"
    call = "C%d(x)" % k
    start = grammar.index(call, grammar.index("C%d(x) ::=" % (k - 1)) + len("C%d(x) ::=" % (k - 1)))
    reject_at(name, grammar, start, call, "E-PARAM-LIMIT", message)

# Boundaries of the inline nesting: chains whose length times body nesting reaches exactly
# 128 levels; one more level is refused. (A flat chain of 127 instances also reaches 128 and
# is checked by the frontend tests; the Rust generator's existing structural analysis depth
# of 256 rejects that many chained rules even when written by hand, so it is no consumer case.)
chain_accept("chain-nested-at-limit", "chain length times body nesting reaches exactly the limit", 2, 62, 0)
chain_accept("chain-split-at-limit", "eight instances of 15 levels and a 7-level innermost body", 8, 14, 6)
chain_reject("limit-chain-flat", 127, 0, 0)
chain_reject("limit-chain-nested", 2, 62, 1)
# The review's reproduction shape (48 instances, 30 groups each) fails at the fifth instance.
chain_reject("limit-chain-times-nesting", 48, 30, 0)
# Arguments wrapped once more per level: N_k receives a replacement k groups deep. Its call
# N{k+1}((x)) sits at k + 2 open bodies and the argument nests 2 + k levels.
g = "grammar Bad { Root ::= N0('a'); " + " ".join("N%d(x) ::= N%d((x));" % (k, k + 1) for k in range(200)) + " N200(x) ::= 'a'; }"
k = next(k for k in range(200) if (k + 2) + (2 + k) > NESTING)
reject_at("limit-argument-nesting", g, g.index("N%d(x) ::= N%d((x));" % (k, k + 1)) + len("N%d(x) ::= " % k), "N%d((x))" % (k + 1),
          "E-PARAM-LIMIT", "argument of rule N%d nests %d levels; the limit is %d" % (k + 1, 2 * k + 4, NESTING))
# Root's body (1) + F's body (1 + 120 groups + the 10-group argument).
g = "grammar Bad { Root ::= F(" + "(" * 10 + "'a'" + ")" * 10 + "); F(x) ::= " + "(" * 120 + "x" + ")" * 120 + "; }"
reject("limit-nesting", g, "E-PARAM-LIMIT", "F(" + "(" * 10, "expansion of rule F nests 132 levels; the limit is 128",
       length=len("F(" + "(" * 10 + "'a'" + ")" * 10 + ")"))

json.dump(cases, open(sys.argv[1], "w", encoding="utf-8"), ensure_ascii=False, indent=2)
open(sys.argv[1], "a").write("\n")
print(len(cases), "cases")
