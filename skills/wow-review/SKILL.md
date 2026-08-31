---
name: "wow-review"
description: "Review Wow use or adoption in downstream application code, diffs, or pull requests for findings, merge readiness, or review-and-fix. Activate only for scoped me.ahoo.wow imports/wow-* dependencies or an explicit downstream Wow review; generic DDD/CQRS or checkout-wide markers do not qualify. Exclude the Wow framework repository, non-Wow scopes, diagnosis, development, and breaking migration/data-cutover review."
---

# Review Wow Changes

## Scope gate

Use only to review a downstream application; the Wow framework repository and its modules never qualify. Require scoped `me.ahoo.wow` imports, `wow-*` dependencies, or an explicit downstream request to review Wow use or introduction. Application scopes without Wow semantics, negated/comparative mentions, and generic DDD/CQRS vocabulary do not qualify. Otherwise state that this Skill does not apply and stop using it.

Own the complete review or review-and-fix task. Do not route to another Wow Skill.

## Contract

- Keep review-only requests read-only. Do not edit files, post or resolve comments, approve, merge, or change remote state without explicit authorization.
- Resolve the actual Wow version from the downstream build and dependency graph before applying exact symbols, defaults, or V9 rules; label version-specific conclusions unverified when the version cannot be confirmed.
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
