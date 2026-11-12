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
 * A dashboard panel's header: its marks, the handles that move it, its title
 * or the field it is renamed in, and its menu (`PanelHeader`).
 * `DashboardPanelCard` draws it above the panel's body.
 */

import type { RefObject } from 'react';
import { cn } from 'cn';
import type { ArrangeStep } from '../../dashboard/index.js';
import { hasMarks, PanelMarks, type PanelMarkSet } from './PanelMarks.js';
import { PanelHandle, PanelOrder } from './DashboardArrange.js';
import { hasMenu, PanelMenu, PanelTitleInput } from './PanelMenu.js';
import type { ChartImageOffer } from '../analysis/imageExport.js';
import { CardHeader, CardTitle } from '../components/card.js';
import { Truncated } from '../kit/Truncated.js';

import type {
  DashboardPanelCardProps,
  PanelHeadingLevel,
} from './DashboardPanelCard.js';

/**
 * A panel's header: the marks before the title, the arrange handle while the
 * board is built, the reorder handle in the one-column reading, the title — or the
 * field it is renamed in — and the 「⋯」 menu; the badges on a line under it.
 */
/**
 * A panel's menu at rest on a board that is read: out of sight where a
 * pointer can hover, and back under the pointer on the panel
 * (`group/panel`, the card), under the keyboard and while its menu is open.
 * It is never hidden from the keyboard or a screen reader, and a screen
 * with no hover shows it always. Every data panel carries it — 「刷新这个面
 * 板」 is always on offer — so drawn at rest it was a mark on every card
 * that said nothing about the card (2026-11 audit).
 */
const MENU_RESTS =
  'fve:transition-opacity fve:[@media(hover:hover)]:opacity-0 fve:group-hover/panel:opacity-100 fve:focus-within:opacity-100 fve:has-[[aria-expanded=true]]:opacity-100';

export function PanelHeader({
  panel,
  name,
  headingLevel,
  untitled,
  marks,
  arrange,
  onArrangeCancel,
  order,
  commands,
  menuTrigger,
  onExport,
  picture,
}: Pick<
  DashboardPanelCardProps,
  'panel' | 'onArrangeCancel' | 'order' | 'commands'
> & {
  name: string;
  headingLevel: PanelHeadingLevel;
  untitled: boolean;
  marks: PanelMarkSet;
  /** The arrange command, only while the board is built. */
  arrange?: (step: ArrangeStep) => boolean;
  menuTrigger: RefObject<HTMLButtonElement | null>;
  onExport(): void;
  /** The chart the body draws, as a picture, while it draws one. */
  picture: ChartImageOffer | null;
}) {
  const Title: `h${PanelHeadingLevel}` = `h${headingLevel}`;
  const heading = panel.panel.kind === 'heading';
  const menu = commands && hasMenu(commands) ? commands : undefined;
  // Built, a panel is arranged (`arrange`) or, on a narrow screen, put in
  // order (`order`): the two say the board is being worked on.
  const building = Boolean(arrange ?? order);
  // A title turned off is read, not seen; the row it stood in goes with it
  // when nothing else stands there.
  const bare =
    untitled &&
    !hasMarks(marks) &&
    !arrange &&
    !order &&
    !commands?.renaming &&
    !menu;
  return (
    <CardHeader
      data-untitled={untitled || undefined}
      className={cn('fve:px-(--_fve-panel-padding)', bare && 'fve:sr-only')}
    >
      <CardTitle
        className={cn(
          'fve:flex fve:min-w-0 fve:items-center fve:gap-1',
          heading ? 'fve:text-base' : 'fve:text-sm',
        )}
      >
        <PanelMarks marks={marks} line="glyphs" />
        {/*
        One handle, dragged by a pointer and arranged with by keyboard
        (V-02); only while the board is built (D22 A): outside that,
        nothing on a panel moves it.
      */}
        {arrange && (
          <PanelHandle
            title={name}
            onStep={arrange}
            onCancel={steps => onArrangeCancel?.(steps)}
          />
        )}
        {order && (
          <PanelOrder
            panelId={panel.id}
            title={name}
            index={order.index}
            total={order.total}
            onMove={order.onMove}
          />
        )}
        {commands?.renaming ? (
          <PanelTitleInput
            initial={
              panel.panel.kind === 'heading'
                ? panel.panel.content
                : panel.title || undefined
            }
            name={name}
            heading={heading}
            renaming={commands.renaming}
            returnTo={menuTrigger}
          />
        ) : (
          <Truncated
            as={Title}
            data-slot="panel-title"
            className={cn('fve:min-w-0', untitled && 'fve:sr-only')}
            text={name}
          />
        )}
        {/* A 24px target hung in the title's own line (`-my-1`): the
          menu is on every panel a reader sees, and a header grown by
          it would take its height from a metric card's two rows. */}
        {menu && (
          <span
            // Read, a board is its panels' data: the menu shows when the
            // pointer is on the panel, when the keyboard is on the menu and
            // while it is open, and stays a Tab stop throughout. Always on
            // a screen with no hover, and while the board is built, where
            // it is how a panel is worked on.
            data-rests={building ? undefined : ''}
            className={cn(
              'fve:-my-1 fve:ml-auto fve:flex fve:shrink-0',
              !building && MENU_RESTS,
            )}
          >
            <PanelMenu
              name={name}
              commands={menu}
              triggerRef={menuTrigger}
              onExport={onExport}
              picture={picture}
              {...(arrange ? { arrange } : {})}
            />
          </span>
        )}
      </CardTitle>
      <PanelMarks marks={marks} line="badges" />
    </CardHeader>
  );
}
