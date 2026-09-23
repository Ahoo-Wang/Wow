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
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  DEFAULT_RUNTIME_LIMITS,
  MemoryViewStore,
  ViewEngine,
  limitBounds,
  type AnalysisViewConfig,
  type DataViewDefinition,
  type ViewInstance,
  type ViewSource,
} from '../src/index.js';
import {
  DataWorkbench,
  defaultMessages,
  formatMessage,
} from '../src/ui/index.js';
import { analysisConfig, ordersDefinition, testSource } from './fixtures.js';
import { openTray } from './fixtures/workbench.js';

afterEach(cleanup);

const view: ViewInstance = {
  id: 'orders-1',
  definitionId: 'orders',
  title: 'By warehouse',
  scope: 'personal',
  revision: '1',
  config: analysisConfig({ layout: 'table' }),
};

async function open(
  config: Partial<AnalysisViewConfig> = {},
  definition: DataViewDefinition = ordersDefinition(),
): Promise<{ engine: ViewEngine; source: ViewSource }> {
  const source = testSource();
  const engine = new ViewEngine({
    definitions: [definition],
    store: new MemoryViewStore({
      instances: [
        { ...view, config: analysisConfig({ layout: 'table', ...config }) },
      ],
    }),
    resolveSource: () => source,
  });
  render(
    <DataWorkbench
      engine={engine}
      definitionId="orders"
      instanceId="orders-1"
      kinds={['analysis']}
    />,
  );
  await openTray();
  return { engine, source };
}

const runtimeOf = (engine: ViewEngine) => engine.openRuntimes()[0]!;
const draft = (engine: ViewEngine) =>
  runtimeOf(engine).getSnapshot().draft as AnalysisViewConfig;

const box = () => screen.getByLabelText<HTMLInputElement>('Top N groups');
const field = () =>
  document.querySelector<HTMLElement>('[data-slot="analysis-limit"]');

/** Types into the box the way a user replaces what it holds. */
function type(value: string) {
  fireEvent.change(box(), { target: { value } });
}

/** The sentence the box is described by, or null when nothing is wrong. */
function refusal(): string | null {
  const id = box().getAttribute('aria-describedby');
  return id ? (document.getElementById(id)?.textContent ?? null) : null;
}

const outOfRange = (max: number) =>
  formatMessage(defaultMessages, 'label.analysis.row-limit-invalid', { max });

function applyButton(): HTMLElement {
  return within(
    document.querySelector<HTMLElement>('[data-slot="analysis-tray-actions"]')!,
  ).getByRole('button', { name: defaultMessages['label.filter.apply'] });
}

/**
 * 「前 N 组」 (2026-09-23 audit). The box used to write whatever parsed
 * straight into the draft and ignore a blank, so an emptied box sprang back
 * to the -3 before it, 2.5 was refused as "not positive", and a -3 left
 * behind when the last dimension took the row away went on refusing Apply
 * with nothing on screen to fix it. The draft now only ever holds an N Wow
 * takes; the rest stays in the box, marked where it is.
 */
describe('the top-N groups field', () => {
  it('takes a blank as the N a view starts at, and stays blank', async () => {
    const { engine } = await open({ limit: 25 });
    const { fallback } = limitBounds(
      ordersDefinition().analysis!,
      DEFAULT_RUNTIME_LIMITS,
    );

    type('');

    expect(draft(engine).limit).toBe(fallback);
    // Blank, not refilled under the cursor, and the placeholder says what
    // the blank stands for.
    expect(box().value).toBe('');
    expect(box().placeholder).toBe(String(fallback));
    expect(box().getAttribute('aria-invalid')).not.toBe('true');
    expect(refusal()).toBeNull();
  });

  it('keeps an N out of range as typed, marked in place, and out of the draft', async () => {
    const { engine } = await open({ limit: 25 });
    const max = limitBounds(
      ordersDefinition().analysis!,
      DEFAULT_RUNTIME_LIMITS,
    ).max;

    for (const typed of ['-3', '2.5', '0', String(max + 1)]) {
      type(typed);
      expect(box().value.replace(/,/g, '')).toBe(typed);
      expect(box().getAttribute('aria-invalid')).toBe('true');
      expect(field()!.hasAttribute('data-invalid')).toBe(true);
      // The range itself, bounds from the model: a fraction is told it must
      // be whole, not that it must be positive.
      expect(refusal()).toBe(outOfRange(max));
      expect(draft(engine).limit).toBe(25);
    }

    // And an emptied box is let go of, rather than springing back.
    type('');
    expect(box().value).toBe('');
    expect(box().getAttribute('aria-invalid')).not.toBe('true');
    expect(refusal()).toBeNull();

    type('40');
    expect(draft(engine).limit).toBe(40);
    expect(refusal()).toBeNull();
  });

  it('says the bounds the definition declares', async () => {
    const definition = ordersDefinition();
    definition.analysis = { ...definition.analysis!, limits: { maxLimit: 50 } };
    await open({ limit: 25 }, definition);

    type('60');

    expect(refusal()).toBe(outOfRange(50));
  });

  it('takes its text and its refusal away with it when the row goes', async () => {
    const { engine, source } = await open({ limit: 25 });
    type('-3');
    expect(refusal()).not.toBeNull();

    // The last dimension leaving takes the row, and with it the box.
    fireEvent.click(
      screen.getByRole('button', { name: 'Remove dimension Warehouse' }),
    );
    await waitFor(() => expect(field()).toBeNull());

    // Nothing left behind refuses Apply: no finding about the limit, and the
    // question runs.
    expect(
      runtimeOf(engine)
        .getSnapshot()
        .issues.filter(found => found.path[0] === 'limit'),
    ).toEqual([]);
    const before = vi.mocked(source.aggregate).mock.calls.length;
    fireEvent.click(applyButton());
    await waitFor(() =>
      expect(vi.mocked(source.aggregate).mock.calls.length).toBeGreaterThan(
        before,
      ),
    );
    expect(
      (runtimeOf(engine).getSnapshot().applied as AnalysisViewConfig).groups,
    ).toEqual([]);

    // A dimension back brings the box back from the draft, not the old text.
    fireEvent.click(screen.getByRole('button', { name: 'Add dimension' }));
    fireEvent.click(await screen.findByRole('menuitem', { name: 'Warehouse' }));
    await waitFor(() => expect(field()).not.toBeNull());
    expect(box().value).toBe('25');
    expect(box().getAttribute('aria-invalid')).not.toBe('true');
  });

  it('marks a stored N out of range, and lets a good one replace it', async () => {
    const { engine } = await open({ limit: 0 });

    expect(box().getAttribute('aria-invalid')).toBe('true');
    expect(refusal()).toBe(outOfRange(DEFAULT_RUNTIME_LIMITS.maxAnalysisRows));
    // Admission says the same thing in its own words, once.
    expect(
      runtimeOf(engine)
        .getSnapshot()
        .issues.map(found => found.code),
    ).toContain('analysis.limit.out-of-range');

    type('10');

    expect(draft(engine).limit).toBe(10);
    expect(refusal()).toBeNull();
    await waitFor(() =>
      expect(
        runtimeOf(engine)
          .getSnapshot()
          .issues.map(found => found.code),
      ).not.toContain('analysis.limit.out-of-range'),
    );
  });

  /**
   * The draft's N moving underneath — here a discard back to what ran —
   * takes the box back: text held against one N is not about another.
   */
  it('follows the draft when its N moves under text it was holding', async () => {
    const { engine } = await open({ limit: 25 });
    type('-3');
    expect(box().getAttribute('aria-invalid')).toBe('true');
    // Whatever moves the N is pressed somewhere else, so the box has let go
    // of the keyboard first; while it holds it, the primitive keeps the text.
    fireEvent.blur(box());

    runtimeOf(engine).edit({ limit: 30 });

    await waitFor(() => expect(box().value).toBe('30'));
    expect(box().getAttribute('aria-invalid')).not.toBe('true');
  });
});
