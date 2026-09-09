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

import { PanelLeftCloseIcon, Settings2Icon } from 'lucide-react';
import { useState, type RefObject } from 'react';
import { Button } from '../../components/ui/button.js';
import {
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectLabel,
  SelectTrigger,
  SelectValue,
} from '../../components/ui/select.js';
import type { RecordSession, ViewEngineState } from '../recordModel.js';

const GROUPS = [
  { id: 'personal', label: '个人视图' },
  { id: 'public', label: '公共视图' },
] as const;
function instanceLabel(session: RecordSession, showPending = true) {
  return `${session.instance.title}${showPending && session.dirty ? ' · 已编辑' : ''}${showPending && session.filterPending ? ' · 待查询' : ''}`;
}
function ViewInstanceLabel({
  session,
  showPending,
}: {
  session: RecordSession;
  showPending: boolean;
}) {
  return (
    <span className="fve:flex fve:w-full fve:min-w-0 fve:items-center fve:gap-2">
      <span className="fve:min-w-0 fve:flex-1">
        {instanceLabel(session, showPending)}
      </span>
      {session.instance.scope.type === 'public' &&
        session.instance.scope.source === 'system' && (
          <span className="fve:inline-flex fve:h-5 fve:shrink-0 fve:items-center fve:justify-center fve:rounded-4xl fve:border fve:border-border fve:px-2 fve:py-0.5 fve:text-xs fve:font-medium fve:whitespace-nowrap fve:text-foreground">
            系统
          </span>
        )}
    </span>
  );
}

export type InstanceGroup = {
  id: 'personal' | 'public';
  label: string;
  sessions: RecordSession[];
};

export function groupViewInstances(state: ViewEngineState): InstanceGroup[] {
  return GROUPS.map(group => ({
    ...group,
    sessions: state.instanceIds
      .map(id => state.sessions[id])
      .filter(session => session.instance.scope.type === group.id),
  })).filter(group => group.sessions.length);
}

interface NavigationProps {
  groups: InstanceGroup[];
  selectedId: string | null;
  onSelect(id: string): void;
  onManage(trigger: HTMLElement | null): void;
}

export function ViewInstanceSwitcher({
  groups,
  selectedId,
  onSelect,
  onManage,
  triggerRef,
  managerOpen,
}: NavigationProps & {
  triggerRef: RefObject<HTMLButtonElement | null>;
  managerOpen: boolean;
}) {
  const [open, setOpen] = useState(false);
  const sessions = groups.flatMap(group => group.sessions);
  return (
    <Select<string | null>
      open={open}
      onOpenChange={setOpen}
      value={selectedId}
      items={sessions.map(session => ({
        value: session.instance.id,
        label: instanceLabel(session, session.instance.id !== selectedId),
      }))}
      onValueChange={next => {
        setOpen(false);
        if (next !== null) onSelect(next);
      }}
    >
      <SelectTrigger ref={triggerRef} aria-label="选择视图实例">
        <SelectValue placeholder="选择视图">
          {
            sessions.find(session => session.instance.id === selectedId)
              ?.instance.title
          }
        </SelectValue>
      </SelectTrigger>
      <SelectContent
        alignItemWithTrigger={false}
        align="start"
        finalFocus={!managerOpen}
        footer={
          <Button
            variant="ghost"
            size="sm"
            className="fve:w-full fve:justify-start"
            onClick={() => {
              setOpen(false);
              onManage(triggerRef.current);
            }}
          >
            <Settings2Icon aria-hidden="true" />
            管理视图
          </Button>
        }
      >
        {groups.map(group => (
          <SelectGroup key={group.id}>
            <SelectLabel>{group.label}</SelectLabel>
            {group.sessions.map(session => (
              <SelectItem key={session.instance.id} value={session.instance.id}>
                <ViewInstanceLabel
                  session={session}
                  showPending={session.instance.id !== selectedId}
                />
              </SelectItem>
            ))}
          </SelectGroup>
        ))}
      </SelectContent>
    </Select>
  );
}

export function ViewSidebar({
  groups,
  selectedId,
  onSelect,
  onManage,
  onCollapse,
}: NavigationProps & { onCollapse(): void }) {
  return (
    <aside
      aria-label="视图列表"
      className="fve:hidden fve:w-52 fve:shrink-0 fve:flex-col fve:gap-3 fve:border-r fve:pr-3 fve:@min-[64rem]:flex"
    >
      <div className="fve:flex fve:items-center fve:justify-between fve:gap-2">
        <span className="fve:font-medium">视图</span>
        <div className="fve:flex fve:items-center fve:gap-1">
          <Button
            variant="ghost"
            size="icon-sm"
            aria-label="管理视图"
            title="管理视图"
            onClick={event => onManage(event.currentTarget)}
          >
            <Settings2Icon aria-hidden="true" />
          </Button>
          <Button
            variant="ghost"
            size="icon"
            aria-label="收起视图列表"
            onClick={onCollapse}
          >
            <PanelLeftCloseIcon aria-hidden="true" />
          </Button>
        </div>
      </div>
      {groups.map(group => (
        <section key={group.id} className="fve:flex fve:flex-col fve:gap-1">
          <h3 className="fve:px-2 fve:py-1 fve:text-xs fve:font-medium fve:text-muted-foreground">
            {group.label}
          </h3>
          {group.sessions.map(session => (
            <Button
              key={session.instance.id}
              variant={
                selectedId === session.instance.id ? 'secondary' : 'ghost'
              }
              className="fve:h-auto fve:min-h-8 fve:justify-start fve:whitespace-normal fve:break-words fve:text-left"
              aria-current={
                selectedId === session.instance.id ? 'page' : undefined
              }
              onClick={() => onSelect(session.instance.id)}
            >
              <ViewInstanceLabel
                session={session}
                showPending={session.instance.id !== selectedId}
              />
            </Button>
          ))}
        </section>
      ))}
    </aside>
  );
}
