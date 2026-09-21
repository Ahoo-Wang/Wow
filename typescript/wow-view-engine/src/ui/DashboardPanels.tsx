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
import type { ReactNode } from 'react';
import Markdown from 'react-markdown';
import { ExternalLinkIcon, ImageOffIcon } from 'lucide-react';
import type { DashboardContentPanel } from '../model/index.js';
import { isSafeContentUrl } from '../dashboard/index.js';
import { useViewMessages } from './MessagesProvider.js';
import {
  Empty,
  EmptyDescription,
  EmptyHeader,
  EmptyMedia,
} from './components/empty.js';
import { TEXT_UI } from './layout.js';
import { cn } from 'cn';

export interface ContentPanelProps {
  panel: DashboardContentPanel;
}

/**
 * The static panels: a note, a picture, a list of links.
 *
 * `validateDashboard` refuses anything but http, https, mailto and relative
 * paths, and the grid keeps a panel it refused off the screen. These
 * components check again anyway: they are exported on their own, so a host may
 * render one from a config nothing admitted, and a guard that only runs
 * somewhere else is not a guard. A URL that passes still says nothing about
 * the resource behind it, so markdown renders without raw HTML, an image that
 * fails to load shows a placeholder rather than a broken frame, and every link
 * opens with `rel="noopener noreferrer"`.
 */
export function ContentPanel({ panel }: ContentPanelProps) {
  switch (panel.kind) {
    case 'markdown':
      return <MarkdownPanel content={panel.content} />;
    case 'image':
      return (
        <ImagePanel
          src={panel.src}
          alt={panel.alt}
          fit={panel.fit}
          href={panel.href}
        />
      );
    default:
      return <LinksPanel items={panel.items} />;
  }
}

export interface MarkdownPanelProps {
  content: string;
}

/**
 * A link written in the markdown, on the same terms as every other link this
 * package draws: an unsafe destination costs the link and leaves the words,
 * and a safe one opens in its own tab without handing the opener over.
 *
 * Markdown is the one content panel whose links are not listed anywhere a
 * config could be checked against — they are inside the prose — so this is
 * where that promise is kept.
 */
const MARKDOWN_COMPONENTS = {
  a({
    href,
    title,
    children,
  }: {
    href?: string;
    title?: string;
    children?: ReactNode;
  }) {
    // `title` is the author's own hint — the check is about where the link
    // goes, so it takes nothing else away from them.
    return href !== undefined && isSafeContentUrl(href) ? (
      <a href={href} title={title} target="_blank" rel="noopener noreferrer">
        {children}
      </a>
    ) : (
      <span title={title}>{children}</span>
    );
  },
};

/**
 * The type scale inside a markdown panel — what `react-markdown` renders has
 * no classes of its own, so someone has to say how a heading and a list look.
 *
 * `@tailwindcss/typography` is the obvious reuse and it is declined here, for
 * reasons rather than for taste:
 *
 * - **`prose` is an article column, this is a box the user sized.** The
 *   plugin clamps to `max-width: 65ch` and scales around a 16–20px body; even
 *   `prose-sm` gives `h1` about 30px, which is twice the panel's own title
 *   and taller than a short panel has to spare.
 * - **Its colours are a gray ramp, not this package's tokens.** Every
 *   `--tw-prose-*` default is a Tailwind gray, so making it obey
 *   `--foreground` / `--muted-foreground` / `--primary` means redefining
 *   sixteen variables in `styles.css` — more theme than the four rules below,
 *   and a second place where a colour is decided.
 * - **Most of it is for elements this panel does not draw.** Raw HTML is off,
 *   so there is no `figure`, no `lead`, no styled table coming.
 *
 * So the four rules stay explicit, and they are written down here rather than
 * inline: the class list is the decision, and `prose-sm` sat in it for months
 * doing nothing at all, because the plugin it belongs to was never installed.
 */
const MARKDOWN_PROSE =
  'text-sm [&_a]:underline [&_h1]:text-base [&_h1]:font-semibold ' +
  '[&_h2]:text-sm [&_h2]:font-semibold [&_ul]:list-disc [&_ul]:pl-4';

/** Markdown with raw HTML left off, which is the whole point of using it. */
export function MarkdownPanel({ content }: MarkdownPanelProps) {
  return (
    <div
      data-slot="markdown-panel"
      className={cn('flex h-full flex-col gap-2 overflow-auto', MARKDOWN_PROSE)}
    >
      <Markdown components={MARKDOWN_COMPONENTS}>{content}</Markdown>
    </div>
  );
}

export interface ImagePanelProps {
  src: string;
  alt?: string;
  fit?: 'contain' | 'cover';
  href?: string;
}

export function ImagePanel({
  src,
  alt,
  fit = 'contain',
  href,
}: ImagePanelProps) {
  const [failed, setFailed] = useState(false);
  const messages = useViewMessages();

  // The same shape every other panel says nothing with: a picture that did
  // not arrive is an empty state, and `Empty` is what this package draws one
  // with (`DashboardGrid` already does, twice). There is no title beside the
  // description, because the `alt` the author wrote is the only wording there
  // is, and repeating "This image could not be loaded" above it would say the
  // failure twice.
  if (failed || !isSafeContentUrl(src))
    return (
      <Empty data-slot="image-panel-placeholder" className="h-full p-4">
        <EmptyHeader>
          <EmptyMedia variant="icon">
            <ImageOffIcon />
          </EmptyMedia>
          <EmptyDescription>
            {alt ?? messages.label('label.image.failed')}
          </EmptyDescription>
        </EmptyHeader>
      </Empty>
    );

  const image = (
    <img
      data-slot="image-panel"
      src={src}
      alt={alt ?? ''}
      loading="lazy"
      referrerPolicy="no-referrer"
      onError={() => setFailed(true)}
      className={cn(
        'h-full w-full',
        fit === 'cover' ? 'object-cover' : 'object-contain',
      )}
    />
  );

  // An unsafe destination costs the link, not the picture.
  return href && isSafeContentUrl(href) ? (
    <a href={href} target="_blank" rel="noopener noreferrer" className="h-full">
      {image}
    </a>
  ) : (
    image
  );
}

export interface LinksPanelProps {
  items: readonly { label: string; href: string; description?: string }[];
}

export function LinksPanel({ items }: LinksPanelProps) {
  return (
    <ul data-slot="links-panel" className="flex flex-col gap-2 overflow-auto">
      {items.map(item => (
        <li key={`${item.href}:${item.label}`}>
          {isSafeContentUrl(item.href) ? (
            <a
              href={item.href}
              target="_blank"
              rel="noopener noreferrer"
              className="flex items-center gap-1 text-sm underline-offset-4 hover:underline"
            >
              {item.label}
              <ExternalLinkIcon className="size-3" aria-hidden />
            </a>
          ) : (
            <span className="text-muted-foreground text-sm">{item.label}</span>
          )}
          {item.description && (
            <p className={cn('text-muted-foreground', TEXT_UI)}>
              {item.description}
            </p>
          )}
        </li>
      ))}
    </ul>
  );
}
