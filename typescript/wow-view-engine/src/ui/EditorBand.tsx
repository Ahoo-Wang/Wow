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
import { cn } from 'cn';
import { ChevronRightIcon } from 'lucide-react';
import { buttonVariants } from './components/button.js';
import {
  Collapsible,
  CollapsibleContent,
  CollapsibleTrigger,
} from './components/collapsible.js';
import { useViewMessages } from './MessagesProvider.js';

export interface EditorBandProps {
  open: boolean;
  onOpenChange(open: boolean): void;
  /** The wording of the collapsed row; a record view names its filter. */
  label: string;
  /**
   * How many nodes of the editor say something other than what ran. The band
   * carries the count so the one credential for "edited, not applied" is on
   * screen even while the editor that owns those nodes is folded away.
   */
  pending: number;
  children: ReactNode;
  className?: string;
}

/**
 * The fold the editor of a view lives in.
 *
 * The result is the point of a view, so the editor gives it the room: a saved
 * view opens folded, a new one open, and the state is the opening's alone —
 * nothing about how a view is looked at is worth persisting.
 *
 * It is a shell and nothing else. It holds no idea of conditions, panels or
 * aggregations, which is what lets the three workbenches share it while their
 * editors stay their own.
 */
export function EditorBand({
  open,
  onOpenChange,
  label,
  pending,
  children,
  className,
}: EditorBandProps) {
  const messages = useViewMessages();
  return (
    <Collapsible
      data-slot="editor-band"
      open={open}
      // Base UI hands the reason for the change along with it; the band has
      // one way to open and nothing to decide from the reason.
      onOpenChange={next => onOpenChange(next)}
      className={cn('flex flex-col', className)}
    >
      <CollapsibleTrigger
        // Base UI marks the trigger of an open panel, so the chevron turns
        // off the state the primitive already publishes rather than off a
        // second copy of it kept here.
        className={cn(
          buttonVariants({ variant: 'ghost', size: 'sm' }),
          'group/band w-full justify-start',
        )}
      >
        <ChevronRightIcon
          data-icon="inline-start"
          className="transition-transform group-data-[panel-open]/band:rotate-90"
        />
        <span className="truncate">{label}</span>
        {pending > 0 && (
          <span className="text-muted-foreground ml-auto flex items-center gap-1 text-xs font-normal">
            <span
              aria-hidden="true"
              className="bg-primary size-1.5 rounded-full"
            />
            {messages.label('label.editor.pending', { count: pending })}
          </span>
        )}
      </CollapsibleTrigger>
      <CollapsibleContent className="pt-2">{children}</CollapsibleContent>
    </Collapsible>
  );
}
