# `@longestChoice` transaction guard benchmark (2026-09-20)

This benchmark checks whether making nested `Choice` / `NonOrdered` selection metadata
transactional regresses existing parsers. It does **not** claim that `@longestChoice` itself is
faster; the tinyexpression root-retry comparison is tracked separately by #187.

## Environment and protocol

- Baseline: `c1499b909ad03fac95791a82f0268d6924a2d630`
- Candidate: `feat/longest-choice-187` working tree (content of issue #198)
- Java: 21.0.9, Linux amd64
- Harness: `org.unlaxer.parser.EngineBenchmark`
- Per process/scenario: 50 warmups, 200 measurements
- Three fresh Maven/exec invocations per revision
- Summary: median of the three reported per-run medians

Reproduction:

```bash
mvn -q -Dflatten.skip=true -pl unlaxer-common test-compile
for run in 1 2 3; do
  mvn -q -Dflatten.skip=true -pl unlaxer-common exec:java \
    -Dexec.mainClass=org.unlaxer.parser.EngineBenchmark \
    -Dexec.classpathScope=test -Dgpg.skip=true
done
```

## Summary

| scenario | memo | baseline median (µs) | candidate median (µs) | change |
|---|---:|---:|---:|---:|
| calculator-short | off | 669.809 | 602.048 | -10.12% |
| calculator-short | safe | 694.597 | 493.366 | -28.97% |
| calculator-long | off | 39,741.193 | 40,362.603 | +1.56% |
| calculator-long | safe | 40,136.924 | 40,018.559 | -0.29% |
| exponential-deep | off | 177,969.473 | 172,870.657 | -2.87% |
| exponential-deep | safe | 181,313.044 | 175,281.766 | -3.33% |

The short scenario is noisy at sub-millisecond scale. The long scenario, which is the more useful
guard for transaction overhead, changed by +1.56% without memoization and -0.29% with safe-failure
memoization. This supports the bounded conclusion: no material regression was detected. It does not
support a speedup claim.

## Raw per-run medians (µs)

| revision | scenario | memo | run 1 | run 2 | run 3 |
|---|---|---:|---:|---:|---:|
| baseline | calculator-short | off | 558.453 | 669.809 | 697.463 |
| baseline | calculator-short | safe | 694.597 | 707.312 | 481.414 |
| baseline | calculator-long | off | 39,279.674 | 40,305.907 | 39,741.193 |
| baseline | calculator-long | safe | 40,136.924 | 40,791.971 | 40,094.967 |
| baseline | exponential-deep | off | 178,843.649 | 177,969.473 | 176,091.781 |
| baseline | exponential-deep | safe | 181,535.010 | 181,204.201 | 181,313.044 |
| candidate | calculator-short | off | 563.092 | 602.048 | 727.081 |
| candidate | calculator-short | safe | 689.518 | 480.532 | 493.366 |
| candidate | calculator-long | off | 39,864.130 | 40,362.603 | 40,921.813 |
| candidate | calculator-long | safe | 40,018.559 | 39,950.531 | 40,178.036 |
| candidate | exponential-deep | off | 172,870.657 | 184,302.103 | 172,510.337 |
| candidate | exponential-deep | safe | 175,281.766 | 182,545.612 | 173,056.723 |
