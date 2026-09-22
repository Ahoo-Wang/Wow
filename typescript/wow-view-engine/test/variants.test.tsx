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
 * The three wrappers here that hold what a vendored component's cva does not
 * (`LineAlert` is the fourth, in `ui/alerts.tsx`, and `test/statusStrip` holds
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
  ToneBadge,
} from '../src/ui/variants.js';
import { Select } from '../src/ui/components/select.js';
import { ViewSurface } from '../src/ui/index.js';

afterEach(cleanup);

describe('ToneBadge', () => {
  it('fills a toned badge and leaves a neutral one its edge', () => {
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

    // A filled status surface, in the token the theme picked for writing on
    // — never a 10% wash of it, which measured under 4.5:1 in both themes.
    expect(success.className).toContain('bg-success');
    expect(success.className).toContain('text-success-foreground');
    // The fill said twice, because the registry's `destructive` says its own
    // twice and only the unprefixed one is replaced by an unprefixed rule.
    expect(danger.className).toContain('bg-destructive dark:bg-destructive');
    // No tone is no fill at all: the edge is the whole of a neutral badge,
    // because the row under it moves through the registry's `secondary`.
    expect(neutral.className).toContain('border-input');
    expect(neutral.className).not.toContain('bg-success');
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
  it('marks the open view with a bar rather than with another grey', () => {
    render(
      <ViewSurface>
        <SidebarItem current>Mine</SidebarItem>
        <SidebarItem>Ours</SidebarItem>
      </ViewSurface>,
    );
    const current = screen.getByRole('button', { name: 'Mine' });
    const other = screen.getByRole('button', { name: 'Ours' });

    expect(current.className).toContain(
      'shadow-[inset_2px_0_0_var(--primary)]',
    );
    expect(current.getAttribute('aria-current')).toBe('true');
    // Hover is the column's own step in the other direction — on this ground
    // the ghost variant's `muted` *is* the ground.
    expect(other.className).toContain('hover:bg-sidebar-accent');
    expect(other.className).not.toContain('shadow-[inset');
  });
});
