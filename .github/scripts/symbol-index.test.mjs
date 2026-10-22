import assert from 'node:assert/strict';
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import {
  coverageCell,
  curatedCoverage,
  firstSentence,
  itemsOf,
  parseIndex,
  referenceFolder,
  renderIndex,
} from './symbol-index.mjs';

test('a package reference folder is its directory under the English TypeScript reference', () => {
  assert.match(
    referenceFolder('wow-client'),
    /documentation\/docs\/en\/reference\/typescript\/wow-client$/,
  );
});

test('a summary is the first sentence of a doc comment, links read as code', () => {
  assert.equal(
    firstSentence(
      '/**\n * Reads a {@link StateView}. Then more.\n * @param x - y\n */',
    ),
    'Reads a `StateView`.',
  );
  assert.equal(
    firstSentence('/** Ends at `a.b` not there. Next. */'),
    'Ends at `a.b` not there.',
  );
  assert.equal(firstSentence(undefined), '');
});

test('the items of a doc model merge the declarations that share a name', () => {
  const items = itemsOf({
    members: [
      {
        members: [
          { name: 'Stage', kind: 'Variable', docComment: '/** The stages. */' },
          { name: 'Stage_2', kind: 'TypeAlias', docComment: '' },
          { name: 'Alpha', kind: 'Function', docComment: '/** First. */' },
        ],
      },
    ],
  });
  assert.deepEqual(items, [
    { name: 'Alpha', kind: 'function', summary: 'First.' },
    { name: 'Stage', kind: 'const, type', summary: 'The stages.' },
  ]);
});

test('a page names its package, its entry and its API report, and reads back', () => {
  const dir = mkdtempSync(join(tmpdir(), 'symbol-index-'));
  try {
    writeFileSync(
      join(dir, 'host.md'),
      '# Host\n\n## The host {#api-ViewHost}\n',
    );
    const coverage = curatedCoverage(dir);
    assert.equal(
      coverageCell('ViewHost', coverage),
      '[Host](./host#api-ViewHost)',
    );
    const page = renderIndex({
      packageDir: 'wow-react',
      entry: '@ahoo-wang/wow-react',
      report: 'root',
      items: [
        { name: 'ViewHost', kind: 'class', summary: 'Hosts <views> | panes.' },
        { name: 'useListQuery', kind: 'function', summary: '' },
      ],
      coverage,
    });
    assert.match(page, /^title: '@ahoo-wang\/wow-react symbol index'$/m);
    assert.match(page, /typescript\/wow-react\/scripts\/api-report\.mjs/);
    assert.match(page, /`pnpm --filter @ahoo-wang\/wow-react test:api -u`/);
    assert.match(page, /typescript\/wow-react\/test\/api\/root\.api\.md/);
    assert.match(page, /Hosts &lt;views&gt; \\\| panes\./);
    assert.deepEqual(
      [...parseIndex(page)],
      [
        ['ViewHost', '[Host](./host#api-ViewHost)'],
        ['useListQuery', '—'],
      ],
    );
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});
