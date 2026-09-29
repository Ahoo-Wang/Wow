import { Tabs as TabsPrimitive } from "@base-ui/react/tabs"
import { cva, type VariantProps } from "class-variance-authority"
import { cn } from "cn"

function Tabs({
  className,
  orientation = "horizontal",
  ...props
}: TabsPrimitive.Root.Props) {
  return (
    <TabsPrimitive.Root
      data-slot="tabs"
      data-orientation={orientation}
      className={cn(
        "fve:group/tabs fve:flex fve:gap-2 fve:data-horizontal:flex-col",
        className
      )}
      {...props}
    />
  )
}

const tabsListVariants = cva(
  "fve:group/tabs-list fve:inline-flex fve:w-fit fve:items-center fve:justify-center fve:rounded-lg fve:p-[3px] fve:text-muted-foreground fve:group-data-horizontal/tabs:h-8 fve:group-data-vertical/tabs:h-fit fve:group-data-vertical/tabs:flex-col fve:data-[variant=line]:rounded-none",
  {
    variants: {
      variant: {
        default: "fve:bg-muted",
        line: "fve:gap-1 fve:bg-transparent",
      },
    },
    defaultVariants: {
      variant: "default",
    },
  }
)

function TabsList({
  className,
  variant = "default",
  ...props
}: TabsPrimitive.List.Props & VariantProps<typeof tabsListVariants>) {
  return (
    <TabsPrimitive.List
      data-slot="tabs-list"
      data-variant={variant}
      className={cn(tabsListVariants({ variant }), className)}
      {...props}
    />
  )
}

function TabsTrigger({ className, ...props }: TabsPrimitive.Tab.Props) {
  return (
    <TabsPrimitive.Tab
      data-slot="tabs-trigger"
      className={cn(
        "fve:relative fve:inline-flex fve:h-[calc(100%-1px)] fve:flex-1 fve:items-center fve:justify-center fve:gap-1.5 fve:rounded-md fve:border fve:border-transparent fve:px-1.5 fve:py-0.5 fve:text-sm fve:font-medium fve:whitespace-nowrap fve:text-quiet-foreground fve:transition-all fve:group-data-vertical/tabs:w-full fve:group-data-vertical/tabs:justify-start fve:hover:text-foreground fve:focus-visible:border-ring fve:focus-visible:ring-[3px] fve:focus-visible:ring-ring/50 fve:focus-visible:outline-1 fve:focus-visible:outline-ring fve:disabled:pointer-events-none fve:disabled:opacity-50 fve:has-data-[icon=inline-end]:pr-1 fve:has-data-[icon=inline-start]:pl-1 fve:aria-disabled:pointer-events-none fve:aria-disabled:opacity-50 fve:dark:text-muted-foreground fve:dark:hover:text-foreground fve:group-data-[variant=default]/tabs-list:data-active:shadow-sm fve:group-data-[variant=line]/tabs-list:data-active:shadow-none fve:[&_svg]:pointer-events-none fve:[&_svg]:shrink-0 fve:[&_svg:not([class*='size-'])]:size-4",
        "fve:group-data-[variant=line]/tabs-list:bg-transparent fve:group-data-[variant=line]/tabs-list:data-active:bg-transparent fve:dark:group-data-[variant=line]/tabs-list:data-active:border-transparent fve:dark:group-data-[variant=line]/tabs-list:data-active:bg-transparent",
        "fve:data-active:bg-background fve:data-active:text-foreground fve:dark:data-active:border-input fve:dark:data-active:bg-input/30 fve:dark:data-active:text-foreground",
        "fve:after:absolute fve:after:bg-foreground fve:after:opacity-0 fve:after:transition-opacity fve:group-data-horizontal/tabs:after:inset-x-0 fve:group-data-horizontal/tabs:after:bottom-[-5px] fve:group-data-horizontal/tabs:after:h-0.5 fve:group-data-vertical/tabs:after:inset-y-0 fve:group-data-vertical/tabs:after:-right-1 fve:group-data-vertical/tabs:after:w-0.5 fve:group-data-[variant=line]/tabs-list:data-active:after:opacity-100",
        className
      )}
      {...props}
    />
  )
}

function TabsContent({ className, ...props }: TabsPrimitive.Panel.Props) {
  return (
    <TabsPrimitive.Panel
      data-slot="tabs-content"
      className={cn("fve:flex-1 fve:text-sm fve:outline-none", className)}
      {...props}
    />
  )
}

export { Tabs, TabsList, TabsTrigger, TabsContent, tabsListVariants }
