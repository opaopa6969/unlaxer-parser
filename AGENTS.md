## Java / Rust の機能対称性

- 機能追加・不具合修正は、原則としてJava版とRust版の両方へ実装する。APIの表現は各言語に合わせてよいが、対応する言語機能・観測可能な振る舞いを揃える。
- 共通の入力・期待値・ソース位置・失敗条件で比較し、片側だけのテスト成功を両方の完了根拠にしない。
- 言語固有の制約や段階的実装による差は、対応表・issue・受け入れ条件に明記する。片側が未対応のまま共通機能を完了扱いにしない。
- 新しい機能提案をissue化するときも、Java / Rust双方の実装と検証を受け入れ条件に含める。

## DGE — Dialogue-driven Gap Extraction

When the user says "DGE", "run DGE", "DGE して", "壁打ち", "find gaps", "brainstorm":

1. Read `dge/method.md` for the full methodology
2. Read `dge/characters/index.md` (ja) or `dge/characters/index.en.md` (en) for character list
3. Read `dge/flows/*.yaml` for available session structures
4. Auto-collect project context: README.md, docs/, directory structure, package.json/go.mod, recent git log
5. Select characters based on theme (fixed: simplification + assumption-questioning, variable: theme specialists)
6. Generate dialogue where characters argue about the design, marking gaps with `→ Gap found:`
7. Each character must respond to others' points (agree/disagree/defer)
8. Output a Gap list with Category + Severity
9. Save to `dge/sessions/`
10. Show numbered next-action choices

When the user says just "DGE" without a theme, show the toolkit overview from `dge/method.md` TL;DR section.

Session structures (auto-selected from keywords):
- "review"/"査読" → tribunal (independent eval → rebuttal → synthesis)
- "attack"/"攻撃" → wargame (attack plan → defense → judge)
- "pitch"/"ピッチ" → pitch (pitch → Q&A → investment decision)
- "diagnose"/"診断" → consult (specialist consults → synthesis)
- "incident"/"振り返り" → investigation (timeline → testimony → root cause)
- default → roundtable (free discussion)

Details: `dge/method.md`, `dge/characters/`, `dge/flows/`
