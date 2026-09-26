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

import { memo, useMemo, type ComponentProps } from 'react';
import { cn } from 'cn';
import { Calendar } from '../../components/calendar.js';
import {
  useViewMessages,
  type MessageFormatters,
} from '../../MessagesProvider.js';
import { useSurfaceDisplay } from '../../ViewSurface.js';

/**
 * The registry's calendar, speaking the surface's language.
 *
 * `react-day-picker` reads its month names, its weekday heads and its ARIA
 * labels out of a **date-fns `Locale` object**, and defaults to `en-US` when
 * it is given none — so a `zh-CN` surface whose trigger said
 * «2026年9月15日 – 2026年9月17日» opened a popover headed `September 2026` over
 * `Su Mo Tu We Th Fr Sa`, with `Sunday, August 30th, 2026` and
 * `Go to the Next Month` for a screen reader.
 *
 * A `ViewSurface`'s `locale` is a BCP-47 tag, not a date-fns locale, and
 * there is no mapping from one to the other that does not mean bundling every
 * locale the library ships. `Intl` needs no such table and no dependency: the
 * library takes `formatters` and `labels` per string it draws, and every one
 * of them is a date formatted in a language — which is the one thing
 * `Intl.DateTimeFormat` does. The rest of this package already formats its
 * dates that way (`ui/display.ts`), so the picker and the cell beside it now
 * name the same month in the same words.
 *
 * What is **not** covered: the month and year dropdowns' own names, which
 * only exist under `captionLayout="dropdown"` and nothing here asks for.
 */
export const SurfaceCalendar = memo(function SurfaceCalendar({
  className,
  ...props
}: ComponentProps<typeof Calendar>) {
  const { locale } = useSurfaceDisplay();
  const messages = useViewMessages();
  const localized = useMemo(
    () => calendarLocale(locale, messages),
    [locale, messages],
  );
  return (
    <Calendar
      {...localized}
      {...props}
      // A weekday head is a word in most languages and a single character in
      // some — `周日` needs more than the registry's 28px cell, and a grid
      // whose heads are clipped says nothing at all. The cell is the calendar's
      // one size variable, so widening it is the whole change; it also puts
      // the calendar (7 × 36 + 16 = 268px) and the popover's floor within a
      // few pixels of each other, so the clock under it is not sized by a
      // grid of digits.
      className={cn('[--cell-size:--spacing(9)]', className)}
    />
  );
});

/** Every string the picker draws, in one language. */
function calendarLocale(
  locale: string | undefined,
  messages: MessageFormatters,
): Pick<
  ComponentProps<typeof Calendar>,
  'formatters' | 'labels' | 'weekStartsOn'
> {
  const caption = formatter(locale, { year: 'numeric', month: 'long' });
  const monthShort = formatter(locale, { month: 'short' });
  const weekdayShort = formatter(locale, { weekday: 'short' });
  const weekdayLong = formatter(locale, { weekday: 'long' });
  const year = formatter(locale, { year: 'numeric' });
  const whole = formatter(locale, { dateStyle: 'full' });
  // A day cell is a bare number, not "15日": the unit word belongs to a date
  // read out loud, and the grid around it already says which month it is in.
  // It is still the locale's own numbering system, which is the half of this
  // `format(date, 'd')` could never do.
  const digits = digitsIn(locale);

  return {
    weekStartsOn: weekStart(locale),
    formatters: {
      formatCaption: date => caption.format(date),
      formatMonthDropdown: date => monthShort.format(date),
      formatYearDropdown: date => year.format(date),
      formatWeekdayName: date => weekdayShort.format(date),
      formatDay: date => digits.format(date.getDate()),
      formatWeekNumber: week => digits.format(week),
    },
    labels: {
      labelPrevious: () => messages.label('label.date.calendar-previous'),
      labelNext: () => messages.label('label.date.calendar-next'),
      labelGrid: date => caption.format(date),
      labelWeekday: date => weekdayLong.format(date),
      // The name a day button answers to: the date in words, and the two
      // things about it the grid says with colour alone.
      labelDayButton: (date, modifiers) => {
        let said = whole.format(date);
        if (modifiers.today)
          said = messages.label('label.date.calendar-today', { date: said });
        if (modifiers.selected)
          said = messages.label('label.date.calendar-selected', { date: said });
        return said;
      },
    },
  };
}

/**
 * Which day the locale's week starts on, as the picker counts it.
 *
 * CLDR numbers the days 1 (Monday) to 7 (Sunday); `react-day-picker` counts
 * 0 (Sunday) to 6 (Saturday), so the remainder is the whole conversion. Not
 * every engine has `getWeekInfo` yet, and one that does not keeps the
 * library's own default — a calendar that starts on the wrong day is a
 * defect, but a calendar that throws while being asked is not a calendar.
 */
function weekStart(locale: string | undefined): 0 | 1 | 2 | 3 | 4 | 5 | 6 {
  const fallback = 0;
  if (locale === undefined) return fallback;
  try {
    // `getWeekInfo` is newer than this project's TypeScript lib and is not
    // in every engine either, so it is asked for rather than declared.
    const asked = new Intl.Locale(locale) as Intl.Locale & {
      getWeekInfo?: () => { firstDay: number };
    };
    const info = asked.getWeekInfo?.();
    if (info === undefined) return fallback;
    return (info.firstDay % 7) as 0 | 1 | 2 | 3 | 4 | 5 | 6;
  } catch {
    // A tag `Intl.Locale` refuses is a tag the formatters above fell back on
    // too; one answer for the whole calendar.
    return fallback;
  }
}

/** The locale's own digits, or the environment's for a tag it refuses. */
function digitsIn(locale: string | undefined): Intl.NumberFormat {
  try {
    return new Intl.NumberFormat(locale);
  } catch {
    return new Intl.NumberFormat();
  }
}

/**
 * One formatter per set of options, built once per render of the picker.
 *
 * A tag `Intl` refuses throws on construction rather than on use, and the
 * surface's `locale` is a host's string — the environment's own language is
 * the answer the rest of this package gives for one it cannot read.
 */
function formatter(
  locale: string | undefined,
  options: Intl.DateTimeFormatOptions,
): Intl.DateTimeFormat {
  try {
    return new Intl.DateTimeFormat(locale, options);
  } catch {
    return new Intl.DateTimeFormat(undefined, options);
  }
}
