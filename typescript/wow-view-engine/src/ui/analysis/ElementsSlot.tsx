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
import { ChevronRightIcon, PlusIcon, XIcon } from 'lucide-react';
import { describeFilter } from '../../filter/index.js';
import type { AnalysisElement, FieldOption } from '../../model/index.js';
import type { AnalysisEditorController } from '../../react/index.js';
import { cn } from 'cn';
import { Button } from '../components/button.js';
import { IconButton } from '../IconButton.js';
import { TEXT_UI } from '../layout.js';
import { useViewMessages } from '../MessagesProvider.js';
import { EditorCard, EditorSlot } from '../variants.js';
import { useListFocus, type ListFocus } from './listFocus.js';
import {
  ConditionButton,
  ConditionLine,
  ConditionsBlock,
} from './MetricCondition.js';

/**
 * The expansion slot (D20 屏 G): the chain of arrays the analysis counts
 * inside, outermost first — 订单 → 明细项 → 批次 — one card a level, each
 * with its own gate on the entries it lets through. The chain is the one
 * the capability declares, so 「展开：…」 offers the next step and nothing
 * else; a level taken out takes every level inside it. The footer says
 * what is being counted, because after an expansion it is no longer the
 * record. The slot exists only where the capability declares a chain.
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
  return (
    <EditorSlot
      name="elements"
      title={messages.label('label.analysis.slot.elements')}
      hint={messages.label('label.analysis.hint.elements')}
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
        {next && (
          <Button
            variant="ghost"
            size="sm"
            data-slot="expand-into"
            disabled={disabled}
            onClick={() => analysis.expand(next.path)}
          >
            <PlusIcon data-icon="inline-start" />
            {messages.label('label.analysis.expand-into', { name: next.label })}
          </Button>
        )}
      </div>
      <span
        data-slot="counting-unit"
        className={cn('text-muted-foreground', TEXT_UI)}
      >
        {messages.label('label.analysis.unit', {
          name: analysis.unit ?? messages.label('label.analysis.records'),
        })}
      </span>
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
  const name = analysis.elementLabel(index);
  const held = element.filter !== undefined;
  const fields = analysis.elementFields(index);
  const items =
    element.filter && analysis.kinds
      ? describeFilter(fields, element.filter, analysis.kinds)
      : [];
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
        <ConditionButton
          label={messages.label('label.analysis.element-condition-of', {
            name,
          })}
          open={conditioning}
          held={held}
          disabled={disabled}
          onToggle={() => {
            if (!conditioning && !held)
              analysis.setElementFilter(index, { op: 'and', children: [] });
            setConditioning(!conditioning);
          }}
        />
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
