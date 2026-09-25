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
import {
  contrastPairs,
  type ContrastPair,
  type Layer,
  linesOf,
  type PairKind,
} from '@/ui/theme/pairs';
import { measureBorderContrast, measureTextContrast } from './contrast.js';

/**
 * The contrast matrix (phase 5, 5D): every preset × mode × token pair, as the
 * cascade resolves it in a real browser.
 *
 * A pair is two tokens the package paints one on the other — words on the
 * ground they sit on, a control's edge or a mark on what is behind it — and
 * the line it owes: 4.5:1 for text (WCAG 1.4.3), 3:1 for a control's edge,
 * the focus mark and a mark that carries state (1.4.11), higher where a
 * preset promises more. The pairs and the lines are the package's registry
 * (`src/ui/theme/pairs.ts`), the same list its jsdom suite measures by
 * arithmetic, so the two cannot part. Each is drawn here as a probe inside a
 * surface root pinned to the preset and the mode, so what is measured is
 * what `styles.css`, `themes.css` and whatever a host set on `<html>` come
 * to together — the same arithmetic the component stories use
 * (`contrast.ts`), over tokens rather than one screen's elements. The
 * component stories keep measuring `neutral` where a token meets a recipe
 * (a focused row, a checked box); this is the net under every preset.
 */

/** One layer of the registry's, as CSS. */
const paint = ({ token, alpha }: Layer) =>
  alpha === undefined
    ? `var(--${token})`
    : `color-mix(in oklab, var(--${token}) ${Math.round(alpha * 100)}%, transparent)`;

/** The two modes a token has; `system` resolves to one of them. */
export const MEASURED_MODES = ['light', 'dark'] as const;

export type MeasuredMode = (typeof MEASURED_MODES)[number];

/** The pairs the matrix draws in each mode: the registry's, expanded. */
export const PAIRS: Readonly<Record<MeasuredMode, readonly ContrastPair[]>> = {
  light: contrastPairs('light'),
  dark: contrastPairs('dark'),
};

/** One pair in one preset and mode, measured. */
export interface Measurement {
  preset: string;
  mode: MeasuredMode;
  pair: string;
  kind: PairKind;
  /** The ratio the pair owes in this preset. */
  line: number;
  ratio: number;
  colors: Record<string, string>;
}

/** Whether a measurement clears the line its pair owes. */
export function passes({ line, ratio }: Measurement): boolean {
  return ratio >= line;
}

/**
 * One probe: the pair drawn the way the package draws it — words in the
 * ink, or an edge or a mark as a 1px border of it — on its ground, bottom
 * layer first, each tint composed over the one under it by the browser.
 */
function Probe({ pair }: { pair: ContrastPair }) {
  const inner: ReactNode =
    pair.kind === 'text' ? (
      <span data-ink style={{ color: paint(pair.ink), fontSize: 13 }}>
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
          border: `1px solid ${paint(pair.ink)}`,
        }}
      />
    );
  return pair.ground.reduceRight<ReactNode>(
    (child, layer, index) => (
      <div
        style={{
          background: paint(layer),
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
    const kind = cell.dataset.kind as PairKind;
    const measured =
      kind === 'text' ? measureTextContrast(ink) : measureBorderContrast(ink);
    return {
      preset: cell.dataset.preset!,
      mode: cell.dataset.mode as MeasuredMode,
      pair: cell.dataset.probe!,
      kind,
      line: Number(cell.dataset.line),
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
    kind: row.dataset.kind as PairKind,
    line: Number(row.dataset.line),
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
              {PAIRS[mode].map(pair => {
                const found = measured?.get(`${preset}/${mode}/${pair.name}`);
                return (
                  <div
                    key={pair.name}
                    data-probe={pair.name}
                    data-preset={preset}
                    data-mode={mode}
                    data-kind={pair.kind}
                    data-line={linesOf(preset)[pair.kind]}
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
                    <Probe pair={pair} />
                    <span style={{ fontVariantNumeric: 'tabular-nums' }}>
                      {found ? `${found.ratio.toFixed(2)}:1` : '…'}
                    </span>
                    {found && (
                      <Badge
                        variant={passes(found) ? 'secondary' : 'destructive'}
                        title={`≥${found.line}:1`}
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
