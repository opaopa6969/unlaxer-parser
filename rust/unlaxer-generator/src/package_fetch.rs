//! Explicit HTTPS retrieval. User/CI credentials are scoped to an exact origin.
use std::collections::BTreeMap;
use std::path::PathBuf;
use std::time::Duration;

#[cfg(unix)]
mod passwd_home;

fn error(message: &str) -> String {
    format!("E-PACKAGE: {message}")
}
pub fn fetch(source: &str) -> Result<Vec<u8>, String> {
    let uri: ureq::http::Uri = source
        .parse()
        .map_err(|_| error("invalid HTTPS artifact URL"))?;
    let host = uri
        .host()
        .ok_or_else(|| error("invalid HTTPS artifact URL"))?;
    if uri.scheme_str() != Some("https")
        || source.contains(['?', '#'])
        || uri.authority().is_some_and(|v| v.as_str().contains('@'))
        || host.is_empty()
        || !host
            .bytes()
            .all(|c| c.is_ascii_alphanumeric() || b".-".contains(&c))
        || uri.port_u16() == Some(0)
    {
        return Err(error(
            "HTTPS artifact URL must omit credentials, query and fragment",
        ));
    }
    let origin = format!(
        "https://{}{}",
        host.to_ascii_lowercase(),
        uri.port_u16()
            .filter(|p| *p != 443)
            .map_or(String::new(), |p| format!(":{p}"))
    );
    let (agent, token) = client(&origin)?;
    let mut request = agent.get(source);
    if let Some(token) = token {
        request = request.header("Authorization", format!("Bearer {token}"));
    }
    let mut response = request
        .call()
        .map_err(|_| error("HTTPS artifact retrieval failed (TLS, timeout, status or redirect)"))?;
    if response.status() != ureq::http::StatusCode::OK {
        return Err(error(
            "HTTPS artifact requires status 200; redirects are forbidden",
        ));
    }
    response
        .body_mut()
        .with_config()
        .limit(8 * 1024 * 1024)
        .read_to_vec()
        .map_err(|_| error("HTTPS artifact retrieval failed (timeout or size limit)"))
}

/// A source with an unpaired UTF-16 unit: Java's `URI.create` accepts it, then its
/// HTTP request encoder fails after the credentials, CA and token checks. Run the
/// same checks for the Java-derived origin and never send any substituted URL.
pub(crate) fn reject_unencodable(origin: &str) -> Result<Vec<u8>, String> {
    client(origin)?;
    Err(error(
        "HTTPS artifact retrieval failed (TLS, timeout or size limit)",
    ))
}

/// Exact-origin credentials: CA before token, as UBNFPackageFetcher does.
fn client(origin: &str) -> Result<(ureq::Agent, Option<String>), String> {
    let credentials = credentials(origin)?;
    let mut configuration = ureq::Agent::config_builder()
        .max_redirects(0)
        .proxy(None)
        .timeout_connect(Some(Duration::from_secs(5)))
        .timeout_global(Some(Duration::from_secs(20)));
    if let Some(path) = credentials.get("caCertificate") {
        let path = path
            .as_deref()
            .ok_or_else(|| error("invalid artifact credentials"))?;
        if std::fs::metadata(path)
            .map_err(|_| error("cannot read artifact CA certificate"))?
            .len()
            > 65536
        {
            return Err(error("artifact CA file exceeds limit"));
        }
        let bytes =
            std::fs::read(path).map_err(|_| error("cannot read artifact CA certificate"))?;
        let mut certificates = Vec::new();
        for item in ureq::tls::parse_pem(&bytes) {
            match item.map_err(|_| error("cannot read artifact CA certificate"))? {
                ureq::tls::PemItem::Certificate(certificate) => certificates.push(certificate),
                _ => return Err(error("artifact CA file must contain certificates")),
            }
        }
        if certificates.is_empty() {
            return Err(error("invalid artifact CA certificate"));
        }
        configuration = configuration.tls_config(
            ureq::tls::TlsConfig::builder()
                .root_certs(ureq::tls::RootCerts::new_with_certs(&certificates))
                .build(),
        );
    }
    let agent = ureq::Agent::new_with_config(configuration.build());
    let token = match credentials.get("bearerToken") {
        None => None,
        Some(token) => Some(
            token
                .as_deref()
                .filter(|v| {
                    !v.is_empty()
                        && v.bytes()
                            .all(|c| c.is_ascii_alphanumeric() || b"._~+/=-".contains(&c))
                })
                .ok_or_else(|| error("invalid artifact credentials"))?
                .to_owned(),
        ),
    };
    Ok((agent, token))
}
fn credentials(origin: &str) -> Result<BTreeMap<&'static str, Option<String>>, String> {
    let override_path = std::env::var_os("UBNF_CREDENTIALS_FILE");
    let path = override_path
        .as_ref()
        .map(|value| java_override_path(value.as_os_str()))
        .or_else(|| default_home().map(|home| home.join(".config/unlaxer/credentials.json")))
        .ok_or_else(|| error("cannot locate artifact credentials file"))?;
    if !path.exists() {
        return if override_path.is_some() {
            Err(error("artifact credentials file is missing"))
        } else {
            Ok(BTreeMap::new())
        };
    }
    if std::fs::metadata(&path)
        .map_err(|_| error("cannot read artifact credentials"))?
        .len()
        > 65536
    {
        return Err(error("artifact credentials file exceeds limit"));
    }
    let bytes = std::fs::read(path).map_err(|_| error("cannot read artifact credentials"))?;
    crate::packages::credential_fields(&bytes, origin)
}

// Java's UnixPath for Path.of(override): "" denotes the working directory, not a
// missing file, and trailing slashes are dropped (so "file.json/" names the file).
// Other bytes are kept; the kernel treats repeated inner slashes alike.
fn java_override_path(value: &std::ffi::OsStr) -> PathBuf {
    #[cfg(unix)]
    {
        use std::os::unix::ffi::OsStrExt;
        let bytes = value.as_bytes();
        if bytes.is_empty() {
            PathBuf::from(".")
        } else {
            let end = bytes.iter().rposition(|&b| b != b'/').map_or(1, |i| i + 1);
            PathBuf::from(std::ffi::OsStr::from_bytes(&bytes[..end]))
        }
    }
    #[cfg(not(unix))]
    {
        PathBuf::from(value)
    }
}

// Java's normal Unix user.home comes from the real uid's passwd entry.
// Non-Unix retains the existing environment-based behavior.
fn default_home() -> Option<PathBuf> {
    #[cfg(unix)]
    {
        Some(passwd_home::get())
    }
    #[cfg(not(unix))]
    {
        std::env::var_os("HOME").map(PathBuf::from)
    }
}
