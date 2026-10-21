/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
import assert from 'node:assert/strict';
import { mkdirSync, mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { trackedFiles } from './docs-layout.mjs';
import { isSampled, sampleProblems, sampleText } from './docs-samples.mjs';

const root = mkdtempSync(join(tmpdir(), 'docs-samples-'));
mkdirSync(join(root, 'src'), { recursive: true });
writeFileSync(
  join(root, 'src/Account.kt'),
  `/*
 * License.
 */

package bank

class Account {
    // region open
    fun open() {
        // region inner
        state = OPEN
        // endregion
    }
    // endregion
}
`,
);

const page = (...lines) => lines.join('\n');

test('a whole-file sample shows the file without its license header and region markers', () => {
  assert.equal(
    sampleText(
      '/*\n * License.\n */\n\npackage a\n// region x\nval x = 1\n// endregion\n',
    ),
    'package a\nval x = 1',
  );
});

test('a region sample shows the region without its common indentation', () => {
  assert.equal(
    sampleText(
      'class A {\n    // region open\n    fun open() {\n        // region inner\n        x()\n        // endregion\n    }\n    // endregion\n}',
      'open',
    ),
    'fun open() {\n    x()\n}',
  );
  assert.equal(
    sampleText('# region keys\nwow:\n  service: bank\n# endregion\n', 'keys'),
    'wow:\n  service: bank',
  );
  assert.equal(sampleText('val a = 1', 'missing'), undefined);
});

test('a sample equal to its source passes', () => {
  const text = page(
    '<!-- sample: src/Account.kt#open -->',
    '',
    '```kotlin',
    'fun open() {',
    '    state = OPEN',
    '}',
    '```',
  );
  assert.deepEqual(sampleProblems('p.md', text, root), []);
});

test('a sample that drifted from its source fails', () => {
  const text = page(
    '<!-- sample: src/Account.kt#open -->',
    '```kotlin',
    'fun open() = Unit',
    '```',
  );
  assert.match(
    sampleProblems('p.md', text, root)[0],
    /^p\.md:2: the sample differs from src\/Account\.kt#open/,
  );
});

test('a sample of a missing file or region fails', () => {
  assert.match(
    sampleProblems(
      'p.md',
      page('<!-- sample: src/Gone.kt -->', '```kotlin', 'x', '```'),
      root,
    )[0],
    /src\/Gone\.kt does not exist/,
  );
  assert.match(
    sampleProblems(
      'p.md',
      page('<!-- sample: src/Account.kt#close -->', '```kotlin', 'x', '```'),
      root,
    )[0],
    /has no region close/,
  );
});

test('a whole Kotlin or Java file must be a sample or say it is a fragment', () => {
  const whole = page('```kotlin', 'package bank', '', 'class Account', '```');
  assert.match(
    sampleProblems('p.md', whole, root)[0],
    /^p\.md:1: a kotlin block with a package or import line is a whole file/,
  );
  assert.match(
    sampleProblems('p.md', page('```java', 'import a.B;', '```'), root)[0],
    /a java block/,
  );
  assert.deepEqual(
    sampleProblems('p.md', page('<!-- sample: fragment -->', '', whole), root),
    [],
  );
  // A snippet without package or import lines, and other languages, are free.
  assert.deepEqual(
    sampleProblems(
      'p.md',
      page(
        '```kotlin',
        'val x = 1',
        '```',
        '```ts',
        "import {a} from 'b'",
        '```',
      ),
      root,
    ),
    [],
  );
});

test('a sample comment applies only to the block right after it', () => {
  const text = page(
    '<!-- sample: fragment -->',
    'Some prose.',
    '',
    '```kotlin',
    'import a.B',
    '```',
  );
  assert.match(sampleProblems('p.md', text, root)[0], /is a whole file/);
});

test('the check reads the pages, the root READMEs and the Skills, not their evals', () => {
  assert.ok(isSampled('documentation/docs/en/guide/v10/quickstart.md'));
  assert.ok(isSampled('README.zh-CN.md'));
  assert.ok(isSampled('skills/wow-develop/references/v10-service.md'));
  assert.ok(
    !isSampled('skills/wow-develop/evals/a01-develop-aggregate/prompt.md'),
  );
  assert.ok(!isSampled('documentation/designs/v10/README.md'));
  assert.ok(!isSampled('typescript/wow-client/README.md'));
  assert.ok(trackedFiles().some(isSampled));
});
