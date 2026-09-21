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

import { useCallback, useEffect, useState } from 'react';
import type { RecordViewRuntime, ViewEngine } from '../src/index.js';
import { useRecordTable, useWorkbench } from '../src/react/index.js';

/**
 * The whole `/react` layer in one plain component: no styling, no component
 * library, no vendor types. It exists to show that the hooks carry the entire
 * loop on their own, and the closed-loop test drives this file rather than a
 * bespoke harness.
 *
 * It is also the reference for "your own markup, the same controllers":
 * `useWorkbench` hands over exactly what `WorkbenchShell` draws from — the
 * list, the open view, the leave guard and the header's four outcomes — and
 * everything below is ordinary HTML. The `/ui` step replaces the markup, not
 * the controllers.
 */
export function PlainRecordWorkbench({
  engine,
  definitionId,
  instanceId,
  onInstanceChange,
}: {
  engine: ViewEngine;
  definitionId: string;
  /**
   * Which view is open. Left out, this component owns it; passed, the caller
   * does — see `HashRoutedRecordWorkbench` below, which passes a route.
   */
  instanceId?: string | null;
  onInstanceChange?(id: string | null): void;
}) {
  const [title, setTitle] = useState('Pending shipments');
  const workbench = useWorkbench(engine, definitionId, {
    kind: 'record',
    instanceId,
    onInstanceChange,
  });
  // `state`, `filter` and `commands` are the controller's, already bound to
  // whichever view is open; nothing here re-derives them from the engine.
  const { commands, filter, list, opened, runtime, state } = workbench;
  const record = runtime?.kind === 'record' ? runtime : null;
  const table = useRecordTable(record as RecordViewRuntime | null);

  return (
    <div>
      <nav aria-label="views">
        {list.items.map(item => (
          <button
            key={item.id}
            type="button"
            aria-current={item.id === state?.saved?.id}
            // Through the guard: switching releases this runtime, and the
            // unsaved draft lives nowhere else.
            onClick={() => workbench.choose(item.id)}
          >
            {item.title}
          </button>
        ))}
      </nav>

      {/* The guard is headless, so the confirmation is the host's markup
          too: without one, a switch that would lose the draft never runs. */}
      {workbench.leave.asking && (
        <div role="alertdialog" aria-label="Leave this view?">
          <button type="button" onClick={() => workbench.leave.confirm()}>
            Leave
          </button>
          <button type="button" onClick={() => workbench.leave.cancel()}>
            Stay
          </button>
        </div>
      )}

      {opened.loading && <p role="status">Loading the view</p>}
      {workbench.unopenable && <p role="alert">{workbench.unopenable.code}</p>}

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

      {/* `setColumns` is told which columns are shown, and the draft's
          columns include the ones switched off — a hidden column keeps its
          entry, which is what keeps its place — so what is toggled here is
          the shown list rather than the whole of `columnFields`. */}
      <section aria-label="columns">
        {filter.fields.map(field => {
          const shown = table.columnFields.filter(
            name => !table.hiddenOf(name),
          );
          return (
            <button
              key={field.name}
              type="button"
              onClick={() =>
                table.setColumns(
                  shown.includes(field.name)
                    ? shown.filter(name => name !== field.name)
                    : [...shown, field.name],
                )
              }
            >
              Toggle column {field.label}
            </button>
          );
        })}
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
              // The copy is what the workbench then shows, and the list is
              // read again so it is on it.
              .then(saved => saved && workbench.onSaved(saved));
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

/**
 * The same workbench with the browser's address bar holding which view is
 * open: `#/orders/<id>`, or `#/orders` for the user's effective default.
 *
 * This is what a controlled `instanceId` is for. The hash is the single copy
 * of that choice — the back button, a pasted link and a click in the sidebar
 * all go through it — and the two props are its two directions:
 * `instanceId` pushes a route into the workbench, `onInstanceChange` writes
 * the user's own switch back out. `null` travels unchanged in both, so
 * "whatever my default is" survives a reload and stays shareable.
 */
export function HashRoutedRecordWorkbench({
  engine,
  definitionId,
}: {
  engine: ViewEngine;
  definitionId: string;
}) {
  const [instanceId, go] = useHashInstance(definitionId);
  return (
    <PlainRecordWorkbench
      engine={engine}
      definitionId={definitionId}
      instanceId={instanceId}
      // The leave guard may refuse a pushed switch, and the workbench then
      // says so by reporting the view that stayed — which lands right back
      // here and puts the address bar back where it was.
      onInstanceChange={go}
    />
  );
}

/** The hash as a value, and the one way to write it. */
function useHashInstance(
  definitionId: string,
): [string | null, (id: string | null) => void] {
  const read = useCallback(() => {
    const prefix = `#/${definitionId}/`;
    return window.location.hash.startsWith(prefix)
      ? decodeURIComponent(window.location.hash.slice(prefix.length))
      : null;
  }, [definitionId]);

  const [instanceId, setInstanceId] = useState(read);
  useEffect(() => {
    const onHashChange = () => setInstanceId(read());
    // The back button is a route change like any other, so it arrives here
    // and is pushed into the workbench exactly as a pasted link would be.
    window.addEventListener('hashchange', onHashChange);
    onHashChange();
    return () => window.removeEventListener('hashchange', onHashChange);
  }, [read]);

  const go = useCallback(
    (id: string | null) => {
      const next =
        id === null
          ? `#/${definitionId}`
          : `#/${definitionId}/${encodeURIComponent(id)}`;
      if (window.location.hash !== next) window.location.hash = next;
      setInstanceId(id);
    },
    [definitionId],
  );
  return [instanceId, go];
}
