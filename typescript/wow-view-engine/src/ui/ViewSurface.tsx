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

import * as React from 'react';
import { cn } from 'cn';
import { TooltipProvider } from './components/tooltip.js';
import type { DisplayContext } from './display.js';
import { MessagesProvider } from './MessagesProvider.js';
import type { ViewMessages } from './messages.js';
import { ViewExpandExit } from './ViewExpansion.js';
import { CHART_TOKENS } from './charts/theme.js';

export interface ViewSurfaceProps extends React.ComponentProps<'div'> {
  /** Follows the host when left out; set it to pin an embedded view. */
  theme?: 'light' | 'dark';
  /**
   * Wording, merged over what is already in force — the defaults, or an outer
   * `MessagesProvider`; this is also where translation goes.
   */
  messages?: ViewMessages;
  /** The language dates and times show in; the runtime's when left out. */
  locale?: string;
  /**
   * The zone times show in. A workbench passes its engine's, the zone a
   * relative filter is evaluated in, so a row's time reads on the same clock
   * it was filtered by.
   */
  timeZone?: string;
}

/**
 * The boundary every view renders inside.
 *
 * The package's stylesheet hangs every token and every base rule off this
 * element, so importing `@ahoo-wang/wow-view-engine/styles.css` changes
 * nothing about the page around it, and an embedded view can pin its own
 * theme while the rest of the application follows the host.
 *
 * Surfaces do not nest: every workbench and `EmbeddedView` renders one root,
 * and a root inside a root is unsupported. CSS has no nearest-ancestor
 * selector, so the inner root's mode reaches only its own tokens — it
 * redeclares them on itself — while the `dark:` utilities still resolve
 * against the outer root, and an inner surface pinned to the opposite mode is
 * served light tokens under dark utilities (`test/styleBoundary.test.tsx`
 * pins the values).
 *
 * A host that wants this package's primitives in its *own* chrome therefore
 * does not wrap that chrome in a second surface: it puts the `fve-tokens`
 * class on it (D17-10). That boundary carries the tokens, the utilities and
 * the preflight, and nothing of a surface — no paint, no `data-theme`, no
 * pinned mode; it follows a `.dark` ancestor exactly as the surfaces do, and
 * hands every element a surface answers for back to that surface, so a view
 * pinned to the other mode inside it stays that mode throughout.
 */
/**
 * The mode a surface is actually in, for what renders outside it. A popup is
 * portalled to the document body, where `.fve-root` and its `data-theme` are
 * not ancestors; `popups.tsx` reads this to carry both onto the popup. It
 * holds the pinned `theme` when there is one, and otherwise the mode the
 * surface resolved from the cascade — so a `.dark` on any ancestor, not only
 * on `<html>`, reaches the popup too.
 */
const SurfaceThemeContext = React.createContext<'light' | 'dark' | undefined>(
  undefined,
);

export function useSurfaceTheme(): 'light' | 'dark' | undefined {
  return React.useContext(SurfaceThemeContext);
}

/**
 * The values of the tokens a chart reads, as the surface's root holds them —
 * one string, equal for two readings of the same theme.
 *
 * The mode alone is not enough to say when to read a chart's colours again:
 * a host that swaps its `--fve-*` (a preset, a brand) changes the tokens and
 * leaves the mode where it was, and a chart that listened for the mode stayed
 * in the old colours while every other part of the view had moved (phase 5,
 * 5A). The library is handed concrete colours, not `var()`s, so the cascade
 * cannot reach it on its own; this is the one thing the context carries for
 * it, and it is a reading of the stylesheet, not a second theme.
 */
const SurfaceTokensContext = React.createContext<string | undefined>(undefined);

export function useSurfaceTokens(): string | undefined {
  return React.useContext(SurfaceTokensContext);
}

/**
 * The tokens `useSurfaceTokens` reads: the chart's own, and the two grounds
 * a chart stands on (a workbench's page, a panel's card), whose paint it
 * reads for the halo round a value label.
 */
const WATCHED_TOKENS = [...CHART_TOKENS, '--background', '--card'];

/**
 * The attributes on the root or an ancestor that can change what the
 * stylesheet resolves to: `.dark` and any class a host themes by,
 * `data-theme` (a pinned mode), `data-fve-preset` (a preset, phase 5) and
 * `style`, where a host may set `--fve-*` inline. A stylesheet swapped with
 * no attribute changing is invisible to this, and the design says so.
 */
const WATCHED_ATTRIBUTES = ['class', 'data-theme', 'data-fve-preset', 'style'];

interface ResolvedTheme {
  mode: 'light' | 'dark';
  tokens: string;
}

/**
 * The mode the cascade gave this element, read back off it, and the tokens a
 * chart reads (`useSurfaceTokens`).
 *
 * The stylesheet stays the one source of truth: its dark token block sets
 * `color-scheme: dark` and its light one `color-scheme: light`, so the
 * computed value answers the question the selectors already decided — no
 * second implementation of `.dark`, `data-theme` and their precedence in
 * JavaScript. What decides it is an attribute on the element or on one of its
 * ancestors, so each of them is observed for a change to one of
 * `WATCHED_ATTRIBUTES` rather than the whole document subtree; a change that
 * leaves both readings as they were renders nothing.
 */
function useResolvedTheme(
  rootRef: React.RefObject<HTMLDivElement | null>,
): ResolvedTheme | undefined {
  const [resolved, setResolved] = React.useState<ResolvedTheme>();
  React.useEffect(() => {
    const el = rootRef.current;
    if (!el) return;
    const read = () => {
      const style = getComputedStyle(el);
      const next: ResolvedTheme = {
        mode: style.colorScheme === 'dark' ? 'dark' : 'light',
        tokens: WATCHED_TOKENS.map(name =>
          style.getPropertyValue(name).trim(),
        ).join(';'),
      };
      setResolved(previous =>
        previous?.mode === next.mode && previous.tokens === next.tokens
          ? previous
          : next,
      );
    };
    read();
    const observers: MutationObserver[] = [];
    for (let node: Element | null = el; node; node = node.parentElement) {
      const observer = new MutationObserver(read);
      observer.observe(node, {
        attributes: true,
        attributeFilter: WATCHED_ATTRIBUTES,
      });
      observers.push(observer);
    }
    return () => {
      for (const observer of observers) observer.disconnect();
    };
  }, [rootRef]);
  return resolved;
}

const SurfaceDisplayContext = React.createContext<DisplayContext>({});

/** The language and zone the nearest surface shows values in. */
export function useSurfaceDisplay(): DisplayContext {
  return React.useContext(SurfaceDisplayContext);
}

export function ViewSurface({
  className,
  theme,
  messages,
  locale,
  timeZone,
  children,
  ref,
  ...props
}: ViewSurfaceProps) {
  const rootRef = React.useRef<HTMLDivElement>(null);
  const resolved = useResolvedTheme(rootRef);
  const display = React.useMemo(
    () => ({ locale, timeZone }),
    [locale, timeZone],
  );
  // The surface keeps its own handle on the root — the resolved theme is read
  // off it — and hands the caller the same element. A caller's ref cannot
  // simply arrive in `...props` and win: it would replace this one, and the
  // theme would stop following the cascade.
  //
  // It always returns a cleanup, which means React never calls it with null
  // and detaching is this function's own job. That is also how a caller's
  // cleanup survives: React 19 lets a callback ref return one — a host
  // installing a `ResizeObserver` on the root returns its disconnect — and a
  // merge that dropped the return value would leave that observer running
  // for the life of the page.
  const attach = React.useCallback(
    (node: HTMLDivElement | null) => {
      rootRef.current = node;
      const released = typeof ref === 'function' ? ref(node) : undefined;
      if (ref && typeof ref !== 'function') ref.current = node;
      return () => {
        rootRef.current = null;
        if (typeof released === 'function') released();
        else if (typeof ref === 'function') ref(null);
        else if (ref) ref.current = null;
      };
    },
    [ref],
  );
  return (
    <div
      data-slot="view-surface"
      data-theme={theme}
      className={cn('fve-root flex min-h-0 flex-col gap-3', className)}
      {...props}
      ref={attach}
    >
      <SurfaceThemeContext.Provider value={theme ?? resolved?.mode}>
        <SurfaceTokensContext.Provider value={resolved?.tokens}>
          <SurfaceDisplayContext.Provider value={display}>
            <MessagesProvider messages={messages} locale={locale}>
              <TooltipProvider>
                {/* Hidden until `useViewExpansion` finds that this surface
                  fills the screen with its control left underneath it; see
                  `ViewExpandExit`. It is a direct child of the root because
                  the stylesheet places it as one of the root's flex items,
                  and it stays out of the page — and out of the a11y tree —
                  the rest of the time. */}
                <ViewExpandExit />
                {children}
              </TooltipProvider>
            </MessagesProvider>
          </SurfaceDisplayContext.Provider>
        </SurfaceTokensContext.Provider>
      </SurfaceThemeContext.Provider>
    </div>
  );
}
