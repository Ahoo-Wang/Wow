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
 * The four wrappers here that hold what a vendored component's cva does not
 * (`LineAlert` is the fifth, in `ui/alerts.tsx`, and `test/statusStrip` holds
 * that one), and the one rule that put them there (D16-8): `ui/components/**` is
 * upstream source and is never edited by hand, and a call site never carries
 * a component's colours in a `className`.
 *
 * The assertions are on classes, which is unusual here and is the point: the
 * question each one asks is *where the colour is written*, and that is a
 * question about the class string and nothing else. What the colours then
 * measure against the surface belongs to the browser stories
 * (`BadgesOnRowsInLightTheme` / `InDarkTheme`).
 *
 * This file is also the **one home** for those class strings (A-09). A
 * wrapper says what it is on the element — `data-tone` on `ToneBadge` and
 * `DestructiveAction`, `aria-current` on `SidebarItem` — and every other
 * suite asks that instead: `test/viewList` asks which row is open,
 * `test/viewManagerUi` asks whether an answer is the destructive one, and
 * neither names a fill. A colour moving into a different variant therefore
 * turns this file red and nothing else.
 */

import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import {
  PillInput,
  PillSelectTrigger,
  SidebarItem,
  TableDataRow,
  ToneBadge,
} from '../src/ui/variants.js';
import { Select } from '../src/ui/components/select.js';
import { ViewSurface } from '../src/ui/index.js';

afterEach(cleanup);

describe('ToneBadge', () => {
  it('tints a toned badge and leaves a neutral one its edge', () => {
    render(
      <ViewSurface>
        <ToneBadge tone="success">Shipped</ToneBadge>
        <ToneBadge tone="danger">Cancelled</ToneBadge>
        <ToneBadge>Boxed</ToneBadge>
      </ViewSurface>,
    );
    const [success, danger, neutral] = [
      ...document.querySelectorAll<HTMLElement>('[data-slot="badge"]'),
    ];

    // The soft recipe: a tint of the tone, written in the tone, with an edge
    // of it — the ink measured on every row ground in the browser stories.
    // The tint and the edge are the stylesheet's, by `data-tone`, as much of
    // the tone as the theme's `badge-fill` and `badge-edge` say (10% and 30%
    // unset; theme-architecture.md 4.2) — the recipe writes the words alone.
    expect(success.dataset.tone).toBe('success');
    expect(danger.dataset.tone).toBe('danger');
    expect(success.className).toContain('text-success');
    expect(success.className).not.toContain('bg-success/10');
    expect(success.className).not.toContain('border-success/30');
    // Danger writes in its own token in both modes: the dark-only mix
    // toward the foreground went with the quieter dark status colours (Q44),
    // whose ink `test/presetContrast.test.ts` measures in every preset.
    expect(danger.className).toContain('text-destructive');
    expect(danger.className).not.toContain('dark:text-');
    // No tone is no fill at all: the edge is the whole of a neutral badge,
    // because the row under it moves through the registry's `secondary`.
    expect(neutral.className).toContain('border-input');
    expect(neutral.className).not.toContain('bg-success');
    // The dot says "a status"; a neutral value is only a value.
    expect(success.className).toContain("before:content-['']");
    expect(neutral.className).not.toContain('before:content');
  });

  it('says its tone on the element, so nothing has to read a colour', () => {
    render(
      <ViewSurface>
        <ToneBadge tone="warning">Pending</ToneBadge>
      </ViewSurface>,
    );
    expect(
      document.querySelector('[data-slot="badge"]')?.getAttribute('data-tone'),
    ).toBe('warning');
  });

  it('drops the dot where an icon already marks the badge', () => {
    render(
      <ViewSurface>
        <ToneBadge tone="success" dot={false}>
          +20%
        </ToneBadge>
      </ViewSurface>,
    );
    // The tone stays; only the dot the icon would stand beside goes.
    const badge = document.querySelector<HTMLElement>('[data-slot="badge"]')!;
    expect(badge.className).toContain('text-success');
    expect(badge.className).toContain('before:hidden');
  });
});

describe('the controls of a condition pill', () => {
  /**
   * One border per condition (D12). The class is the control's own now
   * rather than a `[&_[data-slot=input]]:…` reaching down from the pill,
   * which is the pattern a registry rename undoes without a word.
   */
  it('draws no chrome of its own unless asked for the box back', () => {
    render(
      <ViewSurface>
        <PillInput aria-label="In a pill" />
        <PillInput aria-label="On its own" chrome="box" />
      </ViewSurface>,
    );
    const [inPill, alone] = [
      ...document.querySelectorAll<HTMLElement>('[data-slot="input"]'),
    ];
    expect(inPill.className).toContain('border-transparent');
    expect(inPill.className).toContain('bg-transparent');
    expect(alone.className).not.toContain('border-transparent');
    // Everything the registry ships is still on both of them: the wrapper
    // adds a layer, it does not replace the component's own class.
    expect(alone.className).toContain('border-input');
    expect(inPill.className).toContain('focus-visible:ring-ring/50');
  });

  it('strips the select the same way, and keeps its accessible name', () => {
    render(
      <ViewSurface>
        <Select items={[]}>
          <PillSelectTrigger aria-label="Operator" size="sm" />
        </Select>
      </ViewSurface>,
    );
    const trigger = screen.getByLabelText('Operator');
    expect(trigger.getAttribute('data-slot')).toBe('select-trigger');
    expect(trigger.className).toContain('border-transparent');
    expect(trigger.className).toContain('shadow-none');
  });
});

describe('SidebarItem', () => {
  it('marks the open view as a raised sheet rather than with another grey', () => {
    render(
      <ViewSurface>
        <SidebarItem current>Mine</SidebarItem>
        <SidebarItem>Ours</SidebarItem>
      </ViewSurface>,
    );
    const current = screen.getByRole('button', { name: 'Mine' });
    const other = screen.getByRole('button', { name: 'Ours' });

    // A sheet of the work area's ground with an edge all round — no bar,
    // whose inset shadow bent round the item's corners into a "(".
    // The sheet is the theme's `nav-current` roles: the work area's
    // `background`, its words, the `border` edge and the `shadow-xs` lift
    // unless a theme says otherwise.
    expect(current.className).toContain('bg-nav-current');
    expect(current.className).toContain('text-nav-current-foreground');
    expect(current.className).toContain('border-nav-current-edge');
    expect(current.className).toContain('shadow-nav-current');
    expect(current.className).not.toContain('shadow-[inset');
    expect(current.getAttribute('aria-current')).toBe('true');
    // Hover is the column's own step in the other direction — on this ground
    // the ghost variant's `muted` *is* the ground.
    expect(other.className).toContain('hover:bg-sidebar-accent');
    expect(other.className).not.toContain('border-nav-current-edge');
  });
});

/**
 * The three states of one row of records, which is the one wrapper here
 * whose colours are load-bearing for something other than reading: a cell of
 * a held column is `bg-inherit`, so a row's fill is what a frozen column is
 * painted in as well (`ui/record/sticky.ts`).
 */
describe('TableDataRow', () => {
  const row = () => {
    const { container } = render(
      <table>
        <tbody>
          <TableDataRow />
          <TableDataRow data-state="selected" />
        </tbody>
      </table>,
    );
    return [...container.querySelectorAll('tr')];
  };

  it('fills a row opaquely at rest and under the pointer', () => {
    const [rest] = row();
    // A wash is what the registry hovers to — `bg-muted/50` — and through
    // it the reader saw the scrolling column the held cell stands in front
    // of. `--_fve-row-hover` is the same shade mixed rather than washed, and the
    // opacity itself is measured in the browser (`PinnedEdges`).
    // At rest the row is the rows' ground (`content`, the page's own
    // unset), and every other one the stripe a theme may turn on.
    expect(rest.className).toContain('bg-content');
    expect(rest.className).toContain('even:bg-row-stripe');
    expect(rest.className).toContain('hover:bg-row-hover');
    // The row whose menu is open, which the registry washes the same way.
    expect(rest.className).toContain('has-aria-expanded:bg-row-hover');
    expect(rest.className).not.toContain('hover:bg-muted/50');
    expect(rest.className).not.toContain('has-aria-expanded:bg-muted/50');
  });

  it('keeps a picked row picked while the pointer is on it', () => {
    const [, selected] = row();
    // Said again at the higher specificity, or the unqualified `:hover`
    // above takes the tint away and with it the only mark saying the row is
    // in the selection.
    // The tint is the theme's `row-selected` role, `muted` unset.
    expect(selected.className).toContain(
      'data-[state=selected]:bg-row-selected',
    );
    expect(selected.className).toContain(
      'data-[state=selected]:hover:bg-row-selected',
    );
    expect(selected.className).toContain(
      'data-[state=selected]:text-row-selected-foreground',
    );
  });

  it('leaves the row its own layout classes', () => {
    const { container } = render(
      <table>
        <tbody>
          <TableDataRow className="group/row" />
        </tbody>
      </table>,
    );
    expect(container.querySelector('tr')!.className).toContain('group/row');
  });
});
