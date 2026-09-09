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

import type * as React from 'react';
import { cn } from '../../lib/utils.js';

function Table({ className, ...props }: React.ComponentProps<'table'>) {
  return (
    <div
      data-slot="table-container"
      className="fve:relative fve:w-full fve:min-w-0 fve:max-w-full fve:overflow-x-auto"
    >
      <table
        data-slot="table"
        className={cn('fve:w-full fve:caption-bottom fve:text-sm', className)}
        {...props}
      />
    </div>
  );
}
function TableHeader({ className, ...props }: React.ComponentProps<'thead'>) {
  return (
    <thead
      data-slot="table-header"
      className={cn('fve:[&_tr]:border-b', className)}
      {...props}
    />
  );
}
function TableBody({ className, ...props }: React.ComponentProps<'tbody'>) {
  return (
    <tbody
      data-slot="table-body"
      className={cn('fve:[&_tr:last-child]:border-0', className)}
      {...props}
    />
  );
}
function TableFooter({ className, ...props }: React.ComponentProps<'tfoot'>) {
  return (
    <tfoot
      data-slot="table-footer"
      className={cn(
        'fve:border-t fve:bg-muted/50 fve:font-medium fve:[&>tr]:last:border-b-0',
        className,
      )}
      {...props}
    />
  );
}
function TableRow({ className, ...props }: React.ComponentProps<'tr'>) {
  return (
    <tr
      data-slot="table-row"
      className={cn(
        'fve:border-b fve:transition-colors fve:hover:bg-muted/50 fve:has-aria-expanded:bg-muted/50 fve:data-[state=selected]:bg-muted',
        className,
      )}
      {...props}
    />
  );
}
function TableHead({ className, ...props }: React.ComponentProps<'th'>) {
  return (
    <th
      data-slot="table-head"
      className={cn(
        'fve:h-10 fve:px-2 fve:text-left fve:align-middle fve:font-medium fve:whitespace-nowrap fve:text-foreground fve:[&:has([role=checkbox])]:pr-0',
        className,
      )}
      {...props}
    />
  );
}
function TableCell({ className, ...props }: React.ComponentProps<'td'>) {
  return (
    <td
      data-slot="table-cell"
      className={cn(
        'fve:p-2 fve:align-middle fve:whitespace-nowrap fve:[&:has([role=checkbox])]:pr-0',
        className,
      )}
      {...props}
    />
  );
}

export {
  Table,
  TableHeader,
  TableBody,
  TableFooter,
  TableHead,
  TableRow,
  TableCell,
};
