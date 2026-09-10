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

import { describe, it, expect } from 'vitest';
import { resolveRecordPresentation } from '../src/record/resolveRecordPresentation.js';
import {
  validateViewDefinition,
  validateViewInstance,
} from '../src/record/recordValidation.js';
import { definition, instance } from './engine/fixtures.js';

describe('record presentation', () => {
  it('rejects missing, empty, duplicate and unknown allowed layouts', () => {
    for (const allowedLayouts of [
      undefined,
      [],
      ['table', 'table'],
      ['grid'],
    ]) {
      expect(() =>
        validateViewDefinition({ ...definition, allowedLayouts }),
      ).toThrow();
    }
  });
  it('rejects disabled layouts when resolving and loading saved instances', () => {
    const restricted = { ...definition, allowedLayouts: ['table'] as const };
    expect(() => resolveRecordPresentation(restricted, 'card')).toThrow();
    const saved = instance();
    saved.config.presentation = resolveRecordPresentation(definition, 'card');
    expect(() => validateViewInstance(saved, restricted)).toThrow();
  });
  it('initializes only the requested layout and preserves both on return', () => {
    const first = resolveRecordPresentation(definition, 'card');
    expect(first.table).toBeUndefined();
    expect(first.card?.title.field).toBe(definition.rowKey);
    const table = resolveRecordPresentation(definition, 'table', first);
    expect(table.table?.columns.map(column => column.id)).toEqual(
      definition.fields.map(field => field.field),
    );
    const back = resolveRecordPresentation(definition, 'card', table);
    expect(back.card).toEqual(first.card);
    expect(back.card).not.toBe(first.card);
    const saved = instance();
    saved.config.presentation = first;
    expect(() => validateViewInstance(saved, definition)).not.toThrow();
  });
  it('rejects explicit null before selecting defaults', () => {
    for (const value of [null, { table: null }, { card: null }]) {
      expect(() =>
        resolveRecordPresentation(definition, 'card', value as never),
      ).toThrow();
    }
  });
  it('copies definition presets, preferring existing configuration', () => {
    const card = {
      title: { id: 'title', field: definition.rowKey },
      fields: [],
    };
    const custom = { ...definition, defaultPresentation: { card } };
    validateViewDefinition(custom);
    const resolved = resolveRecordPresentation(custom, 'card');
    expect(resolved.card).toEqual(card);
    expect(resolved.card).not.toBe(card);
    const existing = { ...card, title: { id: 'title', field: 'state.amount' } };
    expect(
      resolveRecordPresentation(custom, 'card', { card: existing }).card,
    ).toEqual(existing);
  });
  it('allows a key-only card, but not an empty table', () => {
    const empty = { ...definition, fields: [] };
    expect(() => resolveRecordPresentation(empty, 'card')).not.toThrow();
    expect(() => resolveRecordPresentation(empty, 'table')).toThrow();
  });
  it('validates inactive presets and action references', () => {
    expect(() =>
      validateViewDefinition({
        ...definition,
        defaultPresentation: { card: null },
      }),
    ).toThrow();
    const card = {
      title: { id: 'title', field: definition.rowKey },
      fields: [],
      actions: {},
    };
    expect(() =>
      resolveRecordPresentation(definition, 'card', { card }),
    ).toThrow();
    expect(() =>
      resolveRecordPresentation(definition, 'card', {
        card: {
          ...card,
          actions: { visible: false, renderer: { name: 'custom' } },
        },
      }),
    ).not.toThrow();
  });
});

it.each([
  { title: { id: 'title', field: 'missing' }, fields: [] },
  { title: { id: 'title', field: 'state.id' }, fields: null },
  {
    title: { id: 'title', field: 'state.id' },
    fields: [
      { id: 'x', field: 'state.amount' },
      { id: 'x', field: 'state.amount' },
    ],
  },
  {
    title: { id: 'title', field: 'state.id' },
    fields: [],
    cover: { field: 'state.amount' },
  },
  {
    title: { id: 'title', field: 'state.id' },
    fields: [],
    actions: { visible: 'false', renderer: { name: 'custom' } },
  },
])('rejects malformed card configuration before use', card => {
  expect(() =>
    resolveRecordPresentation(definition, 'table', { card } as never),
  ).toThrow();
});
