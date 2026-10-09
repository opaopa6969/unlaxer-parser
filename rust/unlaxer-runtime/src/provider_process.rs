//! Explicitly registered analysis command transport; never a shell or workspace auto-discovery.
use crate::language_queries;
use crate::provider_protocol::{self, Identity, Request, Response, Status};
use crate::source::{Operation, Result, State};
use std::collections::HashSet;
use std::io::{Read, Write};
use std::process::{Command, Stdio};
use std::sync::mpsc;
use std::time::{Duration, Instant};

pub struct ProviderProcess {
    pub command: Vec<String>,
    pub identity: Identity,
    pub operations: HashSet<Operation>,
    pub timeout: Duration,
}
fn empty(status: Status) -> Response {
    Response {
        status,
        capabilities: HashSet::new(),
        diagnostics: vec![],
        items: vec![],
    }
}
fn read(mut stream: impl Read) -> std::io::Result<Vec<u8>> {
    let mut result = vec![];
    stream
        .by_ref()
        .take(4 * 1024 * 1024 + 1)
        .read_to_end(&mut result)?;
    if result.len() > 4 * 1024 * 1024 {
        return Err(std::io::Error::other("provider response exceeds limit"));
    }
    Ok(result)
}
impl ProviderProcess {
    pub fn invoke(&self, request: &Request<'_>) -> Result<Response> {
        if self.command.is_empty()
            || self.command[0].is_empty()
            || self.timeout.as_millis() < 1
            || self.timeout.as_millis() > 120000
        {
            return Err("invalid analysis command");
        }
        if request.provider != &self.identity {
            return Err("wrong provider identity");
        }
        if request.execute_user_code || !self.operations.contains(&request.operation) {
            return Ok(empty(Status::Unsupported));
        }
        let frame = provider_protocol::encode(request)?;
        let Ok(mut child) = Command::new(&self.command[0])
            .args(&self.command[1..])
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .spawn()
        else {
            return Ok(empty(Status::Unavailable));
        };
        let deadline = Instant::now() + self.timeout;
        let input = child.stdin.take().unwrap();
        let output = child.stdout.take().unwrap();
        let errors = child.stderr.take().unwrap();
        let (send, receive) = mpsc::channel();
        let input_send = send.clone();
        let bytes = frame.text.clone().into_bytes();
        std::thread::spawn(move || {
            let mut input = input;
            let result = input.write_all(&bytes).map(|_| vec![]);
            let _ = input_send.send((0, result));
        });
        let output_send = send.clone();
        std::thread::spawn(move || {
            let _ = output_send.send((1, read(output)));
        });
        std::thread::spawn(move || {
            let _ = send.send((2, read(errors)));
        });
        let result = (|| {
            let mut stdout = vec![];
            for _ in 0..3 {
                let Some(remaining) = deadline.checked_duration_since(Instant::now()) else {
                    return Ok(empty(Status::Timeout));
                };
                match receive.recv_timeout(remaining) {
                    Ok((kind, Ok(bytes))) => {
                        if kind == 1 {
                            stdout = bytes;
                        }
                    }
                    Ok((_, Err(_))) => return Ok(empty(Status::Failed)),
                    Err(mpsc::RecvTimeoutError::Timeout) => return Ok(empty(Status::Timeout)),
                    Err(_) => return Ok(empty(Status::Failed)),
                }
            }
            loop {
                match child.try_wait() {
                    Ok(Some(status)) => {
                        if !status.success() {
                            return Ok(empty(Status::Failed));
                        }
                        let wire =
                            String::from_utf8(stdout).map_err(|_| "invalid response UTF-8")?;
                        return provider_protocol::decode(request, &frame, &wire);
                    }
                    Ok(None) => {}
                    Err(_) => return Ok(empty(Status::Failed)),
                }
                if Instant::now() >= deadline {
                    return Ok(empty(Status::Timeout));
                }
                std::thread::sleep(Duration::from_millis(2));
            }
        })();
        let _ = child.kill();
        let _ = child.wait();
        result
    }
}
impl language_queries::Provider for ProviderProcess {
    fn capabilities(&self) -> HashSet<Operation> {
        self.operations.clone()
    }
    fn query(&self, request: &language_queries::Request<'_>) -> Result<language_queries::Response> {
        let response = self.invoke(&Request {
            id: &request.region.id,
            provider: &self.identity,
            language: &request.region.language,
            region: &request.region.id,
            snapshot: request.region.source_map.output(),
            project: request.project,
            operation: request.operation,
            cursor: request.cursor,
            parameters: request.parameters,
            execute_user_code: false,
        })?;
        let state = match response.status {
            Status::Ok => State::Complete,
            Status::Diagnostics => State::Partial,
            Status::Unavailable => State::Unavailable,
            Status::Unsupported => State::Unsupported,
            Status::Timeout => State::Timeout,
            Status::Failed => State::Failed,
        };
        Ok(language_queries::Response {
            snapshot: request.region.source_map.output().clone(),
            project: request.project.id.clone(),
            project_version: request.project.version,
            state,
            items: response.items,
        })
    }
}

pub struct GrammarAdapter<'a> {
    pub process: &'a ProviderProcess,
    pub language: crate::source::Language,
    pub project: language_queries::Project,
    pub parameters: std::collections::BTreeMap<String, String>,
}
impl crate::embedded::Grammar for GrammarAdapter<'_> {
    fn name(&self) -> &str {
        &self.language.grammar
    }
    fn parse(
        &self,
        entry: &str,
        snapshot: &crate::source::Snapshot,
    ) -> Result<crate::embedded::Parsed> {
        if entry != self.language.entry {
            return Ok(crate::embedded::Parsed {
                snapshot: snapshot.clone(),
                state: State::Unsupported,
                children: vec![],
            });
        }
        let response = self.process.invoke(&Request {
            id: &snapshot.uri,
            provider: &self.process.identity,
            language: &self.language,
            region: &snapshot.uri,
            snapshot,
            project: &self.project,
            operation: Operation::Parse,
            cursor: 0,
            parameters: &self.parameters,
            execute_user_code: false,
        })?;
        let state = match response.status {
            Status::Ok => State::Complete,
            Status::Diagnostics | Status::Failed => State::Failed,
            Status::Unavailable => State::Unavailable,
            Status::Unsupported => State::Unsupported,
            Status::Timeout => State::Timeout,
        };
        Ok(crate::embedded::Parsed {
            snapshot: snapshot.clone(),
            state,
            children: vec![],
        })
    }
}
