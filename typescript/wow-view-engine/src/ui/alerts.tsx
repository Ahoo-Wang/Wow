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

import type * as React from 'react';
import { cva, type VariantProps } from 'class-variance-authority';
import { cn } from 'cn';
import { CircleAlertIcon, InfoIcon, TriangleAlertIcon } from 'lucide-react';
import { Alert } from './components/alert.js';
import { TEXT_UI } from './layout.js';

/** How loud a callout is; it decides the colour, the icon and the role. */
export type AlertTone = 'error' | 'warning' | 'info';

/**
 * The icon each tone wears, written once.
 *
 * Lucide's own distinction: the circle is the error — something stopped —
 * and the triangle is the warning — something is off but the view ran. Two
 * callouts that pick their own glyph teach the reader two vocabularies, so
 * every one of them takes the icon from here.
 */
export const TONE_ICON: Record<AlertTone, typeof InfoIcon> = {
  error: CircleAlertIcon,
  warning: TriangleAlertIcon,
  info: InfoIcon,
};

/**
 * The registry's `Alert`, laid out as one line.
 *
 * `Alert` is a block: a grid of title over description, padded for a banner
 * and with its action pinned to the top-right corner over a reserved column.
 * Everything this package has to say about a view — a warning, a failed
 * query, a write that did not land, a ceiling about to be hit — is a
 * sentence and at most a row of buttons, said above a result that is still
 * the real one (D1). So the grid becomes a wrapping row and the action slot
 * joins the end of it; nothing else about the component is touched.
 *
 * It is a cva extension in a wrapper rather than a copy of these classes at
 * each call site (D16 ruling 8): there is one callout recipe, and a second
 * one is how two callouts start disagreeing about what a warning looks like.
 */
const lineAlertVariants = cva(
  [
    'flex flex-wrap items-center gap-2 rounded-md px-2 py-1',
    // The width is the container's, not 100% of it. The registry's `Alert`
    // is `w-full`, which is a length — 100% of the containing block, margins
    // not deducted — and every callout here is laid out by a flex column
    // that would have stretched it to exactly the right width on its own.
    // The two disagree the moment the callout has a margin: the query strip
    // inside the framed result block carries `m-3`, so `w-full` made it 24px
    // wider than the room it was given and its right edge ran out under the
    // frame (F-14). `w-auto` hands the question back to the layout, which is
    // the only thing that knows about the margins.
    'w-auto',
    // The icon is nudged down half a step to meet the first line of a block;
    // centred on a row, that only leaves it sitting low. It must not shrink
    // either — in a grid cell nothing asked it to.
    '*:[svg]:translate-y-0 *:[svg]:shrink-0',
    // The sentence takes whatever room the rest of the line leaves.
    '[&>[data-slot=alert-title]]:min-w-0 [&>[data-slot=alert-title]]:flex-1',
    // The buttons are the last thing on the line rather than a corner
    // overlay, so the block's reserved column goes with the absolute one.
    'has-data-[slot=alert-action]:pr-2',
    '[&>[data-slot=alert-action]]:static [&>[data-slot=alert-action]]:flex',
    '[&>[data-slot=alert-action]]:items-center [&>[data-slot=alert-action]]:gap-2',
    // A control on this line takes no fill of its own in the dark theme.
    // The tone's text colour was measured against the callout's surface,
    // and `outline` carries `dark:bg-input/30` — which lightens that
    // surface under the words until the pair stops reading: #ff6467 over
    // the blend measured 4.37:1 against 1.4.3's 4.5 (the story
    // `ErrorCalloutInDarkTheme` is where that was caught). The border and
    // the hover are the button's own; only the resting fill goes, and it is
    // written here rather than at each call site because there is one
    // callout recipe (D16 ruling 8) and this is a fact about it.
    'dark:[&>[data-slot=alert-action]_button]:bg-transparent',
  ],
  {
    variants: {
      tone: {
        // `destructive` has a registry token of its own; `warning` is this
        // theme's (`styles.css`), and `info` is the page's own quiet grey.
        error: 'border-destructive text-destructive',
        warning: 'border-warning text-warning',
        info: 'border-border text-muted-foreground',
      },
      frame: {
        /** A band of its own: the registry's border and card surface. */
        box: '',
        /**
         * No frame at all, in the smaller type: one line among many inside
         * a dialog — a manager row's outcome — or a slot that sits in a row
         * of controls, where a framed band would break the line it is on.
         */
        bare: [
          'border-0 bg-transparent p-0',
          'has-data-[slot=alert-action]:pr-0',
          TEXT_UI,
        ],
      },
    },
    defaultVariants: { frame: 'box' },
  },
);

export interface LineAlertProps
  extends
    Omit<React.ComponentProps<typeof Alert>, 'variant'>,
    Omit<VariantProps<typeof lineAlertVariants>, 'tone'> {
  tone: AlertTone;
}

/**
 * One callout, one line high.
 *
 * The tone decides three things together, which is the point of having one
 * of these: the colour, the icon, and how it is announced — an error
 * interrupts a screen reader (`role="alert"`), a warning or a note waits its
 * turn (`role="status"`), because only what stopped the view is worth
 * cutting across whatever its reader was doing.
 *
 * The sentence goes in an `AlertTitle` and the buttons in an `AlertAction`.
 * There is no description: the sentence *is* the message (D1), and a title
 * with nothing under it is a heading for an empty section.
 */
export function LineAlert({
  tone,
  frame,
  className,
  children,
  ...props
}: LineAlertProps) {
  const Icon = TONE_ICON[tone];
  return (
    <Alert
      data-tone={tone}
      role={tone === 'error' ? 'alert' : 'status'}
      className={cn(lineAlertVariants({ tone, frame }), className)}
      {...props}
    >
      <Icon aria-hidden="true" />
      {children}
    </Alert>
  );
}
