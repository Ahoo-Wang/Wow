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

import type { ReactNode } from 'react';
import { cn } from 'cn';
import { EditorBand } from '../EditorBand.js';
import { SPACE, TRAY } from '../layout.js';
import { RenderBoundary } from '../RenderBoundary.js';
import type { WorkbenchShellProps } from '../WorkbenchShell.js';

export interface ConditionBlockProps extends Pick<
  WorkbenchShellProps,
  'onRenderFailure'
> {
  /** The view's own editor; the shell draws the block only when there is one. */
  editor: ReactNode;
  /** Whether the editor lives in the title bar's fold rather than open. */
  folded: boolean;
  /** The band's id. */
  id: string;
  /** What resets the editor's boundary: the open view. */
  resetKeys: readonly unknown[];
}

/**
 * The conditions, on a surface of their own: the fold's band when the editor
 * folds, a block that stays open when it does not. Either way the editor is
 * held by its own boundary, so a throwing editor takes the tray and the rest
 * of the view stays.
 */
export function ConditionBlock({
  editor,
  folded,
  id,
  resetKeys,
  onRenderFailure,
}: ConditionBlockProps) {
  return folded ? (
    <EditorBand id={id} className={cn(TRAY, SPACE.ROWS)}>
      <RenderBoundary
        name="editor"
        resetKeys={resetKeys}
        onFailure={onRenderFailure}
      >
        {editor}
      </RenderBoundary>
    </EditorBand>
  ) : (
    <section
      data-slot="condition-block"
      className={cn('flex flex-col', TRAY, SPACE.ROWS)}
    >
      <RenderBoundary
        name="editor"
        resetKeys={resetKeys}
        onFailure={onRenderFailure}
      >
        {editor}
      </RenderBoundary>
    </section>
  );
}
