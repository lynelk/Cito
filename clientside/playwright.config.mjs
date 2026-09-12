import { defineConfig } from '@playwright/test';

const desktop = (browserName, width, height) => ({
  browserName,
  viewport: { width, height },
  deviceScaleFactor: 1,
});

const mobile = (browserName, width, height, deviceScaleFactor) => ({
  browserName,
  viewport: { width, height },
  deviceScaleFactor,
  isMobile: true,
  hasTouch: true,
});

export default defineConfig({
  testDir: './e2e',
  testMatch: '**/*.pw.mjs',
  outputDir: 'test-results/browser-matrix',
  timeout: 60_000,
  expect: { timeout: 12_000 },
  fullyParallel: true,
  forbidOnly: Boolean(process.env.CI),
  retries: process.env.CI ? 1 : 0,
  // Browser engines are memory-heavy. Two workers keep the matrix reliable on
  // standard hosted runners instead of turning layout checks into CPU races.
  workers: process.env.CI ? 2 : undefined,
  reporter: [
    ['list'],
    ['html', { outputFolder: 'playwright-report', open: 'never' }],
  ],
  use: {
    baseURL: 'http://127.0.0.1:4173',
    colorScheme: 'light',
    locale: 'en-GB',
    reducedMotion: 'reduce',
    screenshot: 'only-on-failure',
    trace: 'retain-on-failure',
    video: 'off',
  },
  webServer: {
    command: 'npm run preview -- --host 127.0.0.1 --port 4173',
    url: 'http://127.0.0.1:4173',
    reuseExistingServer: !process.env.CI,
    timeout: 120_000,
  },
  projects: [
    { name: 'compact-320', grep: /merchant payment credentials|admin can review|admin MTN workspace/, use: desktop('chromium', 320, 800) },
    { name: 'tablet-768', grep: /merchant payment credentials|admin can review|admin MTN workspace/, use: desktop('chromium', 768, 1024) },
    { name: 'chrome-edge-1366', use: desktop('chromium', 1366, 768) },
    { name: 'chrome-edge-1440', use: desktop('chromium', 1440, 900) },
    { name: 'chrome-edge-1920', use: desktop('chromium', 1920, 1080) },
    { name: 'firefox-desktop', use: desktop('firefox', 1440, 900) },
    { name: 'safari-webkit-desktop', use: desktop('webkit', 1440, 900) },
    { name: 'android-small-chrome', use: mobile('chromium', 360, 800, 3) },
    { name: 'android-chrome', use: mobile('chromium', 412, 915, 2.625) },
    { name: 'iphone-safari', use: mobile('webkit', 390, 844, 3) },
    { name: 'ipad-safari', use: mobile('webkit', 820, 1180, 2) },
  ],
});
