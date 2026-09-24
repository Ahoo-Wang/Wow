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

import { useEffect, type ReactNode } from 'react';
import type { ViewKind } from '../../model/index.js';
import type { AnyViewRuntime, ViewEngine } from '../../runtime/index.js';
import { kindMismatch, type OpenViewState } from '../../react/index.js';
import { Alert, AlertDescription, AlertTitle } from '../components/alert.js';
import { Skeleton } from '../components/skeleton.js';
import { useViewMessages } from '../MessagesProvider.js';
import { RenderBoundary } from '../RenderBoundary.js';
import { ViewSurface } from '../ViewSurface.js';
import type { EmbedBaseProps } from './options.js';

/**
 * What both embeds are drawn inside: the surface — its theme, wording,
 * language and zone, and how tall it is (`data-embed-size`) — the opening
 * and what can go wrong with it, and one render boundary around the rest.
 *
 * Opening can go wrong three ways, each said here and nowhere else: the view
 * cannot be opened; it opened but is of a kind this entry does not draw (a
 * dashboard named to `EmbeddedView`, a record view to `EmbeddedDashboard` —
 * the two are split by resource, as the workbenches are); or the page's own
 * narrowing was refused, which is never shown in silence (D17-5).
 */
export function EmbedFrame({
  engine,
  opened,
  kinds,
  props,
  children,
}: {
  engine: ViewEngine;
  opened: OpenViewState;
  /** The kinds this entry draws. */
  kinds: readonly ViewKind[];
  props: EmbedBaseProps;
  children(runtime: AnyViewRuntime): ReactNode;
}) {
  const {
    size = 'content',
    autoRefresh = true,
    theme,
    messages: wording,
    locale,
    className,
    ref,
    onRenderFailure,
  } = props;
  const messages = useViewMessages(wording, locale);
  const runtime = opened.runtime;
  const wrongKind = kindMismatch(runtime, kinds);
  const unopenable = opened.error ?? wrongKind;
  // The host's word on the view's own timer, followed as it changes. Set
  // once the view is open; a timer is seconds long, so nothing fires first.
  useEffect(() => {
    runtime?.setAutoRefresh(autoRefresh);
  }, [runtime, autoRefresh]);

  return (
    <ViewSurface
      ref={ref}
      theme={theme}
      messages={wording}
      locale={locale}
      timeZone={engine.environment.timeZone}
      className={className}
      data-embed-size={size}
    >
      {unopenable && (
        <Alert variant="destructive">
          <AlertTitle>{messages.label('label.view.unopenable')}</AlertTitle>
          <AlertDescription>{messages.issue(unopenable)}</AlertDescription>
        </Alert>
      )}
      {/*
        A refused narrowing leaves the wider result running, which is the one
        outcome this must never show in silence: the page asked for one
        customer's shipments and would otherwise quietly list everyone's. It
        reads the same on the first open as on any later one — what was
        refused is the page's own condition, and the page is who can change
        it; the view below is whatever its author saved, and still worth
        showing (D17-5).
      */}
      {opened.scopeIssues.length > 0 && (
        <Alert variant="destructive">
          <AlertTitle>{messages.label('label.scope.refused')}</AlertTitle>
          <AlertDescription>
            {messages.issues(opened.scopeIssues)}
          </AlertDescription>
        </Alert>
      )}
      {opened.loading && <Skeleton className="h-24 w-full" />}
      {runtime && !wrongKind && (
        <RenderBoundary
          name="result"
          resetKeys={[runtime.id]}
          onFailure={onRenderFailure}
        >
          {children(runtime)}
        </RenderBoundary>
      )}
    </ViewSurface>
  );
}
