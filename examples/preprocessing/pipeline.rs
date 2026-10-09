//! Executable adapter example; its JSON artifact payload is not a UBNF syntax extension.
use serde_json::{json, Value};
use std::collections::HashMap;
use unlaxer_runtime::{
    editor_cst::EditorCst,
    pipeline::*,
    semantic_rules as rules,
    source::{Kind, Location, Result, Segment, Snapshot, SourceMap},
    Span,
};
fn phase(id: &str, dependencies: &[&str], inputs: &[&str], keys: &[&str]) -> Phase {
    Phase {
        id: id.into(),
        dependencies: dependencies.iter().map(|s| (*s).into()).collect(),
        inputs: inputs.iter().map(|s| (*s).into()).collect(),
        configuration_keys: keys.iter().map(|s| (*s).into()).collect(),
        executes_user_code: false,
    }
}
fn source(snapshot: &Snapshot) -> Artifact {
    Artifact {
        state: State::Complete,
        payload: snapshot.text.clone(),
        origins: vec![Location::new(
            snapshot.clone(),
            Span {
                start: 0,
                end: snapshot.len(),
            },
        )
        .unwrap()],
        diagnostics: vec![],
    }
}
pub fn pipeline(
    program: rules::Program,
    inventory: rules::Inventory,
    parser: fn(&str) -> EditorCst,
) -> AnalysisPipeline {
    let mut executors: HashMap<String, Box<dyn Executor>> = HashMap::new();
    executors.insert(
        "include".into(),
        Box::new(|r: &Request| Ok(source(&r.inputs["included"]))),
    );
    executors.insert(
        "optional".into(),
        Box::new(|r: &Request| Ok(source(&r.inputs["optional"]))),
    );
    executors.insert("expand".into(), Box::new(expand));
    executors.insert(
        "semantic".into(),
        Box::new(move |r: &Request| analyze(r, &program, &inventory, parser)),
    );
    AnalysisPipeline::with_conditions(
        vec![
            phase("semantic", &["expand"], &[], &["cursor"]),
            phase(
                "expand",
                &["include", "optional"],
                &["main"],
                &["generation"],
            ),
            phase("optional", &[], &["optional"], &[]),
            phase("include", &[], &["included"], &[]),
        ],
        executors,
        HashMap::from([(
            "optional".into(),
            Condition {
                key: "extra".into(),
                expected: "true".into(),
            },
        )]),
    )
    .unwrap()
}
fn expand(r: &Request) -> Result<Artifact> {
    let main = &r.inputs["main"];
    let included = &r.dependencies["include"];
    let optional = &r.dependencies["optional"];
    let anchor = Location::new(
        main.clone(),
        Span {
            start: 0,
            end: 6.min(main.len()),
        },
    )?;
    let mut origins = vec![anchor.clone()];
    let mut output = String::new();
    let mut segments = vec![];
    append(
        &mut output,
        &mut segments,
        &mut origins,
        "/*generated😀*/",
        Kind::Generated,
        anchor.clone(),
    );
    append(
        &mut output,
        &mut segments,
        &mut origins,
        &included.payload,
        Kind::Copy,
        included.origins[0].clone(),
    );
    if optional.state == State::Complete {
        append(
            &mut output,
            &mut segments,
            &mut origins,
            &optional.payload,
            Kind::Copy,
            optional.origins[0].clone(),
        );
    }
    append(
        &mut output,
        &mut segments,
        &mut origins,
        "\n",
        Kind::Generated,
        anchor,
    );
    let main_start = output.chars().count();
    append(
        &mut output,
        &mut segments,
        &mut origins,
        &main.text,
        Kind::Copy,
        Location::new(
            main.clone(),
            Span {
                start: 0,
                end: main.len(),
            },
        )?,
    );
    let version = r.configuration["generation"]
        .parse::<u64>()
        .map_err(|_| "invalid generation")?;
    let payload=json!({"text":output,"segments":segments,"version":version,"mainStart":main_start,"mainLength":main.len(),"optional":format!("{:?}",optional.state).to_uppercase()}).to_string();
    Ok(Artifact {
        state: State::Complete,
        payload,
        origins,
        diagnostics: vec![],
    })
}
fn append(
    output: &mut String,
    segments: &mut Vec<Value>,
    origins: &mut Vec<Location>,
    text: &str,
    kind: Kind,
    origin: Location,
) {
    if text.is_empty() {
        return;
    }
    let start = output.chars().count();
    output.push_str(text);
    let index = origins.len();
    origins.push(origin);
    segments.push(json!([
        start,
        output.chars().count(),
        format!("{kind:?}").to_uppercase(),
        index
    ]));
}
fn map(artifact: &Artifact) -> Result<SourceMap> {
    let data: Value =
        serde_json::from_str(&artifact.payload).map_err(|_| "invalid expansion payload")?;
    let output = Snapshot::new(
        "memory:expanded",
        data["version"].as_u64().unwrap(),
        data["text"].as_str().unwrap(),
    )?;
    let segments = data["segments"]
        .as_array()
        .unwrap()
        .iter()
        .map(|row| Segment {
            output: Span {
                start: row[0].as_u64().unwrap() as usize,
                end: row[1].as_u64().unwrap() as usize,
            },
            kind: if row[2] == "COPY" {
                Kind::Copy
            } else {
                Kind::Generated
            },
            origin: Some(artifact.origins[row[3].as_u64().unwrap() as usize].clone()),
        })
        .collect();
    SourceMap::new(output, segments)
}
fn analyze(
    r: &Request,
    program: &rules::Program,
    inventory: &rules::Inventory,
    parser: fn(&str) -> EditorCst,
) -> Result<Artifact> {
    let expanded = &r.dependencies["expand"];
    let data: Value =
        serde_json::from_str(&expanded.payload).map_err(|_| "invalid expansion payload")?;
    let map = map(expanded)?;
    let source = map.output();
    let cursor_text = r.configuration.get("cursor").ok_or("invalid cursor")?;
    if cursor_text.is_empty()
        || cursor_text.len() > 10
        || !cursor_text.bytes().all(|c| c.is_ascii_digit())
    {
        return Err("invalid cursor");
    }
    let relative = cursor_text.parse::<i32>().map_err(|_| "invalid cursor")? as usize;
    if relative > data["mainLength"].as_u64().unwrap() as usize {
        return Err("invalid cursor");
    }
    let cursor = (data["mainStart"].as_u64().unwrap() as usize)
        .checked_add(relative)
        .ok_or("invalid cursor")?;
    let analysis = rules::analyze(
        program,
        inventory,
        &source.uri,
        i64::try_from(source.version).map_err(|_| "invalid generation")?,
        &parser(&source.text),
    )
    .map_err(|_| "semantic snapshot")?;
    let mut report = json!({"optional":data["optional"],"types":analysis.model().map(|m|m.data().types.iter().map(|t|&t.id).collect::<Vec<_>>()).unwrap_or_default(),"expected":[],"completion":[],"edit":[]});
    let mut diagnostics = vec![];
    let mut mappings = vec![];
    for diagnostic in analysis.diagnostics() {
        for location in map.diagnostics(diagnostic.span)? {
            let at = &location.location;
            diagnostics.push(json!([
                diagnostic.code,
                at.snapshot.uri,
                at.snapshot.version,
                at.span.start,
                at.span.end,
                location.exact
            ]));
            mappings.push(location);
        }
    }
    report["diagnostics"] = json!(diagnostics);
    if let Some(query) = rules::query(&analysis, &source.uri, source.version as i64, cursor, "")
        .map_err(|_| "semantic query")?
    {
        report["expected"] = json!(query.expected.iter().map(|t| t.name()).collect::<Vec<_>>());
        report["completion"] = json!(query
            .completions
            .iter()
            .map(|v| &v.value.name)
            .collect::<Vec<_>>());
        let edit = map.edit(query.edit)?;
        report["edit"] = json!([
            edit.snapshot.uri,
            edit.snapshot.version,
            edit.span.start,
            edit.span.end
        ]);
    }
    assert!(map.edit(Span { start: 0, end: 1 }).is_err());
    report["generatedEdit"] = json!("REJECTED");
    report["expandedLength"] = json!(source.len());
    let state = match analysis.status() {
        unlaxer_runtime::editor_cst::Status::Complete => State::Complete,
        unlaxer_runtime::editor_cst::Status::Partial => State::Partial,
        unlaxer_runtime::editor_cst::Status::Failed => State::Failed,
    };
    Ok(Artifact {
        state,
        payload: report.to_string(),
        origins: expanded.origins.clone(),
        diagnostics: mappings,
    })
}
