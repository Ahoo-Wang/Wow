---
name: "wow-review"
description: "Review downstream application code, diffs, or pull requests that use or introduce Wow and produce evidence-backed findings about correctness, compatibility, reactive behavior, and tests. Never activate for the Wow framework repository itself. Require scoped me.ahoo.wow imports, wow-* dependencies, or an explicit request to review Wow use or introduction in a downstream application; checkout-wide markers and generic DDD/CQRS terms are insufficient. Use for findings, merge readiness, and review-and-fix. Do not use for non-Wow scopes, diagnosis, development, or breaking migration/data-cutover review."
---

# Review Wow Changes

## Scope gate

Before reviewing, confirm that the target is a downstream application rather than the Wow framework repository itself. Then require scoped `me.ahoo.wow` imports, `wow-*` dependencies, or an explicit request to review Wow use or introduction in that downstream application. Framework-repository modules, negated/comparative mentions, shared DDD/CQRS vocabulary, and application scopes without Wow semantics do not qualify. If no downstream scoped Wow evidence exists, state that this Skill does not apply and stop using it.

Own the complete review or review-and-fix task. Do not route to another Wow Skill.

## Contract

- Keep review-only requests read-only. Do not edit files, post or resolve comments, approve, merge, or change remote state without explicit authorization.
- Review Wow semantics and observable behavior before style.
- Derive findings from the requested diff, current source, tests, configuration, and generated contracts.
- Separate review evidence from any later implementation evidence.
- Lead with findings; if none remain, state that directly. Report executed checks and results, residual risks or gaps, and every intentionally omitted write, remote, approval, or merge action.

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
