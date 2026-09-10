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

'use client';

import * as React from 'react';
import { cn } from '../../lib/utils.js';
import {
  DayPicker,
  getDefaultClassNames,
  type DayButton,
  type Locale,
} from 'react-day-picker';

import { Button, buttonVariants } from './button.js';
import {
  ChevronLeftIcon,
  ChevronRightIcon,
  ChevronDownIcon,
} from 'lucide-react';

function Calendar({
  className,
  classNames,
  showOutsideDays = true,
  captionLayout = 'label',
  buttonVariant = 'ghost',
  locale,
  formatters,
  components,
  ...props
}: React.ComponentProps<typeof DayPicker> & {
  buttonVariant?: React.ComponentProps<typeof Button>['variant'];
}) {
  const defaultClassNames = getDefaultClassNames();

  return (
    <DayPicker
      showOutsideDays={showOutsideDays}
      className={cn(
        'fve-root fve:group/calendar fve:bg-background fve:p-2 fve:[--fve-cell-radius:var(--fve-radius-md)] fve:[--fve-cell-size:--spacing(7)] fve:in-data-[slot=card-content]:bg-transparent fve:in-data-[slot=popover-content]:bg-transparent',
        String.raw`fve:rtl:**:[.rdp-button\_next>svg]:rotate-180`,
        String.raw`fve:rtl:**:[.rdp-button\_previous>svg]:rotate-180`,
        className,
      )}
      captionLayout={captionLayout}
      locale={locale}
      formatters={{
        formatMonthDropdown: date =>
          date.toLocaleString(locale?.code, { month: 'short' }),
        ...formatters,
      }}
      classNames={{
        root: cn('fve:w-fit', defaultClassNames.root),
        months: cn(
          'fve:relative fve:flex fve:flex-col fve:gap-4 fve:md:flex-row',
          defaultClassNames.months,
        ),
        month: cn(
          'fve:flex fve:w-full fve:flex-col fve:gap-4',
          defaultClassNames.month,
        ),
        nav: cn(
          'fve:absolute fve:inset-x-0 fve:top-0 fve:flex fve:w-full fve:items-center fve:justify-between fve:gap-1',
          defaultClassNames.nav,
        ),
        button_previous: cn(
          buttonVariants({ variant: buttonVariant }),
          'fve:size-(--fve-cell-size) fve:p-0 fve:select-none fve:aria-disabled:opacity-50',
          defaultClassNames.button_previous,
        ),
        button_next: cn(
          buttonVariants({ variant: buttonVariant }),
          'fve:size-(--fve-cell-size) fve:p-0 fve:select-none fve:aria-disabled:opacity-50',
          defaultClassNames.button_next,
        ),
        month_caption: cn(
          'fve:flex fve:h-(--fve-cell-size) fve:w-full fve:items-center fve:justify-center fve:px-(--fve-cell-size)',
          defaultClassNames.month_caption,
        ),
        dropdowns: cn(
          'fve:flex fve:h-(--fve-cell-size) fve:w-full fve:items-center fve:justify-center fve:gap-1.5 fve:text-sm fve:font-medium',
          defaultClassNames.dropdowns,
        ),
        dropdown_root: cn(
          'fve:relative fve:rounded-(--fve-cell-radius)',
          defaultClassNames.dropdown_root,
        ),
        dropdown: cn(
          'fve:absolute fve:inset-0 fve:bg-popover fve:opacity-0',
          defaultClassNames.dropdown,
        ),
        caption_label: cn(
          'fve:font-medium fve:select-none',
          captionLayout === 'label'
            ? 'fve:text-sm'
            : 'fve:flex fve:items-center fve:gap-1 fve:rounded-(--fve-cell-radius) fve:text-sm fve:[&>svg]:size-3.5 fve:[&>svg]:text-muted-foreground',
          defaultClassNames.caption_label,
        ),
        month_grid: cn(
          'fve:w-full fve:border-collapse',
          defaultClassNames.month_grid,
        ),
        weekdays: cn('fve:flex', defaultClassNames.weekdays),
        weekday: cn(
          'fve:flex-1 fve:rounded-(--fve-cell-radius) fve:text-[length:calc(var(--fve-font-size)*32/35)] fve:font-normal fve:text-muted-foreground fve:select-none',
          defaultClassNames.weekday,
        ),
        week: cn('fve:mt-2 fve:flex fve:w-full', defaultClassNames.week),
        week_number_header: cn(
          'fve:w-(--fve-cell-size) fve:select-none',
          defaultClassNames.week_number_header,
        ),
        week_number: cn(
          'fve:text-[length:calc(var(--fve-font-size)*32/35)] fve:text-muted-foreground fve:select-none',
          defaultClassNames.week_number,
        ),
        day: cn(
          'fve:group/day fve:relative fve:aspect-square fve:h-full fve:w-full fve:rounded-(--fve-cell-radius) fve:p-0 fve:text-center fve:select-none fve:[&:last-child[data-selected=true]_button]:rounded-r-(--fve-cell-radius)',
          props.showWeekNumber
            ? 'fve:[&:nth-child(2)[data-selected=true]_button]:rounded-l-(--fve-cell-radius)'
            : 'fve:[&:first-child[data-selected=true]_button]:rounded-l-(--fve-cell-radius)',
          defaultClassNames.day,
        ),
        range_start: cn(
          'fve:relative fve:isolate fve:z-0 fve:rounded-l-(--fve-cell-radius) fve:bg-muted fve:after:absolute fve:after:inset-y-0 fve:after:right-0 fve:after:w-4 fve:after:bg-muted',
          defaultClassNames.range_start,
        ),
        range_middle: cn('fve:rounded-none', defaultClassNames.range_middle),
        range_end: cn(
          'fve:relative fve:isolate fve:z-0 fve:rounded-r-(--fve-cell-radius) fve:bg-muted fve:after:absolute fve:after:inset-y-0 fve:after:left-0 fve:after:w-4 fve:after:bg-muted',
          defaultClassNames.range_end,
        ),
        today: cn(
          'fve:rounded-(--fve-cell-radius) fve:bg-muted fve:text-foreground fve:data-[selected=true]:rounded-none',
          defaultClassNames.today,
        ),
        outside: cn(
          'fve:text-muted-foreground fve:aria-selected:text-muted-foreground',
          defaultClassNames.outside,
        ),
        disabled: cn(
          'fve:text-muted-foreground fve:opacity-50',
          defaultClassNames.disabled,
        ),
        hidden: cn('fve:invisible', defaultClassNames.hidden),
        ...classNames,
      }}
      components={{
        Root: ({ className, rootRef, ...props }) => {
          return (
            <div
              data-slot="calendar"
              ref={rootRef}
              className={cn(className)}
              {...props}
            />
          );
        },
        Chevron: ({ className, orientation, ...props }) => {
          if (orientation === 'left') {
            return (
              <ChevronLeftIcon
                className={cn('fve:size-4', className)}
                {...props}
              />
            );
          }

          if (orientation === 'right') {
            return (
              <ChevronRightIcon
                className={cn('fve:size-4', className)}
                {...props}
              />
            );
          }

          return (
            <ChevronDownIcon
              className={cn('fve:size-4', className)}
              {...props}
            />
          );
        },
        DayButton: ({ ...props }) => (
          <CalendarDayButton locale={locale} {...props} />
        ),
        WeekNumber: ({ children, ...props }) => {
          return (
            <td {...props}>
              <div className="fve:flex fve:size-(--fve-cell-size) fve:items-center fve:justify-center fve:text-center">
                {children}
              </div>
            </td>
          );
        },
        ...components,
      }}
      {...props}
    />
  );
}

function CalendarDayButton({
  className,
  day,
  modifiers,
  locale,
  ...props
}: React.ComponentProps<typeof DayButton> & { locale?: Partial<Locale> }) {
  const defaultClassNames = getDefaultClassNames();

  const ref = React.useRef<HTMLButtonElement>(null);
  React.useEffect(() => {
    if (modifiers.focused) ref.current?.focus();
  }, [modifiers.focused]);

  return (
    <Button
      ref={ref}
      variant="ghost"
      size="icon"
      data-day={day.date.toLocaleDateString(locale?.code)}
      data-selected-single={
        modifiers.selected &&
        !modifiers.range_start &&
        !modifiers.range_end &&
        !modifiers.range_middle
      }
      data-range-start={modifiers.range_start}
      data-range-end={modifiers.range_end}
      data-range-middle={modifiers.range_middle}
      className={cn(
        'fve:relative fve:isolate fve:z-10 fve:flex fve:aspect-square fve:size-auto fve:w-full fve:min-w-(--fve-cell-size) fve:flex-col fve:gap-1 fve:border-0 fve:leading-none fve:font-normal fve:group-data-[focused=true]/day:relative fve:group-data-[focused=true]/day:z-10 fve:group-data-[focused=true]/day:border-ring fve:group-data-[focused=true]/day:ring-[3px] fve:group-data-[focused=true]/day:ring-ring/50 fve:data-[range-end=true]:rounded-(--fve-cell-radius) fve:data-[range-end=true]:rounded-r-(--fve-cell-radius) fve:data-[range-end=true]:bg-primary fve:data-[range-end=true]:text-primary-foreground fve:data-[range-middle=true]:rounded-none fve:data-[range-middle=true]:bg-muted fve:data-[range-middle=true]:text-foreground fve:data-[range-start=true]:rounded-(--fve-cell-radius) fve:data-[range-start=true]:rounded-l-(--fve-cell-radius) fve:data-[range-start=true]:bg-primary fve:data-[range-start=true]:text-primary-foreground fve:data-[selected-single=true]:bg-primary fve:data-[selected-single=true]:text-primary-foreground fve:dark:hover:text-foreground fve:[&>span]:text-xs fve:[&>span]:opacity-70',
        defaultClassNames.day,
        className,
      )}
      {...props}
    />
  );
}

export { Calendar, CalendarDayButton };
