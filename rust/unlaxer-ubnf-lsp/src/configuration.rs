//! The generated Java base owns configuration; Ext document queries bypass its gates.
use crate::{invalid, Error};
use serde_json::{json, Value};
use unlaxer_lsp::{LanguageProfile, Selection};

#[derive(Default)]
pub(crate) struct Configuration {
    profile: Option<Selection>,
}
impl Configuration {
    pub(crate) fn read(params: &Value) -> Result<Self, Error> {
        let Some(value) = params["initializationOptions"].get("languageProfile") else {
            return Ok(Self::default());
        };
        let object = value
            .as_object()
            .ok_or_else(|| invalid("languageProfile must contain tsv and entry"))?;
        if object.len() != 2 || !object.contains_key("tsv") || !object.contains_key("entry") {
            return Err(invalid("languageProfile must contain only tsv and entry"));
        }
        let (Some(tsv), Some(entry)) = (value["tsv"].as_str(), value["entry"].as_str()) else {
            return Err(invalid("languageProfile fields must be strings"));
        };
        if entry != "UBNFFile" {
            return Err(invalid("languageProfile entry must match generated root"));
        }
        let profile = LanguageProfile::parse(tsv)
            .map_err(|error| (-32602, java_profile_error(tsv).unwrap_or(error)))?
            .select("UBNF", entry)
            .map_err(|error| (-32602, error))?;
        Ok(Self {
            profile: Some(profile),
        })
    }
    pub(crate) fn allows(&self, name: &str) -> bool {
        self.profile.as_ref().is_none_or(|p| p.allows_local(name))
    }
    pub(crate) fn capabilities(&self) -> Value {
        let mut caps = json!({"textDocumentSync":1,"hoverProvider":true,"definitionProvider":true,"referencesProvider":true,"renameProvider":true,"documentSymbolProvider":true,"foldingRangeProvider":true,"linkedEditingRangeProvider":true,"completionProvider":{"resolveProvider":false,"triggerCharacters":["@","=","."]}});
        let mut operations = serde_json::Map::new();
        for name in [
            "VALIDATE",
            "COMPLETION",
            "HOVER",
            "DEFINITION",
            "RENAME",
            "FORMAT",
            "CODE_ACTION",
        ] {
            let transport = matches!(name, "VALIDATE" | "COMPLETION" | "HOVER" | "DEFINITION");
            operations.insert(name.into(), json!({"transport":transport,"providerRegistered":false,"profileAllowed":self.allows(name),"available":false}));
        }
        caps["experimental"] =
            json!({"languageQueryConsumer":{"schemaVersion":1,"operations":operations}});
        if let Some(profile) = &self.profile {
            if !self.allows("COMPLETION") {
                caps.as_object_mut().unwrap().remove("completionProvider");
            }
            for (name, key) in [
                ("HOVER", "hoverProvider"),
                ("DEFINITION", "definitionProvider"),
                ("RENAME", "renameProvider"),
                ("CODE_ACTION", "codeActionProvider"),
            ] {
                if !self.allows(name) {
                    caps[key] = json!(false);
                }
            }
            if !self.allows("FORMAT") {
                caps["documentFormattingProvider"] = json!(false);
                caps["documentRangeFormattingProvider"] = json!(false);
            }
            let l = &profile.language;
            caps["experimental"]["languageProfile"] = json!({"schemaVersion":1,"tsv":profile.profile.canonical_tsv(),"entry":l.entry,"grammar":l.grammar,"language":l.id,"package":l.package_id,"version":l.version});
        }
        json!({"capabilities":caps})
    }
}

// Support.valueOf has its own Java error. Use it only when preceding structural
// checks would not stop the Java reader. The shared validator decides acceptance.
fn java_profile_error(tsv: &str) -> Option<String> {
    if tsv.len() > 65536 || !tsv.ends_with('\n') || tsv.contains(['\r', '\0']) {
        return None;
    }
    let mut keys = std::collections::BTreeSet::new();
    for (index, line) in tsv.strip_suffix('\n')?.split('\n').enumerate() {
        let row: Vec<_> = line.split('\t').collect();
        let width = match row[0] {
            "profile" | "language" | "fixture" => 2,
            "package" | "target" | "tool" | "grammar" | "entry" | "capability" | "syntax"
            | "difference" => 3,
            "source" => 4,
            _ => return None,
        };
        if index >= 512 || row.len() != width || row.iter().any(|s| s.is_empty()) {
            return None;
        }
        let repeated = matches!(row[0], "entry" | "capability" | "syntax" | "difference");
        let key = if repeated {
            format!("{}\t{}", row[0], row[1])
        } else {
            row[0].into()
        };
        if !keys.insert(key)
            || repeated
                && (!row[1].as_bytes()[0].is_ascii_alphabetic()
                    || !row[1]
                        .bytes()
                        .all(|b| b.is_ascii_alphanumeric() || b == b'_' || b == b'-'))
        {
            return None;
        }
        if matches!(row[0], "entry" | "capability" | "syntax")
            && !matches!(row[2], "SUPPORTED" | "PARTIAL" | "EXTERNAL" | "UNSUPPORTED")
        {
            return Some(format!(
                "No enum constant org.unlaxer.source.LanguageProfile.Support.{}",
                row[2]
            ));
        }
    }
    None
}

pub(crate) fn notification_method(method: &str) -> bool {
    matches!(
        method,
        "initialized"
            | "exit"
            | "$/setTrace"
            | "textDocument/didOpen"
            | "textDocument/didChange"
            | "textDocument/didClose"
            | "textDocument/didSave"
            | "textDocument/willSave"
            | "workspace/didChangeConfiguration"
            | "workspace/didChangeWatchedFiles"
            | "workspace/didChangeWorkspaceFolders"
            | "workspace/didCreateFiles"
            | "workspace/didRenameFiles"
            | "workspace/didDeleteFiles"
            | "window/workDoneProgress/cancel"
            | "notebookDocument/didOpen"
            | "notebookDocument/didChange"
            | "notebookDocument/didSave"
            | "notebookDocument/didClose"
    )
}
