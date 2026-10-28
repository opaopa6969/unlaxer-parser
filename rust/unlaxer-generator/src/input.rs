//! Grammar-file failures retain their category until the CLI selects an exit status.
use std::fmt;
use std::io;
use std::path::Path;

#[derive(Debug)]
pub enum FileError {
    Io(String),
    Grammar(String),
}

impl FileError {
    pub fn exit_code(&self) -> u8 {
        match self {
            Self::Io(_) => 4,
            Self::Grammar(_) => 3,
        }
    }

    pub(crate) fn context(self, context: &str) -> Self {
        match self {
            Self::Io(message) => Self::Io(format!("{context}: {message}")),
            Self::Grammar(message) => Self::Grammar(format!("{context}: {message}")),
        }
    }
}

impl fmt::Display for FileError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Io(message) | Self::Grammar(message) => f.write_str(message),
        }
    }
}

impl std::error::Error for FileError {}

impl From<String> for FileError {
    fn from(message: String) -> Self {
        Self::Grammar(message)
    }
}

/// Read an already absolute, lexically normalized entry grammar (Java Files.readString).
pub fn read_grammar(path: &Path) -> Result<String, FileError> {
    std::fs::read_to_string(path).map_err(|error| FileError::Io(io_message(path, error)))
}

/// Match Java NIO's file exception messages, without Rust's numeric errno suffix.
pub(crate) fn io_message(path: &Path, error: io::Error) -> String {
    match error.kind() {
        io::ErrorKind::NotFound | io::ErrorKind::PermissionDenied => path.display().to_string(),
        io::ErrorKind::IsADirectory => "Is a directory".into(),
        // Content decoding is a separate contract; keep the existing strict UTF-8 error.
        io::ErrorKind::InvalidData => error.to_string(),
        _ => {
            #[cfg(unix)]
            if error.raw_os_error() == Some(libc::ELOOP) {
                return format!("{}: Too many levels of symbolic links or unable to access attributes of symbolic link", path.display());
            }
            let message = error.to_string();
            let reason = error
                .raw_os_error()
                .and_then(|errno| message.strip_suffix(&format!(" (os error {errno})")))
                .unwrap_or(&message);
            format!("{}: {reason}", path.display())
        }
    }
}
