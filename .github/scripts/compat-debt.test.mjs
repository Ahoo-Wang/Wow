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
  parseLedger,
  unscheduledDeprecations,
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

test('the repository ledger matches its markers', () => {
  assert.deepEqual(ledgerProblems(), []);
});

test('markers are either a v10 removal note or a scoped compat comment', () => {
  assert.ok(hasMarker(deprecated));
  assert.ok(hasMarker(compat));
  assert.ok(hasMarker('# compat(fetcher): old bin name'));
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
