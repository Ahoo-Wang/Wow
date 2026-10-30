/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import {
  addIstanbul,
  addJacoco,
  changedLines,
  ignorePatterns,
  isMainSource,
  patchVerdict,
  reportKind,
  summary,
  TARGET,
  THRESHOLD,
} from './patch-coverage.mjs';

const KT = 'core/kernel/src/main/kotlin/me/ahoo/wow/kernel/A.kt';
const TS = 'typescript/wow-client/src/fetcher.ts';

const line = (nr, mi, ci, mb = 0, cb = 0) =>
  `<line nr="${nr}" mi="${mi}" ci="${ci}" mb="${mb}" cb="${cb}"/>`;
// A report after test/code-coverage-report's rewrite and jacoco-braces.mjs: the
// package is the file's directory, and the closing braces (lines 4, 9) are gone.
const JACOCO =
  '<report name="r"><package name="core/kernel/src/main/kotlin/me/ahoo/wow/kernel">' +
  '<sourcefile name="A.kt">' +
  line(3, 0, 4) + // covered
  line(5, 2, 0) + // missed
  line(6, 0, 3, 1, 1) + // a branch left: partial
  line(7, 0, 0) + // no instruction: not counted
  line(8, 0, 2) +
  '<counter type="LINE" missed="1" covered="3"/></sourcefile></package></report>';

const ROOT = '/home/runner/work/Wow/Wow';
const ISTANBUL = {
  [`${ROOT}/${TS}`]: {
    path: `${ROOT}/${TS}`,
    statementMap: {
      0: { start: { line: 10 } },
      1: { start: { line: 11 } },
      2: { start: { line: 11 } },
      3: { start: { line: 12 } },
      4: { start: { line: 13 } },
    },
    s: { 0: 1, 1: 0, 2: 5, 3: 0, 4: 2 },
    branchMap: {
      0: {
        loc: { start: { line: 13 } },
        locations: [{ start: { line: 13 } }, { start: { line: 13 } }],
      },
    },
    b: { 0: [2, 0] },
  },
};

const DIFF = `diff --git a/${KT} b/${KT}
index 1..2 100644
--- a/${KT}
+++ b/${KT}
@@ -2,0 +3 @@
+    val a = 1
@@ -4,2 +5,4 @@
-    old()
-    }
+    b()
+    if (x) c() else d()
+    // a comment
+    }
diff --git a/core/kernel/src/test/kotlin/ATest.kt b/core/kernel/src/test/kotlin/ATest.kt
--- a/core/kernel/src/test/kotlin/ATest.kt
+++ b/core/kernel/src/test/kotlin/ATest.kt
@@ -1 +1 @@
-x
+y
diff --git a/gone.kt b/gone.kt
deleted file mode 100644
--- a/gone.kt
+++ /dev/null
@@ -1 +0,0 @@
-gone
`;

test('a unified diff gives the lines each file adds or changes', () => {
  const changed = changedLines(DIFF);
  assert.deepEqual([...changed.get(KT)], [3, 5, 6, 7, 8]);
  assert.deepEqual(
    [...changed.get('core/kernel/src/test/kotlin/ATest.kt')],
    [1],
  );
  assert.equal(changed.has('gone.kt'), false);
});

test('main sources are Kotlin and Java main code and the packages’ sources, not tests or types', () => {
  for (const path of [
    KT,
    TS,
    'examples/java-smoke/src/main/java/A.java',
    'typescript/wow-react/src/a.tsx',
  ])
    assert.ok(isMainSource(path), path);
  for (const path of [
    'core/kernel/src/test/kotlin/ATest.kt',
    'typescript/wow-client/src/a.test.ts',
    'typescript/wow-client/src/types.d.ts',
    'typescript/wow-react/src/a.stories.tsx',
    'typescript/wow-client/test/a.ts',
    'typescript/storybook/src/a.ts',
    'README.md',
  ])
    assert.ok(!isMainSource(path), path);
  assert.equal(reportKind(KT), 'jvm');
  assert.equal(reportKind(TS), 'wow-client');
});

test("JaCoCo's lines: covered, missed, partial, and none without instructions", () => {
  const lines = addJacoco(new Map(), JACOCO).get(KT);
  assert.deepEqual(Object.fromEntries(lines), {
    3: 'covered',
    5: 'missed',
    6: 'partial',
    8: 'covered',
  });
});

test("Istanbul's lines: a line's highest statement, partial with an untaken branch", () => {
  const lines = addIstanbul(new Map(), ISTANBUL, ROOT).get(TS);
  assert.deepEqual(Object.fromEntries(lines), {
    10: 'covered',
    11: 'covered',
    12: 'missed',
    13: 'partial',
  });
});

test('a line any report covers is covered', () => {
  const coverage = addJacoco(new Map(), JACOCO);
  addJacoco(
    coverage,
    JACOCO.replace(line(5, 2, 0), line(5, 0, 2)).replace(
      line(3, 0, 4),
      line(3, 4, 0),
    ),
  );
  assert.equal(coverage.get(KT).get(5), 'covered');
  assert.equal(coverage.get(KT).get(3), 'covered');
});

test("codecov.yml's ignore list: paths and globs", () => {
  const patterns = ignorePatterns(`codecov:
  max_report_age: off
ignore:
  # a reason
  - core/spi/src/main/kotlin/me/ahoo/wow/spi/Ddl.kt
  - "examples/**/generated/*.kt"
coverage:
  range: 60..100
`);
  const ignored = path => patterns.some(pattern => pattern.test(path));
  assert.ok(ignored('core/spi/src/main/kotlin/me/ahoo/wow/spi/Ddl.kt'));
  assert.ok(ignored('examples/eshop/a/generated/B.kt'));
  assert.ok(!ignored('examples/eshop/a/generated/c/B.kt'));
  assert.ok(!ignored(KT));
  assert.deepEqual(ignorePatterns('coverage: {}'), []);
  // The repository's own list reads.
  assert.ok(
    ignorePatterns(
      readFileSync(new URL('../../codecov.yml', import.meta.url), 'utf8'),
    ).length > 0,
  );
});

test('the verdict counts the changed lines a report instruments, and names the uncovered ones', () => {
  const verdict = patchVerdict({
    changed: changedLines(DIFF),
    coverage: addJacoco(new Map(), JACOCO),
    kinds: new Set(['jvm']),
  });
  // Lines 3 and 8 covered, 5 missed, 6 partial; 7 (a comment) instruments nothing; the test file is not main code.
  assert.equal(verdict.counted, 4);
  assert.equal(verdict.covered, 2);
  assert.equal(verdict.state, 'fail');
  assert.deepEqual(verdict.uncovered, [`${KT}:5`, `${KT}:6 (partial)`]);
  assert.match(
    verdict.message,
    /patch coverage 50\.00% \(2 of 4 changed lines\), below 89%/,
  );
  assert.match(
    summary(verdict),
    new RegExp(`- \`${KT.replace(/[.]/g, '\\.')}:5\``),
  );
});

test('at least the target less its threshold passes', () => {
  assert.equal(TARGET - THRESHOLD, 89);
  const coverage = new Map([[KT, new Map()]]);
  const changed = new Map([[KT, new Set()]]);
  for (let nr = 1; nr <= 100; nr++) {
    coverage.get(KT).set(nr, nr <= 89 ? 'covered' : 'missed');
    changed.get(KT).add(nr);
  }
  const pass = patchVerdict({ changed, coverage, kinds: new Set(['jvm']) });
  assert.equal(pass.state, 'pass');
  assert.match(
    pass.message,
    /89\.00% \(89 of 100 changed lines\), at least 89%/,
  );
  coverage.get(KT).set(89, 'partial');
  assert.equal(
    patchVerdict({ changed, coverage, kinds: new Set(['jvm']) }).state,
    'fail',
  );
});

test('ignored files and changes no report instruments leave nothing to judge', () => {
  const changed = new Map([
    ['core/spi/src/main/kotlin/me/ahoo/wow/spi/Ddl.kt', new Set([1])],
    [KT, new Set([7])],
  ]);
  const verdict = patchVerdict({
    changed,
    coverage: addJacoco(new Map(), JACOCO),
    kinds: new Set(['jvm']),
    ignored: [/^core\/spi\/src\/main\/kotlin\/me\/ahoo\/wow\/spi\/Ddl\.kt$/],
  });
  assert.equal(verdict.state, 'none');
  assert.deepEqual(verdict.noData, []);
  assert.match(verdict.message, /no changed line that a report instruments/);
});

test('a file no report instruments warns when its kind ran, and fails when none did', () => {
  const annotation =
    'api/api/src/main/kotlin/me/ahoo/wow/api/annotation/Name.kt';
  const changed = new Map([
    [annotation, new Set([3])],
    [KT, new Set([3])],
  ]);
  const coverage = addJacoco(new Map(), JACOCO);
  const warned = patchVerdict({ changed, coverage, kinds: new Set(['jvm']) });
  assert.equal(warned.state, 'pass');
  assert.deepEqual(warned.noData, [annotation]);
  assert.match(summary(warned), /Changed files no report instruments/);
  // A package whose unit job never ran: its tests did not.
  const untested = patchVerdict({
    changed: new Map([...changed, [TS, new Set([10])]]),
    coverage,
    kinds: new Set(['jvm']),
  });
  assert.equal(untested.state, 'fail');
  assert.deepEqual(untested.untested, [TS]);
  assert.match(
    untested.message,
    /no report of their kind ran, so their tests did not: typescript\/wow-client\/src\/fetcher\.ts/,
  );
});

test('ci.yml judges the patch in the coverage job and ci-gate reads its verdict', () => {
  const workflow = readFileSync(
    new URL('../workflows/ci.yml', import.meta.url),
    'utf8',
  );
  const coverage = workflow.slice(
    workflow.indexOf('\n  coverage:\n'),
    workflow.indexOf('\n  coverage-trend:\n'),
  );
  assert.match(coverage, /fetch-depth: 0/);
  // One artifact or many, each report lands in coverage/<artifact>/ (#5202: a single artifact unpacked into coverage/).
  assert.match(
    coverage,
    /pattern: coverage-\*\n\s+path: coverage\n\s+merge-multiple: true\n/,
  );
  const kept = [
    ...workflow.matchAll(
      /ARTIFACT: (coverage-[^\n]+)\n\s+run: mkdir -p "coverage-artifact\/\$ARTIFACT" && cp .+? "coverage-artifact\/\$ARTIFACT\/"\n[\s\S]*?name: (coverage-[^\n]+)\n\s+path: coverage-artifact\/\n/g,
    ),
  ];
  assert.equal(kept.length, 4);
  for (const [, staged, uploaded] of kept) assert.equal(staged, uploaded);
  assert.match(coverage, /BASE=\$\(git merge-base origin\/main "\$HEAD_SHA"\)/);
  assert.match(
    coverage,
    /node \.github\/scripts\/patch-coverage\.mjs coverage/,
  );
  assert.match(coverage, /if: github\.event_name == 'pull_request'\n/);
  assert.ok(
    coverage.indexOf('patch-coverage.mjs') <
      coverage.indexOf('uses: codecov/codecov-action@'),
  );
  assert.match(coverage, /patch: \$\{\{ steps\.patch\.outputs\.patch \}\}/);
  const gate = workflow.slice(workflow.indexOf('\n  ci-gate:\n'));
  assert.match(gate, /PATCH: \$\{\{ needs\.coverage\.outputs\.patch \}\}/);
  assert.match(gate, /if \[ "\$PATCH" = fail \]; then/);
  assert.doesNotMatch(gate, /codecov-action|codecov\/patch|codecov-patch/);
});
