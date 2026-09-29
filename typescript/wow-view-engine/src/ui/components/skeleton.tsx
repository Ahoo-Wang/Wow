import { cn } from "cn"

function Skeleton({ className, ...props }: React.ComponentProps<"div">) {
  return (
    <div
      data-slot="skeleton"
      className={cn("fve:animate-pulse fve:rounded-md fve:bg-muted", className)}
      {...props}
    />
  )
}

export { Skeleton }
