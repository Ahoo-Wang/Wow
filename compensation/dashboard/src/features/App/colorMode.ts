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

import { useCallback, useEffect, useState } from "react";

/**
 * Light or dark, as the console paints it (console-redesign.md §6): the
 * system's by default, or pinned to one from the top bar and kept on this
 * machine. It is one class on `<html>`: the engine's surfaces and the
 * console's own chrome (`fve-tokens` on `<body>`) both take their dark
 * roles under `.dark`, so there is one switch and no second theme to keep
 * in step — the old console stayed light on a dark system (W14).
 */
export type ColorMode = "system" | "light" | "dark";

export const COLOR_MODES: readonly ColorMode[] = ["system", "light", "dark"];

const STORAGE_KEY = "compensation-console.color-mode";

const SYSTEM_DARK = "(prefers-color-scheme: dark)";

function isColorMode(value: unknown): value is ColorMode {
  return COLOR_MODES.includes(value as ColorMode);
}

/** The mode kept on this machine, or the system's where none is. */
export function readColorMode(): ColorMode {
  try {
    const stored = window.localStorage.getItem(STORAGE_KEY);
    return isColorMode(stored) ? stored : "system";
  } catch {
    // Storage refused (a private window, blocked site data): follow the
    // system, as a first visit does.
    return "system";
  }
}

function storeColorMode(mode: ColorMode): void {
  try {
    if (mode === "system") window.localStorage.removeItem(STORAGE_KEY);
    else window.localStorage.setItem(STORAGE_KEY, mode);
  } catch {
    // Kept for this visit only.
  }
}

/** Whether `mode` paints dark now, the system's answer where it follows it. */
export function paintsDark(
  mode: ColorMode,
  systemDark = window.matchMedia(SYSTEM_DARK).matches,
): boolean {
  return mode === "dark" || (mode === "system" && systemDark);
}

/** Paints `<html>` for `mode`: its `.dark` and the browser's own controls. */
export function applyColorMode(
  mode: ColorMode,
  root: HTMLElement = document.documentElement,
): void {
  const dark = paintsDark(mode);
  root.classList.toggle("dark", dark);
  root.style.colorScheme = dark ? "dark" : "light";
}

/**
 * The mode and its setter. Following the system, it repaints when the
 * system changes; pinned, it does not listen.
 */
export function useColorMode(): [ColorMode, (mode: ColorMode) => void] {
  const [mode, setModeState] = useState<ColorMode>(readColorMode);

  useEffect(() => {
    applyColorMode(mode);
    if (mode !== "system") return;
    const media = window.matchMedia(SYSTEM_DARK);
    const follow = () => applyColorMode("system");
    media.addEventListener("change", follow);
    return () => media.removeEventListener("change", follow);
  }, [mode]);

  const setMode = useCallback((next: ColorMode) => {
    storeColorMode(next);
    setModeState(next);
  }, []);

  return [mode, setMode];
}
