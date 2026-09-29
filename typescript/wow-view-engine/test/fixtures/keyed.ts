/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import {
  MemoryViewStore,
  ViewEngine,
  systemInstanceId,
  text,
  type DashboardDefinition,
  type DataViewDefinition,
} from '../../src/index.js';
import {
  analysisConfig,
  dashboardConfig,
  namedOrdersDefinition,
  overviewDefinition,
  recordConfig,
  testSource,
} from '../fixtures.js';

/** Every member a definition, a view or a board writes words in. */
const LABELS = new Set(['label', 'title', 'description', 'content', 'alt']);

/** A card's `title` names a field, not words. */
const NOT_WORDS = new Set(['card']);

/**
 * `value` with every label it carries written as a key (`text(key)`), and
 * the words of each key put in `words` as `say(original)`: a definition, a
 * view or a board as a host of several languages declares it. A surface
 * drawn from the keyed value under `words` shows what `say` makes of the
 * literal one.
 */
export function keyed<T>(
  value: T,
  words: Record<string, string>,
  prefix: string,
  say: (original: string) => string = original => original,
): T {
  let next = 0;
  const walk = (node: unknown, parent: string): unknown => {
    if (Array.isArray(node)) return node.map(entry => walk(entry, parent));
    if (node === null || typeof node !== 'object') return node;
    const out: Record<string, unknown> = {};
    for (const [key, entry] of Object.entries(node)) {
      if (
        (LABELS.has(key) || (key === 'message' && parent === 'deprecated')) &&
        !NOT_WORDS.has(parent) &&
        typeof entry === 'string' &&
        entry !== ''
      ) {
        const name = `${prefix}.${next++}`;
        words[name] = say(entry);
        out[key] = text(name);
      } else out[key] = walk(entry, key);
    }
    return out;
  };
  return walk(value, '') as T;
}

/** Where a key's marker shows in `root`: its text, its attributes, its fields' values. */
export function markersIn(root: ParentNode): string[] {
  const found: string[] = [];
  const marked = (value: string | null | undefined) =>
    value != null && /[]/.test(value);
  const all = [
    ...(root instanceof Element ? [root] : []),
    ...root.querySelectorAll('*'),
  ];
  for (const element of all) {
    for (const attribute of element.attributes)
      if (marked(attribute.value))
        found.push(`${element.tagName}[${attribute.name}]=${attribute.value}`);
    for (const child of element.childNodes)
      if (child.nodeType === 3 && marked(child.textContent))
        found.push(`${element.tagName}: ${child.textContent}`);
    if (
      (element instanceof HTMLInputElement ||
        element instanceof HTMLTextAreaElement) &&
      marked(element.value)
    )
      found.push(`${element.tagName}.value=${element.value}`);
  }
  if (marked(document.title)) found.push(`document.title=${document.title}`);
  return found;
}

/** The words of every key the keyed orders and board use, in English. */
export const EN: Record<string, string> = {};
/** The same keys' words in a second language: `中:` before the English. */
export const ZH: Record<string, string> = {};

/**
 * The orders, every label a key: the definition, its views, their configs
 * — a chart of each family that names things (bar, line, pie, funnel).
 */
export function keyedOrders(): DataViewDefinition {
  const base = namedOrdersDefinition();
  const literal: DataViewDefinition = {
    ...base,
    title: 'Orders',
    fieldGroups: [{ id: 'money', label: 'Money', fields: ['amount'] }],
    fields: base.fields.map(field =>
      field.name === 'amount'
        ? { ...field, description: 'What the order came to' }
        : field.name === 'status'
          ? { ...field, deprecated: { message: 'Read the state instead' } }
          : field,
    ),
    views: [
      {
        id: 'all',
        title: 'All orders',
        config: recordConfig({
          table: {
            columns: [
              { field: 'id' },
              { field: 'warehouse' },
              { field: 'amount' },
            ],
          },
        }),
      },
      {
        id: 'by-warehouse',
        title: 'By warehouse',
        config: analysisConfig({
          metrics: [
            { alias: 'orders', type: 'COUNT', label: 'Order count' },
            {
              alias: 'amount_sum',
              type: 'NUMERIC',
              function: 'SUM',
              expression: { type: 'FIELD', field: 'amount' },
            },
          ],
          layout: 'table',
        }),
      },
      {
        id: 'chart',
        title: 'Orders chart',
        config: analysisConfig({
          metrics: [{ alias: 'orders', type: 'COUNT', label: 'Order count' }],
          layout: 'chart',
          chart: {
            type: 'bar',
            cartesian: {
              x: 'warehouse',
              series: [{ metric: 'orders' }],
              yAxis: { left: { label: 'How many' } },
              referenceLines: [
                { axis: 'left', value: 1, label: 'Target line' },
                { axis: 'left', value: 0, label: 'Floor line' },
              ],
            },
          },
        }),
      },
      {
        id: 'line',
        title: 'Orders line',
        config: analysisConfig({
          metrics: [{ alias: 'orders', type: 'COUNT', label: 'Order count' }],
          layout: 'chart',
          chart: {
            type: 'line',
            cartesian: {
              x: 'warehouse',
              series: [{ metric: 'orders' }],
              yAxis: { left: { label: 'How many' } },
            },
          },
        }),
      },
      {
        id: 'pie',
        title: 'Orders pie',
        config: analysisConfig({
          metrics: [{ alias: 'orders', type: 'COUNT', label: 'Order count' }],
          layout: 'chart',
          chart: {
            type: 'pie',
            pie: { category: 'warehouse', value: 'orders' },
          },
        }),
      },
      {
        id: 'funnel',
        title: 'Orders funnel',
        config: analysisConfig({
          groups: [],
          metrics: [
            { alias: 'orders', type: 'COUNT', label: 'Order count' },
            {
              alias: 'amount_sum',
              type: 'NUMERIC',
              function: 'SUM',
              label: 'Amount total',
              expression: { type: 'FIELD', field: 'amount' },
            },
          ],
          layout: 'chart',
          chart: {
            type: 'funnel',
            funnel: {
              stages: {
                from: 'metrics',
                items: [
                  { metric: 'orders', label: 'Placed' },
                  { metric: 'amount_sum' },
                ],
              },
            },
          },
        }),
      },
    ],
  } as DataViewDefinition;
  const en = keyed(literal, EN, 'orders');
  keyed(literal, ZH, 'orders', original => `中:${original}`);
  return en;
}

/** The overview board, every label a key. */
export function keyedBoard(): DashboardDefinition {
  const literal = overviewDefinition({
    title: 'Overview',
    views: [
      {
        id: 'main',
        title: 'Main board',
        config: dashboardConfig({
          fields: [{ name: 'region', label: 'Region', kind: 'string' }],
          panels: [
            {
              id: 'heading',
              kind: 'heading',
              content: 'Today',
              layout: { x: 0, y: 0, w: 24, h: 1 },
            },
            {
              id: 'note',
              kind: 'markdown',
              content: 'Read me',
              title: 'Note',
              layout: { x: 0, y: 1, w: 12, h: 2 },
            },
            {
              id: 'links',
              kind: 'links',
              title: 'Links',
              items: [
                {
                  label: 'Docs',
                  href: 'https://example.com',
                  description: 'The manual',
                },
                {
                  label: 'Blog',
                  href: 'https://example.com/blog',
                  description: 'The news',
                },
              ],
              layout: { x: 12, y: 1, w: 12, h: 2 },
            },
            {
              id: 'list',
              kind: 'view',
              title: 'Order list',
              instanceId: systemInstanceId('orders', 'all'),
              bindings: [{ globalField: 'region', panelField: 'warehouse' }],
              layout: { x: 0, y: 3, w: 12, h: 4 },
            },
            {
              id: 'by',
              kind: 'view',
              instanceId: systemInstanceId('orders', 'by-warehouse'),
              bindings: [],
              layout: { x: 12, y: 3, w: 12, h: 4 },
            },
            {
              id: 'own',
              kind: 'view',
              title: 'Own view',
              owned: {
                definitionId: 'orders',
                config: analysisConfig({ layout: 'chart' }),
              },
              bindings: [],
              layout: { x: 0, y: 7, w: 12, h: 4 },
            },
          ],
        }),
      },
    ],
  });
  const en = keyed(literal, EN, 'board');
  keyed(literal, ZH, 'board', original => `中:${original}`);
  return en;
}

/**
 * An engine with the keyed orders and board, and a reader's copy of the
 * board saved as it was made, keys and all (`mine`).
 */
export function keyedEngine() {
  const source = testSource();
  const board = keyedBoard();
  const engine = new ViewEngine({
    resources: [{ definition: keyedOrders(), source }, { definition: board }],
    // A reader's copy of the board, saved as it was made: keys and all.
    store: new MemoryViewStore({
      instances: [
        {
          id: 'mine',
          definitionId: 'overview',
          title: board.views![0].title,
          scope: 'personal',
          revision: '1',
          config: board.views![0].config,
        },
      ],
    }),
    onIssue: found => {
      if (process.env.LEAF_DEBUG) console.log('issue', JSON.stringify(found));
    },
  });
  return { engine, source };
}
