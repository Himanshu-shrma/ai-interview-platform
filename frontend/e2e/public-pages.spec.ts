import { test, expect } from '@playwright/test'

test.describe('Public Pages', () => {
  test('landing page renders title and description', async ({ page }) => {
    await page.goto('/')
    await expect(page.getByRole('heading', { name: 'AI Interview Platform' })).toBeVisible()
    await expect(page.getByText('Practice technical interviews with an AI interviewer')).toBeVisible()
  })

  test('landing page has no console errors', async ({ page }) => {
    const errors: string[] = []
    page.on('console', (msg) => {
      if (msg.type() === 'error') errors.push(msg.text())
    })
    await page.goto('/')
    await page.waitForTimeout(2000)
    // Filter out known Clerk third-party warnings
    const realErrors = errors.filter(
      (e) => !e.includes('clerk') && !e.includes('Clerk') && !e.includes('third-party')
    )
    expect(realErrors).toHaveLength(0)
  })

  test('sign-in page loads Clerk widget', async ({ page }) => {
    await page.goto('/sign-in')
    // Clerk renders a sign-in form — wait for it to appear
    await expect(
      page.locator('.cl-signIn-root, .cl-rootBox, [data-clerk]').first()
    ).toBeVisible({ timeout: 15_000 })
  })

  test('sign-up page loads Clerk widget', async ({ page }) => {
    await page.goto('/sign-up')
    await expect(
      page.locator('.cl-signUp-root, .cl-rootBox, [data-clerk]').first()
    ).toBeVisible({ timeout: 15_000 })
  })
})

test.describe('Protected Route Redirects (unauthenticated)', () => {
  test('dashboard redirects to sign-in', async ({ page }) => {
    await page.goto('/dashboard')
    await page.waitForURL(/sign-in/, { timeout: 10_000 })
    expect(page.url()).toContain('/sign-in')
  })

  test('interview setup redirects to sign-in', async ({ page }) => {
    await page.goto('/interview/setup')
    await page.waitForURL(/sign-in/, { timeout: 10_000 })
    expect(page.url()).toContain('/sign-in')
  })

  test('interview page redirects to sign-in', async ({ page }) => {
    await page.goto('/interview/some-session-id')
    await page.waitForURL(/sign-in/, { timeout: 10_000 })
    expect(page.url()).toContain('/sign-in')
  })

  test('report page redirects to sign-in', async ({ page }) => {
    await page.goto('/report/some-session-id')
    await page.waitForURL(/sign-in/, { timeout: 10_000 })
    expect(page.url()).toContain('/sign-in')
  })
})

test.describe('Responsive Design', () => {
  test('landing page renders on mobile viewport', async ({ page }) => {
    await page.setViewportSize({ width: 375, height: 667 })
    await page.goto('/')
    await expect(page.getByRole('heading', { name: 'AI Interview Platform' })).toBeVisible()
  })

  test('landing page renders on tablet viewport', async ({ page }) => {
    await page.setViewportSize({ width: 768, height: 1024 })
    await page.goto('/')
    await expect(page.getByRole('heading', { name: 'AI Interview Platform' })).toBeVisible()
  })
})
