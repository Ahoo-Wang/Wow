/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 */
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';
import {
  BEGIN,
  END,
  experimentalOf,
  typescriptDeclarations,
  typescriptPackageDeclarations,
  unmarkedCounterparts,
  withExperimental,
} from './docs-reference-typescript.mjs';

const ROOT = fileURLToPath(new URL('../..', import.meta.url));

test('a TypeScript declaration is experimental when its TSDoc carries @experimental, a member by its owner', () => {
  const source = [
    '/** Stable. */',
    'export interface Stable {',
    '  /** A member. */',
    '  value: number;',
    '}',
    '',
    '/**',
    ' * The query.',
    ' *',
    ' * @experimental',
    ' */',
    'export interface AggregationQuery<',
    '  FIELDS extends string = string,',
    '> {',
    '  /** @experimental */',
    '  limit?: number;',
    '}',
    '',
    '/** An operator. */',
    'export enum FilterOperator {',
    '  /** Equal. */',
    "  EQ = 'eq',",
    '  /**',
    '   * An expression.',
    '   *',
    '   * @experimental',
    '   */',
    "  EXPRESSION = 'expression',",
    '}',
    '',
    '/** The client. */',
    'export class Client {',
    '  /** @experimental */',
    '  async aggregate<ROW>(query: unknown): Promise<ROW[]> {',
    '    /** Not a member: a body. */',
    '    const rows = 1;',
    '  }',
    '}',
    '',
    '/** Builders. */',
    'export const expressionFilters = {',
    '  /** @experimental */',
    '  expression<FIELDS extends string>(value: number) {',
    '    return value;',
    '  },',
    '};',
    '',
    '/** @experimental An overload. */',
    'export function use(options: string): void;',
    '/** The other overload. */',
    'export function use(options: number): void;',
    'export function use(options: unknown): void {}',
    '',
    '// @experimental in a line comment marks nothing.',
    'export type Plain = string;',
  ].join('\n');
  assert.deepEqual(
    typescriptDeclarations(source).filter(({ marked }) => marked),
    [
      { name: 'AggregationQuery', kind: 'interface', marked: true },
      { name: 'AggregationQuery.limit', kind: 'property', marked: true },
      { name: 'FilterOperator.EXPRESSION', kind: 'enum member', marked: true },
      { name: 'Client.aggregate', kind: 'method', marked: true },
      { name: 'filter.expression', kind: 'method', marked: true },
      { name: 'use', kind: 'function', marked: true },
    ],
  );
  const names = typescriptDeclarations(source).map(({ name }) => name);
  assert.ok(names.includes('Stable.value'));
  assert.ok(names.includes('FilterOperator.EQ'));
  assert.ok(names.includes('Plain'));
  assert.ok(!names.some(name => name.endsWith('.rows')));
});

test('a TypeScript counterpart of an experimental Kotlin declaration of the query API must be marked', () => {
  const kotlin = [
    {
      name: 'me.ahoo.wow.api.query.AggregationQuery',
      artifact: 'wow-query-api',
    },
    {
      name: 'me.ahoo.wow.api.query.FilterOperator.EXPRESSION',
      artifact: 'wow-query-api',
    },
    { name: 'me.ahoo.wow.api.query.fields', artifact: 'wow-query-api' },
    // Not a module the TypeScript packages mirror.
    { name: 'me.ahoo.wow.cosec.CoSecIdentity', artifact: 'wow-cosec' },
  ];
  const declarations = [
    { name: 'AggregationQuery', marked: true, entry: 'dsl' },
    { name: 'FilterOperator.EXPRESSION', marked: false, entry: 'dsl' },
    { name: 'FilterOperator.EQ', marked: false, entry: 'dsl' },
    { name: 'CoSecIdentity', marked: false, entry: 'root' },
  ];
  assert.deepEqual(
    unmarkedCounterparts(kotlin, declarations).map(({ name }) => name),
    ['FilterOperator.EXPRESSION'],
  );
});

test('the compatibility page lists the packages’ experimental declarations by the entry that exports them', () => {
  const read = path => readFileSync(join(ROOT, path), 'utf8');
  const experimental = experimentalOf(
    typescriptPackageDeclarations(ROOT, read),
  );
  const named = experimental.map(({ name, entry }) => `${entry} ${name}`);
  for (const expected of [
    '@ahoo-wang/wow-client DatasetClient',
    '@ahoo-wang/wow-client QueryModelClient.aggregate',
    '@ahoo-wang/wow-client/dsl AggregationQuery',
    '@ahoo-wang/wow-client/dsl FilterOperator.EXPRESSION',
    '@ahoo-wang/wow-client/dsl filter.expression',
    '@ahoo-wang/wow-react useAggregateQuery',
  ])
    assert.ok(named.includes(expected), expected);
  // Stable declarations stay out.
  assert.ok(
    !named.some(name =>
      / (?:FilterOperator\.EQ|SnapshotQueryClient|useSingleQuery)$/.test(name),
    ),
  );
  const page = withExperimental(
    `# Page\n\n${BEGIN}\nstale\n${END}\n\nAfter.\n`,
    'en',
    experimental.slice(0, 1),
    'page.md',
  );
  assert.match(
    page,
    /\| `DatasetClient` \| class \| `@ahoo-wang\/wow-client` \|/,
  );
  assert.doesNotMatch(page, /stale/);
  assert.match(page, /After\.\n$/);
  assert.throws(
    () => withExperimental('# Page\n', 'en', [], 'page.md'),
    /page\.md must hold <!-- experimental:begin -->/,
  );
});
