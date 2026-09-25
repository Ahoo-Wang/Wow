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

import { useLayoutEffect, useRef, useState } from 'react';
import {
  type Color,
  differenceEuclidean,
  filterDeficiencyDeuter,
  filterDeficiencyProt,
  filterDeficiencyTrit,
  parse,
  wcagContrast,
} from 'culori';
import { Badge } from '@/ui/components/badge';
import { MEASURED_MODES, type MeasuredMode } from './themeContrast.js';

/**
 * The chart palette's gates (themes.md 5.2), measured in a real browser on
 * the colours the cascade gives a pinned surface — the same gates
 * `test/paletteDistance.test.ts` and `test/paletteInk.test.ts` hold the
 * built-in presets to, so a host can hold its own palette to them on this
 * page, pasted or imported (D35 Q62):
 *
 * - **Neighbours stay apart** — every two slots that can sit side by side,
 *   the eighth beside the first too — in OKLab ×100: 15 to a full-colour
 *   eye, 8 under protanopia and deuteranopia, 6 under tritanopia (culori's
 *   filters at full strength).
 * - **A mark stands off the card** at 3:1 (WCAG 1.4.11). Dark slots all
 *   must; a light slot under it is listed, not failed — the default eight
 *   have three, answered by the reading table and the patterns — and a
 *   palette's author says which ones it accepts.
 * - **A number inside a mark reads** at 4.5:1 in one of the two inks the
 *   chart writes with there: the foreground, or the ground it stands on —
 *   the page in a workbench, the card in a board's panel.
 */

const SLOTS = 8;
const distance = differenceEuclidean('oklab');

export type Vision = 'normal' | 'protanopia' | 'deuteranopia' | 'tritanopia';

export const VISION: Record<
  Vision,
  { see: (color: Color) => Color; line: number }
> = {
  normal: { see: color => color, line: 15 },
  protanopia: { see: filterDeficiencyProt(1), line: 8 },
  deuteranopia: { see: filterDeficiencyDeuter(1), line: 8 },
  tritanopia: { see: filterDeficiencyTrit(1), line: 6 },
};

/** One palette in one preset and mode, measured. */
export interface PaletteReading {
  preset: string;
  mode: MeasuredMode;
  /** The closest two neighbours to each eye, and how far apart. */
  closest: Record<Vision, { apart: number; slots: [number, number] }>;
  /** Slots under 3:1 on the card, 1-based. */
  underCard: number[];
  /** Slots where neither ink reads 4.5:1, on the page or on the card. */
  inkless: number[];
}

/** Whether a reading clears every gate; a light slot under 3:1 is listed, not failed. */
export function clears(reading: PaletteReading): boolean {
  return (
    Object.entries(reading.closest).every(
      ([vision, { apart }]) => apart >= VISION[vision as Vision].line,
    ) &&
    reading.inkless.length === 0 &&
    (reading.mode === 'light' || reading.underCard.length === 0)
  );
}

/** The readings a drawn `PaletteGates` holds, read back off its surfaces. */
export function readPalettes(root: Element): PaletteReading[] {
  return [...root.querySelectorAll<HTMLElement>('[data-palette]')].map(
    surface => JSON.parse(surface.dataset.reading ?? 'null'),
  );
}

/** The colour a probe was painted, as culori reads it. */
function painted(element: Element): Color {
  const color = parse(getComputedStyle(element).backgroundColor);
  if (!color) throw new Error('A probe was painted in no colour');
  return color;
}

function read(root: HTMLElement, preset: string, mode: MeasuredMode) {
  const probe = (token: string) =>
    painted(root.querySelector(`[data-token="${token}"]`)!);
  const palette = Array.from({ length: SLOTS }, (_, index) =>
    probe(`chart-${index + 1}`),
  );
  const card = probe('card');
  const page = probe('background');
  const ink = probe('foreground');
  const closest = Object.fromEntries(
    Object.entries(VISION).map(([vision, { see }]) => {
      let worst = { apart: Infinity, slots: [0, 0] as [number, number] };
      palette.forEach((color, index) => {
        const next = (index + 1) % SLOTS;
        const apart = distance(see(color), see(palette[next])) * 100;
        if (apart < worst.apart)
          worst = { apart, slots: [index + 1, next + 1] };
      });
      return [vision, worst];
    }),
  ) as PaletteReading['closest'];
  const slots = (keep: (color: Color) => boolean) =>
    palette.flatMap((color, index) => (keep(color) ? [index + 1] : []));
  return {
    preset,
    mode,
    closest,
    underCard: slots(color => wcagContrast(color, card) < 3),
    inkless: slots(color =>
      [page, card].some(
        ground =>
          Math.max(wcagContrast(ink, color), wcagContrast(ground, color)) < 4.5,
      ),
    ),
  } satisfies PaletteReading;
}

const TOKENS = [
  ...Array.from({ length: SLOTS }, (_, index) => `chart-${index + 1}`),
  'card',
  'background',
  'foreground',
];

/**
 * The gates, drawn and measured: one pinned surface per preset and mode, its
 * eight slots as swatches and a row per gate. `data-palettes` says how many
 * readings fell short once all are taken; each surface carries its reading
 * as `data-reading`, so a play function reports what it saw.
 *
 * `css` is a stylesheet of the host's to measure — its own preset block —
 * put on the page before anything is read.
 */
export function PaletteGates({
  presets,
  css = '',
}: {
  presets: readonly string[];
  css?: string;
}) {
  const root = useRef<HTMLDivElement>(null);
  const [readings, setReadings] = useState<PaletteReading[] | null>(null);
  const key = presets.join(',');
  useLayoutEffect(() => {
    const style = document.createElement('style');
    style.dataset.paletteGates = '';
    style.textContent = css;
    document.head.append(style);
    setReadings(
      [...root.current!.querySelectorAll<HTMLElement>('[data-palette]')].map(
        surface =>
          read(
            surface,
            surface.dataset.fvePreset!,
            surface.dataset.theme as MeasuredMode,
          ),
      ),
    );
    return () => style.remove();
  }, [key, css]);
  const failures = readings?.filter(reading => !clears(reading)).length;
  return (
    <div
      ref={root}
      data-palettes={failures === undefined ? 'measuring' : failures}
      style={{ display: 'flex', flexWrap: 'wrap', gap: 12 }}
    >
      {presets.flatMap(preset =>
        MEASURED_MODES.map(mode => {
          const reading = readings?.find(
            found => found.preset === preset && found.mode === mode,
          );
          return (
            <section
              key={`${preset}/${mode}`}
              data-palette
              data-reading={reading && JSON.stringify(reading)}
              className="fve-root"
              data-theme={mode}
              data-fve-preset={preset}
              aria-label={`${preset} · ${mode === 'light' ? '亮' : '暗'} · 图表八色`}
              style={{
                flex: '1 1 320px',
                padding: 12,
                borderRadius: 8,
                display: 'flex',
                flexDirection: 'column',
                gap: 6,
                fontSize: 12,
              }}
            >
              <h3 style={{ margin: 0, fontSize: 14, fontWeight: 600 }}>
                {preset} · {mode === 'light' ? '亮' : '暗'} · 图表八色
              </h3>
              <div style={{ display: 'flex', gap: 4 }}>
                {TOKENS.map(token => (
                  <span
                    key={token}
                    data-token={token}
                    title={token}
                    style={{
                      background: `var(--${token})`,
                      width: token.startsWith('chart-') ? 24 : 0,
                      height: 24,
                      borderRadius: 4,
                    }}
                  />
                ))}
              </div>
              {reading && (
                <>
                  {Object.entries(reading.closest).map(
                    ([vision, { apart, slots }]) => (
                      <Gate
                        key={vision}
                        label={`${vision}：第 ${slots.join('、')} 色相距最近`}
                        value={apart.toFixed(1)}
                        ok={apart >= VISION[vision as Vision].line}
                        line={`≥${VISION[vision as Vision].line}`}
                      />
                    ),
                  )}
                  <Gate
                    label="对卡片 <3:1 的色位"
                    value={reading.underCard.join('、') || '无'}
                    ok={mode === 'light' || reading.underCard.length === 0}
                    line={mode === 'light' ? '亮色列出' : '暗色不许有'}
                  />
                  <Gate
                    label="没有一种墨色 ≥4.5:1 的色位"
                    value={reading.inkless.join('、') || '无'}
                    ok={reading.inkless.length === 0}
                    line="不许有"
                  />
                </>
              )}
            </section>
          );
        }),
      )}
    </div>
  );
}

function Gate({
  label,
  value,
  ok,
  line,
}: {
  label: string;
  value: string;
  ok: boolean;
  line: string;
}) {
  return (
    <div
      style={{
        display: 'grid',
        gridTemplateColumns: 'minmax(0, 1fr) auto 48px',
        alignItems: 'center',
        gap: 8,
      }}
    >
      <span style={{ overflowWrap: 'anywhere' }}>{label}</span>
      <span style={{ fontVariantNumeric: 'tabular-nums' }}>{value}</span>
      <Badge variant={ok ? 'secondary' : 'destructive'} title={line}>
        {ok ? '达标' : '不足'}
      </Badge>
    </div>
  );
}
