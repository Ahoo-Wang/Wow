"use client"

import * as React from "react"
import { Select as SelectPrimitive } from "@base-ui/react/select"
import { cn } from "cn"
import { ChevronDownIcon, CheckIcon, ChevronUpIcon } from "lucide-react"

const Select = SelectPrimitive.Root

function SelectGroup({ className, ...props }: SelectPrimitive.Group.Props) {
  return (
    <SelectPrimitive.Group
      data-slot="select-group"
      className={cn("fve:scroll-my-1 fve:p-1", className)}
      {...props}
    />
  )
}

function SelectValue({ className, ...props }: SelectPrimitive.Value.Props) {
  return (
    <SelectPrimitive.Value
      data-slot="select-value"
      className={cn("fve:flex fve:flex-1 fve:text-left", className)}
      {...props}
    />
  )
}

function SelectTrigger({
  className,
  size = "default",
  children,
  ...props
}: SelectPrimitive.Trigger.Props & {
  size?: "sm" | "default"
}) {
  return (
    <SelectPrimitive.Trigger
      data-slot="select-trigger"
      data-size={size}
      className={cn(
        "fve:flex fve:w-fit fve:items-center fve:justify-between fve:gap-1.5 fve:rounded-lg fve:border fve:border-input fve:bg-transparent fve:py-2 fve:pr-2 fve:pl-2.5 fve:text-sm fve:whitespace-nowrap fve:transition-colors fve:outline-none fve:select-none fve:focus-visible:border-ring fve:focus-visible:ring-3 fve:focus-visible:ring-ring/50 fve:disabled:cursor-not-allowed fve:disabled:opacity-50 fve:aria-invalid:border-destructive fve:aria-invalid:ring-3 fve:aria-invalid:ring-destructive/20 fve:data-placeholder:text-muted-foreground fve:data-[size=default]:h-8 fve:data-[size=sm]:h-7 fve:data-[size=sm]:rounded-[min(var(--radius-md),10px)] fve:*:data-[slot=select-value]:line-clamp-1 fve:*:data-[slot=select-value]:flex fve:*:data-[slot=select-value]:items-center fve:*:data-[slot=select-value]:gap-1.5 fve:dark:bg-input/30 fve:dark:hover:bg-input/50 fve:dark:aria-invalid:border-destructive/50 fve:dark:aria-invalid:ring-destructive/40 fve:[&_svg]:pointer-events-none fve:[&_svg]:shrink-0 fve:[&_svg:not([class*='size-'])]:size-4",
        className
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
  )
}

function SelectContent({
  className,
  children,
  side = "bottom",
  sideOffset = 4,
  align = "center",
  alignOffset = 0,
  alignItemWithTrigger = true,
  ...props
}: SelectPrimitive.Popup.Props &
  Pick<
    SelectPrimitive.Positioner.Props,
    "align" | "alignOffset" | "side" | "sideOffset" | "alignItemWithTrigger"
  >) {
  return (
    <SelectPrimitive.Portal>
      <SelectPrimitive.Positioner
        side={side}
        sideOffset={sideOffset}
        align={align}
        alignOffset={alignOffset}
        alignItemWithTrigger={alignItemWithTrigger}
        className="fve:isolate fve:z-50"
      >
        <SelectPrimitive.Popup
          data-slot="select-content"
          data-align-trigger={alignItemWithTrigger}
          className={cn("fve:relative fve:isolate fve:z-50 fve:max-h-(--available-height) fve:w-(--anchor-width) fve:min-w-36 fve:origin-(--transform-origin) fve:overflow-x-hidden fve:overflow-y-auto fve:rounded-lg fve:bg-popover fve:text-popover-foreground fve:shadow-md fve:ring-1 fve:ring-foreground/10 fve:duration-100 fve:data-[align-trigger=true]:animate-none fve:data-[side=bottom]:slide-in-from-top-2 fve:data-[side=inline-end]:slide-in-from-left-2 fve:data-[side=inline-start]:slide-in-from-right-2 fve:data-[side=left]:slide-in-from-right-2 fve:data-[side=right]:slide-in-from-left-2 fve:data-[side=top]:slide-in-from-bottom-2 fve:data-open:animate-in fve:data-open:fade-in-0 fve:data-open:zoom-in-95 fve:data-closed:animate-out fve:data-closed:fade-out-0 fve:data-closed:zoom-out-95", className )}
          {...props}
        >
          <SelectScrollUpButton />
          <SelectPrimitive.List>{children}</SelectPrimitive.List>
          <SelectScrollDownButton />
        </SelectPrimitive.Popup>
      </SelectPrimitive.Positioner>
    </SelectPrimitive.Portal>
  )
}

function SelectLabel({
  className,
  ...props
}: SelectPrimitive.GroupLabel.Props) {
  return (
    <SelectPrimitive.GroupLabel
      data-slot="select-label"
      className={cn("fve:px-1.5 fve:py-1 fve:text-xs fve:text-muted-foreground", className)}
      {...props}
    />
  )
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
        "fve:relative fve:flex fve:w-full fve:cursor-default fve:items-center fve:gap-1.5 fve:rounded-md fve:py-1 fve:pr-8 fve:pl-1.5 fve:text-sm fve:outline-hidden fve:select-none fve:focus:bg-accent fve:focus:text-accent-foreground fve:not-data-[variant=destructive]:focus:**:text-accent-foreground fve:data-disabled:pointer-events-none fve:data-disabled:opacity-50 fve:[&_svg]:pointer-events-none fve:[&_svg]:shrink-0 fve:[&_svg:not([class*='size-'])]:size-4 fve:*:[span]:last:flex fve:*:[span]:last:items-center fve:*:[span]:last:gap-2",
        className
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
  )
}

function SelectSeparator({
  className,
  ...props
}: SelectPrimitive.Separator.Props) {
  return (
    <SelectPrimitive.Separator
      data-slot="select-separator"
      className={cn("fve:pointer-events-none fve:-mx-1 fve:my-1 fve:h-px fve:bg-border", className)}
      {...props}
    />
  )
}

function SelectScrollUpButton({
  className,
  ...props
}: React.ComponentProps<typeof SelectPrimitive.ScrollUpArrow>) {
  return (
    <SelectPrimitive.ScrollUpArrow
      data-slot="select-scroll-up-button"
      className={cn(
        "fve:top-0 fve:z-10 fve:flex fve:w-full fve:cursor-default fve:items-center fve:justify-center fve:bg-popover fve:py-1 fve:[&_svg:not([class*='size-'])]:size-4",
        className
      )}
      {...props}
    >
      <ChevronUpIcon
      />
    </SelectPrimitive.ScrollUpArrow>
  )
}

function SelectScrollDownButton({
  className,
  ...props
}: React.ComponentProps<typeof SelectPrimitive.ScrollDownArrow>) {
  return (
    <SelectPrimitive.ScrollDownArrow
      data-slot="select-scroll-down-button"
      className={cn(
        "fve:bottom-0 fve:z-10 fve:flex fve:w-full fve:cursor-default fve:items-center fve:justify-center fve:bg-popover fve:py-1 fve:[&_svg:not([class*='size-'])]:size-4",
        className
      )}
      {...props}
    >
      <ChevronDownIcon
      />
    </SelectPrimitive.ScrollDownArrow>
  )
}

export {
  Select,
  SelectContent,
  SelectGroup,
  SelectItem,
  SelectLabel,
  SelectScrollDownButton,
  SelectScrollUpButton,
  SelectSeparator,
  SelectTrigger,
  SelectValue,
}
