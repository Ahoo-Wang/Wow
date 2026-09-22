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

import type { ReactNode } from 'react';
import { cn } from 'cn';
import { SPACE } from '../layout.js';
import { RenderBoundary } from '../RenderBoundary.js';
import { resultFrameChrome } from '../variants.js';
import type { WorkbenchShellProps } from '../WorkbenchShell.js';

export interface ResultBlockContents {
  /** Whether the block would be the framed one. */
  framed: boolean;
  /** Whether a result is on screen for it to hold. */
  hasResult: boolean;
  /** Whether a request is on its way, which the rows are drawn loading for. */
  pending: boolean;
  /** Whether the strips slot will actually draw something. */
  strips: boolean;
  /** Whether the kind's own result slot will actually draw something. */
  result: boolean;
}

/**
 * Whether there is a result block at all.
 *
 * A frame is a frame **round a result**, so it waits for one: a config the
 * definition refuses never ran, so there is no result and nothing on its
 * way, and the frame drawn round the toolbar alone was an empty result —
 * ruled off, captioned, and with Export still pressable over nothing
 * (F-14). What the reader is owed in that state is the error above it and
 * the way to fix it, which is where both now are. A query that failed keeps
 * the frame: the strip is what the block holds, and it is about the rows.
 *
 * An unframed block draws no frame and so has no empty frame to avoid: it
 * is the kind's own stack, and a dashboard's grid of panels is its result
 * whether a panel has answered yet or not — an editable board with nothing
 * on it is where panels are added from.
 */
export function resultBlockShown({
  framed,
  hasResult,
  pending,
  strips,
  result,
}: ResultBlockContents): boolean {
  if (hasResult || pending || strips) return true;
  return !framed && result;
}

/**
 * The result and its caption, on no card of their own (D12).
 *
 * **The frame knows the four parts it holds, not what they are made of** —
 * a toolbar first, the strips under it, the kind's result, and the caption
 * row that ends it. It used to style five children by descendant selector,
 * three of them slots only a record view has, so the frame all three kinds
 * share carried one kind's furniture by name; D18-1 puts the record and
 * analysis workbenches behind one shell in phase 2, and that shell is meant
 * to grow no `if` per kind. What stays here is the chrome of the two slots
 * the shell itself fills (`ui/variants.tsx`'s `resultFrameChrome` — the
 * toolbar as the first row, a callout's margin); the rest each kind hands
 * in through `slots`, as the recipe `resultSlots()` gave it.
 *
 * The card used to be here for every kind but the dashboard, and it was a
 * frame around a frame in all of them: a table draws its own header layer,
 * its own hairlines between rows and its own edges on the held columns, so a
 * border and 12px of padding around that put the first row of data behind
 * five layers of chrome. Without it the table runs to the block's edge and
 * the lines on screen are the table's own, which is the only set of lines
 * that means anything. A dashboard still opts out (`resultFramed={false}`
 * on the shell): its result is already a grid of cards, and a frame round
 * that would be the card of cards the rule forbids.
 */
export function ResultBlock({
  framed,
  slots,
  children,
}: {
  framed: boolean;
  /**
   * What this kind's own parts wear inside the frame, from
   * `resultSlots()`. Nothing given is a frame that dresses only the two
   * slots the shell fills — which is the whole of an analysis view's, and
   * of the skeleton that stands in for one that is opening.
   */
  slots?: string;
  children: ReactNode;
}) {
  return (
    <section
      data-slot="result-block"
      data-framed={framed || undefined}
      className={cn(
        'flex min-w-0 flex-col',
        // One frame round the result and nothing else (D12): the toolbar is
        // its top row and the caption its bottom row, ruled off; the rows
        // run to its edge; what else lands in it — a query strip, an empty
        // state, cards — keeps a margin of its own.
        framed ? cn(resultFrameChrome, slots) : SPACE.ROWS,
      )}
    >
      {children}
    </section>
  );
}

export interface ShellResultProps extends Pick<
  WorkbenchShellProps,
  'toolbar' | 'result' | 'onRenderFailure'
> {
  framed: boolean;
  /** The kind's slot recipe, as `ResultBlock.slots` takes it. */
  slots?: string;
  /** The strip between the toolbar and the rows, as the shell resolved it. */
  strip: ReactNode;
  /** What resets both boundaries: the open view. */
  resetKeys: readonly unknown[];
}

/**
 * The result block as the shell fills it: the toolbar, the strip and the
 * kind's result, in that order.
 *
 * The host's bulk slot renders in the toolbar and its row slot in the rows,
 * so both are held: a throwing one takes the bar or the rows, and the title
 * bar, the editor and the draft stay. Two boundaries rather than one so that
 * the half that still works still draws — and the strip between them is
 * neither's, because a query that failed has to be readable whatever the
 * host's buttons did.
 */
export function ShellResult({
  framed,
  slots,
  toolbar,
  strip,
  result,
  resetKeys,
  onRenderFailure,
}: ShellResultProps) {
  return (
    <ResultBlock framed={framed} slots={slots}>
      <RenderBoundary
        name="result"
        resetKeys={resetKeys}
        onFailure={onRenderFailure}
      >
        {toolbar}
      </RenderBoundary>
      {strip}
      <RenderBoundary
        name="result"
        resetKeys={resetKeys}
        onFailure={onRenderFailure}
      >
        {result}
      </RenderBoundary>
    </ResultBlock>
  );
}
