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
import { createContext, useContext } from 'react';
import { useOverlayOpen, useOverlayVisible } from '../../lib/OverlayScope.js';
import { usePortalTheme, type PortalTheme } from '../../lib/usePortalTheme.js';
import { Select as SelectPrimitive } from '@base-ui/react/select';
import { cn } from '../../lib/utils.js';
import { ChevronDownIcon, CheckIcon, ChevronUpIcon } from 'lucide-react';

const SelectTheme = createContext<PortalTheme>({ style: {} });

function Select<Value, Multiple extends boolean | undefined = false>(
  props: SelectPrimitive.Root.Props<Value, Multiple>,
) {
  const [localOpen, setOpen] = useOverlayOpen(props.defaultOpen);
  const visible = useOverlayVisible();
  const actualOpen = visible && (props.open ?? localOpen);
  const { scope, theme, captureTheme } = usePortalTheme(
    actualOpen,
    props.defaultOpen,
  );
  return (
    <span
      ref={scope}
      className="fve-root fve:inline-flex fve:min-w-0 fve:max-w-full"
    >
      <SelectTheme.Provider value={theme}>
        <SelectPrimitive.Root
          {...props}
          open={actualOpen}
          onOpenChange={(open, details) => {
            props.onOpenChange?.(open, details);
            if (!details.isCanceled) {
              setOpen(open);
              captureTheme(open);
            }
          }}
        />
      </SelectTheme.Provider>
    </span>
  );
}

function SelectGroup({ className, ...props }: SelectPrimitive.Group.Props) {
  return (
    <SelectPrimitive.Group
      data-slot="select-group"
      className={cn('fve:scroll-my-1 fve:p-1', className)}
      {...props}
    />
  );
}

function SelectValue({ className, ...props }: SelectPrimitive.Value.Props) {
  return (
    <SelectPrimitive.Value
      data-slot="select-value"
      className={cn('fve:flex fve:min-w-0 fve:flex-1 fve:text-left', className)}
      {...props}
    />
  );
}

function SelectTrigger({
  className,
  size = 'default',
  children,
  ...props
}: SelectPrimitive.Trigger.Props & {
  size?: 'sm' | 'default';
}) {
  return (
    <SelectPrimitive.Trigger
      data-slot="select-trigger"
      data-size={size}
      className={cn(
        'fve:flex fve:min-w-0 fve:w-fit fve:max-w-full fve:items-center fve:justify-between fve:gap-1.5 fve:rounded-lg fve:border fve:border-input fve:bg-transparent fve:py-2 fve:pr-2 fve:pl-2.5 fve:text-sm fve:whitespace-nowrap fve:transition-colors fve:outline-none fve:select-none fve:focus-visible:border-ring fve:focus-visible:ring-3 fve:focus-visible:ring-ring fve:disabled:cursor-not-allowed fve:disabled:opacity-50 fve:aria-invalid:border-destructive fve:aria-invalid:ring-3 fve:aria-invalid:ring-destructive/20 fve:data-placeholder:text-muted-foreground fve:data-[size=default]:h-(--fve-control-height) fve:data-[size=sm]:h-7 fve:data-[size=sm]:rounded-[min(var(--fve-radius-md),10px)] fve:*:data-[slot=select-value]:line-clamp-1 fve:*:data-[slot=select-value]:flex fve:*:data-[slot=select-value]:items-center fve:*:data-[slot=select-value]:gap-1.5 fve:dark:bg-input/30 fve:dark:hover:bg-input/50 fve:dark:aria-invalid:border-destructive/50 fve:dark:aria-invalid:ring-destructive/40 fve:[&_svg]:pointer-events-none fve:[&_svg]:shrink-0 fve:[&_svg:not([class*=size-])]:size-4',
        className,
      )}
      {...props}
    >
      {children}
      <SelectPrimitive.Icon
        render={
          <ChevronDownIcon className="fve:pointer-events-none fve:size-4 fve:text-muted-foreground" />
        }
      />
    </SelectPrimitive.Trigger>
  );
}

function SelectContent({
  className,
  children,
  side = 'bottom',
  sideOffset = 4,
  align = 'center',
  alignOffset = 0,
  alignItemWithTrigger = true,
  container,
  footer,
  ...props
}: SelectPrimitive.Popup.Props & { footer?: React.ReactNode } & Pick<
    SelectPrimitive.Positioner.Props,
    'align' | 'alignOffset' | 'side' | 'sideOffset' | 'alignItemWithTrigger'
  > &
  Pick<SelectPrimitive.Portal.Props, 'container'>) {
  const theme = useContext(SelectTheme);
  return (
    <SelectPrimitive.Portal
      container={container}
      className="fve-root"
      {...theme}
    >
      <SelectPrimitive.Positioner
        side={side}
        sideOffset={sideOffset}
        align={align}
        alignOffset={alignOffset}
        alignItemWithTrigger={alignItemWithTrigger}
        className="fve-root fve:isolate fve:z-50"
      >
        <SelectPrimitive.Popup
          data-slot="select-content"
          data-align-trigger={alignItemWithTrigger}
          className={cn(
            'fve:relative fve:isolate fve:z-50 fve:max-h-(--available-height) fve:w-(--anchor-width) fve:min-w-36 fve:origin-(--transform-origin) fve:overflow-x-hidden fve:overflow-y-auto fve:rounded-lg fve:bg-popover fve:text-popover-foreground fve:shadow-md fve:ring-1 fve:ring-foreground/10 fve:duration-100 fve:data-[align-trigger=true]:animate-none fve:data-[side=bottom]:slide-in-from-top-2 fve:data-[side=inline-end]:slide-in-from-left-2 fve:data-[side=inline-start]:slide-in-from-right-2 fve:data-[side=left]:slide-in-from-right-2 fve:data-[side=right]:slide-in-from-left-2 fve:data-[side=top]:slide-in-from-bottom-2 fve:data-open:animate-in fve:data-open:fade-in-0 fve:data-open:zoom-in-95 fve:data-closed:animate-out fve:data-closed:fade-out-0 fve:data-closed:zoom-out-95',
            className,
          )}
          {...props}
        >
          <SelectScrollUpButton />
          <SelectPrimitive.List>{children}</SelectPrimitive.List>
          <SelectScrollDownButton />
          {footer && (
            <div className="fve:sticky fve:bottom-0 fve:border-t fve:bg-popover fve:p-1">
              {footer}
            </div>
          )}
        </SelectPrimitive.Popup>
      </SelectPrimitive.Positioner>
    </SelectPrimitive.Portal>
  );
}

function SelectLabel({
  className,
  ...props
}: SelectPrimitive.GroupLabel.Props) {
  return (
    <SelectPrimitive.GroupLabel
      data-slot="select-label"
      className={cn(
        'fve:px-1.5 fve:py-1 fve:text-xs fve:text-muted-foreground',
        className,
      )}
      {...props}
    />
  );
}

function SelectItem({
  className,
  children,
  ...props
}: SelectPrimitive.Item.Props) {
  return (
    <SelectPrimitive.Item
      data-slot="select-item"
      className={cn(
        'fve:relative fve:flex fve:w-full fve:cursor-default fve:items-center fve:gap-1.5 fve:rounded-md fve:py-1 fve:pr-8 fve:pl-1.5 fve:text-sm fve:outline-hidden fve:select-none fve:focus:bg-accent fve:focus:text-accent-foreground fve:not-data-[variant=destructive]:focus:**:text-accent-foreground fve:data-disabled:pointer-events-none fve:data-disabled:opacity-50 fve:[&_svg]:pointer-events-none fve:[&_svg]:shrink-0 fve:[&_svg:not([class*=size-])]:size-4 fve:*:[span]:last:flex fve:*:[span]:last:items-center fve:*:[span]:last:gap-2',
        className,
      )}
      {...props}
    >
      <SelectPrimitive.ItemText className="fve:flex fve:flex-1 fve:shrink-0 fve:gap-2 fve:whitespace-nowrap">
        {children}
      </SelectPrimitive.ItemText>
      <SelectPrimitive.ItemIndicator
        render={
          <span className="fve:pointer-events-none fve:absolute fve:right-2 fve:flex fve:size-4 fve:items-center fve:justify-center" />
        }
      >
        <CheckIcon className="fve:pointer-events-none" />
      </SelectPrimitive.ItemIndicator>
    </SelectPrimitive.Item>
  );
}

function SelectScrollUpButton({
  className,
  ...props
}: React.ComponentProps<typeof SelectPrimitive.ScrollUpArrow>) {
  return (
    <SelectPrimitive.ScrollUpArrow
      data-slot="select-scroll-up-button"
      className={cn(
        'fve:top-0 fve:z-10 fve:flex fve:w-full fve:cursor-default fve:items-center fve:justify-center fve:bg-popover fve:py-1 fve:[&_svg:not([class*=size-])]:size-4',
        className,
      )}
      {...props}
    >
      <ChevronUpIcon />
    </SelectPrimitive.ScrollUpArrow>
  );
}

function SelectScrollDownButton({
  className,
  ...props
}: React.ComponentProps<typeof SelectPrimitive.ScrollDownArrow>) {
  return (
    <SelectPrimitive.ScrollDownArrow
      data-slot="select-scroll-down-button"
      className={cn(
        'fve:bottom-0 fve:z-10 fve:flex fve:w-full fve:cursor-default fve:items-center fve:justify-center fve:bg-popover fve:py-1 fve:[&_svg:not([class*=size-])]:size-4',
        className,
      )}
      {...props}
    >
      <ChevronDownIcon />
    </SelectPrimitive.ScrollDownArrow>
  );
}

export {
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectLabel,
  SelectScrollDownButton,
  SelectScrollUpButton,
  SelectTrigger,
  SelectValue,
};
