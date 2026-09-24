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

/**
 * What the condition editor does with a field it has no editor for.
 *
 * There are two ways to arrive there and one answer. The field's kind may be
 * missing from the `FieldKindRegistry` the runtime was built with, or a
 * registered kind may ask for an `EditorDescriptor.input` outside the
 * closed union `FilterValueEditor` switches over. Either way the condition is
 * shown and not offered: the value as stored, the reason beside it, the ✕
 * still working, and Apply refused with a count, because the kernel can
 * neither admit nor compile it.
 */

import {
  act,
  cleanup,
  fireEvent,
  render,
  renderHook,
  screen,
  within,
} from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import {
  builtinFieldKinds,
  dataViewRuntime,
  DEFAULT_RUNTIME_LIMITS,
  MemoryViewStore,
  RequestRunner,
  validateFilter,
  ViewEngine,
  withFieldKinds,
} from '../src/index.js';
import type { FieldDefinition, FieldKind, FilterValue } from '../src/index.js';
import { useFilterEditor } from '../src/react/index.js';
import type { FilterEditorController } from '../src/react/index.js';
import { FilterPanel, FilterValueEditor } from '../src/ui/index.js';
import {
  dashboardConfig,
  overviewDefinition,
  ordersDefinition,
  recordConfig,
  testEnvironment,
  testSource,
} from './fixtures.js';

afterEach(cleanup);

/** A field of a kind nothing registers; `swatch` is nobody's built-in. */
const COLOUR: FieldDefinition = {
  name: 'colour',
  label: 'Colour',
  kind: 'swatch',
};

/**
 * The panel over a record view whose definition has a field of that kind,
 * built directly as a host that assembles its own runtime may. The engine
 * never opens one: a definition is refused whole for the slip
 * (`definition.field.kind-unregistered`), and a dashboard, which declares
 * its fields as data, has no condition of its own to edit over them (D27).
 */
async function recordPanel(): Promise<{ filter(): FilterEditorController }> {
  const orders = ordersDefinition();
  const runtime = dataViewRuntime({
    id: 'colours',
    definition: { ...orders, fields: [...orders.fields, COLOUR] },
    config: recordConfig({
      filter: {
        op: 'and',
        children: [{ field: 'colour', operator: 'EQ', value: 'red' }],
      },
    }),
    title: 'Orders',
    scope: 'personal',
    saved: null,
    kinds: builtinFieldKinds,
    limits: DEFAULT_RUNTIME_LIMITS,
    environment: testEnvironment().environment,
    source: testSource(),
    runner: new RequestRunner(),
  });
  let latest: FilterEditorController | null = null;
  function Probe() {
    const filter = useFilterEditor(runtime);
    latest = filter;
    return <FilterPanel filter={filter} />;
  }
  render(<Probe />);
  await act(async () => {
    await Promise.resolve();
  });
  return { filter: () => latest as FilterEditorController };
}

/** The pill drawn for the colour condition, by the name it answers to. */
function colourPill(): HTMLElement {
  return screen.getByRole('group', { name: 'Colour condition' });
}

describe('a condition on a field whose kind is not registered', () => {
  it('shows the condition without offering to edit it', async () => {
    await recordPanel();
    const pill = colourPill();

    // The field, the operator as the word the dropdown would have shown, and
    // the value exactly as the config holds it.
    expect(pill.textContent).toContain('Colour');
    expect(pill.textContent).toContain('is');
    expect(pill.textContent).toContain('red');
    // And the reason, which is the part that was missing: the pill used to be
    // red and silent, with an empty operator select where the choice should
    // have been.
    expect(pill.textContent).toContain(
      "This field's kind (swatch) has no editor registered.",
    );

    // Nothing in it can be changed: no operator select, no value box.
    expect(within(pill).queryByRole('combobox')).toBeNull();
    expect(within(pill).queryByRole('textbox')).toBeNull();
    // Refused, in the same tone as every other refused condition.
    expect(pill.hasAttribute('data-invalid')).toBe(true);
  });

  it('still lets the condition be taken out', async () => {
    const { filter } = await recordPanel();

    fireEvent.click(
      within(colourPill()).getByRole('button', { name: 'Remove Colour' }),
    );

    expect(filter().tree.children).toEqual([]);
    expect(
      screen.queryByRole('group', { name: 'Colour condition' }),
    ).toBeNull();
  });

  /**
   * One voice. The kernel refuses the leaf, so Apply is disabled and the row
   * says how many stand in the way; the sentence itself lives on the pill,
   * which is where the fix is, and the strip above the editor stays quiet —
   * `unmarkedErrors` is for findings no pill can carry.
   */
  it('blocks apply, and says why once', async () => {
    const { filter } = await recordPanel();

    expect(filter().blocked).toBe(1);
    expect(filter().unmarked).toEqual([]);
    expect(
      screen.getByRole('button', { name: 'Apply' }).hasAttribute('disabled'),
    ).toBe(true);
    expect(screen.getByText('1 to fix')).toBeTruthy();
  });

  /** The picker never offers a condition that cannot be made. */
  it('is not a field the picker offers', async () => {
    const { filter } = await recordPanel();
    const offered = filter()
      .fieldsFor()
      .map(field => field.name);

    expect(offered).not.toContain('colour');
    expect(offered).toContain('warehouse');
  });
});

describe('a registered kind that asks for an editor nobody wrote', () => {
  /** A kind whose descriptor names an input outside the closed union. */
  const swatch: FieldKind = {
    ...(builtinFieldKinds.get('string') as FieldKind),
    id: 'swatch',
    editor: () => ({ input: 'colourWheel' as never }),
  };

  it('is refused by admission rather than drawn as a text box', () => {
    const issues = validateFilter(
      [COLOUR],
      {
        op: 'and',
        children: [{ field: 'colour', operator: 'EQ', value: 'red' }],
      },
      withFieldKinds(builtinFieldKinds, [swatch]),
    );

    expect(issues).toEqual([
      {
        code: 'filter.kind.unknown-editor',
        severity: 'error',
        path: ['children', 0],
        params: { kind: 'swatch', input: 'colourWheel' },
      },
    ]);
  });

  it('reads the value out where the control would have been', () => {
    render(
      <FilterValueEditor
        kind="swatch"
        editor={{ input: 'colourWheel' as never }}
        value={'red' as FilterValue}
        label="Colour value"
        onChange={() => {}}
      />,
    );

    expect(screen.queryByRole('textbox')).toBeNull();
    expect(screen.getByText('red')).toBeTruthy();
    expect(
      screen.getByText("This field's kind (swatch) has no editor registered."),
    ).toBeTruthy();
  });
});

describe('the value editor of a shape it does not hold', () => {
  /**
   * A condition whose value is a condition is drawn as a block by
   * `ConditionPill`, over the same group editor the outer filter uses, so
   * there is no value control to give it here. That is not the fallback: the
   * shape is supported, just not by a value editor.
   */
  it('draws nothing for a predicate', () => {
    const { container } = render(
      <FilterValueEditor
        kind="elementMatch"
        editor={{ input: 'predicate' }}
        value={null}
        label="Items value"
        onChange={() => {}}
      />,
    );

    expect(container.textContent).toBe('');
  });

  /**
   * A config arrives from a store and may hold a cycle, which
   * `JSON.stringify` throws on. A value that cannot even be printed reads as
   * no value rather than as a panel that crashed on the way to saying so.
   */
  it('prints nothing for a value it cannot serialise', () => {
    const looping: Record<string, unknown> = {};
    looping.self = looping;

    render(
      <FilterValueEditor
        kind="swatch"
        editor={{ input: 'colourWheel' as never }}
        value={looping as FilterValue}
        label="Colour value"
        onChange={() => {}}
      />,
    );

    expect(
      screen.getByText("This field's kind (swatch) has no editor registered.")
        .previousSibling,
    ).toBeNull();
  });
});

describe('the value editor over a shape it does hold', () => {
  it('keeps text a text box rather than a fallback', () => {
    render(
      <FilterValueEditor
        kind="string"
        editor={{ input: 'text' }}
        value={'A-1' as FilterValue}
        label="SKU value"
        onChange={() => {}}
      />,
    );

    expect((screen.getByRole('textbox') as HTMLInputElement).value).toBe('A-1');
  });
});

/** Kept honest: the hook's own picker answers the same as the controller's. */
describe('the editor bound to a runtime', () => {
  it('offers only fields it can build a condition on', async () => {
    const store = new MemoryViewStore({ instances: [] });
    const engine = new ViewEngine({
      definitions: [ordersDefinition(), overviewDefinition()],
      store,
      resolveSource: () => testSource(),
    });
    const instance = await store.create(
      {
        definitionId: 'overview',
        title: 'Overview',
        scope: 'personal',
        config: dashboardConfig({
          fields: [COLOUR, { name: 'sku', label: 'SKU', kind: 'string' }],
        }),
      },
      { requestId: 'r' },
    );
    const runtime = await engine.open(instance.id);
    const { result } = renderHook(() => useFilterEditor(runtime));

    expect(result.current.fields.map(field => field.name)).toEqual([
      'colour',
      'sku',
    ]);
    expect(result.current.fieldsFor().map(field => field.name)).toEqual([
      'sku',
    ]);
  });
});
