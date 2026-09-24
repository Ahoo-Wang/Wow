/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { test } from 'node:test';
import { manifestProblems, typeDiagnostics } from './package-check.mjs';
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

test("fetcher's declaration errors are reported apart from ours", () => {
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
