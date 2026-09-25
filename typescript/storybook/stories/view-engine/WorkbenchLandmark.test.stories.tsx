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
import type { Meta, StoryObj } from '@storybook/react-vite';
import { expect, userEvent, waitFor, within } from 'storybook/test';
import {
  DataWorkbench,
  zhCN,
  type WorkbenchLandmark,
} from '@ahoo-wang/wow-view-engine/ui';
import { HOST_LANGUAGE, createStoryEngine, savedViews } from './fixtures.js';
import { StoryEngine } from './StoryEngine.js';
import '@ahoo-wang/wow-view-engine/styles.css';

/**
 * The landmark the workbench's column is (Q64).
 *
 * By default the column is the page's `main`. A host whose page already has
 * a `main` of its own, with the workbench inside it, says `landmark="region"`
 * and the column is a `<section>` named by the open view — one `main` on the
 * page, which axe checks after every play here
 * (`landmark-no-duplicate-main`, `landmark-main-is-top-level`).
 *
 * The stylesheet reads the column by its slot, never by its tag, and the
 * second story measures what that buys: the same boxes in both forms.
 */
const meta = {
  title: 'View Engine/组件状态/工作台地标/回归',
  tags: ['!dev', '!autodocs', 'test'],
  parameters: { layout: 'fullscreen' },
} satisfies Meta;

export default meta;

type Story = StoryObj<typeof meta>;

/** The view every story opens, and the name its column is given. */
const VIEW = savedViews[0];

/** A box the same size in every story, so the two forms are laid out alike. */
const FRAME = { width: 1100, height: 640 } as const;

function Workbench({ landmark }: { landmark?: WorkbenchLandmark }) {
  return (
    <StoryEngine create={() => createStoryEngine({ behaviour: 'data' })}>
      {engine => (
        <DataWorkbench
          engine={engine}
          definitionId="orders"
          instanceId={VIEW.id}
          messages={HOST_LANGUAGE.messages}
          locale={HOST_LANGUAGE.locale}
          landmark={landmark}
        />
      )}
    </StoryEngine>
  );
}

/**
 * A host page with its own `main`: a heading of its own, and the workbench
 * placed inside it as a region.
 */
export const InsideHostMain: Story = {
  render: () => (
    <div>
      <header style={{ padding: 12 }}>订单中心</header>
      <main aria-label="订单中心" style={{ padding: 12 }}>
        <h1 style={{ margin: '0 0 12px' }}>订单</h1>
        <div style={FRAME}>
          <Workbench landmark="region" />
        </div>
      </main>
    </div>
  ),
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('table');

    // One `main`, the host's, and the workbench a named region inside it.
    const mains = canvas.getAllByRole('main');
    await expect(mains).toHaveLength(1);
    await expect(mains[0]).toHaveAccessibleName('订单中心');
    const region = canvas.getByRole('region', { name: VIEW.title });
    await expect(region.tagName).toBe('SECTION');
    await expect(region).toHaveAttribute('data-slot', 'workbench-main');
    await expect(mains[0].contains(region)).toBe(true);
    await expect(canvasElement.querySelectorAll('main')).toHaveLength(1);
  },
};

/** The parts whose boxes must not move when the landmark changes. */
const PARTS = [
  'workbench-main',
  'view-sidebar',
  'view-header-block',
  'editor-band',
  'result-block',
  'result-toolbar',
  'record-table',
  'record-pagination',
] as const;

type Boxes = Record<(typeof PARTS)[number], string>;

/** Each part's box, relative to the frame, rounded to the pixel. */
function boxesIn(frame: HTMLElement): Boxes {
  const origin = frame.getBoundingClientRect();
  return Object.fromEntries(
    PARTS.map(slot => {
      const part = frame.querySelector(`[data-slot="${slot}"]`);
      if (!part) return [slot, 'missing'];
      const box = part.getBoundingClientRect();
      return [
        slot,
        [box.left - origin.left, box.top - origin.top, box.width, box.height]
          .map(Math.round)
          .join(','),
      ];
    }),
  ) as Boxes;
}

/** A host that switches the landmark of one mounted workbench. */
function Switching() {
  const [landmark, setLandmark] = useState<WorkbenchLandmark>('main');
  return (
    <div>
      <header style={{ padding: 12 }}>
        <button
          type="button"
          onClick={() =>
            setLandmark(value => (value === 'main' ? 'region' : 'main'))
          }
        >
          换成 {landmark === 'main' ? 'region' : 'main'}
        </button>
      </header>
      <div data-testid="frame" style={FRAME}>
        <Workbench landmark={landmark} />
      </div>
    </div>
  );
}

/**
 * The same layout in both forms.
 *
 * With the editor open, so the rules the column's slot carries — the
 * editor's cap, the result taking what is left, the table filling it — are
 * all in play. The boxes are read in `main`, again in `region`, and once
 * more back in `main`; any rule still spelled with the tag would move a box
 * in the second reading. The column's own rule is read off it as well, so
 * the boxes are not merely equal because nothing applied in either form.
 */
export const SameLayoutEitherWay: Story = {
  render: () => <Switching />,
  play: async ({ canvasElement }) => {
    const canvas = within(canvasElement);
    await canvas.findByRole('table');
    const frame = canvas.getByTestId('frame');
    await userEvent.click(
      canvas.getByRole('button', {
        name: new RegExp(`^${zhCN['label.filter.panel']}`),
      }),
    );
    await waitFor(() =>
      expect(frame.querySelector('[data-slot="editor-band"]')).not.toBeNull(),
    );

    const column = () =>
      frame.querySelector<HTMLElement>('[data-slot="workbench-main"]')!;
    const styled = async () => {
      const style = getComputedStyle(column());
      await expect(style.overflowY).toBe('auto');
      await expect(style.minHeight).toBe('0px');
    };

    await expect(column().tagName).toBe('MAIN');
    await styled();
    const asMain = boxesIn(frame);
    await expect(Object.values(asMain)).not.toContain('missing');

    await userEvent.click(canvas.getByRole('button', { name: '换成 region' }));
    await waitFor(() => expect(column().tagName).toBe('SECTION'));
    await expect(column()).toHaveAccessibleName(VIEW.title);
    await styled();
    await waitFor(() => expect(boxesIn(frame)).toEqual(asMain));

    await userEvent.click(canvas.getByRole('button', { name: '换成 main' }));
    await waitFor(() => expect(column().tagName).toBe('MAIN'));
    await waitFor(() => expect(boxesIn(frame)).toEqual(asMain));
  },
};
