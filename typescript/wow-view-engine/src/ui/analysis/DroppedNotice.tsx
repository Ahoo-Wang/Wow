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

import { useId, useRef } from 'react';
import { InfoIcon, Undo2Icon, XIcon } from 'lucide-react';
import type {
  AnalysisEditorController,
  DropNotice,
} from '../../react/index.js';
import { useAnnouncer, useSentence } from '../Announcer.js';
import { Alert, AlertDescription } from '../components/alert.js';
import { Button } from '../components/button.js';
import { IconButton } from '../IconButton.js';
import type { MessageFormatters } from '../MessagesProvider.js';
import { useViewMessages } from '../MessagesProvider.js';
import { useLanding } from '../focus.js';
import { groupReference, metricReference } from './editing.js';

/**
 * What an edit took out of the question beyond what it was asked to, said
 * with an undo (D71): 「展开 商品 后去掉了 2 个指标：本月至今 GMV、上月同期
 * GMV」. A step into the chain takes every dimension and metric outside the
 * new counting unit with it (D20: they follow rather than fail), and a
 * metric removed takes what was calculated from it; neither asks first — a
 * confirmation before every expansion is a question the analyst cannot
 * answer until they see what came of it — so the notice says what went and
 * **撤销** puts the question back as it was.
 *
 * It stands just above the footer, which does not scroll, so it is seen
 * whatever row the edit was made in, and it lasts while the draft is the
 * one the edit made: the next edit, anywhere, takes it away. It is said
 * once in the tray's voice as it comes, because a sighted analyst sees the
 * cards go and a reader hears nothing of it otherwise.
 */
export function DroppedNotice({
  analysis,
}: {
  analysis: AnalysisEditorController;
}) {
  const messages = useViewMessages();
  const voice = useAnnouncer('dropped-voice');
  const notice = analysis.dropped;
  const sentence = notice ? droppedSentence(notice, messages) : null;
  useSentence(sentence, voice);
  const text = useId();
  const box = useRef<HTMLDivElement>(null);
  const land = useLanding();
  // Both buttons take the notice away, and the keyboard with it: it goes to
  // the tray, where the edit was, rather than to the page's start.
  const leave = () => land(() => box.current?.closest(TRAY));
  return (
    <>
      {notice && sentence && (
        <Alert
          ref={box}
          role="group"
          aria-labelledby={text}
          data-slot="dropped-notice"
          className="flex items-center gap-2 py-1.5"
        >
          <InfoIcon aria-hidden />
          <AlertDescription id={text} className="min-w-0 flex-1">
            {sentence}
          </AlertDescription>
          <Button
            variant="outline"
            size="xs"
            data-slot="dropped-undo"
            onClick={() => {
              leave();
              analysis.undoDrop();
              voice.say(messages.label('label.analysis.dropped.undone'));
            }}
          >
            <Undo2Icon data-icon="inline-start" />
            {messages.label('label.analysis.dropped.undo')}
          </Button>
          <IconButton
            label={messages.label('label.analysis.dropped.dismiss')}
            variant="ghost"
            size="icon-xs"
            onClick={() => {
              leave();
              analysis.dismissDrop();
            }}
          >
            <XIcon />
          </IconButton>
        </Alert>
      )}
      {voice.region}
    </>
  );
}

/** Where the keyboard goes when the notice leaves: the tray it was in. */
const TRAY = '[data-slot="analysis-tray"]';

/**
 * The notice's one sentence: the step it followed, then what went — the
 * dimensions, then the metrics, each counted and named as their columns
 * were headed before the edit.
 */
export function droppedSentence(
  notice: DropNotice,
  messages: MessageFormatters,
): string {
  const join = messages.label('label.filter.join');
  const naming = {
    fields: notice.naming.fields,
    metrics: notice.naming.metrics,
  };
  const parts: string[] = [];
  if (notice.groups.length > 0)
    parts.push(
      messages.label(
        notice.groups.length === 1
          ? 'label.analysis.dropped.dimension'
          : 'label.analysis.dropped.dimensions',
        {
          count: notice.groups.length,
          names: notice.groups
            .map(group => groupReference(naming, group, messages))
            .join(join),
        },
      ),
    );
  if (notice.metrics.length > 0)
    parts.push(
      messages.label(
        notice.metrics.length === 1
          ? 'label.analysis.dropped.metric'
          : 'label.analysis.dropped.metrics',
        {
          count: notice.metrics.length,
          names: notice.metrics
            .map(metric => metricReference(naming, metric, messages))
            .join(join),
        },
      ),
    );
  const what =
    parts.length === 2
      ? messages.label('label.analysis.dropped.both', {
          first: parts[0],
          second: parts[1],
        })
      : (parts[0] ?? '');
  const { cause } = notice;
  return cause
    ? messages.label(`label.analysis.dropped.${cause.kind}`, {
        name: messages.say(cause.name),
        what,
      })
    : messages.label('label.analysis.dropped.edit', { what });
}
