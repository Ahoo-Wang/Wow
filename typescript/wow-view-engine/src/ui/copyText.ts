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
 * Puts `value` on the clipboard, and says whether it got there.
 *
 * The Clipboard API first. It is secure-context only, so an application
 * served over plain HTTP — an operations console on an internal address,
 * the compensation console's own deployment — has no `navigator.clipboard`
 * at all, and a permission the reader refused rejects the write. Either way
 * the copy falls back to the document's `copy` command over a selection of
 * the value, which every browser still carries for exactly this; only when
 * that fails too is the answer no.
 */
export async function copyText(value: string): Promise<boolean> {
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(value);
      return true;
    }
  } catch {
    // Refused or unavailable: the command below is the other way.
  }
  return copyWithCommand(value);
}

/**
 * The `copy` command over an off-screen, read-only text area holding the
 * value, selected. Focus goes back where it was — the button just pressed —
 * so a keyboard reader stays put.
 */
function copyWithCommand(value: string): boolean {
  if (typeof document.execCommand !== 'function') return false;
  const focused =
    document.activeElement instanceof HTMLElement
      ? document.activeElement
      : null;
  const area = document.createElement('textarea');
  area.value = value;
  area.readOnly = true;
  area.tabIndex = -1;
  Object.assign(area.style, {
    position: 'fixed',
    top: '0',
    left: '-9999px',
    opacity: '0',
  });
  // Inside the focused element's dialog, if any: a modal panel keeps focus
  // and selection to itself, and a selection outside it copies nothing.
  (focused?.closest('[role="dialog"]') ?? document.body).append(area);
  try {
    area.focus({ preventScroll: true });
    area.select();
    area.setSelectionRange(0, value.length);
    return document.execCommand('copy');
  } catch {
    return false;
  } finally {
    area.remove();
    focused?.focus({ preventScroll: true });
  }
}
