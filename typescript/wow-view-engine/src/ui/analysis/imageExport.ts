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

import { useMemo, useState } from 'react';
import type { ViewRuntime } from '../../runtime/index.js';
import { useFilterEditor } from '../../react/index.js';
import {
  chartImageSvg,
  IMAGE_MIN_WIDTH,
  rasterize,
  type CaptureChart,
  type ChartImageSlot,
} from '../charts/image.js';
import { isoDay } from '../display.js';
import { downloadFile, fileName } from '../download.js';
import { useViewMessages } from '../MessagesProvider.js';
import { conditionsText } from '../summary.js';
import { useSurfaceDisplay } from '../ViewSurface.js';

/** The two pictures a chart is taken away as (D33 Q58). */
export type ImageFormat = 'png' | 'svg';

const IMAGE_TYPE: Record<ImageFormat, string> = {
  png: 'image/png',
  svg: 'image/svg+xml',
};

/**
 * Where the chart a surface draws hands itself over (`ChartImageTarget`),
 * and what it handed over: `capture` is set while a drawing is on screen
 * and taken back as it goes, so an offer of its picture comes and goes
 * with it.
 */
export function useChartImageSlot(): {
  slot: ChartImageSlot;
  capture: CaptureChart | null;
} {
  const [capture, setCapture] = useState<CaptureChart | null>(null);
  const slot = useMemo<ChartImageSlot>(
    () => ({ register: next => setCapture(() => next) }),
    [],
  );
  return { slot, capture };
}

/** What a menu offers of the chart on screen as a picture. */
export interface ChartImageOffer {
  /** Draw it as a picture of `format` and hand the file to the browser. */
  take(format: ImageFormat): void;
  /** The last picture asked for could not be made; `null` otherwise. */
  failed: ImageFormat | null;
  /** The failure read: the line that said so goes. */
  dismiss(): void;
}

/**
 * The chart on screen as a picture to take away (D33 Q58), offered where
 * 「导出数据…」 is — the result's toolbar, a panel's 「⋯」 (D25 Q28) — or
 * `null` while no chart is drawn: a table, a metric card (its words are no
 * drawing), before the first answer.
 *
 * The picture's head is the view's title and the conditions the rows came
 * back under, said as the export window says them; the file is named after
 * the title and the day, 「图表」 where the view has no title yet.
 */
export function useChartImageOffer({
  runtime,
  title,
  capture,
}: {
  runtime: ViewRuntime | null;
  /** The view's or the panel's title: the picture's head and file name. */
  title: string;
  capture: CaptureChart | null;
}): ChartImageOffer | null {
  const messages = useViewMessages();
  const display = useSurfaceDisplay();
  const filter = useFilterEditor(runtime);
  const [failed, setFailed] = useState<ImageFormat | null>(null);
  if (!capture) return null;
  const take = (format: ImageFormat) => {
    setFailed(null);
    const now = runtime?.environment.now;
    const name = fileName(
      title.trim() === '' ? messages.label('label.export.image-name') : title,
      isoDay(now ? now() : new Date(), display),
      format,
    );
    const range = conditionsText(
      [...filter.applied, ...filter.scoped, ...filter.implied],
      messages,
      display,
    );
    const fail = () => setFailed(format);
    try {
      const drawn = capture(IMAGE_MIN_WIDTH);
      if (!drawn) {
        fail();
        return;
      }
      const picture = chartImageSvg(drawn, { title: title.trim(), range });
      if (format === 'svg') {
        downloadFile({ name, content: picture.svg, type: IMAGE_TYPE.svg });
        return;
      }
      rasterize(picture.svg, picture.width, picture.height).then(
        png => downloadFile({ name, content: png, type: IMAGE_TYPE.png }),
        fail,
      );
    } catch {
      fail();
    }
  };
  return { take, failed, dismiss: () => setFailed(null) };
}
