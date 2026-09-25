# Checklist

## Before review

Descriptor:

- [ ] Read from a committed fixture or a development or staging service; production only with the user's consent given in this conversation.
- [ ] Committed beside the definitions, with its `version` named in the test or a comment.

Fields:

- [ ] Every field `name` is a descriptor `path` (or an element field with the matching `scope`, or a variant field); none invented; aliases replaced by their path.
- [ ] Every `operators`, `sortable`, `groups`, `functions`, `dateUnits`, `distinctCount`, `percentile`, `any`, `search`, `paging` and limit is inside what the descriptor grants.
- [ ] No field with `sensitivity` is an analysis group, metric input, expression operand, metric filter, sort, `rowKey` or card title; CONFIDENTIAL fields take no operators and are not searched.
- [ ] No deprecated field, or each one kept on purpose is commented and flagged.
- [ ] `temporal` matches `semantic`; enum options exist in `enum[].value`; constraints respected.

Event streams:

- [ ] `body` is an `elementMatch` field titled by `bodyType`, with business labels for each event type.
- [ ] Every payload condition (system view filter, metric filter) is inside `ELEMENT_MATCH` on `body`, together with its `bodyType` condition.

Wording:

- [ ] Titles, labels, `recordNoun`, options, field groups, system view titles and display names are business words, in every language the host serves.
- [ ] Analysis words follow the engine's vocabulary (维度, 指标, 记录数, 显示名, 前 N 组, 合计行); one word, one meaning.

## Gates

Run each and read its exit code separately:

- The host's definition test: `validateDefinition` returns no error, and an engine with `describe` answering the committed descriptor opens every system view and dashboard with no `error` issue and no avoidable `capability.*` warning.
- The host's unit tests and type check for the changed module.
- Wow repository Storybook, when stories changed: `pnpm --filter wow-storybook typecheck`, `pnpm --filter wow-storybook lint`, and `pnpm --filter wow-storybook test` (interaction tests run in a browser).
- The compensation console, when its views changed: `pnpm test`, `pnpm lint` and `pnpm build` in `compensation/dashboard`.
- Prettier on the changed files.

## Report

- Scenario and audience; the views written or revised.
- Descriptor source (fixture or environment), its `version`, and whether production was read (and the consent).
- What was narrowed from the descriptor and why; fields the scenario wanted that the descriptor lacks, and where they would come from.
- Protected and deprecated fields and how each was handled.
- Each gate with its command and result; anything not run, stated as missing evidence.
