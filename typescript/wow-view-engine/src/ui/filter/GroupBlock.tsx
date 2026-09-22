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

import { XIcon } from 'lucide-react';
import {
  FILTER_GROUP_OPERATORS,
  isFilterGroupOperator,
  type FieldOption,
  type FilterGroup,
} from '../../model/index.js';
import {
  conditions,
  isFilterGroup,
  isFilterLeaf,
  type FilterPath,
} from '../../filter/index.js';
import type { FilterTreeController } from '../../react/index.js';
import { IconButton } from '../IconButton.js';
import { useViewMessages } from '../MessagesProvider.js';
import { AddEntry } from './AddEntry.js';
import { ChoiceValue } from './inputs/shared.js';
import { GROUP_OPERATOR_LABEL } from './groupOperators.js';
import { ConditionPill } from './ConditionPill.js';
import { PendingDot, PENDING_AT_CORNER } from '../PendingDot.js';

/** One group as a framed block: its operator, its children, room to add. */
export function GroupBlock({
  filter,
  group,
  path,
  disabled,
  optionsFor,
  scope,
  isPending,
}: {
  filter: FilterTreeController;
  group: FilterGroup;
  path: FilterPath;
  disabled?: boolean;
  optionsFor?: (remote: string) => FieldOption[] | undefined;
  /**
   * Whether the node at a path has been edited since the last apply. Only
   * the editor bound to a view has an applied tree to compare against; a
   * predicate's editor writes into a leaf and has none, so it passes none.
   */
  isPending?: (path: FilterPath) => boolean;
  /**
   * What this group is a group of, for the controls' accessible names. A
   * predicate renders a second root group on the same screen as the view's
   * own, and two controls called "Group operator" are two controls a screen
   * reader cannot tell apart.
   */
  scope?: string;
}) {
  const nested = path.length > 0;
  const messages = useViewMessages();
  const pending = isPending?.(path) === true;

  return (
    <div
      data-slot="filter-group"
      role="group"
      aria-label={messages.label(GROUP_OPERATOR_LABEL[group.op])}
      data-pending={pending || undefined}
      className="border-border relative flex flex-col gap-2 rounded-md border p-2"
    >
      {pending && <PendingDot named className={PENDING_AT_CORNER} />}
      <div className="flex items-center gap-1">
        {/* One at a time rather than three abreast: the operator is a
            sentence about the conditions below it ("All conditions"), and a
            row of three shouted all three of them at a reader who only
            needed to know which one was in force. Every operator is on
            offer wherever a group is — the kernel admits all three in every
            position — so none of them is ever shown as refused. */}
        <ChoiceValue
          // The one choice on this panel that is not inside a pill: the
          // group's own header has nothing around it to be the field's
          // edge, so this select keeps the registry's.
          chrome="box"
          value={group.op}
          items={FILTER_GROUP_OPERATORS.map(op => ({
            value: op,
            label: messages.label(GROUP_OPERATOR_LABEL[op]),
          }))}
          disabled={disabled}
          onChange={next => {
            if (isFilterGroupOperator(next)) filter.updateGroup(path, next);
          }}
          label={within(
            scope,
            nested
              ? `${messages.label('label.filter.group-operator')} ${path.join('.')}`
              : messages.label('label.filter.group-operator'),
          )}
        />

        {nested && (
          <IconButton
            label={messages.label('label.filter.remove-group')}
            variant="ghost"
            size="icon-sm"
            disabled={disabled}
            onClick={() => filter.remove(path)}
          >
            <XIcon />
          </IconButton>
        )}
      </div>

      <ConditionStrip
        filter={filter}
        group={group}
        path={path}
        disabled={disabled}
        optionsFor={optionsFor}
        scope={scope}
        isPending={isPending}
      />

      <div className="flex flex-wrap items-center gap-1">
        <AddEntry
          filter={filter}
          parent={path}
          disabled={disabled}
          groups
          label={within(scope, messages.label('label.filter.add-here'))}
        />
      </div>
    </div>
  );
}

/**
 * A group's conditions in one strip: a grid of equal columns, as many as
 * fit, so the pills line up and their fields, operators and values fall
 * under one another. A nested group, and a condition that holds a tree,
 * takes a whole line of its own, since it holds conditions of its own.
 */
export function ConditionStrip({
  filter,
  group,
  path,
  disabled,
  optionsFor,
  scope,
  isPending,
  negatable,
}: {
  filter: FilterTreeController;
  group: FilterGroup;
  path: FilterPath;
  disabled?: boolean;
  optionsFor?: (remote: string) => FieldOption[] | undefined;
  scope?: string;
  isPending?: (path: FilterPath) => boolean;
  /**
   * Simple mode's strip: every pill offers its negation switch, and a
   * condition alone in a `nor` group is drawn as that pill, pressed, rather
   * than as the group it is stored as. Advanced mode leaves this off and
   * shows the group — the tree as it is, with the operator to change it.
   */
  negatable?: boolean;
}) {
  if (group.children.length === 0) return null;
  // Which children simple mode draws as a pressed pill rather than as the
  // group they are stored as, by the kernel's reading and at the paths it
  // gives: the strip knows that a negated condition is one pill, not where
  // its leaf sits. Advanced mode asks for none of them and draws the tree.
  const negated = new Map(
    (negatable ? conditions(group, path) : [])
      .filter(condition => condition.negated)
      .map(condition => [condition.index, condition] as const),
  );
  return (
    <div
      data-slot="filter-conditions"
      // `min(20rem,100%)` rather than `20rem`: a bare 20rem is a floor the
      // track keeps even when the band is narrower than it, so at a phone's
      // width every column was pinned to 320px and the pills hung 24px past
      // the editor band they are in. Clamped to the band, the column is
      // 20rem where there is 20rem and the band's own width where there is
      // not — which is the same layout everywhere it used to be right.
      className="@container grid grid-cols-[repeat(auto-fill,minmax(min(20rem,100%),1fr))] gap-1.5"
    >
      {group.children.map((child, index) => {
        const pressed = negated.get(index);
        // The same reading of a node admission and the walk use: a leaf
        // carrying a stray `children` of the wrong shape is still a leaf,
        // and an entry that is neither is drawn by nobody.
        return pressed ? (
          <ConditionPill
            key={`${pressed.leaf.field}-${index}`}
            filter={filter}
            leaf={pressed.leaf}
            path={pressed.path}
            disabled={disabled}
            optionsFor={optionsFor}
            isPending={isPending}
            negatable
            negated
          />
        ) : isFilterGroup(child) ? (
          <div key={index} className="col-span-full">
            <GroupBlock
              filter={filter}
              group={child}
              path={[...path, index]}
              disabled={disabled}
              optionsFor={optionsFor}
              scope={scope}
              isPending={isPending}
            />
          </div>
        ) : isFilterLeaf(child) ? (
          <ConditionPill
            key={`${child.field}-${index}`}
            filter={filter}
            leaf={child}
            path={[...path, index]}
            disabled={disabled}
            optionsFor={optionsFor}
            isPending={isPending}
            negatable={negatable}
          />
        ) : null;
      })}
    </div>
  );
}

/**
 * One control's accessible name, told apart by what it belongs to.
 *
 * A predicate renders a second root group on the same screen as the view's
 * own, so without this two controls would answer to "Group operator" and a
 * screen reader could not say which filter either one edits.
 */
function within(scope: string | undefined, name: string): string {
  return scope ? `${scope} ${name}` : name;
}
