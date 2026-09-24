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
 * What the series list makes of a drag: which drop it will take, and what a
 * screen reader hears while one is under way.
 *
 * Both are here rather than inside the list because neither needs a DOM to
 * be true, and the drop is the one decision a pointer makes that jsdom
 * cannot reach — `@dnd-kit/dom` picks its target by measuring boxes, and
 * every box there is 0×0 at the origin.
 */

import type { CartesianSeries } from '../../model/index.js';
import { dragAccessibility } from '../dragAnnounce.js';
import { dragWording, type DragWordingKeys } from '../dragWording.js';
import { dropped, type DropOperation } from '../dragDrop.js';
import type { MessageFormatters } from '../MessagesProvider.js';

/** The two places one drop on the series list is between. */
export interface SeriesDrop {
  from: number;
  to: number;
}

/**
 * The move a finished drag on the series list is, or null.
 *
 * A series is carried under the alias of the metric it draws: a cartesian
 * chart draws one series per metric — that is what "add series" offers and
 * what the list has always been keyed by — so the two ids the drop reports
 * are the two metrics it is between, and no separate identity has to be
 * invented for the library to hold. Whether there was a drop at all is the
 * guard every sortable list here shares ({@link dropped}); what is added on
 * top is that both ids name a series *this* chart draws, since the panel is
 * not the only draggable thing the page holds.
 */
export function seriesDrop(
  series: readonly CartesianSeries[],
  operation: DropOperation,
  canceled: boolean | undefined,
): SeriesDrop | null {
  const drop = dropped(operation, canceled);
  if (!drop) return null;
  const from = series.findIndex(one => one.metric === drop.source);
  const to = series.findIndex(one => one.metric === drop.target);
  if (from < 0 || to < 0 || from === to) return null;
  return { from, to };
}

/** Where this list's drag sentences live in the catalogue. */
export const SERIES_DRAG_WORDING: DragWordingKeys = {
  instructions: 'label.chart.series-instructions',
  picked: 'label.chart.series-picked',
  cancelled: 'label.chart.series-cancelled',
  placeholder: 'name',
};

/**
 * What a screen reader hears while a series is being carried: the shared
 * wording of a drag ({@link dragAccessibility}) said in this list's own
 * words, with the metric aliases the library is holding turned back into
 * the column titles on screen.
 */
export function seriesDragAccessibility(
  messages: MessageFormatters,
  nameOf: (alias: string) => string,
) {
  return dragAccessibility(dragWording(messages, SERIES_DRAG_WORDING), nameOf);
}
