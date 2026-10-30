//! Java's validated/NFC-encoded origin-form target, outside `http::Uri` (#977/#979).
//! ureq still owns TCP, verified TLS, deadlines, headers and the entire response.
use std::fmt;
use std::sync::Arc;
use ureq::unversioned::resolver::DefaultResolver;
use ureq::unversioned::transport::{
    time::Duration, Buffers, ConnectionDetails, Connector, DefaultConnector, NextTimeout, Transport,
};

// Only called for an accepted scalar HTTPS URL, without userinfo/query/fragment.
fn path_start(url: &str) -> usize {
    url["https://".len()..]
        .find('/')
        .map_or(url.len(), |i| "https://".len() + i)
}

pub(super) fn path(url: &str) -> &str {
    let path = &url[path_start(url)..];
    if path.is_empty() {
        "/"
    } else {
        path
    }
}

pub(super) fn placeholder(url: &str) -> String {
    format!("{}/", &url[..path_start(url)])
}

pub(super) fn agent(config: ureq::config::Config, target: &str) -> ureq::Agent {
    ureq::Agent::with_parts(
        config,
        DefaultConnector::default().chain(Target(Arc::from(target))),
        DefaultResolver::default(),
    )
}

#[derive(Clone)]
struct Target(Arc<str>);

impl fmt::Debug for Target {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("RequestTarget")
            .field("bytes", &self.0.len())
            .finish()
    }
}

impl Connector<Box<dyn Transport>> for Target {
    type Out = TargetTransport;

    fn connect(
        &self,
        _: &ConnectionDetails,
        chained: Option<Box<dyn Transport>>,
    ) -> Result<Option<Self::Out>, ureq::Error> {
        Ok(chained.map(|inner| TargetTransport {
            inner,
            target: Some(self.clone()),
        }))
    }
}

#[derive(Debug)]
struct TargetTransport {
    inner: Box<dyn Transport>,
    target: Option<Target>,
}

impl Transport for TargetTransport {
    fn buffers(&mut self) -> &mut dyn Buffers {
        self.inner.buffers()
    }

    fn transmit_output(&mut self, amount: usize, timeout: NextTimeout) -> Result<(), ureq::Error> {
        let started = std::time::Instant::now();
        let amount = if let Some(Target(target)) = self.target.take() {
            // Fail closed if a dependency changes the prelude, or a caller ever
            // supplies a target outside the validated, ASCII origin-form boundary.
            // No HTTP headers are parsed/rebuilt; move the untouched suffix once.
            if !self.inner.is_tls()
                || !target.starts_with('/')
                || target
                    .bytes()
                    .any(|b| !(0x21..=0x7e).contains(&b) || b == b'?' || b == b'#')
            {
                return Err(ureq::Error::ConnectionFailed);
            }
            let out = self.inner.buffers().output();
            let expanded = amount + target.len() - 1;
            if amount > out.len()
                || expanded > out.len()
                || !out[..amount].starts_with(b"GET / HTTP/1.1\r\n")
            {
                return Err(ureq::Error::ConnectionFailed);
            }
            out.copy_within(5..amount, 4 + target.len());
            out[4..4 + target.len()].copy_from_slice(target.as_bytes());
            expanded
        } else {
            amount
        };
        // One underlying transmit with the original timeout. No fresh per-chunk
        // budget, TLS implementation, response parser or framing recovery here.
        let timeout = NextTimeout {
            after: match timeout.after {
                Duration::Exact(after) => Duration::Exact(after.saturating_sub(started.elapsed())),
                Duration::NotHappening => Duration::NotHappening,
            },
            reason: timeout.reason,
        };
        if timeout.after.is_zero() {
            return Err(ureq::Error::Timeout(timeout.reason));
        }
        self.inner.transmit_output(amount, timeout)
    }

    fn await_input(&mut self, timeout: NextTimeout) -> Result<bool, ureq::Error> {
        self.inner.await_input(timeout)
    }
    fn is_open(&mut self) -> bool {
        self.inner.is_open()
    }
    fn is_tls(&self) -> bool {
        self.inner.is_tls()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Mutex;
    use ureq::unversioned::transport::LazyBuffers;

    type Writes = Arc<Mutex<Vec<(Vec<u8>, NextTimeout)>>>;

    #[derive(Debug)]
    struct Recording {
        buffers: LazyBuffers,
        writes: Writes,
        tls: bool,
    }
    impl Transport for Recording {
        fn buffers(&mut self) -> &mut dyn Buffers {
            &mut self.buffers
        }
        fn transmit_output(&mut self, n: usize, t: NextTimeout) -> Result<(), ureq::Error> {
            self.writes
                .lock()
                .unwrap()
                .push((self.buffers.output()[..n].to_vec(), t));
            Ok(())
        }
        fn await_input(&mut self, _: NextTimeout) -> Result<bool, ureq::Error> {
            Ok(false)
        }
        fn is_open(&mut self) -> bool {
            true
        }
        fn is_tls(&self) -> bool {
            self.tls
        }
    }

    fn transport(target: &str, capacity: usize, tls: bool) -> (TargetTransport, Writes) {
        let writes = Writes::default();
        let inner = Recording {
            buffers: LazyBuffers::new(32, capacity),
            writes: writes.clone(),
            tls,
        };
        (
            TargetTransport {
                inner: Box::new(inner),
                target: Some(Target(Arc::from(target))),
            },
            writes,
        )
    }

    fn timeout() -> NextTimeout {
        NextTimeout {
            after: Duration::from_millis(123),
            reason: ureq::Timeout::Global,
        }
    }

    #[test]
    fn only_target_changes_and_underlying_write_keeps_deadline() {
        for target in [
            "/".to_owned(),
            "/`%60%2f%C3%A9".to_owned(),
            format!("/{}", "a".repeat(200_000)),
        ] {
            let first = b"GET / HTTP/1.1\r\nHost: localhost:1234\r\n";
            let last = b"Accept: */*\r\n\r\n";
            let (mut wire, writes) = transport(&target, target.len() + first.len(), true);
            wire.buffers().output()[..first.len()].copy_from_slice(first);
            wire.transmit_output(first.len(), timeout()).unwrap();
            wire.buffers().output()[..last.len()].copy_from_slice(last);
            wire.transmit_output(last.len(), timeout()).unwrap();
            let writes = writes.lock().unwrap();
            assert_eq!(writes.len(), 2);
            assert_eq!(
                writes[0].0,
                format!("GET {target} HTTP/1.1\r\nHost: localhost:1234\r\n").into_bytes()
            );
            assert_eq!(writes[1].0, last);
            for (_, forwarded) in writes.iter() {
                assert_eq!(forwarded.reason, timeout().reason);
                assert!(forwarded.after <= timeout().after);
                assert!(!forwarded.after.is_zero());
            }
        }
    }

    #[test]
    fn invalid_targets_non_tls_changed_prelude_and_short_buffer_never_write() {
        let first = b"GET / HTTP/1.1\r\n";
        for target in [
            "",
            "relative",
            "/a b",
            "/a\r\nX: x",
            "/?x",
            "/#x",
            "/é",
            "/\u{7f}",
        ] {
            let (mut wire, writes) = transport(target, 128, true);
            wire.buffers().output()[..first.len()].copy_from_slice(first);
            assert!(wire.transmit_output(first.len(), timeout()).is_err());
            assert!(writes.lock().unwrap().is_empty());
        }
        for (tls, prelude, capacity) in [
            (false, first.as_slice(), 128),
            (true, b"POST / HTTP/1.1\r\n".as_slice(), 128),
            (true, first.as_slice(), first.len()),
        ] {
            let (mut wire, writes) = transport("/longer", capacity, tls);
            wire.buffers().output()[..prelude.len()].copy_from_slice(prelude);
            assert!(wire.transmit_output(prelude.len(), timeout()).is_err());
            assert!(writes.lock().unwrap().is_empty());
        }
    }

    #[test]
    fn exhausted_deadline_never_reaches_the_underlying_write() {
        let (mut wire, writes) = transport("/`", 128, true);
        let first = b"GET / HTTP/1.1\r\n";
        wire.buffers().output()[..first.len()].copy_from_slice(first);
        let exhausted = NextTimeout {
            after: Duration::from_millis(0),
            reason: ureq::Timeout::Global,
        };
        assert!(matches!(
            wire.transmit_output(first.len(), exhausted),
            Err(ureq::Error::Timeout(ureq::Timeout::Global))
        ));
        assert!(writes.lock().unwrap().is_empty());
    }
}
