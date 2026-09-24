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
import {
  useLayoutEffect,
  useRef,
  useState,
  type CSSProperties,
  type ReactNode,
} from 'react';
import { Badge } from '@/ui/components/badge';
import { measureBorderContrast, measureTextContrast } from './contrast.js';

/**
 * The contrast matrix (phase 5, 5D): every preset × mode × token pair, as the
 * cascade resolves it in a real browser.
 *
 * A pair is two tokens the package paints one on the other — words on the
 * ground they sit on, or a control's edge on what is behind it — and the line
 * it owes: 4.5:1 for text (WCAG 1.4.3), 3:1 for a control's edge and the
 * focus mark (1.4.11). Each is drawn as a probe inside a surface root pinned
 * to the preset and the mode, so what is measured is what `styles.css`,
 * `themes.css` and whatever a host set on `<html>` come to together — the
 * same arithmetic the component stories use (`contrast.ts`), over tokens
 * rather than one screen's elements. The component stories keep measuring
 * `neutral` where a token meets a recipe (a focused row, a checked box); this
 * is the net under every preset.
 */

/** What one pair owes. */
export type ContrastLine = 'text' | 'edge';

export const LINE_RATIO: Record<ContrastLine, number> = { text: 4.5, edge: 3 };

export interface TokenPair {
  /** How the pair reads, and the key its measurement is filed under. */
  name: string;
  line: ContrastLine;
  /** The ink or the edge, as CSS. */
  ink: string;
  /**
   * The ground, bottom layer first: a token, and any tint painted over it —
   * a toned badge's 10% wash on a card is two layers, composed the way the
   * browser composes them.
   */
  grounds: string[];
  /**
   * An edge drawn around a control's own fill: in the dark, inputs and
   * outline buttons wear `bg-input/30`, and the edge has to clear it too.
   */
  control?: boolean;
}

const v = (token: string) => `var(--${token})`;
const tint = (token: string, percent: number) =>
  `color-mix(in oklab, var(--${token}) ${percent}%, transparent)`;

/**
 * Every pair a preset has to keep. Grounds are the ones the package actually
 * paints each ink on; `muted-foreground` on `muted` is not among them — it
 * measures 4.34:1 in `neutral` and the rows on that layer read
 * `quiet-foreground` instead (`styles.css`).
 */
export const TOKEN_PAIRS: readonly TokenPair[] = [
  ...(
    [
      ['foreground', 'background'],
      ['card-foreground', 'card'],
      ['popover-foreground', 'popover'],
      ['primary-foreground', 'primary'],
      ['secondary-foreground', 'secondary'],
      ['accent-foreground', 'accent'],
      ['muted-foreground', 'background'],
      ['muted-foreground', 'card'],
      ['muted-foreground', 'popover'],
      ['foreground', 'muted'],
      ['foreground', 'row-hover'],
      ['quiet-foreground', 'background'],
      ['quiet-foreground', 'muted'],
      ['sidebar-foreground', 'sidebar'],
      ['sidebar-accent-foreground', 'sidebar-accent'],
      ['destructive-foreground', 'destructive'],
      ['destructive', 'background'],
      ['destructive', 'card'],
      ['success', 'background'],
      ['success', 'card'],
      ['warning', 'background'],
      ['warning', 'card'],
    ] as const
  ).map(([ink, ground]): TokenPair => ({
    name: `${ink} / ${ground}`,
    line: 'text',
    ink: v(ink),
    grounds: [v(ground)],
  })),
  // The view list's group headings: the column's ink at 70%.
  {
    name: 'sidebar-foreground 70% / sidebar',
    line: 'text',
    ink: tint('sidebar-foreground', 70),
    grounds: [v('sidebar')],
  },
  // A toned badge: the tone's ink on its own 10% wash, on a card.
  ...(['success', 'warning', 'destructive'] as const).map(
    (tone): TokenPair => ({
      name: `${tone} badge / card`,
      line: 'text',
      ink: v(tone),
      grounds: [v('card'), tint(tone, 10)],
    }),
  ),
  ...(['input', 'ring'] as const).flatMap(edge =>
    (['background', 'card', 'popover'] as const).map((ground): TokenPair => ({
      name: `${edge} / ${ground}`,
      line: 'edge',
      ink: v(edge),
      grounds: [v(ground)],
      control: true,
    })),
  ),
];

/** The two modes a token has; `system` resolves to one of them. */
export const MEASURED_MODES = ['light', 'dark'] as const;

export type MeasuredMode = (typeof MEASURED_MODES)[number];

/** One pair in one preset and mode, measured. */
export interface Measurement {
  preset: string;
  mode: MeasuredMode;
  pair: string;
  line: ContrastLine;
  ratio: number;
  colors: Record<string, string>;
}

/** Whether a measurement clears the line its pair owes. */
export function passes({ line, ratio }: Measurement): boolean {
  return ratio >= LINE_RATIO[line];
}

/** One probe: the pair drawn the way the package draws it. */
function Probe({ pair, mode }: { pair: TokenPair; mode: MeasuredMode }) {
  const inner: ReactNode =
    pair.line === 'text' ? (
      <span data-ink style={{ color: pair.ink, fontSize: 13 }}>
        Aa 字
      </span>
    ) : (
      <span
        data-ink
        style={{
          display: 'inline-block',
          width: 28,
          height: 16,
          borderRadius: 4,
          border: `1px solid ${pair.ink}`,
          // The dark control wash, `dark:bg-input/30`; light has none.
          background:
            pair.control && mode === 'dark' ? tint('input', 30) : 'transparent',
        }}
      />
    );
  return pair.grounds.reduceRight<ReactNode>(
    (child, ground, index) => (
      <div
        style={{
          background: ground,
          display: 'inline-flex',
          justifyContent: 'center',
          padding: '2px 8px',
          borderRadius: 4,
          ...(index === 0 ? ({ minWidth: 56 } satisfies CSSProperties) : {}),
        }}
      >
        {child}
      </div>
    ),
    inner,
  );
}

/** Measures every probe under `root`, read off the elements. */
function measureAll(root: HTMLElement): Measurement[] {
  return [...root.querySelectorAll<HTMLElement>('[data-probe]')].map(cell => {
    const ink = cell.querySelector('[data-ink]')!;
    const line = cell.dataset.line as ContrastLine;
    const measured =
      line === 'text' ? measureTextContrast(ink) : measureBorderContrast(ink);
    return {
      preset: cell.dataset.preset!,
      mode: cell.dataset.mode as MeasuredMode,
      pair: cell.dataset.probe!,
      line,
      ratio: measured.ratio,
      colors: measured.colors,
    };
  });
}

/**
 * The measurements a drawn matrix holds, read back off its rows: each row
 * says its ratio and colours on itself (`data-ratio`, `data-colors`), so a
 * play function reports every pair that fell short and what it saw.
 */
export function readMatrix(root: Element): Measurement[] {
  return [...root.querySelectorAll<HTMLElement>('[data-probe]')].map(row => ({
    preset: row.dataset.preset!,
    mode: row.dataset.mode as MeasuredMode,
    pair: row.dataset.probe!,
    line: row.dataset.line as ContrastLine,
    ratio: Number(row.dataset.ratio),
    colors: JSON.parse(row.dataset.colors ?? '{}'),
  }));
}

/**
 * The matrix, drawn and measured: one surface root per preset and mode, a row
 * per pair — the probe, its ratio against its line and whether it clears it.
 * `data-matrix` says how many measurements fell short once all are taken.
 *
 * `css` is a stylesheet of the host's to measure with the presets — a preset
 * block of its own, typically — put on the page before anything is read.
 */
export function ContrastMatrix({
  presets,
  css = '',
}: {
  presets: readonly string[];
  css?: string;
}) {
  const root = useRef<HTMLDivElement>(null);
  const [measured, setMeasured] = useState<Map<string, Measurement> | null>(
    null,
  );
  // The presets drawn and the host's stylesheet are the input: a new one is
  // a new measurement, and the probes are read off the page, not the list.
  const key = presets.join(',');
  useLayoutEffect(() => {
    const style = document.createElement('style');
    style.dataset.contrastMatrix = '';
    style.textContent = css;
    document.head.append(style);
    const all = measureAll(root.current!);
    setMeasured(
      new Map(all.map(m => [`${m.preset}/${m.mode}/${m.pair}`, m] as const)),
    );
    return () => style.remove();
  }, [key, css]);
  const failures = measured
    ? [...measured.values()].filter(m => !passes(m)).length
    : undefined;
  return (
    <div
      ref={root}
      data-matrix={failures === undefined ? 'measuring' : failures}
      style={{ display: 'flex', flexDirection: 'column', gap: 24 }}
    >
      {presets.map(preset => (
        <section
          key={preset}
          aria-label={`${preset} 预设`}
          style={{ display: 'flex', flexWrap: 'wrap', gap: 12 }}
        >
          {MEASURED_MODES.map(mode => (
            <div
              key={mode}
              className="fve-root"
              data-theme={mode}
              data-fve-preset={preset}
              style={{
                flex: '1 1 360px',
                padding: 12,
                borderRadius: 8,
                display: 'flex',
                flexDirection: 'column',
                gap: 4,
                fontSize: 12,
              }}
            >
              <h3 style={{ margin: '0 0 4px', fontSize: 14, fontWeight: 600 }}>
                {preset} · {mode === 'light' ? '亮' : '暗'}
              </h3>
              {TOKEN_PAIRS.map(pair => {
                const found = measured?.get(`${preset}/${mode}/${pair.name}`);
                return (
                  <div
                    key={pair.name}
                    data-probe={pair.name}
                    data-preset={preset}
                    data-mode={mode}
                    data-line={pair.line}
                    data-ratio={found?.ratio}
                    data-colors={found && JSON.stringify(found.colors)}
                    style={{
                      display: 'grid',
                      gridTemplateColumns: 'minmax(0, 1fr) auto 56px 48px',
                      alignItems: 'center',
                      gap: 8,
                    }}
                  >
                    <span style={{ overflowWrap: 'anywhere' }}>
                      {pair.name}
                    </span>
                    <Probe pair={pair} mode={mode} />
                    <span style={{ fontVariantNumeric: 'tabular-nums' }}>
                      {found ? `${found.ratio.toFixed(2)}:1` : '…'}
                    </span>
                    {found && (
                      <Badge
                        variant={passes(found) ? 'secondary' : 'destructive'}
                        title={`≥${LINE_RATIO[pair.line]}:1`}
                      >
                        {passes(found) ? '达标' : '不足'}
                      </Badge>
                    )}
                  </div>
                );
              })}
            </div>
          ))}
        </section>
      ))}
    </div>
  );
}
