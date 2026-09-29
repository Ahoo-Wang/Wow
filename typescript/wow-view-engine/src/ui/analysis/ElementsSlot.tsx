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

import { useState } from 'react';
import { ChevronRightIcon, FunnelIcon, PlusIcon, XIcon } from 'lucide-react';
import { describeFilter } from '../../filter/index.js';
import type { AnalysisElement, FieldOption } from '../../model/index.js';
import type { AnalysisEditorController } from '../../react/index.js';
import { cn } from 'cn';
import { Button } from '../components/button.js';
import { IconButton } from '../IconButton.js';
import { TEXT_UI } from '../layout.js';
import { useViewMessages } from '../MessagesProvider.js';
import { AddButton, EditorCard, EditorSlot } from '../variants.js';
import { useListFocus, type ListFocus } from './listFocus.js';
import { TermTip } from './TermTip.js';
import { ConditionLine, ConditionsBlock } from './MetricCondition.js';

/**
 * The expansion row (D20 屏 G, D71): the chain of arrays the analysis
 * counts inside, outermost first — 订单 → 明细项 → 批次 — one card a level,
 * each with its own gate on the entries it lets through. It is the tray's
 * first row, because it decides which fields every row after it may name.
 *
 * The chain is the one the capability declares, so the add button offers
 * the next step and nothing else, and says it by the array's name —
 * 「+ 商品」, a chevron leading to it — rather than a label for the idea; a
 * level taken out takes every level inside it. The row's end says what is
 * being counted, and says it louder once something is expanded, because
 * then it is no longer the record. The row exists only where the capability
 * declares a chain.
 */
export function ElementsSlot({
  analysis,
  disabled,
  optionsFor,
}: {
  analysis: AnalysisEditorController;
  disabled?: boolean;
  optionsFor?(remote: string): FieldOption[] | undefined;
}) {
  const messages = useViewMessages();
  const next = analysis.expandable;
  // A level collapsed takes every level inside it, so what is left at that
  // index is the level before it — and that is where the keyboard lands
  // (`listFocus.ts`), or on 「再展开」 when the chain is gone entirely.
  const focus = useListFocus({
    list: '[data-slot="analysis-slot-elements"]',
    item: '[data-slot="element-card"]',
    add: '[data-slot="expand-into"]',
  });
  const title = messages.label('label.analysis.slot.elements');
  const expanded = analysis.elements.length > 0;
  const unit = analysis.unit ?? messages.label('label.analysis.records');
  return (
    <EditorSlot
      name="elements"
      title={title}
      tip={
        <TermTip
          label={messages.label('label.analysis.tip-of', { term: title })}
          tip={messages.label('label.analysis.tip.elements', {
            name: messages.say(expanded || !next ? unit : next.label),
          })}
        />
      }
      end={
        <span
          data-slot="counting-unit"
          data-emphasis={expanded ? 'strong' : undefined}
          className={cn(
            expanded ? 'text-foreground font-medium' : 'text-muted-foreground',
            TEXT_UI,
          )}
        >
          {messages.label('label.analysis.unit', { name: messages.say(unit) })}
        </span>
      }
    >
      <div className="flex flex-wrap items-center gap-2">
        {analysis.elements.map((element, index) => (
          <ElementCard
            key={element.path}
            analysis={analysis}
            element={element}
            index={index}
            focus={focus}
            disabled={disabled}
            optionsFor={optionsFor}
          />
        ))}
        {next && expanded && (
          <ChevronRightIcon
            aria-hidden
            className="text-muted-foreground size-4"
          />
        )}
        {next && (
          // Named by what it does — 「展开 商品」 — and showing the array's
          // name alone: the row's term already says 展开.
          <AddButton
            data-slot="expand-into"
            disabled={disabled}
            aria-label={messages.label('label.analysis.expand-into', {
              name: messages.say(next.label),
            })}
            onClick={() => analysis.expand(next.path)}
          >
            <PlusIcon data-icon="inline-start" />
            {messages.say(next.label)}
          </AddButton>
        )}
      </div>
    </EditorSlot>
  );
}

/**
 * One level of the chain: the array it expands, its gate, and the way out.
 * The arrow in front of every level but the first draws the chain.
 */
function ElementCard({
  analysis,
  element,
  index,
  focus,
  disabled,
  optionsFor,
}: {
  analysis: AnalysisEditorController;
  element: AnalysisElement;
  index: number;
  /** Where the keyboard goes when this level is the one collapsed. */
  focus: ListFocus;
  disabled?: boolean;
  optionsFor?(remote: string): FieldOption[] | undefined;
}) {
  const messages = useViewMessages();
  const [conditioning, setConditioning] = useState(false);
  const name = messages.say(analysis.elementLabel(index));
  const held = element.filter !== undefined;
  const fields = analysis.elementFields(index);
  const items =
    element.filter && analysis.kinds
      ? describeFilter(fields, element.filter, analysis.kinds)
      : [];
  const toggle = () => {
    if (!conditioning && !held)
      analysis.setElementFilter(index, { op: 'and', children: [] });
    setConditioning(!conditioning);
  };
  return (
    <>
      {index > 0 && (
        <ChevronRightIcon
          aria-hidden
          className="text-muted-foreground size-4"
        />
      )}
      <EditorCard data-slot="element-card" data-path={element.path}>
        <span data-slot="card-name" className="truncate font-medium">
          {name}
        </span>
        {/* The gate says what it is for in words (D71), 「只算满足条件的
            明细项」, where a metric's is a bare funnel: a level's card holds
            nothing else to read, and the words are what the analyst looks
            for. Its name begins with them. One element whether a gate is
            held or not, so a press that writes one keeps the keyboard. */}
        <Button
          variant={held ? 'secondary' : 'ghost'}
          size="xs"
          data-slot="metric-condition-toggle"
          data-held={held || undefined}
          aria-label={messages.label('label.analysis.element-condition-of', {
            name,
          })}
          aria-pressed={conditioning}
          disabled={disabled}
          onClick={toggle}
        >
          <FunnelIcon data-icon="inline-start" />
          {messages.label('label.analysis.element-condition')}
        </Button>
        <IconButton
          label={messages.label('label.analysis.collapse', { name })}
          variant="ghost"
          size="icon-xs"
          className="ml-auto"
          disabled={disabled}
          onClick={event => {
            focus.removing(event, index);
            analysis.collapse(index);
          }}
        >
          <XIcon />
        </IconButton>
        {conditioning ? (
          <ConditionsBlock
            name={name}
            title={messages.label('label.analysis.element-condition-title')}
            label={messages.label('label.analysis.element-condition-of', {
              name,
            })}
            tree={element.filter ?? { op: 'and', children: [] }}
            fields={fields}
            issues={analysis.issues}
            at={['elements', index, 'filter']}
            analysis={analysis}
            disabled={disabled}
            optionsFor={optionsFor}
            onChange={tree => analysis.setElementFilter(index, tree)}
            onClear={() => {
              analysis.setElementFilter(index, undefined);
              setConditioning(false);
            }}
            onClose={() => setConditioning(false)}
          />
        ) : (
          <ConditionLine items={items} />
        )}
      </EditorCard>
    </>
  );
}
