import { test, expect } from '@playwright/test'
import { clerk, setupClerkTestingToken } from '@clerk/testing/playwright'

const TEST_EMAIL = process.env.E2E_CLERK_USER_EMAIL || ''

test.describe('Authenticated Pages', () => {
  test.skip(!TEST_EMAIL, 'E2E_CLERK_USER_EMAIL not set — skipping authenticated tests')

  test.beforeEach(async ({ page }) => {
    await setupClerkTestingToken({ page })
    // Navigate to app so Clerk JS loads
    await page.goto('/')
    // Sign in via Clerk testing API
    await clerk.signIn({ page, emailAddress: TEST_EMAIL })
  })

  test.describe('Dashboard', () => {
    test('dashboard page loads and shows heading', async ({ page }) => {
      await page.goto('/dashboard')
      await expect(page.getByRole('heading', { name: 'Dashboard' })).toBeVisible({ timeout: 15_000 })
    })

    test('dashboard shows stats card', async ({ page }) => {
      await page.goto('/dashboard')
      await expect(page.getByText('Your Stats')).toBeVisible({ timeout: 15_000 })
    })

    test('dashboard shows interview history section', async ({ page }) => {
      await page.goto('/dashboard')
      await expect(page.getByText('Interview History')).toBeVisible({ timeout: 15_000 })
    })

    test('dashboard has Start New Interview button', async ({ page }) => {
      await page.goto('/dashboard')
      await expect(page.getByRole('link', { name: /Start New Interview/i })).toBeVisible({
        timeout: 15_000,
      })
    })

    test('Start New Interview navigates to setup page', async ({ page }) => {
      await page.goto('/dashboard')
      await page.getByRole('link', { name: /Start New Interview/i }).click()
      await page.waitForURL(/\/interview\/setup/)
      expect(page.url()).toContain('/interview/setup')
    })

    test('dashboard has no console errors', async ({ page }) => {
      const errors: string[] = []
      page.on('console', (msg) => {
        if (msg.type() === 'error') errors.push(msg.text())
      })
      await page.goto('/dashboard')
      await page.waitForTimeout(3000)
      const realErrors = errors.filter(
        (e) =>
          !e.includes('clerk') &&
          !e.includes('Clerk') &&
          !e.includes('third-party') &&
          !e.includes('401') &&
          !e.includes('ERR_CONNECTION_REFUSED')
      )
      expect(realErrors).toHaveLength(0)
    })
  })

  test.describe('Interview Setup Page', () => {
    test('setup page loads with heading', async ({ page }) => {
      await page.goto('/interview/setup')
      await expect(
        page.getByRole('heading', { name: 'Configure Your Interview' })
      ).toBeVisible({ timeout: 15_000 })
    })

    test('setup page shows category cards', async ({ page }) => {
      await page.goto('/interview/setup')
      await expect(page.getByText('Interview Category')).toBeVisible({ timeout: 15_000 })
      await expect(page.getByText('Coding Interview')).toBeVisible()
      await expect(page.getByText('DSA Focus')).toBeVisible()
      await expect(page.getByText('Behavioral Interview')).toBeVisible()
      await expect(page.getByText('System Design')).toBeVisible()
    })

    test('setup page shows difficulty buttons', async ({ page }) => {
      await page.goto('/interview/setup')
      await expect(page.getByText('Difficulty')).toBeVisible({ timeout: 15_000 })
      await expect(page.getByRole('button', { name: 'Easy' })).toBeVisible()
      await expect(page.getByRole('button', { name: 'Medium' })).toBeVisible()
      await expect(page.getByRole('button', { name: 'Hard' })).toBeVisible()
    })

    test('setup page shows personality options', async ({ page }) => {
      await page.goto('/interview/setup')
      await expect(page.getByText('Interviewer Personality')).toBeVisible({ timeout: 15_000 })
      await expect(page.getByText('FAANG Senior')).toBeVisible()
      await expect(page.getByText('Friendly Mentor')).toBeVisible()
      await expect(page.getByText('Startup Engineer')).toBeVisible()
      await expect(page.getByText('Adaptive')).toBeVisible()
    })

    test('setup page shows duration buttons', async ({ page }) => {
      await page.goto('/interview/setup')
      await expect(page.getByRole('button', { name: '30 min' })).toBeVisible({ timeout: 15_000 })
      await expect(page.getByRole('button', { name: '45 min' })).toBeVisible()
      await expect(page.getByRole('button', { name: '60 min' })).toBeVisible()
    })

    test('setup page has target role and company inputs', async ({ page }) => {
      await page.goto('/interview/setup')
      await expect(page.getByLabel(/Target Role/i)).toBeVisible({ timeout: 15_000 })
      await expect(page.getByLabel(/Target Company/i)).toBeVisible()
    })

    test('Start Interview button is disabled until required fields selected', async ({ page }) => {
      await page.goto('/interview/setup')
      await page.waitForTimeout(2000)
      const startButton = page.getByRole('button', { name: 'Start Interview' })
      await expect(startButton).toBeDisabled()
    })

    test('selecting category and difficulty enables Start button', async ({ page }) => {
      await page.goto('/interview/setup')
      await page.waitForTimeout(2000)

      await page.getByText('Coding Interview').click()
      await page.getByRole('button', { name: 'Medium' }).click()

      const startButton = page.getByRole('button', { name: 'Start Interview' })
      await expect(startButton).toBeEnabled()
    })

    test('selecting CODING category shows language selector', async ({ page }) => {
      await page.goto('/interview/setup')
      await page.waitForTimeout(2000)

      await expect(page.getByText('Programming Language')).not.toBeVisible()
      await page.getByText('Coding Interview').click()
      await expect(page.getByText('Programming Language')).toBeVisible()
    })

    test('selecting BEHAVIORAL category does NOT show language selector', async ({ page }) => {
      await page.goto('/interview/setup')
      await page.waitForTimeout(2000)

      await page.getByText('Behavioral Interview').click()
      await expect(page.getByText('Programming Language')).not.toBeVisible()
    })

    test('Back to Dashboard link works', async ({ page }) => {
      await page.goto('/interview/setup')
      await page.waitForTimeout(2000)
      await page.getByRole('link', { name: /Back to Dashboard/i }).click()
      await page.waitForURL(/\/dashboard/)
      expect(page.url()).toContain('/dashboard')
    })

    test('setup page has no console errors', async ({ page }) => {
      const errors: string[] = []
      page.on('console', (msg) => {
        if (msg.type() === 'error') errors.push(msg.text())
      })
      await page.goto('/interview/setup')
      await page.waitForTimeout(3000)
      const realErrors = errors.filter(
        (e) =>
          !e.includes('clerk') &&
          !e.includes('Clerk') &&
          !e.includes('third-party') &&
          !e.includes('401') &&
          !e.includes('ERR_CONNECTION_REFUSED')
      )
      expect(realErrors).toHaveLength(0)
    })
  })

  test.describe('Interview Setup Interactions', () => {
    test('can fill out complete interview configuration', async ({ page }) => {
      await page.goto('/interview/setup')
      await page.waitForTimeout(2000)

      await page.getByText('Coding Interview').click()
      await page.getByRole('button', { name: 'Medium' }).click()
      await page.getByText('FAANG Senior').click()
      await page.getByLabel(/Target Role/i).fill('Senior Backend Engineer')
      await page.getByLabel(/Target Company/i).fill('Google')
      await page.getByRole('button', { name: '60 min' }).click()

      const startButton = page.getByRole('button', { name: 'Start Interview' })
      await expect(startButton).toBeEnabled()
    })

    test('duration selection updates correctly', async ({ page }) => {
      await page.goto('/interview/setup')
      await page.waitForTimeout(2000)

      await page.getByRole('button', { name: '30 min' }).click()
      await page.getByRole('button', { name: '60 min' }).click()

      await expect(page.getByRole('button', { name: '30 min' })).toBeVisible()
      await expect(page.getByRole('button', { name: '45 min' })).toBeVisible()
      await expect(page.getByRole('button', { name: '60 min' })).toBeVisible()
    })
  })

  test.describe('Responsive Design (Authenticated)', () => {
    test('dashboard renders on mobile viewport', async ({ page }) => {
      await page.setViewportSize({ width: 375, height: 667 })
      await page.goto('/dashboard')
      await expect(page.getByRole('heading', { name: 'Dashboard' })).toBeVisible({
        timeout: 15_000,
      })
    })

    test('setup page renders on mobile viewport', async ({ page }) => {
      await page.setViewportSize({ width: 375, height: 667 })
      await page.goto('/interview/setup')
      await expect(
        page.getByRole('heading', { name: 'Configure Your Interview' })
      ).toBeVisible({ timeout: 15_000 })
      await expect(page.getByText('Coding Interview')).toBeVisible()
    })
  })
})
