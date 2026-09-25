/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
import assert from 'node:assert/strict';
import { mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import {
  gzippedSize,
  judgeSizes,
  lazyChunks,
  staticClosure,
} from './size-budget.mjs';

/** A built package in a temporary directory, by file name. */
function dist(files) {
  const root = mkdtempSync(join(tmpdir(), 'size-budget-'));
  for (const [file, text] of Object.entries(files))
    writeFileSync(join(root, file), text);
  return root;
}

test('an entry weighs its static imports, transitively, and not its lazy ones', () => {
  const root = dist({
    'index.js': [
      'import { a } from "./a.js";',
      'import {',
      '\tb,',
      '\tc as d',
      '} from "./b.js";',
      'export { e } from "./e.js";',
      'import "react";',
      'const charts = () => import("./charts-X1.js");',
    ].join('\n'),
    'a.js': 'import "./shared.js"; export const a = 1;',
    'b.js': 'import{shared}from"./shared.js";export const b = 2;',
    'e.js': 'export const e = 3;',
    'shared.js': 'export const shared = 4;',
    'charts-X1.js': 'import "./shared.js"; import "./heavy.js";',
    'heavy.js': 'export const heavy = 5;',
  });
  const at = file => join(root, file);

  const files = staticClosure(at('index.js'));

  assert.deepEqual(
    files,
    ['a.js', 'b.js', 'e.js', 'index.js', 'shared.js'].map(at),
  );
  assert.deepEqual(lazyChunks(files), [at('charts-X1.js')]);
  // A lazy chunk weighs what it adds to what its importer already loaded.
  assert.deepEqual(staticClosure(at('charts-X1.js'), new Set(files)), [
    at('charts-X1.js'),
    at('heavy.js'),
  ]);
  assert.ok(gzippedSize(files) > 0);
});

test('a size under its ceiling passes and is summarised', () => {
  const { problems, summary } = judgeSizes({
    packageName: '@ahoo-wang/example',
    budgetFile: join(
      process.cwd(),
      'typescript/example/scripts/size-budget.json',
    ),
    measured: { '.': 1000, './dsl': 500 },
    ceilings: { '.': { gzip: 1200 }, './dsl': { gzip: 500 } },
  });

  assert.deepEqual(problems, []);
  assert.equal(summary, 'root entry 1,000 B of 1,200 B, ./dsl 500 B of 500 B');
});

test('a size over its ceiling names the entry, both numbers and the way to raise it', () => {
  const { problems } = judgeSizes({
    packageName: '@ahoo-wang/example',
    budgetFile: join(
      process.cwd(),
      'typescript/example/scripts/size-budget.json',
    ),
    measured: { './dsl': 1300 },
    ceilings: { './dsl': { gzip: 1000 } },
  });

  assert.equal(problems.length, 1);
  assert.match(problems[0], /@ahoo-wang\/example \.\/dsl is 1,300 B gzipped/);
  assert.match(problems[0], /ceiling of 1,000 B by 300 B \(\+30\.0%\)/);
  assert.match(problems[0], /size-budget\.json in the same pull request/);
  assert.match(problems[0], /"reason"/);
});

test('an entry with no ceiling, and a ceiling with no entry, are both problems', () => {
  const { problems } = judgeSizes({
    packageName: '@ahoo-wang/example',
    budgetFile: join(process.cwd(), 'size-budget.json'),
    measured: { './new': 10 },
    ceilings: { './gone': { gzip: 10 } },
  });

  assert.equal(problems.length, 2);
  assert.match(problems[0], /\.\/new measured 10 B gzipped and has no ceiling/);
  assert.match(problems[1], /holds a ceiling for \.\/gone/);
});
