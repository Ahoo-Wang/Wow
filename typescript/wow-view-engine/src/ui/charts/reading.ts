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

import type { ChartData } from '../../analysis/index.js';
import type { ChartSpec } from '../../model/index.js';
import type { MessageFormatters } from '../kit/MessagesProvider.js';
import type { FilledNote, SeriesName, ValueLabel } from './family.js';
import { viewOf } from './familyViews.js';

/**
 * A chart as text: a name for the drawing and the same numbers it draws,
 * laid out as a table.
 *
 * The drawing is one image with a name (`role="img"`), so nothing inside the
 * `<svg>` reaches a screen reader — which is the whole point, since what was
 * inside it was axis ticks and an empty `<title>`. This carries the answer
 * instead, and it is built from `ChartData`, the very projection the marks
 * are drawn from, so the two cannot drift: a value on screen with no row
 * here would mean a family drew something the kernel did not shape.
 */
export interface ChartReading {
  /** What is drawn, in one line; the drawing's accessible name. */
  name: string;
  /**
   * The drawing in one sentence, said after its name (`chartSentence`):
   * how many groups, the highest and the lowest. Absent where there is no
   * number to say; a metric card's is its sparkline's, since its face says
   * the headline in words.
   */
  sentence?: string;
  /** The readable table's column headers. */
  header: readonly string[];
  /** One row per drawn datum, every cell already text. */
  rows: readonly (readonly string[])[];
}

/** What a reading needs besides the data: wording and the two labellers. */
export interface ReadingContext {
  messages: MessageFormatters;
  /** A value as its column shows it, category and measure alike. */
  label: ValueLabel;
  /** An alias as its column is titled, or the alias itself. */
  column: (alias: string | undefined) => string | undefined;
  /**
   * A series or a slice standing for one group value, as the legend names
   * it (`useSeriesName`); left out, the value as its column reads it.
   */
  seriesName?: SeriesName;
  /** The surface's language, for a number an axis format prints. */
  locale: string | undefined;
  /**
   * A value the chart filled in, as the tooltip says it (`useFilledNote`):
   * the table says the same 「0（这一天没有记录）」 the tooltip does.
   */
  filled?: FilledNote;
}

export function readChart(
  data: ChartData,
  spec: ChartSpec | undefined,
  ctx: ReadingContext,
): ChartReading {
  const reading = viewOf(data).read(data, spec, ctx);
  const sentence = chartSentence(data, spec, ctx);
  return sentence === undefined ? reading : { ...reading, sentence };
}

/**
 * A chart in one sentence, said after its name (analysis-echarts.md 2.3):
 * what a sighted reader takes in at a glance before reading any number —
 * how many groups, which is highest and which lowest, and over a time axis
 * where it starts, where it ends and which way it went. 「共 12 组，最高 华东
 * ¥1.2万，最低 西南 ¥980」. The table beside it (`ChartReadingTable`) has
 * every number; this is the reading a screen reader hears first.
 *
 * Built from `ChartData`, as the table is, so it says what is drawn: a
 * filled-in 0 is no measured group and is not the lowest, and 「其他」 is
 * named as the chart names it. A metric card says its number in words on
 * its own face, and its sentence is its sparkline's; a chart of no number
 * has none. Each family says its own (`sentence.ts`).
 */
function chartSentence(
  data: ChartData,
  spec: ChartSpec | undefined,
  ctx: ReadingContext,
): string | undefined {
  return viewOf(data).sentence(data, spec, ctx);
}
