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
import { describeFilter } from '../../filter/index.js';
import type { ViewOrigin } from '../../react/index.js';
import { Button } from '../components/button.js';
import { TEXT_UI } from '../layout.js';
import { useViewMessages } from '../MessagesProvider.js';
import { summaryText } from '../summary.js';
import { WrappingBadge } from '../variants.js';
import { useSurfaceDisplay } from '../ViewSurface.js';

/**
 * The line under the title bar of a view opened from another (D20): the way
 * back, and where this one came from — the origin's name and the conditions
 * the drill added, in the applied bar's own badges. It is the workbench's
 * fact rather than the view's, so the shell draws it from `workbench.held`
 * and no kind's parts know it exists.
 */
export function OriginBar({
  origin,
  onBack,
}: {
  origin: ViewOrigin;
  onBack(): void;
}) {
  const messages = useViewMessages();
  const display = useSurfaceDisplay();
  // Described by the origin, whose fields these conditions name, and in the
  // words the applied bar would use for them.
  const items = describeFilter(
    origin.runtime.fields,
    { op: 'and', children: [...origin.conditions] },
    origin.runtime.kinds,
  );
  return (
    <div
      data-slot="origin-bar"
      role="region"
      aria-label={messages.label('label.origin.from', { title: origin.title })}
      className={`flex flex-wrap items-center gap-2 ${TEXT_UI}`}
    >
      <Button variant="outline" size="xs" onClick={onBack}>
        <ArrowLeftIcon />
        {messages.label('label.origin.back', { title: origin.title })}
      </Button>
      <span className="text-muted-foreground">
        {messages.label('label.origin.from', { title: origin.title })}
      </span>
      {items.map(item => (
        <WrappingBadge
          key={item.path.join('.')}
          variant="secondary"
          data-slot="origin-condition"
        >
          {summaryText(item, messages, display)}
        </WrappingBadge>
      ))}
    </div>
  );
}
