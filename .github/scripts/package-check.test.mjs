/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { test } from 'node:test';
import {
  ALLOWED_FETCHER_DIAGNOSTICS,
  ENTRIES,
  STYLESHEETS,
  TYPESCRIPT_VERSIONS,
  annotation,
  bins,
  applyAllowance,
  distTagProblems,
  manifestProblems,
  typeDiagnostics,
  waitForRegistry,
} from './package-check.mjs';
import { ROOT } from './project-version.mjs';
import { HELD_BACK, PUBLISHED } from './publish-npm.mjs';

const manifest = dir =>
  JSON.parse(readFileSync(join(ROOT, dir, 'package.json'), 'utf8'));

test('the public packages take peers from catalog:peers and the root engines', () => {
  assert.deepEqual(
    manifestProblems(
      Object.fromEntries(
        [...PUBLISHED, ...HELD_BACK].map(dir => [dir, manifest(dir)]),
      ),
      manifest('.').engines.node,
    ),
    [],
  );
  assert.equal(manifest('.').engines.node, '>=22.12.0');
});

test('the consumer smoke reaches every entry, style sheet and bin of every published package', () => {
  const entries = ENTRIES.map(([entry]) => entry);
  for (const dir of PUBLISHED) {
    const { name, exports, bin } = manifest(dir);
    for (const [subpath, target] of Object.entries(exports)) {
      if (subpath === './package.json') continue;
      const specifier = subpath === '.' ? name : `${name}${subpath.slice(1)}`;
      if (typeof target === 'string' && target.endsWith('.css')) {
        // A pattern (`./themes/*.css`) is checked through one of its files.
        const covered = subpath.includes('*')
          ? STYLESHEETS.some(sheet =>
              sheet.startsWith(specifier.slice(0, specifier.indexOf('*'))),
            )
          : STYLESHEETS.includes(specifier);
        assert.ok(covered, `${specifier}: no style sheet check`);
      } else assert.ok(entries.includes(specifier), `${specifier}: no import`);
    }
    for (const command of Object.keys(bin ?? {}))
      assert.ok(
        bins('9.2.0').some(({ bin: checked }) => checked === command),
        `${name}: bin ${command} is never run`,
      );
  }
  for (const entry of entries)
    assert.ok(
      PUBLISHED.some(dir => entry.startsWith(manifest(dir).name)),
      `${entry}: not a published package`,
    );
});

test('consumers compile on the TypeScript floor and the latest 7.x, as the docs say', () => {
  assert.deepEqual(TYPESCRIPT_VERSIONS, { '6.0': '~6.0.0', '7.x': '^7.0.0' });
  // The support range is stated, not declared: no package has a typescript peer.
  for (const dir of [...PUBLISHED, ...HELD_BACK])
    assert.equal(manifest(dir).peerDependencies?.typescript, undefined, dir);
  const compatibility = locale =>
    readFileSync(
      join(
        ROOT,
        `documentation/docs/${locale}/guide/typescript/compatibility.md`,
      ),
      'utf8',
    );
  assert.match(
    compatibility('en'),
    /\| TypeScript \| `>=6\.0` \| .*6\.0 and the latest 7\.x/,
  );
  assert.match(
    compatibility('zh'),
    /\| TypeScript \| `>=6\.0` \| .*6\.0 和最新的 7\.x/,
  );
});

test('a dev catalog peer or a stray engines range is a problem', () => {
  assert.deepEqual(
    manifestProblems(
      {
        'typescript/wow-react': {
          engines: { node: '>=18.20.8' },
          peerDependencies: {
            react: 'catalog:',
            '@ahoo-wang/wow-client': 'workspace:~',
            '@ahoo-wang/fetcher': 'catalog:peers',
          },
        },
      },
      '>=22.12.0',
    ),
    [
      'typescript/wow-react: peer react is catalog:; use catalog:peers (or workspace:~)',
      'typescript/wow-react: engines.node is >=18.20.8; the workspace requires >=22.12.0',
    ],
  );
});

test("fetcher's declaration errors are labelled apart from ours", () => {
  const output = [
    "node_modules/@ahoo-wang/fetcher-openapi/dist/index.d.ts(4,15): error TS2834: Relative import paths need explicit file extensions in ECMAScript imports when '--moduleResolution' is 'node16' or 'nodenext'.",
    "node_modules/@ahoo-wang/wow-client/dist/command/types.d.cts(2,49): error TS1479: The current file is a CommonJS module whose imports will produce 'require' calls; however, the referenced file is an ECMAScript module and cannot be imported with 'require'. Consider writing a dynamic 'import(\"@ahoo-wang/fetcher\")' call instead.",
    "node_modules/@ahoo-wang/wow-generator/dist/utils/parsers.d.ts(1,10): error TS2305: Module '\"@ahoo-wang/fetcher-openapi\"' has no exported member 'OpenAPI'.",
    "node_modules/@ahoo-wang/wow-generator/dist/index.d.ts(2,34): error TS2834: Relative import paths need explicit file extensions in ECMAScript imports when '--moduleResolution' is 'node16' or 'nodenext'.",
    "client.cts(5,1): error TS2578: Unused '@ts-expect-error' directive.",
    '  a continuation line',
    '',
  ].join('\n');
  const { ours, upstream } = typeDiagnostics(output);
  assert.equal(upstream.length, 3);
  assert.deepEqual(ours, [
    "node_modules/@ahoo-wang/wow-generator/dist/index.d.ts(2,34): error TS2834: Relative import paths need explicit file extensions in ECMAScript imports when '--moduleResolution' is 'node16' or 'nodenext'.",
    "client.cts(5,1): error TS2578: Unused '@ts-expect-error' directive.\n  a continuation line",
  ]);
  assert.deepEqual(typeDiagnostics(''), { ours: [], upstream: [] });
});

test('the smoke test waits for the registry, then stops', async () => {
  const served = { '@ahoo-wang/wow-client': 1, '@ahoo-wang/wow-react': 3 };
  const asked = {};
  const slept = [];
  const logs = [];
  await waitForRegistry(Object.keys(served), '9.2.0', {
    attempts: 5,
    delayMs: 10,
    published: name => (asked[name] = (asked[name] ?? 0) + 1) >= served[name],
    sleep: async ms => slept.push(ms),
    log: line => logs.push(line),
  });
  // A package already served is not asked again.
  assert.deepEqual(asked, {
    '@ahoo-wang/wow-client': 1,
    '@ahoo-wang/wow-react': 3,
  });
  assert.deepEqual(slept, [10, 10]);
  assert.equal(
    logs.at(-1),
    'registry: serves @ahoo-wang/wow-client@9.2.0, @ahoo-wang/wow-react@9.2.0',
  );
});

test('the smoke test gives up after a bounded wait and names what is missing', async () => {
  let sleeps = 0;
  await assert.rejects(
    waitForRegistry(
      ['@ahoo-wang/wow-client', '@ahoo-wang/wow-generator'],
      '9.2.0',
      {
        attempts: 3,
        delayMs: 15_000,
        published: name => {
          if (name === '@ahoo-wang/wow-generator')
            throw Object.assign(new Error('npm view failed'), {
              stderr: 'npm error code E503\nnpm error Service Unavailable',
            });
          return false;
        },
        sleep: async () => sleeps++,
        log: () => {},
      },
    ),
    {
      message:
        'the registry does not serve @ahoo-wang/wow-client@9.2.0, @ahoo-wang/wow-generator@9.2.0 after 3 attempts 15s apart\n  @ahoo-wang/wow-generator: npm error code E503',
    },
  );
  assert.equal(sleeps, 2);
});

test('a dist-tag that is missing or points elsewhere is a problem', () => {
  assert.deepEqual(
    distTagProblems(
      {
        '@ahoo-wang/wow-client': { latest: '9.2.0', next: '9.2.0-rc.0' },
        '@ahoo-wang/wow-react': { latest: '9.1.5' },
        '@ahoo-wang/wow-generator': { next: '9.2.0-rc.0' },
      },
      'latest',
      '9.2.0',
    ),
    [
      '@ahoo-wang/wow-react: dist-tag latest is 9.1.5, not 9.2.0',
      '@ahoo-wang/wow-generator: dist-tag latest is missing, not 9.2.0',
    ],
  );
});

test('the package job builds every published package before checking them', () => {
  const workflow = readFileSync(
    join(ROOT, '.github/workflows/typescript.yml'),
    'utf8',
  );
  const job = /^ {2}package:\n(?:(?: {4}.*)?\n)+/m.exec(workflow)?.[0];
  assert.ok(job, 'typescript.yml has no package job');
  // The build takes its packages from PUBLISHED, the list the check reads,
  // so a package added there cannot reach the check unbuilt.
  const build = job.indexOf('node .github/scripts/publish-npm.mjs --list');
  const check = job.indexOf('node .github/scripts/package-check.mjs');
  assert.ok(build >= 0, 'the build does not read publish-npm.mjs --list');
  assert.ok(check > build, 'the check runs before the build');
  assert.match(job, /--filter "\$pkg\.\.\."/);
  assert.doesNotMatch(job, /--filter @ahoo-wang\//);
});

test('a failure becomes one GitHub annotation that keeps its lines', () => {
  assert.equal(
    annotation('package check failed:\n  100% broken\r'),
    '::error title=package check::package check failed:%0A  100%25 broken%0D',
  );
});

test('the release workflow smoke-tests npm after npm-deploy, with pinned actions', () => {
  const workflow = readFileSync(
    join(ROOT, '.github/workflows/package-deploy.yml'),
    'utf8',
  );
  const job = /^ {2}npm-smoke:\n(?:(?: {4}.*)?\n)+/m.exec(workflow)?.[0];
  assert.ok(job, 'package-deploy.yml has no npm-smoke job');
  assert.match(job, /^ {4}needs: npm-deploy$/m);
  assert.match(job, /node \.github\/scripts\/package-check\.mjs --registry/);
  assert.doesNotMatch(job, /id-token|secrets\./);
  for (const [, action] of workflow.matchAll(/uses: (\S+)/g))
    assert.match(action, /@[0-9a-f]{40}$/, `${action} is not pinned to a SHA`);
});

test('npm-deploy publishes only after both Maven publications succeed', () => {
  const workflow = readFileSync(
    join(ROOT, '.github/workflows/package-deploy.yml'),
    'utf8',
  );
  const job = /^ {2}npm-deploy:\n(?:(?: {4}.*)?\n)+/m.exec(workflow)?.[0];
  assert.ok(job, 'package-deploy.yml has no npm-deploy job');
  // No reviewer gates the npm-publish environment: this ordering is what
  // keeps a release from reaching npm without its JVM artifacts.
  assert.match(
    job,
    /^ {4}needs: \[preflight, github-deploy, central-deploy\]$/m,
  );
  // npm's trusted publisher is bound to this environment.
  assert.match(job, /^ {4}environment: npm-publish$/m);
});

const responses = 'node_modules/@ahoo-wang/fetcher-eventstream/dist/responses';
const duplicate = (extension, identifier) =>
  `${responses}${extension}(13,13): error TS2300: Duplicate identifier '${identifier}'.`;
const known = ['.d.ts', '.d.cts'].flatMap(extension =>
  ['contentType', 'isEventStream'].map(identifier =>
    duplicate(extension, identifier),
  ),
);

// The shape of an allowance, as 5.1.4 needed before the floor moved to 5.1.5.
const sample = ['node16', 'nodenext'].flatMap(mode =>
  ['.d.ts', '.d.cts'].flatMap(extension =>
    ['contentType', 'isEventStream'].map(identifier => ({
      mode,
      code: 'TS2300',
      file: `${responses}${extension}`,
      identifier,
    })),
  ),
);

test('no fetcher diagnostic is allowed at the current floor', () => {
  assert.deepEqual(ALLOWED_FETCHER_DIAGNOSTICS, []);
  assert.deepEqual(applyAllowance('node16', known).rest, known);
});

test('an allowance lets through exactly the diagnostics it names', () => {
  assert.deepEqual(applyAllowance('node16', known, sample), {
    allowed: 4,
    rest: [],
    stale: [],
  });
  const other = [
    // Another identifier, another code, another file: none is allowed.
    duplicate('.d.ts', 'eventStream'),
    `${responses}.d.ts(13,13): error TS2717: Duplicate identifier 'contentType'.`,
    "node_modules/@ahoo-wang/fetcher/dist/index.d.cts(1,1): error TS2300: Duplicate identifier 'contentType'.",
  ];
  assert.deepEqual(
    applyAllowance('nodenext', [...known, ...other], sample).rest,
    other,
  );
});

test('an allowance that matches nothing is stale', () => {
  const { allowed, rest, stale } = applyAllowance(
    'node16',
    known.slice(1),
    sample,
  );
  assert.equal(allowed, 3);
  assert.deepEqual(rest, []);
  assert.deepEqual(stale, [
    `TS2300 contentType in ${responses}.d.ts no longer appears; remove its allowance`,
  ]);
  // bundler compiles ESM only and has no allowance.
  assert.deepEqual(applyAllowance('bundler', []), {
    allowed: 0,
    rest: [],
    stale: [],
  });
  assert.deepEqual(applyAllowance('bundler', known).rest, known);
});
