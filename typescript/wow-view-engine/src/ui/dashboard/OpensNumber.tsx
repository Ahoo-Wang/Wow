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
import type { AnalysisViewConfig } from '../../model/index.js';
import { useViewMessages } from '../kit/MessagesProvider.js';

/** A single number with no group to press: a card a reader presses whole. */
export function isNumberCard(config: AnalysisViewConfig): boolean {
  return (
    config.layout === 'chart' &&
    config.chart?.type === 'metric' &&
    config.groups.length === 0
  );
}

/**
 * A number card whose author named the view it counts (`opens`): pressed
 * anywhere, it opens that view — 「需要处理 18」 opens the queue of 18 —
 * as 「在工作台中打开」 in its menu does. One button over the card, named
 * for where it goes, the keyboard's way in too; the card under it is read
 * as it is.
 */
export function OpensNumber({
  name,
  onOpen,
  children,
}: {
  name: string;
  onOpen(): void;
  children: ReactNode;
}) {
  const messages = useViewMessages();
  return (
    <div className="fve:relative fve:flex fve:min-h-0 fve:flex-1 fve:flex-col">
      {children}
      <button
        type="button"
        data-slot="panel-opens"
        aria-label={messages.label('label.panel.open-number', { name })}
        onClick={onOpen}
        className="fve:absolute fve:inset-0 fve:cursor-pointer fve:rounded-md fve:outline-none fve:hover:bg-muted/40 fve:focus-visible:ring-3 fve:focus-visible:ring-ring/50"
      />
    </div>
  );
}
