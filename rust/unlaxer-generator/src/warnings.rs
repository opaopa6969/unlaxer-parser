//! Generation warnings of one UBNF source (#715), shared in meaning with Java's
//! `GenerationWarnings`: the warnings that leave generation unchanged, collected once so that the
//! library, the native `unlaxer generate` and the Java-host `generate --target rust` report the
//! same code, span and text, and fail alike under `--strict` / `--fail-on warning`.
//!
//! Sources: `W-ANNOTATION-IGNORED` ([`crate::simple_annotations`]) and `W-AUTO-COMMIT-SKIPPED`
//! ([`unlaxer_ubnf::auto_commit`], read before the rewrite removes the annotation).
use unlaxer_ubnf::*;

/// The exit status of `generate` when `--strict` / `--fail-on warning` meets a warning; the
/// same as the Java CLI's strict validation failure.
pub const STRICT_EXIT: u8 = 5;

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Warning {
    pub code: &'static str,
    pub grammar: String,
    pub rule: String,
    /// Code-point span in this source.
    pub span: Option<Span>,
    /// The full text, starting with the code and the rule.
    pub message: String,
}

impl Warning {
    pub fn severity(&self) -> &'static str {
        "warning"
    }

    /// The line the CLIs print to stderr.
    pub fn line(&self) -> String {
        match self.span {
            Some(span) => format!(
                "warning: CP {}..{}: {}",
                span.codepoint_start, span.codepoint_end, self.message
            ),
            None => format!("warning: {}", self.message),
        }
    }
}

/// Every generation warning of one source, by grammar and then by span (start, end, code); a
/// warning without a span comes last in its grammar. A source that does not parse has none:
/// generation reports that error instead.
pub fn collect(source: &str) -> Vec<Warning> {
    let (Ok(file), Ok(before)) = (parse(source), auto_commit::parse_before_auto_commit(source))
    else {
        return Vec::new();
    };
    let mut out = Vec::new();
    for (grammar, written) in file.grammars.iter().zip(&before.grammars) {
        let mut found = Vec::new();
        for warning in crate::simple_annotations::warnings(grammar) {
            found.push(Warning {
                code: warning.code(),
                grammar: grammar.name.clone(),
                rule: warning.rule.clone(),
                span: Some(warning.span),
                message: warning.message(),
            });
        }
        for warning in auto_commit::warnings(written) {
            found.push(Warning {
                code: warning.code(),
                grammar: grammar.name.clone(),
                rule: warning.rule.clone(),
                span: Some(warning.span),
                message: warning.message(),
            });
        }
        found.sort_by_key(|w| {
            (
                w.span.is_none(),
                w.span.map(|s| (s.codepoint_start, s.codepoint_end)),
                w.code,
            )
        });
        out.extend(found);
    }
    out
}

/// The stderr text for `warnings` and, under a strict policy with at least one warning, the
/// failure: `Err` carries [`STRICT_EXIT`] and the text, and nothing may be written.
pub fn report(warnings: &[Warning], strict: bool) -> Result<String, (u8, String)> {
    let mut text = warnings
        .iter()
        .map(Warning::line)
        .collect::<Vec<_>>()
        .join("\n");
    if strict && !warnings.is_empty() {
        text.push_str(&format!(
            "\nstrict: {} warning(s) fail generation",
            warnings.len()
        ));
        return Err((STRICT_EXIT, text));
    }
    Ok(text)
}
