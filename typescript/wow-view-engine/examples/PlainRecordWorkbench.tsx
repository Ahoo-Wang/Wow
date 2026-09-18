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

import { useState } from 'react';
import type { RecordViewRuntime, ViewEngine } from '../src/index.js';
import {
  useFilterEditor,
  useOpenView,
  useRecordTable,
  useSaveCommands,
  useViewList,
  useViewRuntime,
} from '../src/react/index.js';

/**
 * The whole `/react` layer in one plain component: no styling, no component
 * library, no vendor types. It exists to show that the hooks carry the entire
 * loop on their own, and the closed-loop test drives this file rather than a
 * bespoke harness.
 *
 * Everything below is ordinary HTML. The `/ui` step replaces the markup, not
 * the controllers.
 */
export function PlainRecordWorkbench({
  engine,
  definitionId,
  initialInstanceId = null,
}: {
  engine: ViewEngine;
  definitionId: string;
  /** Which view to open first; the effective default when left out. */
  initialInstanceId?: string | null;
}) {
  const list = useViewList(engine, definitionId);
  const [chosen, setChosen] = useState<string | null>(initialInstanceId);
  const [title, setTitle] = useState('Pending shipments');

  const { runtime, loading, error } = useOpenView(
    engine,
    chosen ?? list.defaultInstanceId,
  );
  // Subscribed rather than read during render: `getSnapshot()` off a runtime
  // in the middle of rendering is a value React never hears about changing.
  const state = useViewRuntime(runtime);
  const record = runtime?.kind === 'record' ? runtime : null;
  const table = useRecordTable(record as RecordViewRuntime | null);
  const filter = useFilterEditor(runtime);
  const commands = useSaveCommands(engine, runtime);

  return (
    <div>
      <nav aria-label="views">
        {list.items.map(item => (
          <button
            key={item.id}
            type="button"
            aria-current={item.id === state?.saved?.id}
            onClick={() => setChosen(item.id)}
          >
            {item.title}
          </button>
        ))}
      </nav>

      {loading && <p role="status">Loading the view</p>}
      {error && <p role="alert">{error.code}</p>}

      <section aria-label="filter">
        {filter.fields.map(field => (
          <button
            key={field.name}
            type="button"
            onClick={() => filter.addLeaf(field.name)}
          >
            Filter by {field.label}
          </button>
        ))}
        {filter.tree.children.map((node, index) =>
          'children' in node ? null : (
            <input
              key={`${node.field}-${index}`}
              aria-label={`${node.field} value`}
              value={String(node.value ?? '')}
              onFocus={filter.focus}
              onBlur={filter.blur}
              onChange={event =>
                filter.updateLeaf([index], { value: event.target.value })
              }
            />
          ),
        )}
        <button type="button" onClick={filter.submit}>
          Apply filter
        </button>
        <button type="button" onClick={filter.clear}>
          Clear filter
        </button>
      </section>

      <section aria-label="columns">
        {filter.fields.map(field => (
          <button
            key={field.name}
            type="button"
            onClick={() =>
              table.setColumns(
                table.columnFields.includes(field.name)
                  ? table.columnFields.filter(name => name !== field.name)
                  : [...table.columnFields, field.name],
              )
            }
          >
            Toggle column {field.label}
          </button>
        ))}
      </section>

      <table>
        <thead>
          <tr>
            <th scope="col">
              <input
                type="checkbox"
                aria-label="select all"
                checked={
                  table.rows.length > 0 &&
                  table.selection.length === table.rows.length
                }
                onChange={table.toggleAll}
              />
            </th>
            {table.columns.map(column => (
              <th key={column.field} scope="col">
                <button
                  type="button"
                  onClick={() => table.toggleSort(column.field)}
                >
                  {column.label}
                  {table.sortOf(column.field) ?? ''}
                </button>
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {table.rows.map(row => (
            <tr key={String(row.key)}>
              <td>
                <input
                  type="checkbox"
                  aria-label={`select ${String(row.key)}`}
                  checked={table.isSelected(row.key)}
                  onChange={() => table.toggle(row.key)}
                />
              </td>
              {table.columns.map(column => (
                <td key={column.field}>
                  {String(row.data[column.field] ?? '')}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>

      <footer>
        <span data-testid="paging">
          {table.paging?.mode === 'paged'
            ? `page ${table.paging.index} of ${table.paging.total ?? '?'}`
            : (table.paging?.nextCursor ?? 'no cursor')}
        </span>
        <span data-testid="selection">{table.selection.join(',')}</span>
        <span data-testid="status">{table.status}</span>
        <span data-testid="dirty">
          {commands.state.dirty ? 'dirty' : 'clean'}
        </span>
        <button type="button" onClick={table.previous}>
          Previous page
        </button>
        <button type="button" onClick={table.next}>
          Next page
        </button>
        <button type="button" onClick={table.refresh}>
          Refresh
        </button>
        <input
          aria-label="view title"
          value={title}
          onChange={event => setTitle(event.target.value)}
        />
        <button
          type="button"
          disabled={!commands.can.saveAs}
          onClick={() => {
            void commands
              .saveAs({ title, scope: 'personal' })
              .then(saved => saved && setChosen(saved.id));
          }}
        >
          Save as personal view
        </button>
        <button
          type="button"
          disabled={!commands.can.save}
          onClick={() => void commands.save()}
        >
          Save
        </button>
      </footer>
    </div>
  );
}
