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
 * A surface says the language its words are in (WCAG 3.1.2): the catalogue
 * names it (`label.language`), the surface writes it as `lang`, and every
 * popup it portals out carries it too — a popup sits under the host's
 * `<html lang>`, not under the surface. A host that knows better says so.
 */

import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { Dialog, DialogTitle } from '../src/ui/components/dialog.js';
import { DialogContent } from '../src/ui/kit/popups.js';
import { MessagesProvider, ViewSurface, zhCN } from '../src/ui/index.js';
import type { ViewMessages } from '../src/ui/kit/messages.js';
import { NumberInput } from '../src/ui/filter/FilterValueEditor.js';
import { SurfaceCalendar } from '../src/ui/filter/inputs/calendar.js';
import { TimeOfDay } from '../src/ui/filter/inputs/time.js';

afterEach(cleanup);

function open(props: { messages?: ViewMessages; lang?: string } = {}) {
  render(
    <ViewSurface {...props}>
      <Dialog defaultOpen>
        <DialogContent>
          <DialogTitle>Probe</DialogTitle>
        </DialogContent>
      </Dialog>
    </ViewSurface>,
  );
  return {
    surface: document.querySelector('[data-slot="view-surface"]'),
    popup: screen.getByRole('dialog'),
  };
}

describe('the language a surface is in', () => {
  it('is English under the default catalogue, popups too', () => {
    const { surface, popup } = open();
    expect(surface?.getAttribute('lang')).toBe('en');
    expect(popup.getAttribute('lang')).toBe('en');
  });

  it('is Chinese under the Chinese catalogue, popups too', () => {
    const { surface, popup } = open({ messages: zhCN });
    expect(surface?.getAttribute('lang')).toBe('zh-CN');
    expect(popup.getAttribute('lang')).toBe('zh-CN');
  });

  it('follows a catalogue set around the surface', () => {
    render(
      <MessagesProvider messages={zhCN}>
        <ViewSurface />
      </MessagesProvider>,
    );
    expect(
      document
        .querySelector('[data-slot="view-surface"]')
        ?.getAttribute('lang'),
    ).toBe('zh-CN');
  });

  it('is the host’s where the host says it', () => {
    const { surface, popup } = open({ messages: zhCN, lang: 'zh-Hant' });
    expect(surface?.getAttribute('lang')).toBe('zh-Hant');
    expect(popup.getAttribute('lang')).toBe('zh-Hant');
  });
});

/**
 * The words a third-party piece writes itself, in English, said in the
 * surface's language instead (second-round review): Base UI's number field
 * names its role 「Number field」, which a reader says in place of the role;
 * the date picker names its arrows' bar 「Navigation bar」.
 */
describe('the words a vendored piece would say in English', () => {
  it('names a number box’s role in the surface language', () => {
    render(
      <ViewSurface messages={zhCN}>
        <NumberInput label="数量" value={3} onNumber={() => {}} />
      </ViewSurface>,
    );
    expect(
      screen
        .getByRole('textbox', { name: '数量' })
        .getAttribute('aria-roledescription'),
    ).toBe(zhCN['label.filter.number-field']);
  });

  it('names a time’s hour and minute boxes’ role likewise', () => {
    render(
      <ViewSurface messages={zhCN}>
        <TimeOfDay
          range={false}
          from={{ day: '2026-09-30', time: '10:30' }}
          to={{ day: '', time: '' }}
          onFrom={() => {}}
          onTo={() => {}}
        />
      </ViewSurface>,
    );
    const boxes = screen.getAllByRole('textbox');
    expect(boxes.length).toBeGreaterThan(0);
    for (const box of boxes)
      expect(box.getAttribute('aria-roledescription')).toBe(
        zhCN['label.filter.number-field'],
      );
  });

  it('names the calendar’s month navigation', () => {
    render(
      <ViewSurface messages={zhCN}>
        <SurfaceCalendar mode="single" />
      </ViewSurface>,
    );
    expect(
      screen.getByRole('navigation', {
        name: zhCN['label.date.calendar-nav'],
      }),
    ).toBeDefined();
  });
});
