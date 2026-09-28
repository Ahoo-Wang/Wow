/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
import assert from 'node:assert/strict';
import { mkdirSync, mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { test } from 'node:test';
import {
  hasMarker,
  ledgerProblems,
  kotlinSourceFiles,
  parseLedger,
  unscheduledDeprecations,
  unscheduledKotlinDeprecations,
} from './compat-debt.mjs';

function repository(files) {
  const root = mkdtempSync(join(tmpdir(), 'compat-debt-'));
  for (const [path, text] of Object.entries(files)) {
    mkdirSync(dirname(join(root, path)), { recursive: true });
    writeFileSync(join(root, path), text);
  }
  return root;
}

const ledger = (...entries) =>
  [
    '# Compatibility Debt',
    '',
    '## Marker Rules',
    '',
    '- **Markers**: `typescript/ignored/src/rules.ts`',
    '',
    '## Entries',
    '',
    ...entries.flatMap(([title, files]) => [
      `### ${title}`,
      '',
      '- **Kept compatible**: something',
      `- **Markers**: ${files.map(file => `\`${file}\``).join(', ')}`,
      '',
    ]),
  ].join('\n');

const deprecated =
  '/** @deprecated Use next instead. Removed in v10. */\nexport const old = 1;\n';
const compat =
  '// compat(wow<9): servers before 8.11 need this.\nexport const legacy = 1;\n';
const kotlinDeprecated =
  '@Deprecated("Scheduled for removal in 10.0.0. Use next.")\nfun old() = Unit\n';

test('the repository ledger matches its markers', () => {
  assert.deepEqual(ledgerProblems(), []);
});

test('markers are either a v10 removal note or a scoped compat comment', () => {
  assert.ok(hasMarker(deprecated));
  assert.ok(hasMarker(compat));
  assert.ok(hasMarker('# compat(fetcher): old bin name'));
  assert.ok(hasMarker(kotlinDeprecated));
  assert.ok(!hasMarker('// compat(wow<9):'));
  assert.ok(!hasMarker('/** @deprecated Use next instead. */'));
  assert.ok(!hasMarker('const compatible = true;'));
});

test('every @deprecated comment must say it is removed in v10', () => {
  const text = [
    '/**',
    ' * Old.',
    ' * @deprecated Use next instead.',
    ' */',
    'export const a = 1;',
    '/** @deprecated Use b instead. Removed in v10. */',
    'export const b = 1;',
    '/** Not deprecated. */',
    '/** @deprecated Use c instead. */',
  ].join('\n');
  assert.deepEqual(unscheduledDeprecations(text), [3, 9]);
});

test('every Kotlin @Deprecated must say it is scheduled for removal in 10.0.0', () => {
  const text = [
    '@Deprecated("Use next.")',
    'fun a() = Unit',
    '@Deprecated("Scheduled for removal in 10.0.0. Use next.")',
    'fun b() = Unit',
    '/** `@Deprecated` in prose is no annotation. */',
    'val generated = "@Deprecated(\\"generated\\")"',
    '@Deprecated(message = "Use \\"next\\".")',
    'fun c() = Unit',
  ].join('\n');
  assert.deepEqual(unscheduledKotlinDeprecations(text), [1, 7]);
});

test('Kotlin sources are the main source sets, outside build output and dot-directories', () => {
  const root = repository({
    'wow-a/src/main/kotlin/me/A.kt': 'class A\n',
    'test/wow-b/src/main/kotlin/me/B.kt': 'class B\n',
    'wow-a/src/test/kotlin/me/ATest.kt': 'class ATest\n',
    'wow-a/src/main/java/me/J.kt': 'class J\n',
    'wow-a/build/src/main/kotlin/me/Gen.kt': 'class Gen\n',
    '.claude/worktrees/x/wow-a/src/main/kotlin/me/A.kt': 'class A\n',
    'node_modules/pkg/src/main/kotlin/me/N.kt': 'class N\n',
  });
  assert.deepEqual(kotlinSourceFiles(root), [
    'test/wow-b/src/main/kotlin/me/B.kt',
    'wow-a/src/main/kotlin/me/A.kt',
  ]);
});

test('entries are ### headings under the ledger with their Markers files', () => {
  assert.deepEqual(
    parseLedger(ledger(['A', ['x/a.ts', 'x/b.ts']], ['B', []])),
    [
      { title: 'A', files: ['x/a.ts', 'x/b.ts'] },
      { title: 'B', files: [] },
    ],
  );
});

test('a marked source file must be listed, and listed files must hold markers', () => {
  const clean = repository({
    'docs/compat-debt.md': ledger([
      'Legacy',
      ['typescript/pkg/src/a.ts', 'typescript/pkg/test/b.test.ts'],
    ]),
    'typescript/pkg/src/a.ts': deprecated,
    'typescript/pkg/src/plain.ts': 'export const plain = 1;\n',
    'typescript/pkg/test/b.test.ts': compat,
    'typescript/other/package.json': '{}',
  });
  assert.deepEqual(ledgerProblems(clean), []);

  const kotlin = repository({
    'docs/compat-debt.md': ledger([
      'Kotlin',
      ['wow-a/src/main/kotlin/me/Old.kt', 'wow-a/src/main/kotlin/me/Wire.kt'],
    ]),
    'typescript/pkg/src/plain.ts': 'export const plain = 1;\n',
    'wow-a/src/main/kotlin/me/Old.kt': kotlinDeprecated,
    'wow-a/src/main/kotlin/me/Wire.kt':
      '// compat(wow<9): legacy bodies.\nval legacy = 1\n',
    'wow-a/src/main/kotlin/me/Unlisted.kt': kotlinDeprecated,
    'wow-a/src/main/kotlin/me/Unscheduled.kt':
      '\n@Deprecated("Use next.")\nfun old() = Unit\n',
  });
  assert.deepEqual(ledgerProblems(kotlin), [
    'wow-a/src/main/kotlin/me/Unlisted.kt: has compatibility markers but no docs/compat-debt.md entry lists it',
    'wow-a/src/main/kotlin/me/Unscheduled.kt:2: @Deprecated without "Scheduled for removal in 10.0.0."',
  ]);

  const broken = repository({
    'docs/compat-debt.md': ledger(
      ['Legacy', ['typescript/pkg/src/a.ts', 'typescript/pkg/src/gone.ts']],
      ['Stale', ['typescript/pkg/src/plain.ts']],
      ['Empty', []],
    ),
    'typescript/pkg/src/a.ts': deprecated,
    'typescript/pkg/src/plain.ts': 'export const plain = 1;\n',
    'typescript/pkg/src/unlisted.ts': compat,
    'typescript/pkg/src/nested/unscheduled.ts':
      '/** @deprecated Use next instead. */\nexport const x = 1;\n',
  });
  assert.deepEqual(ledgerProblems(broken), [
    'typescript/pkg/src/nested/unscheduled.ts:1: @deprecated without "Removed in v10."',
    'typescript/pkg/src/unlisted.ts: has compatibility markers but no docs/compat-debt.md entry lists it',
    'docs/compat-debt.md "Legacy": typescript/pkg/src/gone.ts does not exist',
    'docs/compat-debt.md "Stale": typescript/pkg/src/plain.ts holds no compatibility marker',
    'docs/compat-debt.md "Empty": lists no marked file',
  ]);
});
