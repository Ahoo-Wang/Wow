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
import { MessagesProvider } from './MessagesProvider.js';
import type { ViewMessages } from './messages.js';

export interface ViewSurfaceProps extends React.ComponentProps<'div'> {
  /** Follows the host when left out; set it to pin an embedded view. */
  theme?: 'light' | 'dark';
  /** Wording, merged over the defaults; this is also where translation goes. */
  messages?: ViewMessages;
}

/**
 * The boundary every view renders inside.
 *
 * The package's stylesheet hangs every token and every base rule off this
 * element, so importing `@ahoo-wang/fetcher-view-engine/styles.css` changes
 * nothing about the page around it, and an embedded view can pin its own
 * theme while the rest of the application follows the host.
 */
/**
 * The theme a surface was given, for what renders outside it. A popup is
 * portalled to the document body, where `.fve-root` and its `data-theme` are
 * not ancestors; `popups.tsx` reads this to carry both onto the popup.
 */
const SurfaceThemeContext = React.createContext<'light' | 'dark' | undefined>(
  undefined,
);

export function useSurfaceTheme(): 'light' | 'dark' | undefined {
  return React.useContext(SurfaceThemeContext);
}

export function ViewSurface({
  className,
  theme,
  messages,
  children,
  ...props
}: ViewSurfaceProps) {
  return (
    <div
      data-slot="view-surface"
      data-theme={theme}
      className={cn('fve-root flex min-h-0 flex-col gap-3', className)}
      {...props}
    >
      <SurfaceThemeContext.Provider value={theme}>
        <MessagesProvider messages={messages}>
          <TooltipProvider>{children}</TooltipProvider>
        </MessagesProvider>
      </SurfaceThemeContext.Provider>
    </div>
  );
}
