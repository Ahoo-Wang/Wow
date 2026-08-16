---
name: "wow-review"
description: "Review existing Wow framework code, diffs, or pull requests and produce evidence-backed findings about correctness, compatibility, reactive behavior, and tests. Use only when the review target shows Wow-specific markers such as me.ahoo.wow imports, wow-* starters, @AggregateRoot, @OnCommand, @OnSourcing, @StatelessSaga, AggregateSpec, or SagaSpec in the project or the changed code, and the primary outcome is findings, merge readiness, approval evidence, or a review-and-fix workflow that must begin with review. Do not activate for code review in projects that neither depend on Wow nor introduce it in the changed code, for review scopes without Wow semantics, or for other DDD, CQRS, or event-sourcing frameworks that merely share terms like aggregate, saga, projection, or command gateway. Do not use for symptom-led failure diagnosis, proactive feature development, or review of a breaking migration or storage/data cutover centered on migration, reconciliation, rollout, or rollback."
---

# Review Wow Changes

## Scope gate

This Skill exists only for code that runs on the Wow framework. Before reviewing, confirm Wow is actually involved: either the project depends on Wow (`me.ahoo.wow` imports, `wow-*` Gradle/Maven dependencies or starters), or the changed files introduce Wow constructs such as `@AggregateRoot`, `@OnCommand`, `@OnSourcing`, `@StatelessSaga`, `AggregateSpec`, or `SagaSpec`. Shared DDD vocabulary alone — aggregate, saga, projection, event sourcing, command gateway — does not qualify, because frameworks like Axon use the same terms. Likewise, when the project depends on Wow but the requested review scope touches no Wow semantics at all (for example a dashboard-only diff), this Skill does not apply to that scope. If no Wow-specific marker exists, state that this Skill does not apply, produce no findings, and stop.

Own the complete review or review-and-fix task. Do not route to another Wow Skill.

## Contract

- Keep review-only requests read-only. Do not edit files, post or resolve comments, approve, merge, or change remote state without explicit authorization.
- Review Wow semantics and observable behavior before style.
- Derive findings from the requested diff, current source, tests, configuration, and generated contracts.
- Separate review evidence from any later implementation evidence.
- Report no findings directly when appropriate, then name residual risks and unverified checks.

## Review workflow

1. **Resolve diff range**: inspect worktree state, requested base, merge-base, staged/unstaged changes, and touched modules.
2. **Read context**: inspect changed files, their callers/consumers, neighboring implementations, tests, configuration, and generated outputs.
3. **Check semantics**: use `references/review-rubric.md`; verify exact APIs in the current checkout instead of relying on remembered rules.
4. **Run evidence**: execute the narrowest relevant tests/checks when feasible. Label unavailable checks unverified.
5. **Report findings**: order by severity; include tight file/line evidence, impact, triggering conditions, and the smallest correct direction.

## Review-and-fix

When the user explicitly authorizes fixes:

1. Complete the findings pass first.
2. Select only authorized findings.
3. Add a failing test or equivalent pre-fix evidence for behavior changes.
4. Implement one coherent fix pass.
5. Run focused verification.
6. Review the new diff again from the requested base.
7. Repeat only for newly discovered issues that remain inside the authorized scope.

Do not turn “review and fix” into permission to fix unrelated pre-existing issues.

## Completion

Lead with findings. If none remain, state that explicitly. Then report executed commands, results, remaining test or environment gaps, and whether any write, remote, approval, or merge action was intentionally not performed.
