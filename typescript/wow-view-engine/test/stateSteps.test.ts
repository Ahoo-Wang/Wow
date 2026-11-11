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
 * A state can be told from the one beside it (ui/theme.md「状态阶梯」): a
 * hovered row from a row at rest, a selected row from both and from the
 * header band above it, a control under the pointer from the control at
 * rest, and a pressed one from that.
 *
 * Each step is the distance in OKLab between the two colours the surface
 * paints, on the values the shipped stylesheets resolve to, in every
 * built-in preset and both modes. A control's state also moves away from
 * the page it stands on, never back toward it: a filled control that lost
 * its fill under the pointer was a step, the wrong way. `STEP` is about the
 * smallest difference between two adjoining areas a reader sees; before the
 * state strengths a selected row was the header band's own colour and a
 * hovered row sat 0.015 from the row at rest.
 */

import { converter } from 'culori';
import { describe, expect, it } from 'vitest';
import {
  type Mode,
  over,
  PRESET_NAMES,
  resolveTokens,
  type Rgba,
  tokenVariable,
} from './fixtures/themeTokens';

const toOklab = converter('oklab');

/** The least distance in OKLab between two states that adjoin. */
const STEP = 0.02;

/** A stack of tokens, bottom first; one the theme leaves unset paints nothing. */
type Stack = readonly string[];

const ROW: Stack = ['background', 'content'];
const CONTROL: Stack = ['background', 'control'];

const STEPS: readonly {
  name: string;
  from: Stack;
  to: Stack;
  /** `to` is farther from the page than `from` is. */
  onward?: true;
}[] = [
  {
    name: 'hovered row off the row at rest',
    from: ROW,
    to: [...ROW, 'row-hover'],
  },
  {
    name: 'selected row off the row at rest',
    from: ROW,
    to: [...ROW, 'row-selected'],
  },
  {
    name: 'selected row off the hovered row',
    from: [...ROW, 'row-hover'],
    to: [...ROW, 'row-selected'],
  },
  {
    name: 'selected row off the header band',
    from: [...ROW, 'table-header'],
    to: [...ROW, 'row-selected'],
  },
  {
    name: 'hovered control off the control at rest',
    from: CONTROL,
    to: ['background', 'control-hover'],
    onward: true,
  },
  {
    name: 'pressed control off the hovered control',
    from: ['background', 'control-hover'],
    to: ['background', 'control-pressed'],
    onward: true,
  },
];

/**
 * A step a preset is short of today, each cleared when the preset is tuned
 * to the state strengths; an entry goes once its step is long enough.
 */
const PENDING: readonly { preset: string; mode: Mode; step: string }[] = [
  { preset: 'azure', mode: 'light', step: 'hovered row off the row at rest' },
];

function distance(one: Rgba, other: Rgba): number {
  const [a, b] = [one, other].map(({ r, g, b }) =>
    toOklab({ mode: 'rgb', r, g, b })!,
  );
  return Math.hypot(a.l - b.l, a.a - b.a, a.b - b.b);
}

function steps(preset: string, mode: Mode) {
  const tokens = resolveTokens(preset, mode);
  const painted = (stack: Stack) =>
    stack
      .map(token => tokens.get(tokenVariable(token)))
      .filter(color => color !== undefined)
      .reduce((below, color) => over(color, below));
  const page = painted(['background']);
  return STEPS.map(({ name, from, to, onward }) => ({
    name,
    // A step back toward the page is no step.
    distance:
      onward && distance(painted(to), page) < distance(painted(from), page)
        ? 0
        : distance(painted(from), painted(to)),
  }));
}

const PLACES = PRESET_NAMES.flatMap(preset =>
  (['light', 'dark'] as const).map(mode => [preset, mode] as const),
);

describe('a state is told from the one beside it', () => {
  it.each(PLACES)('%s, %s', (preset, mode) => {
    const short = steps(preset, mode).filter(
      ({ name, distance: length }) =>
        length < STEP &&
        !PENDING.some(
          pending =>
            pending.preset === preset &&
            pending.mode === mode &&
            pending.step === name,
        ),
    );
    expect(short).toEqual([]);
  });

  it('and a pending step is still short', () => {
    for (const { preset, mode, step } of PENDING) {
      const found = steps(preset, mode).find(({ name }) => name === step);
      expect(found, step).toBeDefined();
      expect(found!.distance, `${preset} ${mode} ${step}`).toBeLessThan(STEP);
    }
  });
});
