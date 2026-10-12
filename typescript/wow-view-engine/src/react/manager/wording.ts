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

import { useCallback, useState } from 'react';
import type {
  DatasetOverrides,
  DatasetWordingEditor,
} from '../../runtime/datasetResources.js';
import { newRequestId } from '../../runtime/requestId.js';
import type { ViewEngine } from '../../runtime/viewEngine.js';
import type { ViewPermissions } from '../../store/ViewStore.js';

/** The manager's hold on a dataset's wording (D91); see `ViewManagerController.wording`. */
export interface ManagedWording {
  /** The editor in force: what the fields are, what they are called now. */
  editor: DatasetWordingEditor;
  /** Saves `wording` whole; `false` when the store refused it (`error` says why). */
  save(wording: DatasetOverrides): Promise<boolean>;
  saving: boolean;
  /** Why the last save failed, until the next one. */
  error: unknown;
}

/**
 * The wording of the definition's dataset, where the host grants
 * `editDatasetWording` and the dataset was registered with a store of
 * wording: what the view manager's wording tab edits. Read from the engine
 * on every render, so a save — which registers the dataset again — answers
 * the editor of the new words and revision.
 */
export function useDatasetWording(
  engine: ViewEngine,
  definitionId: string,
  permissions: ViewPermissions | null,
): ManagedWording | undefined {
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<unknown>(undefined);
  const [, setSaved] = useState(0);
  const editor =
    permissions?.editDatasetWording === true
      ? engine.wordingOf(definitionId)
      : undefined;
  const save = useCallback(
    async (wording: DatasetOverrides) => {
      const current = engine.wordingOf(definitionId);
      if (!current) return false;
      setSaving(true);
      setError(undefined);
      try {
        await current.save(wording, { requestId: newRequestId() });
        setSaved(count => count + 1);
        return true;
      } catch (thrown) {
        setError(thrown);
        return false;
      } finally {
        setSaving(false);
      }
    },
    [engine, definitionId],
  );
  return editor ? { editor, save, saving, error } : undefined;
}
