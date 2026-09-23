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

import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import {
  ContentPanel,
  ImagePanel,
  LinksPanel,
  MarkdownPanel,
} from '../src/ui/index.js';

afterEach(cleanup);

/**
 * A link's name with the note that it opens a tab of its own. The note is a
 * hidden span of its own, and jsdom's name computation drops the space a
 * browser keeps between it and the words before it, so the space is
 * optional here — what is held is that the note is part of the name.
 */
function newTab(name: string): RegExp {
  const words = name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  return new RegExp(`^${words} ?\\(opens in a new tab\\)$`);
}

/**
 * The panels that carry no query — a note, an image, a list of links. They
 * are their own file (`src/ui/DashboardPanels.tsx`), exported on their own,
 * and tested on their own rather than through the grid.
 */
describe('content panels', () => {
  it('draws a heading as its one line of plain text', () => {
    const { container } = render(
      <ContentPanel
        panel={{
          id: 'h',
          kind: 'heading',
          content: '**Stock** <b>now</b>',
          layout: { x: 0, y: 0, w: 24, h: 1 },
        }}
      />,
    );

    const heading = container.querySelector('[data-slot="panel-heading"]');
    // Plain text: nothing in it is read as markdown or markup.
    expect(heading?.textContent).toBe('**Stock** <b>now</b>');
    expect(container.querySelector('b, strong')).toBeNull();
  });

  /**
   * These components are exported on their own, so a host can render one from
   * a config that no `validateDashboard` ever saw. The scheme guard therefore
   * runs here too, and not only during admission.
   */
  it.each(['javascript:alert(1)', 'data:text/html,<script>', 'vbscript:x'])(
    'refuses to load an image from %s',
    src => {
      render(<ImagePanel src={src} alt="Chart" />);

      expect(screen.queryByRole('img')).toBeNull();
      expect(screen.getByText('Chart')).toBeTruthy();
    },
  );

  it('keeps an unsafe destination out of the document', () => {
    render(
      <LinksPanel items={[{ label: 'Payroll', href: 'vbscript:msgbox(1)' }]} />,
    );

    // The label stays — the reader still sees what was meant to be there.
    expect(screen.getByText('Payroll')).toBeTruthy();
    expect(screen.getByText('Payroll').closest('a')).toBeNull();
  });

  it('shows an image whose href is unsafe, without the link', () => {
    render(
      <ImagePanel src="/chart.png" alt="Chart" href="javascript:alert(1)" />,
    );

    expect(screen.getByRole('img')).toBeTruthy();
    expect(screen.getByRole('img').closest('a')).toBeNull();
  });

  it('renders markdown without raw HTML', () => {
    render(<MarkdownPanel content={'# Title\n\n<b>bold</b>'} />);

    expect(screen.getByRole('heading', { name: 'Title' })).toBeTruthy();
    // react-markdown is used without `rehype-raw`, so the tag stays text.
    expect(document.querySelector('b')).toBeNull();
  });

  /**
   * The links in a markdown panel are the only ones a config never lists on
   * their own — they are inside the prose — so they are the ones worth
   * checking twice.
   */
  it('opens a markdown link in its own tab without the opener', () => {
    render(
      <MarkdownPanel content={'See [the report](https://example.com).'} />,
    );

    const link = screen.getByRole('link', { name: newTab('the report') });
    expect(link.getAttribute('target')).toBe('_blank');
    expect(link.getAttribute('rel')).toBe('noopener noreferrer');
  });

  /**
   * A protocol-relative destination is one react-markdown's own transform
   * lets through, so this case fails the moment our check stops running —
   * which a `javascript:` target would not, since that one never reaches us.
   */
  it('keeps the words of a markdown link that goes somewhere unsafe', () => {
    render(<MarkdownPanel content={'[click me](//evil.example/steal)'} />);

    expect(screen.queryByRole('link')).toBeNull();
    expect(screen.getByText('click me')).toBeTruthy();
  });

  it('keeps the hint the author wrote on a link', () => {
    render(
      <MarkdownPanel
        content={'See [the report](https://example.com "Quarterly numbers").'}
      />,
    );

    expect(
      screen
        .getByRole('link', { name: newTab('the report') })
        .getAttribute('title'),
    ).toBe('Quarterly numbers');
  });

  it('shows a placeholder when an image fails to load', () => {
    render(<ImagePanel src="/missing.png" alt="Sales trend" />);

    fireEvent.error(screen.getByRole('img'));

    expect(screen.getByText('Sales trend')).toBeTruthy();
    expect(screen.queryByRole('img')).toBeNull();
  });

  it('falls back to a message when a failed image has no alt text', () => {
    render(<ImagePanel src="/missing.png" fit="cover" />);

    fireEvent.error(
      document.querySelector('[data-slot="image-panel"]') as HTMLImageElement,
    );

    expect(screen.getByText('This image could not be loaded')).toBeTruthy();
  });

  it('wraps a linked image in an anchor that cannot reach the opener', () => {
    render(<ImagePanel src="/a.png" href="https://example.com" alt="A" />);

    const link = screen.getByRole('link', { name: newTab('A') });
    expect(link.getAttribute('rel')).toBe('noopener noreferrer');
    expect(link.getAttribute('target')).toBe('_blank');
  });

  /**
   * A link's name is the text inside it, and a picture without `alt` has
   * none: the link used to be announced as a bare "link". It takes the
   * panel's title when there is one, and says what it does when not.
   */
  it('names a linked image that has no alt text of its own', () => {
    const { rerender } = render(
      <ImagePanel src="/a.png" href="https://example.com" title="Floor plan" />,
    );
    expect(
      screen.getByRole('link', { name: newTab('Floor plan') }),
    ).toBeTruthy();

    rerender(<ImagePanel src="/a.png" href="https://example.com" />);
    expect(
      screen.getByRole('link', {
        name: newTab('Open the linked page'),
      }),
    ).toBeTruthy();
  });

  it('says every link opens a tab of its own', () => {
    render(<LinksPanel items={[{ label: 'Runbook', href: '/runbook' }]} />);

    expect(screen.getByRole('link', { name: newTab('Runbook') })).toBeTruthy();
  });

  it('opens every link with noopener', () => {
    render(
      <LinksPanel
        items={[
          { label: 'Runbook', href: '/runbook', description: 'What to do' },
          { label: 'Mail ops', href: 'mailto:ops@example.com' },
        ]}
      />,
    );

    const links = screen.getAllByRole('link');
    expect(links).toHaveLength(2);
    for (const link of links)
      expect(link.getAttribute('rel')).toBe('noopener noreferrer');
    expect(screen.getByText('What to do')).toBeTruthy();
  });

  it('dispatches by panel kind', () => {
    const { rerender } = render(
      <ContentPanel
        panel={{
          id: 'a',
          kind: 'markdown',
          content: 'note',
          layout: { x: 0, y: 0, w: 1, h: 1 },
        }}
      />,
    );
    expect(screen.getByText('note')).toBeTruthy();

    rerender(
      <ContentPanel
        panel={{
          id: 'a',
          kind: 'image',
          src: '/a.png',
          layout: { x: 0, y: 0, w: 1, h: 1 },
        }}
      />,
    );
    expect(document.querySelector('[data-slot="image-panel"]')).toBeTruthy();

    rerender(
      <ContentPanel
        panel={{
          id: 'a',
          kind: 'links',
          items: [{ label: 'Docs', href: '/docs' }],
          layout: { x: 0, y: 0, w: 1, h: 1 },
        }}
      />,
    );
    expect(screen.getByRole('link', { name: newTab('Docs') })).toBeTruthy();
  });
});
