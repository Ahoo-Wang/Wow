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

import { ArrowLeftIcon } from 'lucide-react';
import { Button } from '../components/button.js';
import { TEXT_UI } from '../layout.js';
import { useViewMessages } from '../MessagesProvider.js';

/**
 * The line under the title bar of a view opened from another (D20): the way
 * back, which names where this one came from — once. It used to say the
 * origin's name twice (「返回 X · 来自 X」) and the conditions the follow-up
 * added a third time beside the applied bar and the editor (2026-09-23
 * audit); the conditions are the applied bar's, which is where every view
 * says what its rows were fetched under, and the view's own name says which
 * group it is. It is the workbench's fact rather than the view's, so the
 * shell draws it from `workbench.held` and no kind's parts know it exists.
 *
 * A view handed over from a dashboard has the same line (D26 Q33,
 * 「返回〈仪表盘〉」): the board is where it came from, and going back is
 * the host's route (`workbench.board`) — `from` says which it is.
 */
export function OriginBar({
  title,
  from,
  onBack,
}: {
  title: string;
  from: 'view' | 'dashboard';
  onBack(): void;
}) {
  const messages = useViewMessages();
  return (
    <div
      data-slot="origin-bar"
      role="region"
      data-from={from}
      aria-label={messages.label(
        from === 'dashboard'
          ? 'label.origin.board-region'
          : 'label.origin.region',
      )}
      className={`flex flex-wrap items-center gap-2 ${TEXT_UI}`}
    >
      <Button variant="outline" size="xs" onClick={onBack}>
        <ArrowLeftIcon data-icon="inline-start" />
        {messages.label('label.origin.back', { title })}
      </Button>
    </div>
  );
}
