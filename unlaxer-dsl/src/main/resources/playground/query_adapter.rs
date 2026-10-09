//! Optional host registration. Construct immutable bindings for this exact snapshot.
//! Register only trusted providers that can execute inside the browser/WASM sandbox.
pub fn bind(_host: &unlaxer_runtime::source::Snapshot) -> unlaxer_runtime::source::Result<Option<unlaxer_runtime::language_queries::LanguageQueries>> { Ok(None) }
