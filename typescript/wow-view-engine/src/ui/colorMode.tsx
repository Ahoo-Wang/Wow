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
 * Light or dark, as `ViewHost` paints the page (host-integration.md 4.1,
 * 4.2): one `.dark` class and the browser's `color-scheme` on `<html>`,
 * which the engine's surfaces, the host's own chrome in `fve-tokens` and a
 * shadcn host's own tokens all follow — the system's by default, followed
 * live, or pinned by the reader and kept on this machine. A host that
 * already paints its mode (next-themes, its own switch) says `host`, and
 * the engine leaves `<html>` alone.
 */

import {
  createContext,
  useContext,
  useLayoutEffect,
  useMemo,
  useState,
  type ReactNode,
} from 'react';

/** The modes a reader may pick: the system's, or one pinned. */
export type ColorMode = 'system' | 'light' | 'dark';

/** Who paints the mode: the engine, starting from one of the three, or the host. */
export type HostColorMode = ColorMode | 'host';

/** What `useColorMode` hands a host's switch. */
export interface ColorModeControl {
  /** The mode in force; `host` where the host paints its own. */
  mode: HostColorMode;
  /**
   * Picks a mode for this reader — kept on this machine where the host asked
   * (`ViewHost`'s `rememberColorMode`); picking the host's own starting mode
   * forgets the pick. Does nothing under `host`.
   */
  setMode(mode: ColorMode): void;
}

const SYSTEM_DARK = '(prefers-color-scheme: dark)';

const HOST: ColorModeControl = { mode: 'host', setMode: () => {} };

const ColorModeContext = createContext<ColorModeControl>(HOST);

function isColorMode(value: unknown): value is ColorMode {
  return value === 'system' || value === 'light' || value === 'dark';
}

/** The mode kept under `key`, or `null` where none is (or storage refuses). */
function readKept(key: string | undefined): ColorMode | null {
  if (!key) return null;
  try {
    const kept = window.localStorage.getItem(key);
    return isColorMode(kept) ? kept : null;
  } catch {
    // Storage refused (a private window, blocked site data).
    return null;
  }
}

/** Keeps `mode` under `key`; the host's own starting mode is no pick to keep. */
function keep(
  key: string | undefined,
  mode: ColorMode,
  starting: ColorMode,
): void {
  if (!key) return;
  try {
    if (mode === starting) window.localStorage.removeItem(key);
    else window.localStorage.setItem(key, mode);
  } catch {
    // Kept for this visit only.
  }
}

/** The system's preference, where the browser has one to ask. */
function systemQuery(): MediaQueryList | undefined {
  return typeof window.matchMedia === 'function'
    ? window.matchMedia(SYSTEM_DARK)
    : undefined;
}

/** Paints `root` light or dark: its `.dark` and the browser's own controls. */
function paint(root: HTMLElement, dark: boolean): void {
  root.classList.toggle('dark', dark);
  root.style.colorScheme = dark ? 'dark' : 'light';
}

export interface ColorModeHostProps {
  /** Who paints, and from which mode; `system` by default. */
  colorMode?: HostColorMode;
  /** Where the reader's pick is kept (a `localStorage` key); not kept when left out. */
  rememberColorMode?: string;
  /** Whether this host paints `<html>` at all: only the outermost one does. */
  paints: boolean;
  children: ReactNode;
}

/**
 * Paints `<html>` for the mode in force, before the first paint, and again
 * as the system changes while it follows it; puts `<html>` back as it found
 * it when it goes.
 */
export function ColorModeHost({
  colorMode = 'system',
  rememberColorMode,
  paints,
  children,
}: ColorModeHostProps) {
  const outer = useContext(ColorModeContext);
  const [picked, setPicked] = useState<ColorMode | null>(() =>
    readKept(rememberColorMode),
  );
  // The mode the host starts from, where the engine paints at all.
  const starting: ColorMode | null =
    paints && colorMode !== 'host' ? colorMode : null;
  const mode: ColorMode | null = starting && (picked ?? starting);

  useLayoutEffect(() => {
    if (mode === null) return;
    const root = document.documentElement;
    const before = {
      dark: root.classList.contains('dark'),
      scheme: root.style.colorScheme,
    };
    const media = systemQuery();
    const draw = () =>
      paint(root, mode === 'dark' || (mode === 'system' && !!media?.matches));
    draw();
    if (mode === 'system') media?.addEventListener('change', draw);
    return () => {
      media?.removeEventListener('change', draw);
      root.classList.toggle('dark', before.dark);
      root.style.colorScheme = before.scheme;
    };
  }, [mode]);

  const control = useMemo<ColorModeControl>(
    () =>
      mode === null || starting === null
        ? paints
          ? HOST
          : outer
        : {
            mode,
            setMode: next => {
              keep(rememberColorMode, next, starting);
              setPicked(next);
            },
          },
    [mode, paints, outer, rememberColorMode, starting],
  );
  return (
    <ColorModeContext.Provider value={control}>
      {children}
    </ColorModeContext.Provider>
  );
}

/**
 * The page's mode and a way to pick one, for the host's own switch (a
 * menu in its top bar): what the `ViewHost` above paints. Under a host
 * that paints its own (`colorMode="host"`), or with no `ViewHost` above,
 * the mode is `host` and picking does nothing.
 */
export function useColorMode(): ColorModeControl {
  return useContext(ColorModeContext);
}
