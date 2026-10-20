/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
import assert from 'node:assert/strict';
import { test } from 'node:test';
import { layoutProblems, trackedFiles } from './docs-layout.mjs';

const allowed = [
  'README.md',
  'README.zh-CN.md',
  'CONTRIBUTING.md',
  'AGENTS.md',
  'typescript/wow-client/CLAUDE.md',
  'wow-core/README.zh-CN.md',
  'documentation/docs/en/guide/index.md',
  'documentation/designs/2026-10-09-wow-bi-refactor-design.md',
  'documentation/designs/v10/README.md',
  'typescript/wow-view-engine/docs/design/ui/theme.md',
  'typescript/storybook/docs/scenarios.md',
  'docs/todo.md',
  'example/eshop/DX-GAPS.md',
  'typescript/TODO.md',
  'typescript/RELEASING.md',
  'typescript/release-notes/v9.8.0.md',
  'test/benchmarks/results/reports/quick-webflux.md',
  'skills/wow-client/references/api.md',
  '.claude/skills/shadcn/SKILL.md',
  'typescript/wow-client/test/api/root.api.md',
  'typescript/wow-view-engine/THIRD_PARTY_NOTICES.md',
  '.github/ISSUE_TEMPLATE/bug_report.md',
  '.github/release-notes/v10-prerelease.md',
  'documentation/diagrams/Event-Compensation-UserCase.puml',
  'documentation/docs/public/images/logo.svg',
  'typescript/storybook/stories/view-engine/Intro.mdx',
];

test('the canonical locations pass', () => {
  assert.deepEqual(layoutProblems(allowed), []);
});

test('nothing may be tracked under the retired trees', () => {
  const problems = layoutProblems([
    'document/design/assets/logo.svg',
    'docs/superpowers/specs/a.md',
  ]);
  assert.equal(problems.length, 2);
  assert.match(
    problems[0],
    /^document\/design\/assets\/logo\.svg: document\/ is retired/,
  );
  assert.match(
    problems[1],
    /^docs\/superpowers\/specs\/a\.md: docs\/superpowers\/ is retired/,
  );
});

test('Markdown outside the canonical locations fails', () => {
  for (const path of [
    'NOTES.md',
    'docs/plan.md',
    'typescript/docs/review.md',
    'typescript/release-notes/archive/v9.2.0.md',
    'typescript/storybook/docs/review2/r1.md',
    'platform/ops/console/design-qa.md',
    'documentation/designs/archive/old-design.md',
    'documentation/designs/v10/notes/a.md',
    'typescript/wow-client/docs/superpowers/plans/a.md',
    'wow-core/src/main/kotlin/NOTES.md',
  ])
    assert.match(
      layoutProblems([path]).join('\n'),
      /Markdown outside the canonical locations/,
      path,
    );
});

test('PlantUML lives only under documentation/diagrams/', () => {
  assert.deepEqual(
    layoutProblems(['documentation/diagrams/use-case.puml']),
    [],
  );
  assert.match(
    layoutProblems(['documentation/docs/public/flow.puml'])[0],
    /PlantUML belongs under documentation\/diagrams\//,
  );
});

test('a rendered SVG beside its diagram source fails', () => {
  const problems = layoutProblems([
    'documentation/diagrams/use-case.puml',
    'documentation/diagrams/use-case.svg',
    'documentation/docs/public/images/flow.mmd',
    'documentation/docs/public/images/flow.svg',
    'documentation/docs/public/images/logo.svg',
  ]);
  assert.deepEqual(problems, [
    'documentation/diagrams/use-case.svg: rendered beside its diagram source; commit only the source',
    'documentation/docs/public/images/flow.svg: rendered beside its diagram source; commit only the source',
  ]);
});

test('the repository follows the layout', () => {
  assert.deepEqual(layoutProblems(trackedFiles()), []);
});
