import { Tooltip as TooltipPrimitive } from "@base-ui/react/tooltip"
import { cn } from "cn"

function TooltipProvider({
  delay = 0,
  ...props
}: TooltipPrimitive.Provider.Props) {
  return (
    <TooltipPrimitive.Provider
      data-slot="tooltip-provider"
      delay={delay}
      {...props}
    />
  )
}

function Tooltip({ ...props }: TooltipPrimitive.Root.Props) {
  return <TooltipPrimitive.Root data-slot="tooltip" {...props} />
}

function TooltipTrigger({ ...props }: TooltipPrimitive.Trigger.Props) {
  return <TooltipPrimitive.Trigger data-slot="tooltip-trigger" {...props} />
}

function TooltipContent({
  className,
  side = "top",
  sideOffset = 4,
  align = "center",
  alignOffset = 0,
  children,
  ...props
}: TooltipPrimitive.Popup.Props &
  Pick<
    TooltipPrimitive.Positioner.Props,
    "align" | "alignOffset" | "side" | "sideOffset"
  >) {
  return (
    <TooltipPrimitive.Portal>
      <TooltipPrimitive.Positioner
        align={align}
        alignOffset={alignOffset}
        side={side}
        sideOffset={sideOffset}
        className="fve:isolate fve:z-50"
      >
        <TooltipPrimitive.Popup
          data-slot="tooltip-content"
          className={cn(
            "fve:z-50 fve:inline-flex fve:w-fit fve:max-w-xs fve:origin-(--transform-origin) fve:items-center fve:gap-1.5 fve:rounded-md fve:bg-foreground fve:px-3 fve:py-1.5 fve:text-xs fve:text-background fve:has-data-[slot=kbd]:pr-1.5 fve:data-[side=bottom]:slide-in-from-top-2 fve:data-[side=inline-end]:slide-in-from-left-2 fve:data-[side=inline-start]:slide-in-from-right-2 fve:data-[side=left]:slide-in-from-right-2 fve:data-[side=right]:slide-in-from-left-2 fve:data-[side=top]:slide-in-from-bottom-2 fve:**:data-[slot=kbd]:relative fve:**:data-[slot=kbd]:isolate fve:**:data-[slot=kbd]:z-50 fve:**:data-[slot=kbd]:rounded-sm fve:data-[state=delayed-open]:animate-in fve:data-[state=delayed-open]:fade-in-0 fve:data-[state=delayed-open]:zoom-in-95 fve:data-open:animate-in fve:data-open:fade-in-0 fve:data-open:zoom-in-95 fve:data-closed:animate-out fve:data-closed:fade-out-0 fve:data-closed:zoom-out-95",
            className
          )}
          {...props}
        >
          {children}
          <TooltipPrimitive.Arrow className="fve:z-50 fve:size-2.5 fve:translate-y-[calc(-50%-2px)] fve:rotate-45 fve:rounded-[2px] fve:bg-foreground fve:fill-foreground fve:data-[side=bottom]:top-1 fve:data-[side=inline-end]:top-1/2! fve:data-[side=inline-end]:-left-1 fve:data-[side=inline-end]:-translate-y-1/2 fve:data-[side=inline-start]:top-1/2! fve:data-[side=inline-start]:-right-1 fve:data-[side=inline-start]:-translate-y-1/2 fve:data-[side=left]:top-1/2! fve:data-[side=left]:-right-1 fve:data-[side=left]:-translate-y-1/2 fve:data-[side=right]:top-1/2! fve:data-[side=right]:-left-1 fve:data-[side=right]:-translate-y-1/2 fve:data-[side=top]:-bottom-2.5" />
        </TooltipPrimitive.Popup>
      </TooltipPrimitive.Positioner>
    </TooltipPrimitive.Portal>
  )
}

export { Tooltip, TooltipTrigger, TooltipContent, TooltipProvider }
