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

import type { EChartsCoreOption } from 'echarts/core';
import { createContext } from 'react';
import type { LegendEntry } from './ChartLegend.js';
import type { ChartLibrary } from './load.js';
import { measureText } from './measure.js';
import type { ChartTheme } from './theme.js';

/**
 * A chart as a picture to take away (D33 Q58): PNG or SVG, with the title,
 * the legend and a line saying what range it covers drawn in, so it is read
 * the same once it has left the page.
 *
 * The legend on the page is HTML beside the drawing (`ChartLegend`), not in
 * what the library draws, so a copy of the library's picture would lose it.
 * The picture is drawn again instead, off the page: the drawing as it
 * stands — the family's option at the size it is drawn, patterns and all,
 * without the slider, the brush or the tooltip, which are for a pointer —
 * rendered to an SVG string by the library on its own (`ssr`), under a
 * head of the same library's graphic elements: the title, the range and
 * the legend's entries, laid out here in rows. Both come out as one SVG;
 * a PNG is that SVG drawn onto a canvas at twice its size. Nothing is
 * registered for it that the page's charts do not register already: the
 * graphic component draws the head, where the library's own title and
 * legend components would have added some 6 KB to every first chart.
 *
 * Nothing here evaluates code or writes an inline script or style: the
 * SVG is a string handed to the browser as a file or as an image, which no
 * `script-src` or `style-src` governs; drawing it to a canvas loads it from
 * a `blob:` URL, which a page's `img-src` has to allow (README, CSP).
 */

/** The drawing as it stands, handed over by the chart that draws it. */
export interface ChartCapture {
  library: ChartLibrary;
  /**
   * The family's option for its theme, fitted to the size below as a
   * resize fits it, the zoom left at the whole range.
   */
  option: EChartsCoreOption;
  width: number;
  height: number;
  theme: ChartTheme;
  /** What the legend beside it lists, as drawn: none when it shows none. */
  legend: readonly LegendEntry[];
}

/**
 * How a chart is captured: at least `minWidth` wide, its height following
 * its shape on the page; `undefined` while it has nothing drawn.
 */
export type CaptureChart = (minWidth: number) => ChartCapture | undefined;

/** Where a chart hands over its capture; `null` takes it back. */
export interface ChartImageSlot {
  register(capture: CaptureChart | null): void;
}

/**
 * The slot the chart under it registers with. A surface that offers the
 * picture (`useChartImage`) provides one around the chart it draws; the
 * drawings of a metric card and everything elsewhere find none.
 */
export const ChartImageTarget = createContext<ChartImageSlot | null>(null);

/**
 * The theme a picture is drawn in: the page's, its font stack quoted in
 * single quotes. The library writes a text's font into an SVG attribute
 * without escaping it, and a stack naming 「"system-ui"」 in double quotes
 * closed the attribute early — the file no longer parsed, and a PNG could
 * not be drawn from it (found in the browser walk of batch E).
 */
export function pictureTheme(theme: ChartTheme): ChartTheme {
  return {
    ...theme,
    text: { ...theme.text, family: theme.text.family.replace(/"/g, "'") },
  };
}

/** What the head says over the drawing. */
export interface ImageHead {
  title: string;
  /** One line: the conditions the numbers came back under. */
  range: string;
}

/** The narrowest picture: a phone's chart is drawn wider to be read away. */
export const IMAGE_MIN_WIDTH = 640;

const PAD = 16;
const TITLE_SIZE = 16;
const TEXT_SIZE = 12;
const LINE = 20;
const DOT = 8;
const ENTRY_GAP = 16;

/**
 * The picture as one SVG document: the head, then the drawing under it, on
 * the chart's own ground.
 */
export function chartImageSvg(
  capture: ChartCapture,
  head: ImageHead,
): { svg: string; width: number; height: number } {
  const { library, theme } = capture;
  const width = Math.round(capture.width);
  const height = Math.round(capture.height);
  const drawing = forPicture(capture.option);
  const { elements, height: headHeight } = headElements(
    head,
    capture.legend,
    theme,
    width,
  );
  const top = render(
    library,
    { animation: false, graphic: { elements } },
    {
      width,
      height: headHeight,
    },
  );
  const chart = render(library, drawing, { width, height });
  const total = headHeight + height;
  const svg =
    `<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${total}" viewBox="0 0 ${width} ${total}">` +
    `<rect width="${width}" height="${total}" fill="${attribute(theme.ground)}"/>` +
    placed(top, 0) +
    placed(chart, headHeight) +
    `</svg>`;
  return { svg, width, height: total };
}

/**
 * The drawing without what a pointer uses — the zoom's slider, the brush,
 * the tooltip — and without motion: a picture is its last frame.
 */
function forPicture(option: EChartsCoreOption): EChartsCoreOption {
  const drawing: EChartsCoreOption = { ...option, animation: false };
  for (const pointer of ['dataZoom', 'brush', 'toolbox', 'tooltip'])
    delete drawing[pointer];
  return drawing;
}

/** One option drawn off the page, as an SVG string. */
function render(
  library: ChartLibrary,
  option: EChartsCoreOption,
  size: { width: number; height: number },
): string {
  const chart = library.init(null, null, {
    renderer: 'svg',
    ssr: true,
    ...size,
  });
  try {
    chart.setOption(option);
    return chart.renderToSVGString();
  } finally {
    chart.dispose();
  }
}

/** An SVG document nested at a height of the picture. */
function placed(svg: string, y: number): string {
  return svg.replace(/^\s*<svg\b/, `<svg x="0" y="${y}"`);
}

/** Text for an attribute: nothing that would close it. */
function attribute(text: string): string {
  return text.replace(/[&<>"]/g, char => `&#${char.charCodeAt(0)};`);
}

/**
 * The head's graphic elements and its height: the title, the range under
 * it, then the legend's entries — a dot, or a dash for a computed line, and
 * the name — in as many rows as the width takes.
 */
export function headElements(
  head: ImageHead,
  legend: readonly LegendEntry[],
  theme: ChartTheme,
  width: number,
): { elements: object[]; height: number } {
  const font = (size: number, weight = 400) =>
    `${weight} ${size}px ${theme.text.family}`;
  const room = width - PAD * 2;
  const line = (
    text: string,
    y: number,
    size: number,
    fill: string,
    weight?: number,
  ) => ({
    type: 'text',
    x: PAD,
    y,
    style: {
      text,
      fill,
      font: font(size, weight),
      width: room,
      overflow: 'truncate',
      ellipsis: '…',
      verticalAlign: 'top',
    },
  });
  const elements: object[] = [];
  let y = PAD;
  if (head.title !== '') {
    elements.push(line(head.title, y, TITLE_SIZE, theme.foreground, 600));
    y += LINE + 4;
  }
  elements.push(line(head.range, y, TEXT_SIZE, theme.muted));
  y += LINE;
  let x = PAD;
  if (legend.length > 0) y += 4;
  for (const entry of legend) {
    const name =
      entry.value === undefined ? entry.label : `${entry.label} ${entry.value}`;
    const wide = DOT + 6 + measureText(name, theme.text.family);
    if (x > PAD && x + wide > width - PAD) {
      x = PAD;
      y += LINE;
    }
    const color =
      entry.color === 'currentColor'
        ? theme.foreground
        : theme.resolve(entry.color);
    const middle = y + TEXT_SIZE / 2 + 1;
    elements.push(
      entry.dashed
        ? {
            type: 'line',
            shape: { x1: x, y1: middle, x2: x + DOT + 4, y2: middle },
            style: {
              stroke: color,
              lineWidth: 2,
              lineDash: entry.dashed === 'dotted' ? [1, 2] : [4, 2],
            },
          }
        : {
            type: 'circle',
            shape: { cx: x + DOT / 2, cy: middle, r: DOT / 2 },
            style: { fill: color },
          },
      {
        type: 'text',
        x: x + DOT + 6 + (entry.dashed ? 4 : 0),
        y,
        style: {
          text: name,
          fill: theme.foreground,
          font: font(TEXT_SIZE),
          verticalAlign: 'top',
        },
      },
    );
    x += wide + ENTRY_GAP + (entry.dashed ? 4 : 0);
  }
  if (legend.length > 0) y += LINE;
  return { elements, height: y + PAD / 2 };
}

/**
 * An SVG drawn onto a canvas at `scale` times its size, as a PNG: loaded as
 * an image from a `blob:` URL, which the page's `img-src` must allow.
 */
export async function rasterize(
  svg: string,
  width: number,
  height: number,
  scale = 2,
): Promise<Blob> {
  const url = URL.createObjectURL(
    new Blob([svg], { type: 'image/svg+xml;charset=utf-8' }),
  );
  try {
    const image = new Image();
    await new Promise<void>((resolve, reject) => {
      image.onload = () => resolve();
      image.onerror = () => reject(new Error('The chart image did not load.'));
      image.src = url;
    });
    const canvas = document.createElement('canvas');
    canvas.width = Math.round(width * scale);
    canvas.height = Math.round(height * scale);
    const context = canvas.getContext('2d');
    if (!context) throw new Error('No canvas to draw the chart image on.');
    context.scale(scale, scale);
    context.drawImage(image, 0, 0, width, height);
    return await new Promise<Blob>((resolve, reject) =>
      canvas.toBlob(
        blob =>
          blob ? resolve(blob) : reject(new Error('The PNG was not made.')),
        'image/png',
      ),
    );
  } finally {
    URL.revokeObjectURL(url);
  }
}
