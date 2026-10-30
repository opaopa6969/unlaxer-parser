//! Explicit HTTPS retrieval. User/CI credentials are scoped to an exact origin.
use std::collections::BTreeMap;
use std::path::PathBuf;
use std::time::Duration;

#[cfg(unix)]
mod passwd_home;

const BODY_LIMIT: usize = 8 * 1024 * 1024;
// JDK21's default budget is 384 KiB, but it accounts for parsed fields rather
// than wire bytes. Keep this finite wire cap; the exact boundary is host-specific.
const RESPONSE_HEADER_LIMIT: usize = 384 * 1024;

fn error(message: &str) -> String {
    format!("E-PACKAGE: {message}")
}
pub fn fetch(source: &str) -> Result<Vec<u8>, String> {
    use crate::packages::java_uri::{ascii_url, classify_str, Https};
    // URI.create checks the entire raw source before origin checks or credentials.
    let origin = match classify_str(source) {
        Https::Invalid => return Err(error("invalid HTTPS artifact URL")),
        Https::Omit => {
            return Err(error(
                "HTTPS artifact URL must omit credentials, query and fragment",
            ));
        }
        Https::Origin(origin) => origin,
    };
    let (agent, token) = client(&origin)?;
    // Only the transport URL is NFC/percent encoded; callers keep the raw source.
    let encoded = ascii_url(source);
    let mut request = match java_route(&encoded, &origin) {
        Route::Direct => agent.get(encoded.as_ref()),
        Route::Literal { url, host } => agent.get(&url).header("Host", host),
        // Java fails after the credentials, CA and token checks, before connecting.
        Route::Unreachable => {
            return Err(error(
                "HTTPS artifact retrieval failed (TLS, timeout or size limit)",
            ))
        }
    };
    if let Some(token) = token {
        request = request.header("Authorization", format!("Bearer {token}"));
    }
    let mut response = request
        .call()
        .map_err(|_| error("HTTPS artifact retrieval failed (TLS, timeout or size limit)"))?;
    // Only a 200 body becomes the artifact. For any other status Java completes the
    // body and reports the status, so ureq's framing is read and discarded as before.
    if rejected_by_java_exchange(&response)
        || (response.status() == ureq::http::StatusCode::OK && ambiguous_framing(&response))
    {
        return Err(error(
            "HTTPS artifact retrieval failed (TLS, timeout or size limit)",
        ));
    }
    // Java's sendAsync completes its bounded body before checking the status.
    // A failed/oversized body therefore takes precedence even for 3xx/4xx/5xx.
    let bytes = response
        .body_mut()
        .with_config()
        // ureq's limit is exclusive: probe one extra byte to accept exactly 8 MiB.
        .limit((BODY_LIMIT + 1) as u64)
        .read_to_vec()
        .map_err(|_| error("HTTPS artifact retrieval failed (TLS, timeout or size limit)"))?;
    if bytes.len() > BODY_LIMIT {
        return Err(error(
            "HTTPS artifact retrieval failed (TLS, timeout or size limit)",
        ));
    }
    if response.status() != ureq::http::StatusCode::OK {
        return Err(error(
            "HTTPS artifact requires status 200; redirects are forbidden",
        ));
    }
    Ok(bytes)
}

/// How java.net.http reaches the host of a URL that `java_uri` accepted (#962).
#[derive(Debug, PartialEq, Eq)]
enum Route {
    /// ureq connects to the URL's own host, as Java does.
    Direct,
    /// `IPAddressUtil.textToNumericFormatV4` (`Utils.getServerName`, `InetAddress`) reads
    /// four dotted octets of at most 15 characters as decimal, so `127.0.0.010` is
    /// 127.0.0.10 (getaddrinfo would read octal 8). Connect to the canonical literal
    /// (TLS by IP, no SNI) but keep Java's raw `Host` header.
    Literal { url: String, host: String },
    /// Java fails before any connection: a longer numeric host is not a literal and
    /// `InetAddress` rejects it as ambiguous (or finds no DNS name); any other host
    /// becomes an `SNIHostName`, which throws for a trailing `.` or a label over 63
    /// (`IDN.toASCII` with STD3 rules).
    Unreachable,
}

fn java_route(source: &str, origin: &str) -> Route {
    // An accepted origin has an `https://` authority without userinfo/query/fragment.
    let Some(authority) = source.strip_prefix("https://") else {
        return Route::Direct;
    };
    let host = &authority[..authority.find([':', '/']).unwrap_or(authority.len())];
    let octets: Vec<&str> = host.split('.').collect();
    if octets.len() == 4 && octets.iter().all(|o| o.bytes().all(|c| c.is_ascii_digit())) {
        if host.len() > 15 {
            return Route::Unreachable;
        }
        let Ok(octets) = octets
            .iter()
            .map(|o| o.parse::<u8>())
            .collect::<Result<Vec<_>, _>>()
        else {
            return Route::Direct;
        };
        let canonical = octets
            .iter()
            .map(u8::to_string)
            .collect::<Vec<_>>()
            .join(".");
        if canonical == host {
            return Route::Direct;
        }
        // Java's Host is getHost() plus the port unless it is absent or 443, as in origin.
        let port = origin
            .get("https://".len() + host.len()..)
            .unwrap_or_default();
        return Route::Literal {
            url: format!("https://{canonical}{}", &authority[host.len()..]),
            host: format!("{host}{port}"),
        };
    }
    if host.ends_with('.') || host.split('.').any(|label| label.len() > 63) {
        return Route::Unreachable;
    }
    Route::Direct
}

/// java.net.http fails the exchange itself (not the status check) for an unrequested
/// `101` (`Exchange`: "Unexpected 101 response, when not upgrading") and for a `204`
/// whose first `Content-Length` is not `0` or which has any `Transfer-Encoding`
/// (`MultiExchange.bodyIsPresent`; a non-numeric length also fails there).
fn rejected_by_java_exchange(response: &ureq::http::Response<ureq::Body>) -> bool {
    use ureq::http::{header, StatusCode};
    match response.status() {
        StatusCode::SWITCHING_PROTOCOLS => true,
        StatusCode::NO_CONTENT => {
            let headers = response.headers();
            headers.get(header::CONTENT_LENGTH).is_some_and(|value| {
                value.to_str().ok().and_then(|v| v.parse::<i64>().ok()) != Some(0)
            }) || headers.contains_key(header::TRANSFER_ENCODING)
        }
        _ => false,
    }
}

/// Reject ambiguous framing and transfer codings we cannot decode. ureq accepts
/// CL+TE and treats any coding list containing chunked as chunked (RFC 9112 6.1/6.3).
/// Inspect every field occurrence without exposing its value in diagnostics.
fn ambiguous_framing(response: &ureq::http::Response<ureq::Body>) -> bool {
    use ureq::http::header;
    let headers = response.headers();
    if !headers.contains_key(header::TRANSFER_ENCODING) {
        return false;
    }
    headers.contains_key(header::CONTENT_LENGTH)
        || headers.get_all(header::TRANSFER_ENCODING).iter().count() != 1
        || !headers[header::TRANSFER_ENCODING]
            .as_bytes()
            .eq_ignore_ascii_case(b"chunked")
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
        .http_status_as_error(false)
        .max_response_header_size(RESPONSE_HEADER_LIMIT)
        // ureq's fixed input buffer must also hold a complete header. One probe
        // byte lets it reject an oversized, still-incomplete header at the cap.
        .input_buffer_size(RESPONSE_HEADER_LIMIT + 1)
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

#[cfg(test)]
mod tests {
    use super::{java_route, Route};

    fn literal(url: &str, host: &str) -> Route {
        Route::Literal {
            url: url.into(),
            host: host.into(),
        }
    }

    #[test]
    fn java_route_follows_java_transport() {
        // Expected values follow Oracle JDK 17.0.12 / 21.0.9 InetAddress / SNIHostName.
        let label = |n: usize| "a".repeat(n);
        for (source, origin, expected) in [
            (
                "https://localhost:1/x",
                "https://localhost:1",
                Route::Direct,
            ),
            (
                "https://127.0.0.1:1/x",
                "https://127.0.0.1:1",
                Route::Direct,
            ),
            ("https://0.0.0.0/x", "https://0.0.0.0", Route::Direct),
            (
                "https://127.0.0.010:8443/a/b",
                "https://127.0.0.010:8443",
                literal("https://127.0.0.10:8443/a/b", "127.0.0.010:8443"),
            ),
            (
                "https://00127.000.0.08:443/x",
                "https://00127.000.0.08",
                literal("https://127.0.0.8:443/x", "00127.000.0.08"),
            ),
            (
                "https://127.0.0.01:/x",
                "https://127.0.0.01",
                literal("https://127.0.0.1:/x", "127.0.0.01"),
            ),
            (
                "https://127.0.0.01",
                "https://127.0.0.01",
                literal("https://127.0.0.1", "127.0.0.01"),
            ),
            (
                "https://0127.0.000.010/x",
                "https://0127.0.000.010",
                literal("https://127.0.0.10/x", "0127.0.000.010"),
            ),
            (
                "https://00127.000.000.010:1/x",
                "https://00127.000.000.010:1",
                Route::Unreachable,
            ),
            (
                "https://0000000128.0.0.1:1/x",
                "https://0000000128.0.0.1:1",
                Route::Unreachable,
            ),
            (
                "https://localhost.:1/x",
                "https://localhost.:1",
                Route::Unreachable,
            ),
            ("https://a.b./x", "https://a.b.", Route::Unreachable),
            (
                &format!("https://{}.b:1/x", label(63)),
                &format!("https://{}.b:1", label(63)),
                Route::Direct,
            ),
            (
                &format!("https://{}.b:1/x", label(64)),
                &format!("https://{}.b:1", label(64)),
                Route::Unreachable,
            ),
            (
                &format!("https://b.{}/x", label(64)),
                &format!("https://b.{}", label(64)),
                Route::Unreachable,
            ),
        ] {
            assert_eq!(java_route(source, origin), expected, "{source}");
        }
    }
}
