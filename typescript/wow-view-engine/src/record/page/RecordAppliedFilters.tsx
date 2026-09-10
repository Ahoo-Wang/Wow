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

import { FilterOperator } from '@ahoo-wang/fetcher-wow';
import { XIcon } from 'lucide-react';
import { useId, useRef } from 'react';
import { Badge } from '../../components/ui/badge.js';
import { Button } from '../../components/ui/button.js';
import { getBuiltinFilterCompiler } from '../../filter/builtinFilterCompilers.js';
import { compileFilterConfiguration } from '../../filter/filterCore.js';
import { clearFilterValues } from '../../filter/filterConfiguration.js';
import type {
  FilterComponentConfig,
  FilterFieldDefinition,
} from '../../filter/filterModel.js';
import { replaceFilterNode, sameFilterNode } from '../../filter/filterTree.js';
import { cloneSnapshot, type DeepReadonly } from '../../lib/types.js';
import { describeRecordFilter } from '../recordFilterSummary.js';
import type { RecordSession, ViewDefinition } from '../recordModel.js';
import type { ViewEngine } from '../ViewEngine.js';

export function RecordAppliedFilters({
  engine,
  definition,
  session,
  run,
}: {
  engine: ViewEngine;
  definition: ViewDefinition;
  session: RecordSession;
  run(action: () => void | Promise<void>): void;
}) {
  const rootRef = useRef<HTMLElement>(null);
  const pendingId = useId();
  const baseline = session.filterBaseline.root;
  // Only the outer AND is split. OR/NOR and element scopes remain complete groups.
  const nodes =
    session.appliedFilter === null
      ? []
      : baseline.operator === FilterOperator.AND
        ? (baseline.operands ?? [])
        : [baseline];
  function describe(
    node: DeepReadonly<FilterComponentConfig>,
    fields: readonly FilterFieldDefinition[],
  ): ReturnType<typeof describeRecordFilter> | undefined {
    const result = compileFilterConfiguration(
      { mode: 'advanced', root: node },
      fields,
      definition.allowedOperators,
      engine.filterCompilers,
      definition.timeZone,
    );
    if (
      !result.expression ||
      (result.expression.op === FilterOperator.MATCH_ALL &&
        node.operator !== FilterOperator.MATCH_ALL)
    )
      return undefined;
    const field = fields.find(field => field.field === node.field);
    const editor = node.component;
    const builtin =
      editor.name === 'builtin' ||
      (!Object.prototype.hasOwnProperty.call(
        engine.filterCompilers,
        editor.name,
      ) &&
        getBuiltinFilterCompiler(editor.name) !== undefined);
    return describeRecordFilter(
      result.expression,
      fields,
      {
        node,
        timeZone: definition.timeZone,
        showTime: builtin ? editor.options?.showTime === true : undefined,
        operands: node.operands?.flatMap(
          child => describe(child, fields) ?? [],
        ),
        predicate: node.predicate
          ? describe(node.predicate, field?.fields ?? [])
          : undefined,
      },
      false,
    );
  }
  const items = nodes.flatMap(node => {
    const summary = describe(node, definition.fields);
    if (!summary || node.operator === FilterOperator.MATCH_ALL) return [];
    let clearable = false;
    try {
      clearable = !sameFilterNode(
        node,
        clearFilterValues(
          node,
          definition.fields,
          engine.filterCompilers,
          definition.timeZone,
        ),
      );
    } catch {
      // An invalid extension clear function must not take down the record view.
    }
    return [
      {
        id: node.id,
        text: summary.text,
        clearable,
      },
    ];
  });
  const disabled = session.filterPending || session.queryStatus === 'loading';

  function clear(nodeId: string) {
    run(async () => {
      const state = engine.getSnapshot();
      const current = state.sessions[session.instance.id];
      if (
        !current ||
        !state.definition ||
        current.filterPending ||
        current.queryStatus === 'loading'
      )
        return;
      const root = cloneSnapshot<FilterComponentConfig>(
        current.filterBaseline.root,
      );
      const node =
        root.id === nodeId
          ? root
          : root.operator === FilterOperator.AND
            ? root.operands?.find(child => child.id === nodeId)
            : undefined;
      if (!node) return;
      const cleared = clearFilterValues(
        node,
        state.definition.fields,
        engine.filterCompilers,
        state.definition.timeZone,
      );
      if (sameFilterNode(node, cleared)) return;
      const draft = replaceFilterNode(root, nodeId, cleared);
      if (!draft) return;
      const configuration = { ...current.filterBaseline, root: draft };
      const result = compileFilterConfiguration(
        configuration,
        state.definition.fields,
        state.definition.allowedOperators,
        engine.filterCompilers,
        state.definition.timeZone,
      );
      if (!result.expression)
        throw new Error(result.errors[0]?.message ?? '无法清空此筛选条件值');
      // Keep every field, operator and editor ID; only values become unset.
      engine.setFilterDraft(configuration, current.instance.id);
      const query = engine.applyFilter(current.instance.id);
      rootRef.current?.focus();
      await query;
    });
  }

  return (
    <section
      ref={rootRef}
      aria-label="已应用筛选"
      data-slot="record-applied-filters"
      tabIndex={-1}
      className="fve:flex fve:min-w-0 fve:flex-wrap fve:items-center fve:gap-[var(--fve-toolbar-gap)] fve:border-t fve:px-[var(--fve-toolbar-padding-x)] fve:py-[var(--fve-toolbar-padding-y)] fve:outline-none fve:focus-visible:ring-2 fve:focus-visible:ring-ring fve:focus-visible:ring-inset"
    >
      <span className="fve:shrink-0 fve:text-xs fve:font-medium fve:text-muted-foreground">
        已应用{items.length > 1 ? ' · 全部满足' : ''}
      </span>
      {items.map(item => (
        <Badge
          key={item.id}
          variant="secondary"
          className="fve:h-auto fve:max-w-full fve:gap-1 fve:overflow-visible fve:py-0 fve:pr-0.5 fve:whitespace-normal"
        >
          <span className="fve:min-w-0 fve:whitespace-pre-wrap fve:break-words">
            {item.text}
          </span>
          {item.clearable && (
            <Button
              variant="ghost"
              size="icon-xs"
              aria-label={`清空条件值：${item.text}`}
              aria-describedby={session.filterPending ? pendingId : undefined}
              disabled={disabled}
              onClick={() => clear(item.id)}
            >
              <XIcon aria-hidden="true" />
            </Button>
          )}
        </Badge>
      ))}
      {!items.length && (
        <span className="fve:text-xs fve:text-muted-foreground">
          {session.appliedFilter === null ? '筛选尚未生效' : '全部记录'}
        </span>
      )}
      {session.filterPending && items.some(item => item.clearable) && (
        <span id={pendingId} className="fve:text-xs fve:text-muted-foreground">
          先查询或撤销筛选修改，再清空已应用条件值。
        </span>
      )}
    </section>
  );
}
