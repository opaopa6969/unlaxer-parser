//! Deterministic portable names for literal enum values; independent of host Unicode tables.
use crate::GenerateError;
use std::fmt::Write;

pub fn enum_constant_name(literal: &str) -> Result<String, GenerateError> {
    let mut out = String::new();
    let mut previous_lower = false;
    for cp in literal.chars() {
        if matches!(cp, '-' | '_' | ' ') {
            out.push('_');
            previous_lower = false;
        } else if !cp.is_ascii_alphanumeric() {
            if !out.is_empty() {
                out.push('_');
            }
            write!(out, "U{:X}_", cp as u32).unwrap();
            previous_lower = false;
        } else {
            if cp.is_ascii_uppercase() && previous_lower {
                out.push('_');
            }
            out.push(cp.to_ascii_uppercase());
            previous_lower = cp.is_ascii_lowercase();
        }
    }
    if out.is_empty()
        || out == "_"
        || !out
            .bytes()
            .next()
            .is_some_and(|b| b.is_ascii_uppercase() || b == b'_')
    {
        return Err(GenerateError("E-ENUM: invalid enum constant name".into()));
    }
    Ok(out)
}

pub fn validate_enum_type(name: &str) -> Result<(), GenerateError> {
    if !name.bytes().next().is_some_and(|b| b.is_ascii_alphabetic())
        || !name.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'_')
        || [
            "java",
            "abstract",
            "assert",
            "boolean",
            "break",
            "byte",
            "case",
            "catch",
            "char",
            "class",
            "const",
            "continue",
            "default",
            "do",
            "double",
            "else",
            "enum",
            "extends",
            "final",
            "finally",
            "float",
            "for",
            "goto",
            "if",
            "implements",
            "import",
            "instanceof",
            "int",
            "interface",
            "long",
            "native",
            "new",
            "package",
            "private",
            "protected",
            "public",
            "return",
            "short",
            "static",
            "strictfp",
            "super",
            "switch",
            "synchronized",
            "this",
            "throw",
            "throws",
            "transient",
            "try",
            "void",
            "volatile",
            "while",
            "true",
            "false",
            "null",
            "_",
            "as",
            "async",
            "await",
            "crate",
            "dyn",
            "extern",
            "fn",
            "impl",
            "in",
            "let",
            "loop",
            "macro",
            "match",
            "mod",
            "move",
            "mut",
            "pub",
            "ref",
            "self",
            "Self",
            "struct",
            "trait",
            "type",
            "unsafe",
            "use",
            "where",
            "yield",
            "union",
            "Ast",
            "AstValue",
            "EnumValue",
            "Span",
            "String",
            "Tree",
            "Option",
            "Vec",
            "Result",
            "Semantics",
            "record",
            "sealed",
            "permits",
            "var",
            "when",
            "box",
            "become",
            "override",
            "priv",
            "typeof",
            "unsized",
            "virtual",
            "gen",
            "macro_rules",
            "List",
            "Optional",
            "Integer",
            "Long",
            "Boolean",
            "Object",
            "Double",
            "Float",
            "Short",
            "Byte",
            "Character",
            "Number",
            "Void",
        ]
        .contains(&name)
    {
        return Err(GenerateError(
            "E-ENUM: invalid or reserved enum type name".into(),
        ));
    }
    Ok(())
}
