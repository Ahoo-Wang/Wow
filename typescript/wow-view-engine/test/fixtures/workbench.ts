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

import { fireEvent, screen, waitFor, within } from '@testing-library/react';
import { expect } from 'vitest';

/**
 * The handle of the editor's fold, in the title bar. Its name is its content
 * — "Filter", and the pending count beside it once the draft says something
 * the last run did not — so it is matched by what it starts with.
 */
export function editorToggle(): HTMLElement {
  return screen.getByRole('button', { name: /^Filter/ });
}

/**
 * The same handle over the analysis view's tray (D20). Its name is the
 * content too — "Analysis", and the pending count beside it
 * once the draft says something the last run did not. Addressed through the
 * group it is the trigger of, so a name that grows does not lose it.
 */
export function analysisToggle(): HTMLElement {
  return within(
    document.querySelector<HTMLElement>('[data-slot="editor-toggle"]')!,
  ).getByRole('button');
}

/** Presses the analysis tray open and answers the tray itself. */
export async function openTray(): Promise<HTMLElement> {
  fireEvent.click(await waitFor(analysisToggle));
  return await waitFor(() => {
    const tray = document.querySelector<HTMLElement>(
      '[data-slot="analysis-tray"]',
    );
    expect(tray).not.toBeNull();
    return tray!;
  });
}

/**
 * Conditions added the way a user adds them: the field picker is a checklist
 * that stays open while several fields are ticked, and Done is the way out.
 */
export async function addConditions(fields: string[]): Promise<void> {
  fireEvent.click(screen.getByRole('button', { name: 'Add' }));
  const picker = await screen.findByRole('dialog', {
    name: 'Choose fields',
  });
  for (const field of fields)
    fireEvent.click(within(picker).getByRole('checkbox', { name: field }));
  fireEvent.click(within(picker).getByRole('button', { name: 'Done' }));
  await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
}
