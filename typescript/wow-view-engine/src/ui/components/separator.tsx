import { Separator as SeparatorPrimitive } from "@base-ui/react/separator"
import { cn } from "cn"

function Separator({
  className,
  orientation = "horizontal",
  ...props
}: SeparatorPrimitive.Props) {
  return (
    <SeparatorPrimitive
      data-slot="separator"
      orientation={orientation}
      className={cn(
        "fve:shrink-0 fve:bg-border fve:data-horizontal:h-px fve:data-horizontal:w-full fve:data-vertical:w-px fve:data-vertical:self-stretch",
        className
      )}
      {...props}
    />
  )
}

export { Separator }
