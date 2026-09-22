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

import { LayersIcon } from 'lucide-react';
import { Button } from '../components/button.js';
import {
  Empty,
  EmptyContent,
  EmptyDescription,
  EmptyHeader,
  EmptyMedia,
  EmptyTitle,
} from '../components/empty.js';
import { useViewMessages } from '../MessagesProvider.js';

/**
 * The work area when the definition has no view of this kind to open.
 *
 * It used to be nothing at all — a title bar with no title, a body with no
 * body — which read as a page that had failed to load rather than as a page
 * with nothing in it yet. **This is the one place the state is stated in
 * full** (user, 2026-09-22): the sentence, why or what next, and the button.
 * The sidebar used to draw the same three things forty pixels under its own
 * `+`; it now says one quiet line where the list would be, and the offer is
 * here, because this is the room the view will fill. Both still press the
 * same `create` command. Without that command the state is the sentence
 * alone (D4): a reader who may not create is told there is nothing, not told
 * to make something.
 */
export function NoViews({
  failed,
  onCreate,
}: {
  /** The list could not be loaded, which is a different sentence. */
  failed: boolean;
  onCreate?(): void;
}) {
  const messages = useViewMessages();
  return (
    <Empty data-slot="view-none">
      <EmptyHeader>
        <EmptyMedia variant="icon">
          <LayersIcon />
        </EmptyMedia>
        <EmptyTitle>{messages.label('label.view.none')}</EmptyTitle>
        {(failed || onCreate) && (
          <EmptyDescription>
            {messages.label(
              failed ? 'label.view.list-failed' : 'label.view.none-hint',
            )}
          </EmptyDescription>
        )}
      </EmptyHeader>
      {onCreate && (
        <EmptyContent>
          <Button variant="outline" size="sm" onClick={onCreate}>
            {messages.label('label.view.new')}
          </Button>
        </EmptyContent>
      )}
    </Empty>
  );
}
