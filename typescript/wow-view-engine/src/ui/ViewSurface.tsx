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
 * element, so importing `@ahoo-wang/fetcher-view-engine/styles.css` changes
 * nothing about the page around it, and an embedded view can pin its own
 * theme while the rest of the application follows the host.
 *
 * Surfaces do not nest: every workbench and `EmbeddedView` renders one root.
 * Nesting a surface pinned to the opposite mode inside another is not
 * supported — the `dark:` utilities follow the outer root, since CSS has no
 * nearest-ancestor selector.
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
 * The mode the cascade gave this element, read back off it.
 *
 * The stylesheet stays the one source of truth: its dark token block sets
 * `color-scheme: dark` and its light one `color-scheme: light`, so the
 * computed value answers the question the selectors already decided — no
 * second implementation of `.dark`, `data-theme` and their precedence in
 * JavaScript. What decides it is an attribute on the element or on one of its
 * ancestors, so each of them is observed for a `class` or `data-theme` change
 * rather than the whole document subtree.
 */
function useResolvedTheme(
  rootRef: React.RefObject<HTMLDivElement | null>,
): 'light' | 'dark' | undefined {
  const [resolved, setResolved] = React.useState<'light' | 'dark' | undefined>(
    undefined,
  );
  React.useEffect(() => {
    const el = rootRef.current;
    if (!el) return;
    const read = () =>
      setResolved(
        getComputedStyle(el).colorScheme === 'dark' ? 'dark' : 'light',
      );
    read();
    const observers: MutationObserver[] = [];
    for (let node: Element | null = el; node; node = node.parentElement) {
      const observer = new MutationObserver(read);
      observer.observe(node, {
        attributes: true,
        attributeFilter: ['class', 'data-theme'],
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
  ...props
}: ViewSurfaceProps) {
  const rootRef = React.useRef<HTMLDivElement>(null);
  const resolved = useResolvedTheme(rootRef);
  const display = React.useMemo(
    () => ({ locale, timeZone }),
    [locale, timeZone],
  );
  return (
    <div
      data-slot="view-surface"
      data-theme={theme}
      className={cn('fve-root flex min-h-0 flex-col gap-3', className)}
      ref={rootRef}
      {...props}
    >
      <SurfaceThemeContext.Provider value={theme ?? resolved}>
        <SurfaceDisplayContext.Provider value={display}>
          <MessagesProvider messages={messages}>
            <TooltipProvider>{children}</TooltipProvider>
          </MessagesProvider>
        </SurfaceDisplayContext.Provider>
      </SurfaceThemeContext.Provider>
    </div>
  );
}
