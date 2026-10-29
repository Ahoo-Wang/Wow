/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import { dropBraceLines, isBraceOnly } from './jacoco-braces.mjs';

const SOURCE = [
  'fun f() {', // 1
  '    foo() }', // 2: code and a brace, stays
  '}', // 3: dropped, covered
  '', // 4
  'fun g() {', // 5
  '    // }', // 6: a brace in a comment, stays
  '}', // 7: dropped, missed
  '    val s = "}"', // 8: a brace in a string, stays
  '}', // 9: a brace with a branch, stays
].join('\n');

const counter = (type, missed, covered) =>
  `<counter type="${type}" missed="${missed}" covered="${covered}"/>`;
const line = (nr, mi, ci, mb = 0, cb = 0) =>
  `<line nr="${nr}" mi="${mi}" ci="${ci}" mb="${mb}" cb="${cb}"/>`;

// A report as test/code-coverage-report's rewrite leaves it: the package named by the source file's directory.
const REPORT =
  '<?xml version="1.0" encoding="UTF-8"?><report name="r"><sessioninfo id="s" start="1" dump="2"/>' +
  '<package name="mod/src/main/kotlin/p">' +
  '<class name="mod/src/main/kotlin/p/AKt" sourcefilename="A.kt">' +
  `<method name="f" desc="()V" line="1">${counter('INSTRUCTION', 0, 5)}${counter('LINE', 0, 3)}</method>` +
  `<method name="g" desc="()V" line="5">${counter('INSTRUCTION', 5, 2)}${counter('BRANCH', 1, 1)}${counter('LINE', 3, 2)}</method>` +
  `${counter('INSTRUCTION', 5, 7)}${counter('BRANCH', 1, 1)}${counter('LINE', 3, 5)}` +
  '</class>' +
  '<sourcefile name="A.kt">' +
  line(1, 0, 2) +
  line(2, 0, 2) +
  line(3, 0, 1) +
  line(5, 3, 0) +
  line(6, 0, 1) +
  line(7, 1, 0) +
  line(8, 0, 1) +
  line(9, 1, 1, 1, 1) +
  `${counter('INSTRUCTION', 5, 7)}${counter('BRANCH', 1, 1)}${counter('LINE', 3, 5)}` +
  '</sourcefile>' +
  `${counter('INSTRUCTION', 5, 7)}${counter('BRANCH', 1, 1)}${counter('LINE', 3, 5)}` +
  '</package>' +
  `${counter('INSTRUCTION', 5, 7)}${counter('BRANCH', 1, 1)}${counter('LINE', 3, 5)}` +
  '</report>';

const read = path => (path === 'mod/src/main/kotlin/p/A.kt' ? SOURCE : null);

test('a closing brace alone is the only line dropped', () => {
  assert.equal(isBraceOnly('    }'), true);
  for (const text of ['foo() }', '// }', 'val s = "}"', '},', '})', '{'])
    assert.equal(isBraceOnly(text), false, text);
});

test('the brace lines go, their method, class, file, package and report counters with them', () => {
  const { xml, dropped } = dropBraceLines(REPORT, read);
  assert.equal(dropped, 2);
  const lines = [...xml.matchAll(/<line nr="(\d+)"/g)].map(match =>
    Number(match[1]),
  );
  assert.deepEqual(lines, [1, 2, 5, 6, 8, 9]);
  // f lost line 3 (covered, one instruction), g line 7 (missed, one instruction); the branch counters stay.
  assert.match(
    xml,
    new RegExp(
      `<method name="f" desc="\\(\\)V" line="1">${counter('INSTRUCTION', 0, 4)}${counter('LINE', 0, 2)}</method>`,
    ),
  );
  assert.match(
    xml,
    new RegExp(
      `<method name="g" desc="\\(\\)V" line="5">${counter('INSTRUCTION', 4, 2)}${counter('BRANCH', 1, 1)}${counter('LINE', 2, 2)}</method>`,
    ),
  );
  const totals = `${counter('INSTRUCTION', 4, 6)}${counter('BRANCH', 1, 1)}${counter('LINE', 2, 4)}`;
  for (const closing of [
    '</class>',
    '</sourcefile>',
    '</package>',
    '</report>',
  ])
    assert.ok(xml.includes(`${totals}${closing}`), closing);
});

test('a source file it cannot read keeps its lines', () => {
  const { xml, dropped } = dropBraceLines(REPORT, () => null);
  assert.equal(dropped, 0);
  assert.equal(xml, REPORT);
});

test('ci.yml drops the brace lines from each JVM report right before it keeps it for the coverage job', () => {
  const workflow = readFileSync(
    new URL('../workflows/ci.yml', import.meta.url),
    'utf8',
  );
  for (const report of ['coverageReport', 'integrationCoverageReport']) {
    const path = `./test/code-coverage-report/build/reports/jacoco/${report}/${report}.xml`;
    const drop = workflow.indexOf(
      `node .github/scripts/jacoco-braces.mjs ${path}`,
    );
    const keep = workflow.indexOf(`path: ${path}`);
    assert.ok(drop > 0, `${report} is normalized`);
    assert.ok(drop < keep, `${report} is normalized before it is kept`);
  }
  // Every Codecov upload names its files and searches for no other.
  const uploads = workflow.split('uses: codecov/codecov-action@').slice(1);
  for (const step of uploads.filter(
    it => !/run_command: send-notifications/.test(it.split('\n      - ')[0]),
  )) {
    const inputs = step.split('\n      - ')[0];
    assert.match(inputs, /disable_search: true/);
    assert.match(inputs, /files: /);
  }
});
