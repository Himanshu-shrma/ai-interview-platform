import { UserButton, useUser } from '@clerk/clerk-react'

export function PageHeader() {
  const { user } = useUser()

  return (
    <header className="border-b bg-background">
      <div className="mx-auto flex h-16 max-w-7xl items-center justify-between px-4 sm:px-6 lg:px-8">
        <div className="flex items-center gap-2">
          <div className="flex h-8 w-8 items-center justify-center rounded-lg bg-primary text-primary-foreground font-bold text-sm">
            AI
          </div>
          <span className="text-lg font-semibold">Interview Platform</span>
        </div>

        <div className="flex items-center gap-3">
          <span className="text-sm text-muted-foreground hidden sm:inline">
            {user?.fullName ?? user?.primaryEmailAddress?.emailAddress}
          </span>
          <UserButton afterSignOutUrl="/" />
        </div>
      </div>
    </header>
  )
}
