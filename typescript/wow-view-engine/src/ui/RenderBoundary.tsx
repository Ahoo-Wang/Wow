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

import type { ErrorInfo, ReactNode } from 'react';
import { ErrorBoundary, type FallbackProps } from 'react-error-boundary';
import { RotateCcwIcon } from 'lucide-react';
import { LineAlert, TONE_ICON } from './alerts.js';
import {
  Alert,
  AlertAction,
  AlertDescription,
  AlertTitle,
} from './components/alert.js';
import { Button } from './components/button.js';
import { useViewMessages } from './MessagesProvider.js';
import { Tooltip, TooltipTrigger } from './components/tooltip.js';
import { TooltipContent } from './popups.js';
import { useKindWord } from './kinds.js';
import { isChartFailure } from './charts/failure.js';
import { useFailureSink } from './failureSink.js';

/**
 * The parts of a view a render can fail in, each behind a boundary of its
 * own, so that one of them failing leaves the others standing.
 *
 * - `actions`: the host's global action slot in the title bar;
 * - `editor`: the condition editor, in its band;
 * - `result`: the result block — rows, cards, charts, and the host's bulk
 *   and row action slots inside it;
 * - `panel`: one dashboard panel, by `panelId`;
 * - `detail`: one of the host's sections in a record's detail
 *   (`RecordDetailSection`).
 */
export type RenderBoundaryName =
  'actions' | 'editor' | 'result' | 'panel' | 'detail';

/**
 * What a boundary caught, handed to the host as it is caught. This surface's
 * own callback; the engine's `environment.onError` hears of the same failure
 * as well, where a workbench or an embed is above the boundary (D40).
 */
export interface RenderFailure {
  boundary: RenderBoundaryName;
  /** The dashboard panel that failed, for `panel`; absent elsewhere. */
  panelId?: string;
  error: unknown;
  /** React's component stack, where the renderer provides one. */
  componentStack?: string;
}

export type RenderFailureHandler = (failure: RenderFailure) => void;

export interface RenderBoundaryProps {
  name: RenderBoundaryName;
  panelId?: string;
  /**
   * Values whose change discards a caught failure and draws the children
   * again — the open view's runtime id, for instance, so a failure in one
   * view does not follow the user to the next.
   */
  resetKeys?: readonly unknown[];
  onFailure?: RenderFailureHandler;
  /**
   * A one-line fallback for a slot that sits inside a row of controls, where
   * a block-level alert would break the line it is on.
   */
  compact?: boolean;
  children: ReactNode;
}

/**
 * A boundary around one part of a view.
 *
 * What the host renders into a workbench — the action slots (D6) and a
 * dashboard's markdown — runs inside this package's tree, and React unmounts
 * the whole tree from the nearest boundary up when any of it throws. Without
 * one, a row action that throws takes the title bar, the editor and the
 * unsaved draft with it. With one per part, it takes the rows and nothing
 * else: the fallback says which part failed and offers to draw it again,
 * and the failure itself goes to the host, which is the only one who can fix
 * it. The error is not swallowed — it is reported and made recoverable.
 */
export function RenderBoundary({
  name,
  panelId,
  resetKeys,
  onFailure,
  compact = false,
  children,
}: RenderBoundaryProps) {
  const sink = useFailureSink();
  return (
    <ErrorBoundary
      resetKeys={resetKeys ? [...resetKeys] : undefined}
      onError={(caught: unknown, info: ErrorInfo) => {
        // A chart that could not be drawn arrives wrapped (`ChartFailure`),
        // and both listeners are handed what the library threw.
        const chart = isChartFailure(caught) ? caught : undefined;
        const error = chart ? chart.cause : caught;
        const where = {
          boundary: name,
          ...(panelId === undefined ? {} : { panelId }),
          ...(info.componentStack
            ? { componentStack: info.componentStack }
            : {}),
        };
        // The host's monitoring first (D40): it never throws, and it hears
        // of the failure even when the surface's own handler does.
        sink?.({
          kind: chart ? 'chart' : 'render',
          error,
          context: { operation: chart ? chart.stage : 'render', ...where },
        });
        onFailure?.({ ...where, error });
      }}
      fallbackRender={props => (
        <RenderFailed {...props} name={name} compact={compact} />
      )}
    >
      {children}
    </ErrorBoundary>
  );
}

/** A failed render is an error, and wears the package's error glyph. */
const ErrorIcon = TONE_ICON.error;

/**
 * What stands where the part was: a sentence saying so, the error's own
 * words, and the one way back. `role="alert"` is the `Alert` component's,
 * so the failure is announced where it happens.
 */
function RenderFailed({
  error,
  resetErrorBoundary,
  name,
  compact,
}: FallbackProps & { name: RenderBoundaryName; compact: boolean }) {
  const messages = useViewMessages();
  const word = useKindWord();
  const detail = describe(error);
  const retry = (
    <Button
      variant="outline"
      size="sm"
      data-slot="render-retry"
      onClick={() => resetErrorBoundary()}
    >
      <RotateCcwIcon data-icon="inline-start" />
      {messages.label('label.render.retry')}
    </Button>
  );

  if (compact) {
    const line = (
      // The same callout as every other one (`ui/alerts.tsx`), in the frame
      // that carries no frame: it stands in a row of controls, so it takes
      // only the width of what it says and leaves the line unbroken.
      <LineAlert
        tone="error"
        frame="bare"
        data-slot="render-failed"
        data-boundary={name}
        className="inline-flex w-auto"
      >
        <AlertTitle>{messages.label('label.render.failed')}</AlertTitle>
        <AlertAction>{retry}</AlertAction>
      </LineAlert>
    );
    // What actually went wrong, for whoever can do something about it —
    // one line has no room for it, and the block form below gives it a
    // paragraph. A `Tooltip` rather than the native `title` this used to be
    // (D16): `title` opens for a mouse and for nothing else. Nothing thrown
    // that has anything to say, no tooltip: an empty black square says less
    // than nothing.
    return detail.length === 0 ? (
      line
    ) : (
      <Tooltip>
        <TooltipTrigger render={line} />
        <TooltipContent>{detail}</TooltipContent>
      </Tooltip>
    );
  }

  return (
    <Alert variant="destructive" data-slot="render-failed" data-boundary={name}>
      {/* The error glyph the whole package uses, so the block and the line
          above do not wear two different faces for one failure. */}
      <ErrorIcon />
      <AlertTitle>{messages.label('label.render.failed')}</AlertTitle>
      <AlertDescription>
        <p>{messages.label(word('label.render.failed-hint'))}</p>
        {detail && (
          <p data-slot="render-detail" className="text-muted-foreground">
            {detail}
          </p>
        )}
        <div>{retry}</div>
      </AlertDescription>
    </Alert>
  );
}

/**
 * A render function turned into an element, so that it runs *inside* the
 * boundary around it rather than in whoever built the tree. `actions.global`
 * is a function the workbench has to call; calling it while assembling the
 * shell's props would throw above every boundary the shell draws.
 */
export function RenderSlot({ render }: { render: () => ReactNode }) {
  return <>{render()}</>;
}

/** The error's own words, where it has any; a thrown string is its own. */
function describe(error: unknown): string {
  if (error instanceof Error) return error.message;
  if (typeof error === 'string') return error;
  return '';
}
