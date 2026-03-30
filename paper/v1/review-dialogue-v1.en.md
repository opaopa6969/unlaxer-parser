# Review Dialogue: ["From Grammar to IDE"](./from-grammar-to-ide.en.md) v1 Review Process

**[日本語版](./review-dialogue-v1.ja.md)**

## Characters

- **R1** (Theorist / Category Theory Purist): Values formal semantics and categorical structure above all. Considers any claim without proof to be "merely an engineering report."
- **R2** (Pragmatist / Industry): Values benchmark methodology, scalability, and real-world track records. Demands quantitative evidence.
- **R3** (Functional Programming Zealot / Haskell Devotee): Believes monadic parser combinators solve everything. Worships purity with religious fervor.
- **Senpai** (Author / Creator): Defends the paper with practical arguments. Prone to irritation. "Does your code even run?"
- **Kōhai** (Co-author / Mediator): Keeps Senpai calm. "Senpai, please don't pick fights with the reviewers." Proposes constructive responses.

> **[→ Jump to Round 2: Author Discussion (Senpai + Kōhai dialogue)](#round-2-authors-discussion)**

> **[→ To the paper](./from-grammar-to-ide.en.md)**

---

## Round 1: Review Comments

---

### R1's Review (Score: Weak Reject)

**Summary:**

This paper presents "unlaxer-parser," a Java 21 framework that generates six artifacts from a single UBNF grammar specification: a parser, AST type definitions, mappers, an evaluator, an LSP server, and a DAP server. It claims three contributions, but all lack formal justification.

**Detailed Comments:**

(1) The "PropagationStopper" in Section 3.3 is presented as a core contribution of this paper, yet no formal semantics are provided whatsoever. It states that it controls two-dimensional propagation of `TokenKind` and `invertMatch`, but what are the morphisms of this "two-dimensional control flow" in which category? The authors should define a category of parser states and characterize PropagationStopper as a functor between such categories. As it stands, the paper merely states "there are four classes," which makes this a design document, not an academic paper.

To be specific, the parser's state space should be defined as `S = TokenKind x Bool`, and PropagationStopper should be defined as a mapping `S -> S`. AllPropagationStopper is a constant functor `const (consumed, false)`, and DoConsumePropagationStopper is a partial override of the projection `(_, b) -> (consumed, b)`. This structure is clearly a monoid action on `S`, and the PropagationStopper hierarchy can be characterized as generators of this monoid. Without this level of formalization, the claim "no equivalent in existing frameworks" is unverifiable.

(2) The "novel contribution" claims are overly strong. The statement at the end of Section 3.3, "To our knowledge, no existing parser combinator framework provides this level of control over parsing mode propagation," is unacceptable without proof of formal correspondence. The claim that Parsec's `try` and `lookAhead` "do not compose along independent dimensions" -- in what specific sense do they not compose? In category-theoretic terms, Parsec's combinators are morphism composition in a Kleisli category, and a formal comparison with PropagationStopper is required.

(3) Regarding `ContainerParser<T>` in Section 3.4, denotational semantics are necessary. The description that "the parse tree serves as a communication channel" is intuitive, but how do you formalize this as a side-effectful computation? `ContainerParser` is clearly a variant of the Writer monad, yet the authors make no mention of this correspondence.

(4) The comparison in Table 1 is too superficial. Rather than a binary "Yes/No" comparison, qualitative and quantitative comparison of each tool's generation capabilities is needed. The fact that ANTLR's "Partial" lacks comparison with Spoofax [Erdweg et al. 2013] and MPS [Volter et al. 2006] is inexcusable. Despite referencing the Language Workbench literature [Erdweg et al. 2013], the complete absence of comparison with Spoofax and JetBrains MPS is a critical omission.

**Questions for Authors:**

- Can you formally characterize the behavior of PropagationStopper under nesting, specifically its associativity and idempotence?
- Does functoriality hold for `ContainerParser<T>` with respect to `T`? That is, given `ContainerParser<A>` and `f: A -> B`, is it possible to obtain `ContainerParser<B>`?

**Recommendation: Weak Reject**

Without the addition of formal semantics and explicit category-theoretic correspondence with existing parser combinators, this paper does not meet the standards of SLE.

---

### R2's Review (Score: Weak Accept — borderline)

**Summary:**

The practical approach of generating six artifacts including IDE integration from a single grammar is worthy of recognition. In particular, the combination of the Generation Gap Pattern with sealed interfaces is practically useful. However, there are serious issues with the evaluation methodology.

**Detailed Comments:**

(1) The benchmarking methodology is questionable. The performance measurements in Section 5.2 use `BackendSpeedComparisonTest`, but do not use JMH (Java Microbenchmark Harness). Not using JMH for JVM benchmarks is unacceptable after 2024. While you state 5,000 warmup iterations and 50,000 measurement iterations, where are the confidence intervals? Standard deviation? GC impact control? The effect of JIT compilation's tiered compilation? The `-XX:+PrintCompilation` results? The approximate notation "~0.10 us/call" for benchmark results is insufficient as a scientific measurement.

What is specifically required:
- Measurements using JMH `@Benchmark` methods
- `@Warmup(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)`
- `@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)`
- `@Fork(3)` for independent JVM repetitions
- Results table including mean, standard deviation, and 99th percentile
- Presentation of GC logs and JIT compilation logs

(2) A generalizability claim cannot stand on an N=1 case study. tinyexpression is an expression language developed by the authors themselves and may be optimized for unlaxer-parser. At minimum, one of the following is needed:
- An application case study on a DSL developed by a third party
- Synthetic grammar benchmarks (measuring generated code quality and performance as grammar complexity varies in stages)
- Quantitative comparison against other language workbenches (Spoofax, Xtext, JetBrains MPS) on the same grammar

(3) The "10x effort reduction" claim (Section 5.3) is based on self-reporting. The "Time estimate" column in Table 3 lists "8 weeks," "5 weeks," "3 days," but these are estimates, not measured values. Without a controlled experiment (implementing the same DSL with different approaches and measuring effort), this claim is unverifiable. At the very least, the LOC comparison is an objective metric, but discussion of LOC quality (maintainability, testability) is lacking.

(4) There are no error recovery benchmarks. While Section 6.1 acknowledges "error recovery" as a limitation, error recovery is one of the most important features for a real LSP server. Quantitative evaluation is needed for: parse success rate on incomplete input (code while the user is typing), error report accuracy, and parse tree quality after recovery. Claiming to "generate an LSP server" without this is an overstatement.

(5) Section 5.4 "LLM-Assisted Development" is interesting, but "10x reduction in token cost" and "eliminates approximately 95% of debugging round-trips" have no evidence. If claiming benefits from LLM-assisted development, a concrete experimental design (task definition, number of subjects, measurement of token usage) is needed. As it stands, these are merely anecdotes.

**Strengths:**

- The problem formulation of unified generation of 6 artifacts is clear and practically important
- The combination of Generation Gap Pattern + sealed interfaces has novelty
- The TinyCalc example in Appendix A effectively demonstrates the framework's ease of use
- The 1,400x performance improvement (reflection -> sealed switch) is impressive

**Questions for Authors:**

- Are you willing to add JMH benchmarks?
- Have you had experience running experiments with grammars other than tinyexpression?
- Do you have specific plans for improving error recovery?

**Recommendation: Weak Accept (borderline)**

The practical contribution is acknowledged, but the evaluation lacks rigor. If benchmark methodology is improved and additional case studies are added, I am prepared to raise this to Accept.

---

### R3's Review (Score: Reject)

**Summary:**

The fundamental problem with this paper is that the authors do not realize they are reinventing the wheel in Java for problems that monadic parser combinators have already solved.

**Detailed Comments:**

(1) The motivation of this paper is itself flawed. Section 1 states "Parser combinator libraries such as Parsec offer compositional parser construction but stop at parsing," but this is an underestimation of Parsec's capabilities. Precisely because Parsec is monadic, it can transform parse results into arbitrary types and accumulate semantic information during parsing. Using Haskell's Type Classes, multiple interpretations (evaluator, pretty-printer, type-checker) can be derived from the same parser. As Swierstra's [2009] combinator parsing tutorial demonstrates, this has been known technology since the 1990s.

(2) PropagationStopper is simply the `local` function of the Reader monad. The authors state "To our knowledge, no existing parser combinator framework provides this level of control," but this merely indicates the authors are unaware of Haskell's MTL (Monad Transformer Library).

The specific correspondence is as follows:

```haskell
type ParserEnv = (TokenKind, InvertMatch)
type Parser a = ReaderT ParserEnv (StateT ParseState (ExceptT ParseError Identity)) a

-- AllPropagationStopper = local (const (Consumed, False))
allStop :: Parser a -> Parser a
allStop = local (const (Consumed, False))

-- DoConsumePropagationStopper = local (\(_, inv) -> (Consumed, inv))
doConsume :: Parser a -> Parser a
doConsume = local (\(_, inv) -> (Consumed, inv))

-- InvertMatchPropagationStopper = local (\(tk, _) -> (tk, False))
stopInvert :: Parser a -> Parser a
stopInvert = local (\(tk, _) -> (tk, False))

-- NotPropagatableSource = local (\(tk, inv) -> (tk, not inv))
notProp :: Parser a -> Parser a
notProp = local (\(tk, inv) -> (tk, not inv))
```

These are merely four specializations of `ReaderT`'s `local`. This is not a "novel contribution" -- a more accurate description would be "reinventing in 200 lines of Java what can be written in 10 lines of Haskell."

(3) `ContainerParser<T>` is the Writer monad. The explanation of "injecting" metadata into the parse tree corresponds exactly to the Writer monad's `tell` operation:

```haskell
type MetadataParser a = WriterT [Metadata] Parser a

errorMessage :: String -> MetadataParser ()
errorMessage msg = tell [ErrorMsg msg]

suggestCompletion :: [Suggest] -> MetadataParser ()
suggestCompletion suggests = tell [Suggestions suggests]
```

It appends metadata to a log without consuming input. This is a fundamental functional programming pattern, and there is zero novelty.

(4) `TagWrapper` is a decorated parser functor. The operation of tagging a parser and transforming its result is nothing other than a functor's `fmap`. Since the authors have not verified the functor laws (identity law, composition law), it is not even clear whether it is correctly implemented.

(5) Regarding the Generation Gap Pattern, Haskell has automatic derivation via `DeriveFunctor`, `DeriveTraversable`, and `GHC.Generics`, code generation via Template Haskell, and generic programming via syb (Scrap Your Boilerplate). The exhaustiveness checking via sealed interfaces is equivalent to Haskell's pattern match exhaustiveness checking (`-Wincomplete-patterns`). Calling the fact that Java 21 has finally caught up with Haskell 98 a "novel contribution" lacks intellectual honesty.

(6) The statement at the end of Section 3.3, "Parsec handles lookahead through `try` and `lookAhead` combinators, but these do not compose along independent dimensions," is simply wrong. Parsec's `try` controls backtracking boundaries, and `lookAhead` provides zero-width assertions. These are independently composable, and by the monad's associativity law, arbitrary nesting works correctly. In the authors' framework, not even the associativity of PropagationStopper nesting has been demonstrated.

(7) Regarding the overall architecture, the monad transformer stack `ReaderT ParserEnv (WriterT [Metadata] (StateT ParseState (ExceptT ParseError Identity)))` provides all the functionality proposed in this paper in a unified manner. Propagation control of the environment (Reader), metadata accumulation (Writer), parser state management (State), failure handling (Except). There is no need to call this "multiple interrelated artifacts that must remain consistent with each other." Type class coherence guarantees that.

**Questions for Authors:**

- Can you concretely demonstrate how PropagationStopper differs from the Reader monad's `local`?
- Can you concretely demonstrate how `ContainerParser<T>` differs from the Writer monad's `tell`?
- Have you considered a unified formulation using a monad transformer stack?

**Recommendation: Reject**

This presents reinventions of known monadic abstractions as "novel contributions" and has insufficient related work coverage. There is NO NOVEL in Monad.

---

## Round 2: Author's Discussion

---

### Senpai and Kōhai Read the Review Results

**Kōhai:** Senpai, the review results are back. Among the three reviewers: Weak Reject, Reject, and Weak Accept (borderline).

**Senpai:** ......Average the three and it's a reject.

**Kōhai:** Yes, as things stand it's a Reject. But R2 is borderline, so they're saying it could move up to Accept depending on the revision.

**Senpai:** Let's start with R2. R2 is making reasonable points. No JMH benchmarks, N=1, weak evidence for the 10x claim. All correct.

**Kōhai:** Agreed. The lack of confidence intervals in the benchmarks is definitely a weakness.

**Senpai:** Just add JMH, that's all it takes. Two days of work. Doing it.

**Kōhai:** They're also asking for error recovery benchmarks, though...

**Senpai:** Ahh... Error recovery is admittedly weak. It's true that panic-mode recovery is structurally difficult with PEG, and we wrote that as a limitation. But claiming "we generate an LSP server" while not showing parse success rates on incomplete input is... yeah, that's not fair.

**Kōhai:** Shall we add quantitative evaluation for incomplete input? Like truncating tinyexpression expressions partway and measuring the accuracy of parse error positions.

**Senpai:** Yeah, we should do that. Specifically, we prepare 50 major expression patterns from tinyexpression, and for each expression, create incomplete inputs by removing the last 1 token, 2 tokens, and 3 tokens. That's 150 test cases total. For each case, we measure: (a) at what position parsing fails, (b) whether the message reported by ErrorMessageParser is appropriate, and (c) how many tokens the failure position deviates from the actual truncation point.

**Kōhai:** That would be enough for a statistically meaningful evaluation.

**Senpai:** R2 also called out the LLM stuff. "No evidence for eliminating 95% of debugging round-trips."

**Kōhai:** That's... honestly just our gut feeling.

**Senpai:** I'll own that putting gut feelings in the paper was a mistake. We either delete it or tone down the wording.

---

### Responding to R1

**Kōhai:** Next, R1. They're demanding a category-theoretic formalization.

**Senpai:** I read it. Define PropagationStopper as a functor, define a category of parser states, write denotational semantics...

**Kōhai:** Senpai, which of R1's points do you think is actually the most important?

**Senpai:** I think we should write operational semantics. The semantics of PropagationStopper's four classes can be written as small-step inference rules. That's a legitimate request.

**Kōhai:** R1 also points out that Table 1 doesn't include Spoofax or MPS.

**Senpai:** That stings. We definitely should have compared against Spoofax, MPS, and Xtext. Especially referencing [Erdweg et al. 2013] but not including Spoofax in Table 1 -- that doesn't make sense. We'll add them.

**Kōhai:** Also, R1's last question... "Does functoriality hold for ContainerParser<T>?"

**Senpai:** ContainerParser<T> is covariant in T, but we don't explicitly provide a map operation. In other words, it wasn't designed as a functor.

**Kōhai:** Why not just be honest about it? State that the current implementation doesn't provide an fmap operation, but in principle it's possible, and we'll consider it as a future extension.

**Senpai:** Will do. However, I refuse to rewrite the whole thing in category theory. I'll go as far as operational semantics, but a category-theoretic semantics is out of scope for this paper. SLE is a software language engineering conference, not a programming language theory conference.

**Kōhai:** Well, R1 is a theorist, so I think that argument would hold... but please don't write "Does your code even run?" in the response.

**Senpai:** ......

**Kōhai:** Also, the specific formalization that R1 proposed -- defining the parser state space as `S = TokenKind x Bool` -- I think that's actually worth doing.

**Senpai:** That goes into the operational semantics. The inference rules would look like this:

```
                    s = (tokenKind, invertMatch)
  ─────────────────────────────────────────────────── [AllStop]
  AllPropagationStopper(p).parse(ctx, s)
    = p.parse(ctx, (consumed, false))
```

**Kōhai:** Very readable. Five rules -- one for each of the four Stoppers plus default propagation -- would cover everything.

**Senpai:** Right. Should we also write the Transaction semantics? The three operations: `begin`, `commit`, `rollback`.

**Kōhai:** R1 didn't ask for that much, but... adding it would increase the paper's formal rigor.

**Senpai:** Transaction semantics go in an Appendix if we have time. Low priority.

**Kōhai:** Also, about R1 asking for a Spoofax comparison. Do you have an accurate understanding of Spoofax's capabilities?

**Senpai:** Spoofax uses SDF3 for grammar definitions, Stratego for AST transformations, and ESV for editor support definitions. Its parser is SGLR (Scannerless GLR), not PEG -- it's GLR-based. Handling ambiguous grammars is a strength of Spoofax, but it's fundamentally different from our PEG-based approach.

**Kōhai:** What about LSP support?

**Senpai:** Spoofax 3 (as of 2023) is making progress on LSP support, but it's not complete. In particular, DAP support is not provided as far as we know. MPS uses a projectional editor, so it's a different paradigm from text-based LSP in the first place. Xtext is the closest to LSP, but DAP requires manual implementation.

**Kōhai:** If we summarize that in a table, it would clearly position our framework.

---

### Responding to R3

**Senpai:** Now then, R3.

**Kōhai:** Senpai, please take a deep breath before reading.

**Senpai:** ......I've read it.

**Kōhai:** ......Senpai?

**Senpai:** Calling a five-layer monad transformer stack "unified" and "elegant" is a religion.

**Kōhai:** Senpai, please don't pick fights with the reviewers.

**Senpai:** No, hear me out. R3 is saying `ReaderT ParserEnv (WriterT [Metadata] (StateT ParseState (ExceptT ParseError Identity)))` solves everything. Look at that type. That type signature alone is 150 characters. That's "elegant"?

**Kōhai:** But there's an important point in R3's critique. The correspondence between PropagationStopper and the Reader monad's `local` is technically correct.

**Senpai:** ......

**Kōhai:** Senpai?

**Senpai:** Ngh...

**Kōhai:** Let's acknowledge it. R3 is right. PropagationStopper's four classes correspond precisely to four instantiations of `local`.

**Senpai:** I'll acknowledge the correspondence. But calling it "merely four specializations of the Reader monad's local" is unfair. Our contribution is that we explicitly identified two independent dimensions -- `TokenKind` and `invertMatch` -- in parser combinators and designed an API that allows independent control of each dimension's propagation. Parsec has `try` and `lookAhead`, but no API that explicitly provides this two-dimensional independent control.

**Kōhai:** That's the key. Our rebuttal to R3's "it can be written in 10 lines in Haskell" is "then why hasn't anyone done it?"

**Senpai:** More precisely, "why don't existing Haskell parser combinator libraries provide this two-dimensional control as an explicit API?" Neither megaparsec nor attoparsec has this API.

**Kōhai:** Let's write that in the rebuttal. Acknowledge that "PropagationStopper corresponds to the Reader monad's local," then state "however, our contribution lies in recognizing that this particular monadic structure is useful in parser combinators and designing and implementing it as an explicit API."

**Senpai:** ...Yeah, I can write that. And we should acknowledge that ContainerParser being the Writer monad's tell too.

**Kōhai:** Yes. In fact, if we leverage R3's critique and add a "Monadic Interpretation" section, it would strengthen the paper.

**Senpai:** What do you mean?

**Kōhai:** We add "3.6 Monadic Interpretation of unlaxer Abstractions" to Section 3. Explicitly state that PropagationStopper corresponds to the Reader monad's local and ContainerParser corresponds to the Writer monad's tell. Then position it as: "the value of our framework lies in realizing these abstractions in Java 21's type system and integrating them with the code generation pipeline."

**Senpai:** So, incorporate R3's criticism to strengthen the paper.

**Kōhai:** Yes. If R3 thinks "the authors don't know about monads," then the best rebuttal is to show that "we knew all along and chose to design it in Java."

**Senpai:** ......Frustrating, but that's the right strategy.

**Kōhai:** Specifically, let's put a correspondence table like this in Section 3.6:

```
| unlaxer Concept                | Monadic Correspondence        | Description                           |
|--------------------------------|-------------------------------|---------------------------------------|
| PropagationStopper             | Reader monad's local          | Local modification of environment     |
| AllPropagationStopper          | local (const (C,F))          | Replace with constant environment     |
| DoConsumePropagationStopper    | local (\(_,i)->(C,i))        | Fix first component only              |
| InvertMatchPropagationStopper  | local (\(t,_)->(t,F))        | Fix second component only             |
| NotPropagatableSource          | local (\(t,i)->(t,not i))    | Invert second component               |
| ContainerParser<T>             | Writer monad's tell           | Accumulate metadata without effects   |
| ErrorMessageParser             | tell [ErrorMsg msg]           | Accumulate error messages             |
| SuggestsCollectorParser        | tell [Suggestions xs]         | Accumulate completion candidates      |
| ParseContext.begin/commit      | State monad's get/put         | Save/restore parser state             |
| Parsed.FAILED                  | ExceptT's throwError          | Propagate parse failure               |
```

**Senpai:** ......That's well organized.

**Kōhai:** With this table, R3's criticism that "the authors don't know about monads" is completely neutralized. We demonstrate the knowledge and then explain our design decisions in Java.

**Senpai:** But the moment we write this correspondence table, R3 will say "See, it's all known stuff."

**Kōhai:** That's why it's crucial to state after the table: "however, recognizing this correspondence alone does not realize a framework that generates six artifacts from a grammar in a unified manner." Monadic structure explains the design of individual components, but the code generation pipeline, the design of the UBNF grammar language, the integration of the Generation Gap Pattern with sealed interfaces, and the automatic generation of LSP/DAP servers are not derivable from knowledge of monads.

**Senpai:** Right. Monads explain "how to parse," but they don't explain "how to generate all six artifacts from a single grammar."

---

### Examining the Specific Rebuttal to R3

**Senpai:** However, R3's point (6) is wrong.

**Kōhai:** The claim that Parsec's `try` and `lookAhead` are independently composable?

**Senpai:** Yes. Parsec's `try` controls backtracking boundaries. `lookAhead` provides zero-width assertions. These are indeed independently composable, but the "two dimensions" we're talking about are different. Our two dimensions are `TokenKind` (consumption mode) and `invertMatch` (inversion flag), whereas Parsec's `try`/`lookAhead` control the consumed/unconsumed and success/failure pair. Similar but not identical.

**Kōhai:** We need to clearly articulate that subtle distinction. Something like: "Parsec's `try` controls committed/uncommitted choice, and `lookAhead` controls consumed/unconsumed. Our PropagationStopper independently controls TokenKind (consumed/matchOnly) and invertMatch (normal/inverted). These two control spaces are similar but not isomorphic, and in particular, the propagation control of invertMatch has no direct counterpart in Parsec's standard combinators."

**Senpai:** Good, we'll write it that way. However, we acknowledge that it can be formulated as Reader monad's local. Formally it's the same structure. Our contribution is the API design and how it solves parser-combinator-specific problems.

---

### The DoConsume Spelling Incident

**Kōhai:** Oh, Senpai, one thing.

**Senpai:** What.

**Kōhai:** Before we get to the category theory stuff, there's an even more basic issue...

**Senpai:** ...What.

**Kōhai:** The class name `DoConsumePropagationStopper`.

**Senpai:** ...It's properly `DoConsume`, isn't it?

**Kōhai:** It is in the source code now, but didn't we have a typo `DoCounsume` at some point?

**Senpai:** Ngh... That was fixed... today...

**Kōhai:** So before category theory, perhaps spell-check...

**Senpai:** Shut it. Moving on.

---

### On R3's "Why not Haskell?"

**Kōhai:** R3's fundamental claim boils down to "Why not write it in Haskell?" right?

**Senpai:** Yeah. We have to answer that.

**Kōhai:** How do you answer?

**Senpai:** First, our target users are Java developers. A parser combinator framework written in Haskell is only usable by Haskell programmers. There are 9 million Java programmers in the world. Haskell programmers number...

**Kōhai:** ...Senpai, that kind of flex won't fly in a review response.

**Senpai:** I know. Seriously though, there are three points. First, we demonstrate that Java 21's sealed interfaces and records have effectively the same expressive power as Haskell's ADTs (Algebraic Data Types). This can be shown formally. The permits list of sealed interfaces corresponds to sum types, records correspond to product types, and exhaustive switch expressions correspond to pattern match exhaustiveness checking.

**Kōhai:** That's already written in the paper.

**Senpai:** Second, LSP/DAP generation doesn't exist in the Haskell ecosystem. Haskell Language Server (HLS) is an LSP server for Haskell itself -- it doesn't have the ability to generate LSP servers for user-defined DSLs from a grammar. Our framework is decisively different here.

**Kōhai:** That's a strong rebuttal.

**Senpai:** Third, integration with the JVM ecosystem. Maven/Gradle build systems, IntelliJ/Eclipse IDEs, Spring Boot, etc. Haskell's cabal and stack are excellent build tools, but the adoption barrier in enterprise Java environments is high. Our framework works with `mvn generate-sources`.

**Kōhai:** But R3 is asking "why reinvent monads within Java's constraints."

**Senpai:** To that, I answer: "We did not reinvent monads; we realized monadic abstractions in Java idioms." We implemented Haskell's Writer monad's `tell` as Java's `ContainerParser`, and Reader monad's `local` as Java's `PropagationStopper`, each in a form appropriate for its host language. This is a design decision, not a knowledge gap.

**Kōhai:** ...Is that really true? Did you consciously design with `local` and `tell` in mind from the start?

**Senpai:** ......It turned out that way in the end.

**Kōhai:** Senpai...

**Senpai:** So what! If we arrived at the correct structure in the end, that means the design was sound. Mathematical structures are discovered, not invented.

**Kōhai:** ...I don't think that defense will work on R3, but for the revision notes it's enough to write "the monadic correspondence has been explicitly added as a section."

**Senpai:** Will do.

---

### Rethinking the Benchmarks

**Kōhai:** Let's go back to R2. The benchmarking issue is serious.

**Senpai:** We use JMH. No argument there. `@Benchmark`, `@Warmup`, `@Measurement`, `@Fork(3)`, the whole works.

**Kōhai:** Any chance the results will change significantly from the current ones?

**Senpai:** I'm confident the 1,400x improvement (reflection -> sealed switch) will show comparable results even with JMH. The overhead of the reflection API doesn't disappear with JMH. However, the 2.8x figure (sealed switch vs. JIT compiled code) might fluctuate somewhat since JMH measures under more rigorous conditions.

**Kōhai:** But the order of magnitude won't change.

**Senpai:** Correct. Sealed switch within 3x of JIT compiled, reflection over 1000x slower -- those conclusions won't change.

**Kōhai:** Also, R2 is asking for error recovery benchmarks. Parse success rate on incomplete input.

**Senpai:** Hmm... that's tough. Error recovery is inherently difficult in PEG-based parsers. Recovery strategies like ANTLR's token insertion/deletion don't fit well with PEG.

**Kōhai:** But we have `ErrorMessageParser`. At the very least, we can measure the accuracy of "where parsing failed."

**Senpai:** I think the parse failure position accuracy is high. PEG's ordered choice reports the deepest match position, so it tells you "which alternative came closest." But we can't do ANTLR-style "insert a token and continue" recovery.

**Kōhai:** Then let's be honest: (a) add parse failure position accuracy measurements, (b) explicitly state error recovery as future work, (c) state that current LSP functionality is limited to "completion and diagnostics when a full parse succeeds."

**Senpai:** Yeah, that's the honest thing to do.

---

### The N=1 Problem

**Kōhai:** One more thing -- R2's N=1 problem. The criticism that there's no case study beyond tinyexpression.

**Senpai:** We have to acknowledge that. But adding another case study in the short revision period isn't realistic.

**Kōhai:** As an alternative, how about a synthetic grammar benchmark? Create synthetic grammars with varying complexity levels (5 rules, 10 rules, 20 rules, 50 rules, 100 rules), and measure generated code volume, parse performance, and generation time at each level.

**Senpai:** That's interesting. We could demonstrate scalability with respect to grammar size. tinyexpression's 520-line grammar would be positioned as "medium-scale."

**Kōhai:** Furthermore, since we have the TinyCalc example in the Appendix, we can use it as a "small-scale grammar" data point. Adding a "large-scale" data point with a synthetic grammar gives us a 3-point scalability curve.

**Senpai:** Right, we'll add synthetic grammar benchmarks. Show that generation doesn't break down at the 100-rule scale.

**Kōhai:** Let's discuss the synthetic grammar design a bit more. Simply "increasing the number of rules" doesn't reflect structural complexity of the grammar (recursion depth, number of alternatives, density of @mapping annotations).

**Senpai:** Good point. We'll vary along three axes: (a) number of rules (5, 10, 20, 50, 100), (b) recursion depth (direct recursion only, 2-level mutual recursion, 4-level mutual recursion), (c) @mapping annotation density (all rules have @mapping, 50% have @mapping, 20% have @mapping).

**Kōhai:** That makes for a lot of combinations... 5 x 3 x 3 = 45 patterns.

**Senpai:** Just the key combinations. We don't need all of them. 4-level recursion with 5 rules is unnatural anyway. We'll narrow it to about 10 patterns.

**Kōhai:** Understood.

**Senpai:** Also, R2 is asking for quantitative comparison against Spoofax, Xtext, and JetBrains MPS, but...

**Senpai:** A quantitative comparison on the same grammar would take a month just for the learning curve of each tool. That's impossible within the revision period.

**Kōhai:** Then let's stick to a qualitative comparison. Expand Table 1 to include Spoofax, Xtext, and MPS, and list each tool's support status for "Parser / AST / Mapper / Evaluator / LSP / DAP." We'll explicitly state that quantitative comparison is future work.

**Senpai:** Will do. Spoofax generates parsers, AST types, and editor support via SDF3 + Stratego + ESV. LSP is partially supported. DAP is... probably not. MPS uses a projectional editor, so it's a different paradigm from LSP. Xtext is EMF-based and generates LSP, but DAP is manual.

**Kōhai:** If you have that knowledge, we can accurately expand Table 1.

**Senpai:** Yeah, doing it.

---

### On the "Novel" Claims

**Kōhai:** A common criticism across all reviewers is that the "novel contribution" claims are too strong.

**Senpai:** ......Specifically, how do we tone them down?

**Kōhai:** My proposal: Change "novel contribution" to "our contribution." Change "To our knowledge, no existing framework" to "Among the parser combinator frameworks we surveyed." Change "no equivalent in existing frameworks" to "this specific combination of controls is not provided as a first-class API in existing frameworks."

**Senpai:** So, retreating from "world first" to "new within our survey scope."

**Kōhai:** Not retreating -- precision. As R3 demonstrated, the mathematical structure is known. If we make clear that our contribution is the API design, it becomes a legitimate claim.

**Senpai:** ......Fine.

---

### Responding to R1's Functoriality Question

**Kōhai:** Oh, going back to R1's question -- "associativity and idempotence of PropagationStopper under nesting."

**Senpai:** Associativity holds. PropagationStopper is a mapping `S -> S`, and composition of mappings is associative. Idempotence is...

**Kōhai:** AllPropagationStopper is idempotent. Applying it twice gives the same result.

**Senpai:** Right. AllPropagationStopper, DoConsumePropagationStopper, and InvertMatchPropagationStopper are all idempotent. NotPropagatableSource is non-idempotent -- applying it twice returns to the original. In other words, it's an involution.

**Kōhai:** That would be interesting to put in the paper, wouldn't it? As algebraic properties of the PropagationStopper hierarchy.

**Senpai:** ......It irks me to write it just to please R1, but it would certainly improve the paper's quality.

**Kōhai:** Senpai, that's what the peer review process is for.

**Senpai:** You don't need to tell me.

---

### Examining the Algebraic Properties

**Senpai:** Since we're at it, let's organize this. Consider PropagationStopper as mappings on `S = {consumed, matchOnly} x {true, false}`.

**Kōhai:** Right. Enumerating each Stopper as an S -> S mapping:

```
All:       (tk, inv) -> (consumed, false)     -- constant map
DoCons:    (tk, inv) -> (consumed, inv)        -- fix first component
StopInv:   (tk, inv) -> (tk, false)            -- fix second component
NotProp:   (tk, inv) -> (tk, !inv)             -- invert second component
Identity:  (tk, inv) -> (tk, inv)              -- identity map (no Stopper)
```

**Senpai:** Writing the composition table...

```
DoCons . StopInv = All          -- (tk,inv) -> (tk,false) -> (consumed,false)
StopInv . DoCons = All          -- (tk,inv) -> (consumed,inv) -> (consumed,false)
DoCons . NotProp = DoCons'      -- (tk,inv) -> (tk,!inv) -> (consumed,!inv)
NotProp . NotProp = Identity    -- involution
All . X = All (for any X)       -- All is a right zero
```

**Kōhai:** Oh, interesting. Since `DoCons . StopInv = StopInv . DoCons = All`, DoCons and StopInv commute.

**Senpai:** Well, more precisely, "the composition of DoCons and StopInv is commutative." They're not commutative in general. `DoCons . NotProp ≠ NotProp . DoCons`.

**Kōhai:** I see. `DoCons . NotProp = (tk,inv) -> (consumed, !inv)` and `NotProp . DoCons = (tk,inv) -> (consumed, !inv)`... wait, those are the same.

**Senpai:** ......Really? Let me compute. `NotProp . DoCons`: first apply DoCons to get `(consumed, inv)`, then apply NotProp to get `(consumed, !inv)`. `DoCons . NotProp`: first apply NotProp to get `(tk, !inv)`, then apply DoCons to get `(consumed, !inv)`. ...They're the same.

**Kōhai:** So all four Stoppers commute?

**Senpai:** Hold on. StopInv and NotProp? `StopInv . NotProp = (tk,inv) -> (tk,!inv) -> (tk,false)` = StopInv. `NotProp . StopInv = (tk,inv) -> (tk,false) -> (tk,true)` ...no, `NotProp` is `(tk,inv) -> (tk, !inv)`, so applying `NotProp` to StopInv's result `(tk, false)` gives `(tk, !false)` = `(tk, true)`. So it becomes `(tk,inv) -> (tk, true)`.

**Kōhai:** That's a new Stopper. One that always sets the second component to true.

**Senpai:** So `StopInv . NotProp ≠ NotProp . StopInv`. Non-commutative.

**Kōhai:** Fascinating! This is worth putting in the paper. The result that the PropagationStopper hierarchy forms a non-commutative monoid.

**Senpai:** R1 would love this sort of thing...

**Kōhai:** Without a doubt.

**Senpai:** ......Right, we'll put the algebraic properties in an Appendix. Composition table, idempotence, commutativity, involution. This partially addresses R1's demand for "formal characterization."

---

### Senpai's True Feelings

**Senpai:** But you know.

**Kōhai:** Yes?

**Senpai:** R3's "it can be written in 10 lines in Haskell" is, well, correct. If you write four specializations of Reader monad's `local`, you can indeed do the equivalent in 10 lines.

**Kōhai:** Yes.

**Senpai:** But here's the thing. After writing those 10 lines, you still need to generate AST types from that parser, generate mappers, generate an LSP server, generate a DAP server, generate a type-safe evaluator skeleton with sealed interface exhaustiveness checking, make everything re-generable with the Generation Gap Pattern, and have it all linked by a single `@mapping` annotation. How many lines does that take in Haskell?

**Kōhai:** ......

**Senpai:** Does your code even run? It's easy to say "it composes elegantly." But show me a Haskell framework where the LSP server runs, the DAP server runs, completion appears in VS Code, step debugging works, breakpoints work, changing the grammar regenerates everything, and compiler errors tell you which evaluation methods are unimplemented.

**Kōhai:** Senpai, I understand how you feel, but that tone can't go in the review response.

**Senpai:** I know.

**Kōhai:** But technically, it's the right rebuttal. Let's write it in polished language: "Our primary contribution is not the monadic abstractions themselves, but rather the integration of these abstractions into a unified code generation pipeline that guarantees consistency across six artifacts, realizing an end-to-end framework. Existing monadic parser combinator frameworks provide excellent composability for parser construction, but do not provide a pipeline that automatically derives AST type generation, mapper generation, evaluator skeleton generation, LSP server generation, and DAP server generation from a grammar."

**Senpai:** ......That works.

---

## Round 3: Revision Plan

---

**Kōhai:** Let's organize the revision plan. In order of priority.

### Revision Items

**1. Add Operational Semantics (Addressing R1)**

- Write small-step operational semantics for PropagationStopper's four classes
- Define propagation of `(TokenKind, invertMatch)` pairs in inference rule form
- Add algebraic properties to the Appendix (composition table, idempotence, involution, non-commutativity)

**Senpai:** 3 days. Five inference rules (4 Stoppers + default propagation).

**Kōhai:** Including the algebraic properties Appendix in 3 days?

**Senpai:** We already computed the composition table just now, so it's just writing it up in LaTeX.

---

**2. Add Monadic Interpretation Section (Addressing R3)**

- Create new Section 3.6 "Monadic Interpretation of unlaxer Abstractions"
- Explicitly state that PropagationStopper = specialization of Reader monad's `local`
- Explicitly state that ContainerParser = Writer monad's `tell` operation
- Then position as: "our contribution is the API design and integration into the code generation pipeline"
- Describe the formal correspondence with Parsec (`try` vs. `TokenKind`, `lookAhead` vs. PropagationStopper)

**Kōhai:** What are the odds this convinces R3?

**Senpai:** 50/50. If R3 sticks to monad fundamentalism, anything written in Java will get a Reject. But if we explicitly acknowledge the monadic correspondence and then argue "even so, a framework that generates six artifacts from Java 21 has value," at minimum the "authors don't know about monads" criticism goes away.

**Kōhai:** That's enough. Even if R3 maintains the Reject, if R1 and R2 move to Accept, it passes.

**Senpai:** 2 days. I'll properly write the Haskell code examples. Compile them with GHC to make sure the types check out.

**Kōhai:** Wait, Senpai, you can write Haskell?

**Senpai:** ......A little, from my student days.

**Kōhai:** A little?

**Senpai:** ...I understand up to Applicative. Monad Transformers... I can manage if I try hard enough.

**Kōhai:** If we show R3 Haskell code with type errors, there'll be hell to pay. Please check it in GHCi.

**Senpai:** I know! I'll check it with `stack ghci`. ...Do I still have stack installed?

**Kōhai:** If not, please install it. There's no escaping Haskell this time.

**Senpai:** Grr...

---

**3. Add JMH Benchmarks (Addressing R2)**

- Convert existing BackendSpeedComparisonTest to JMH
- `@Benchmark`, `@Warmup(iterations=10)`, `@Measurement(iterations=10)`, `@Fork(3)`
- Results table including mean, standard deviation, and 99th percentile
- Add GC log and JIT compilation log analysis to the Appendix

**Senpai:** 2 days. I've set up JMH before.

**Kōhai:** What if the results differ significantly from current values?

**Senpai:** The order of magnitude won't change. If the numbers change, we honestly report that in the revision.

---

**4. Synthetic Grammar Benchmark (Addressing R2)**

- Create synthetic grammars at 5, 10, 20, 50, and 100 rule scales
- At each level, measure: generated code line count, parse performance, generation time
- Plot scalability curves
- Include TinyCalc (~5 rules) and tinyexpression (~50 rules) as real data points

**Senpai:** 3 days. Write a script to auto-generate from synthetic grammar templates.

---

**5. Expand Table 1 (Addressing R1/R2)**

- Add Spoofax (SDF3 + Stratego + ESV)
- Add Xtext (EMF + LSP)
- Add JetBrains MPS (Projectional)
- Document each tool's support status for Parser / AST / Mapper / Evaluator / LSP / DAP
- Add footnotes explaining what is manual when marked "Partial"

**Senpai:** 1 day. Just check the literature and update the table.

---

**6. Quantitative Evaluation of Error Recovery (Addressing R2)**

- For truncated tinyexpression inputs:
  - Parse failure position accuracy (correct position +/- N tokens)
  - Appropriateness of error messages from ErrorMessageParser
- Qualitative comparison with ANTLR's error recovery strategy
- Outline future research directions for error recovery in PEG

**Kōhai:** This requires new experiments.

**Senpai:** 2 days. Creating test cases and analyzing results.

---

**7. Fix "Novel" Wording (Addressing All Reviewers)**

- Change "novel contribution" to "our contribution"
- Change "To our knowledge, no existing framework" to "Among the parser combinator frameworks we surveyed"
- Change "no equivalent" to "this specific combination is not provided as a first-class API"

**Senpai:** 30 minutes. grep & replace.

---

**8. Fix LLM-Assisted Development Section (Addressing R2)**

- Remove quantitative claims: "10x reduction in token cost" and "eliminates approximately 95% of debugging round-trips"
- Limit to qualitative descriptions at the "our experience suggests" level
- Or alternatively, add LLM experiment design (task definitions, actual measured token usage)

**Senpai:** Removing them is faster. If we want to give concrete numbers, we need an experiment design, and that's a separate paper's topic.

**Kōhai:** Then let's preface it with "as a qualitative observation" and remove the specific numbers.

**Senpai:** Will do. 30 minutes.

---

### Effort Estimate

**Kōhai:** Adding it all up...

| Item | Effort |
|------|--------|
| Operational semantics | 3 days |
| Monadic interpretation section | 2 days |
| JMH benchmarks | 2 days |
| Synthetic grammar benchmark | 3 days |
| Table 1 expansion | 1 day |
| Error recovery evaluation | 2 days |
| Wording fixes | 0.5 days |
| LLM section fix | 0.5 days |
| **Total** | **14 days** |

**Senpai:** Two weeks. What's the revision deadline?

**Kōhai:** Usually 4-6 weeks. We have room.

**Senpai:** Good. Some of these can be parallelized. JMH and the Table 1 expansion are independent, and the synthetic grammar benchmark can also be done in parallel. Effectively 10 days.

---

## Round 4: What We Will NOT Revise

---

**Kōhai:** Finally, let's be clear about what we're not doing.

### Not Doing 1: Category-Theoretic Semantics

**Senpai:** I refuse to rewrite it in category theory. I'll go as far as operational semantics, but denotational semantics and category-theoretic models are out of scope for this paper.

**Kōhai:** R1 says "define a category of parser states and characterize PropagationStopper as a functor."

**Senpai:** It's a mapping, not a functor -- how many times do I have to say this. It's a self-mapping S -> S, not a functor between categories. R1 wants to turn everything into category theory, but in this case, sets and mappings describe it perfectly well. The complexity doesn't warrant category theory.

**Kōhai:** For the rebuttal, shall we write: "Since the operational semantics provides sufficient formalization, a category-theoretic model would be excessive abstraction that would not benefit the paper's target audience (software language engineering researchers and practitioners)"?

**Senpai:** Yeah, that. But so we don't bruise R1's ego, add: "As a direction for future theoretical development, a category-theoretic formulation of the PropagationStopper hierarchy is an interesting avenue."

**Kōhai:** Very diplomatic.

**Senpai:** I've been doing this for years, you know. Review responses.

---

### Not Doing 2: Reimplementation in Haskell

**Kōhai:** How do you answer R3's "Why not Haskell?"

**Senpai:** We demonstrate that Java 21's sealed interfaces are equivalent to Haskell's ADTs. And LSP/DAP generation doesn't exist in the Haskell ecosystem. End of story.

**Kōhai:** ...Will that be enough?

**Senpai:** If it's not, then this conference isn't for us.

**Kōhai:** Wait, let me think. Let's show the "equivalence with Haskell's ADTs" more concretely.

**Senpai:** Go ahead.

**Kōhai:** In Haskell:

```haskell
data Expr
  = BinaryExpr { left :: Expr, op :: String, right :: Expr }
  | IfExpr { cond :: Expr, thenBranch :: Expr, elseBranch :: Expr }
  | LiteralExpr { value :: Double }
```

In Java 21:

```java
public sealed interface Expr permits BinaryExpr, IfExpr, LiteralExpr {
    record BinaryExpr(Expr left, String op, Expr right) implements Expr {}
    record IfExpr(Expr cond, Expr thenBranch, Expr elseBranch) implements Expr {}
    record LiteralExpr(double value) implements Expr {}
}
```

**Senpai:** Pattern matching is equivalent too.

```haskell
eval :: Expr -> Double
eval (BinaryExpr l "+" r) = eval l + eval r
eval (IfExpr c t e) = if eval c /= 0 then eval t else eval e
eval (LiteralExpr v) = v
```

```java
Double eval(Expr expr) {
    return switch (expr) {
        case BinaryExpr(var l, "+", var r) -> eval(l) + eval(r);
        case IfExpr(var c, var t, var e) -> eval(c) != 0 ? eval(t) : eval(e);
        case LiteralExpr(var v) -> v;
    };
}
```

**Kōhai:** Exhaustiveness checking is also equivalent between Haskell's `-Wincomplete-patterns` and Java's sealed exhaustive switch.

**Senpai:** Correct. Java 21 has caught up with Haskell 98 in type system expressiveness. It doesn't have higher-kinded polymorphism or type classes, but for ADTs and pattern matching, they're equivalent.

**Kōhai:** R3 says "calling the fact that Java 21 has finally caught up with Haskell 98 a novel contribution lacks intellectual honesty," but...

**Senpai:** We're not calling sealed interfaces themselves a novel contribution. Our contribution is combining sealed interfaces with the Generation Gap Pattern to detect unimplemented methods via compiler errors when the grammar changes. Haskell doesn't have this "regeneration from grammar + exhaustiveness checking for change detection" pipeline.

**Kōhai:** Precisely. You could probably do something similar with Template Haskell, but a unified pipeline including LSP/DAP doesn't exist.

**Kōhai:** Could we phrase it a bit more carefully? "This framework targets the JVM ecosystem. While reimplementation in Haskell is technically possible, (a) the majority of our target users are Java developers, (b) integration with the JVM ecosystem (Maven/Gradle, IDE support, enterprise frameworks) constitutes a significant part of the framework's practical value, and (c) a complete pipeline from grammar to IDE integration including LSP/DAP generation does not exist in the Haskell ecosystem to our knowledge."

**Senpai:** ...Yeah, that's fine.

**Kōhai:** Also, we should explicitly decline the "reformulation using a monad transformer stack."

**Senpai:** "We acknowledge that PropagationStopper and ContainerParser correspond to Reader monad's local and Writer monad's tell, respectively (explicitly stated in Section 3.6). However, reformulating the entire framework using a monad transformer stack would harm readability for Java users and would not affect the design of the generation pipeline, so we have chosen not to pursue it."

**Kōhai:** Perfect.

---

### Not Doing 3: Additional Case Study (Real DSL)

**Senpai:** R2 is asking for application on a third-party DSL, but finding an external user and having them implement a DSL within the revision period is impossible.

**Kōhai:** Let's substitute with the synthetic grammar benchmark. And in the Discussion, we'll explicitly state: "external validation through third-party DSL implementations is the primary target for future evaluation."

**Senpai:** Will do.

---

### Not Doing 4: Implementing Error Recovery

**Kōhai:** R2 is asking for error recovery benchmarks, but what about implementing error recovery itself?

**Senpai:** Not happening. Error recovery in PEG-based parsers is a research-level challenge. Ford [2004] didn't address PEG error recovery either. We'll be honest about this in Limitations.

**Kōhai:** "Error recovery is a known difficult problem in PEG-based parsers and is one of the future research directions for this framework. Currently, we provide point error reporting via ErrorMessageParser and diagnostics based on PEG's deepest match position reporting."

**Senpai:** Right. Beyond that, we demonstrate the accuracy of parse failure positions quantitatively, making the case that "we can't do error recovery, but our error reporting is accurate."

---

### Not Doing 5: Adding a Functor Interface to ContainerParser<T>

**Senpai:** R1 asked about the functoriality of `ContainerParser<T>`, but we're not adding an fmap operation this time.

**Kōhai:** Why not?

**Senpai:** In the current implementation, T in `ContainerParser<T>` is fixed at generation time, so there's no need to transform T at runtime. Functoriality is theoretically interesting but has no practical necessity. "Will consider in future work" is sufficient.

**Kōhai:** Understood.

---

### Summary of the Author Response

**Kōhai:** Let's draft the author response.

**Senpai:** What's the structure?

**Kōhai:** How about this:

```
1. Gratitude to all reviewers
2. Overview of major revisions
3. Individual response to R1
   - Addition of operational semantics (accepted)
   - Addition of algebraic properties (accepted)
   - Expansion of Table 1 (accepted)
   - Category-theoretic model (respectfully declined)
   - ContainerParser functoriality (future work)
4. Individual response to R2
   - JMH benchmarks (accepted)
   - Synthetic grammar benchmarks (accepted)
   - Quantitative error recovery evaluation (partially accepted)
   - LLM section fix (accepted)
   - Third-party DSL (future work)
5. Individual response to R3
   - Addition of monadic interpretation section (accepted)
   - Explicit Reader/Writer monad correspondence (accepted)
   - Fix "novel" wording (accepted)
   - Haskell reimplementation (respectfully declined)
   - MTL stack reformulation (respectfully declined)
```

**Senpai:** That works.

**Kōhai:** Senpai, one last thing.

**Senpai:** What.

**Kōhai:** Please don't write "Does your code even run?" in the author response.

**Senpai:** ......I won't.

**Kōhai:** ...Really?

**Senpai:** I won't! ......Probably.

**Kōhai:** Senpai...

---

## Epilogue: Lessons from the Review Process

**Kōhai:** Let's summarize what we learned from this review cycle.

**Senpai:** First. "Novel" without formal backing is dangerous. We should have included at least operational semantics from the start.

**Kōhai:** Second. It's stronger to acknowledge monadic correspondence than to hide it. If you hide it, people assume "they don't know." Acknowledging it and then saying "so what? The value of our framework isn't there" is far more persuasive.

**Senpai:** Third. Benchmarks without JMH don't cut it after 2024. No excuses. We should have measured with JMH from the beginning.

**Kōhai:** Fourth. You can write a paper with N=1, but you should supplement with synthetic benchmarks for scalability. With tinyexpression alone, it looks like "a language built for this framework worked well with this framework."

**Senpai:** Fifth. Comparison targets should include language workbenches, not just parser generators. Excluding Spoofax and Xtext from the comparison was sloppy.

**Kōhai:** Sixth. Don't cherry-pick references for your convenience. Citing [Erdweg et al. 2013] while not including Spoofax in the comparison table looks dishonest to reviewers.

**Senpai:** Seventh. Don't write quantitative claims without evidence. "10x token cost reduction" and "95% of debugging round-trips" were just gut feelings, but when numbers appear in a paper, readers expect quantitative evidence.

**Kōhai:** Eighth. Write limitations honestly. We mentioned that error recovery isn't possible, but we should have gone further into its impact (the effect on LSP server usability).

**Senpai:** And finally.

**Kōhai:** Yes?

**Senpai:** Reviewers each read the paper through their own belief system (category theory, industrial practicality, monad fundamentalism). It's impossible to satisfy every reviewer 100%. What you can do is incorporate each reviewer's legitimate criticisms to strengthen the paper, while firmly yet politely declining unreasonable demands.

**Kōhai:** We decline R1's category-theoretic model. We decline R3's Haskell reimplementation. But we accept R1's operational semantics. We accept R3's monadic correspondence. We accept almost all of R2's points.

**Senpai:** Because R2 was the most reasonable.

**Kōhai:** R2 is a pragmatist, after all. Values things that work.

**Senpai:** So "Does your code even run?" said more politely is basically R2.

**Kōhai:** ...That might actually be right.

**Senpai:** Right. Let's get to the revision. Starting with the JMH setup.

**Kōhai:** Senpai, one request before that.

**Senpai:** What.

**Kōhai:** When you write the author response, please show it to me before sending it.

**Senpai:** ...You don't trust me?

**Kōhai:** I have complete trust in your technical judgment. It's only your diplomacy that worries me.

**Senpai:** ......

**Kōhai:** Senpai?

**Senpai:** ......Fine.

**Kōhai:** Thank you. Now, let's get started on the revision!

**Senpai:** Yeah. This time we'll make even R3 acknowledge us.

**Kōhai:** (...If he writes the author response in that spirit, we're in trouble...)

---

---

## Appendix: Estimated Score Changes After Revision

**Kōhai:** Let's predict the post-revision scores.

| Reviewer | v1 Score | v2 Prediction | Rationale |
|----------|----------|---------------|-----------|
| R1 | Weak Reject | Borderline Accept | Formal rigor significantly improved with operational semantics and algebraic properties. Absence of a category-theoretic model is a minus, but justified within SLE's scope. |
| R2 | Weak Accept (borderline) | Accept | All major criticisms addressed with JMH benchmarks, synthetic grammar evaluation, and Table 1 expansion. Error recovery is partial but within tolerance with honest discussion. |
| R3 | Reject | Weak Reject | The "didn't know about monads" criticism is resolved by the monadic interpretation section. However, R3's fundamental "Why not Haskell" cannot be fully answered. Moving from Reject to Weak Reject is the ceiling. |

**Senpai:** If R3 moves up to Weak Reject, the average becomes Borderline Accept. It could pass depending on the discussion with the AC.

**Kōhai:** If the AC leans toward R2's perspective, it passes. If they lean toward R3, it's tough.

**Senpai:** SLE is a software language engineering conference. I'm betting the AC is more likely to align with R2.

**Kōhai:** Isn't that a bit too optimistic?

**Senpai:** You can't write papers without being optimistic.

**Kōhai:** ...It's unfair when Senpai occasionally says something genuinely wise.

**Senpai:** Shut it. Let's start the draft.

**Kōhai:** Yes!

---

*This dialogue is a record of the review response process for unlaxer-parser paper v1, and serves as a direction document for the v2 revision.*

*All review comments are fictional, but the technical content reflects actual issues in the paper.*

---

## Navigation

[← Back to Index](../INDEX.md)

| Review | Corresponding Paper |
|--------|---------------------|
| **v1 Review — Current** | [v1 Paper](./from-grammar-to-ide.en.md) |
| [v2 Review →](../v2/review-dialogue-v2.ja.md) | [v2 Paper](../v2/from-grammar-to-ide.ja.md) |
