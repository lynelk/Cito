import { test, expect } from '@playwright/test';

const json = (body) => ({
  status: 200,
  contentType: 'application/json',
  body: JSON.stringify(body),
});

async function assertNoDocumentOverflow(page) {
  const result = await page.evaluate(() => {
    const root = document.documentElement;
    const body = document.body;
    return {
      viewport: window.innerWidth,
      root: root.scrollWidth,
      body: body.scrollWidth,
      scrollX: window.scrollX,
    };
  });

  if (Math.max(result.root, result.body) > result.viewport + 1) {
    result.offenders = await page.evaluate(() => {
      const viewport = window.innerWidth;
      const selector = [
        '.ios-shell', '.ios-main', '.ios-page', '.ios-page > *',
        '.ios-card', '.cito-service-hub', '.cito-compliance-panel',
        '.cito-platform', '.cito-platform__hero', '.cito-platform__metrics',
        '.cito-platform__tabs', '.cito-platform__section', '.ios-sidebar',
        '.cito-provider-console', '.cito-provider-console form',
        '.cito-provider-console label', '.cito-provider-console input',
        '.cito-provider-console select', '.cito-provider-console textarea',
        '.cito-provider-console .ios-table-card', '.cito-provider-console .ios-table-card__body',
        '.cito-provider-console .ios-table-card__row', '.cito-provider-console .ios-table-card__value',
      ].join(',');
      return Array.from(document.querySelectorAll(selector))
        .map((element) => {
          const rect = element.getBoundingClientRect();
          return {
            element: `${element.tagName.toLowerCase()}${element.id ? `#${element.id}` : ''}${element.classList.length ? `.${Array.from(element.classList).join('.')}` : ''}`,
            left: Math.floor(rect.left),
            right: Math.ceil(rect.right),
            width: Math.ceil(rect.width),
          };
        })
        .filter(({ right, width }) => width > 0 && right > viewport + 1)
        .slice(0, 12);
    });
  }

  expect(Math.max(result.root, result.body), JSON.stringify(result)).toBeLessThanOrEqual(result.viewport + 1);
}

async function assertTopbarWithinViewport(page) {
  const topbar = page.getByRole('banner').first();
  await expect(topbar).toBeVisible();
  const box = await topbar.evaluate((element) => {
    const rect = element.getBoundingClientRect();
    return { left: rect.left, right: rect.right, width: rect.width };
  });
  const viewport = page.viewportSize();
  expect(viewport).not.toBeNull();
  expect(box.left).toBeGreaterThanOrEqual(-1);
  expect(box.right).toBeLessThanOrEqual(viewport.width + 1);
}

async function attachEvidence(page, testInfo, name) {
  const path = testInfo.outputPath(`${name}-${testInfo.project.name}.png`);
  // Viewport evidence is deterministic and avoids WebKit's 32,767-pixel
  // full-page screenshot limit on long public pages.
  await page.screenshot({ path, fullPage: false, animations: 'disabled' });
  await testInfo.attach(`${name}-${testInfo.project.name}`, { path, contentType: 'image/png' });
}

async function openMobileNavigation(page) {
  const viewport = page.viewportSize();
  if (!viewport || viewport.width > 900) return;
  const trigger = page.getByRole('button', { name: 'Navigation' });
  await expect(trigger).toBeVisible();
  await trigger.evaluate((element) => element.click());
  const sidebar = page.locator('.ios-sidebar');
  await expect(sidebar).toBeVisible();
  await expect.poll(async () => sidebar.evaluate((element) => Math.round(element.getBoundingClientRect().x))).toBeGreaterThanOrEqual(-1);
}

async function primeAdmin(page) {
  await page.addInitScript(() => {
    localStorage.setItem('user', JSON.stringify({
      name: 'Platform Administrator',
      email: 'admin@example.com',
      privileges: [
        { privilege: 'ACCESS_TRANSACTION_LOG' },
        { privilege: 'ACCESS_COMPLIANCE' },
      ],
    }));
  });

  await page.route('**/auth/csrf**', (route) => route.fulfill(json({
    headerName: 'X-XSRF-TOKEN',
    token: 'browser-matrix-token',
  })));
  await page.route('**/auth/isLoggedIn**', (route) => route.fulfill(json({ code: '000', message: 'true' })));
  await page.route('**/api/v2/admin/compliance/summary**', (route) => route.fulfill(json({
    openComplianceCases: 2,
    highSeverityComplianceCases: 1,
    pendingComplianceProfiles: 3,
    openControlEvents: 1,
    highSeverityControlEvents: 0,
    parkedCallbacks: 0,
  })));
  await page.route('**/api/v2/admin/compliance/cases**', (route) => route.fulfill(json([
    {
      id: 1,
      case_reference: 'CASE-1001',
      case_type: 'IDENTITY_REVIEW',
      severity: 'HIGH',
      case_status: 'OPEN',
      entity_type: 'MERCHANT',
      entity_id: '17',
      source_reference: 'NIN-CHECK-882',
      created_at: '2026-09-03T08:00:00Z',
    },
    {
      id: 2,
      case_reference: 'CASE-1002',
      case_type: 'CRB_SCORE_REVIEW',
      severity: 'MEDIUM',
      case_status: 'IN_REVIEW',
      entity_type: 'CUSTOMER',
      entity_id: 'C-202',
      source_reference: 'CRB-REP-220',
      created_at: '2026-09-03T09:30:00Z',
    },
  ])));
  await page.route('**/api/v2/admin/compliance/profiles**', (route) => route.fulfill(json([
    {
      id: 1,
      entity_id: '17',
      entity_type: 'MERCHANT',
      profile_type: 'KYB',
      tier: 'ENHANCED',
      status: 'IN_REVIEW',
      risk_rating: 'MEDIUM',
      verified_by: null,
    },
  ])));
  await page.route('**/api/v2/admin/cito/service-catalog**', (route) => route.fulfill(json([
    { serviceCode: 'CPAY', serviceName: 'Cito Payments', description: 'Collections and payouts' },
    { serviceCode: 'COMMUNICATIONS', serviceName: 'Communications', description: 'SMS WhatsApp USSD messaging' },
    { serviceCode: 'IDENTITY_SCORING', serviceName: 'Identity, Credit & Scoring', description: 'KYC CRB NIN score' },
    { serviceCode: 'VENDING', serviceName: 'Vending & VAS', description: 'Airtime data utilities' },
    { serviceCode: 'BILLING', serviceName: 'Billing & Monetisation', description: 'BaaS metering rating invoices' },
    { serviceCode: 'INTEGRATIONS', serviceName: 'Integrations & Automation', description: 'API webhook connectors' },
  ])));
}

async function primeMerchant(page) {
  await page.addInitScript(() => {
    localStorage.setItem('merchantUser', JSON.stringify({
      merchant_id: 17,
      name: 'Acme Merchant',
      email: 'merchant@example.com',
    }));
  });

  await page.route('**/auth/csrf**', (route) => route.fulfill(json({
    headerName: 'X-XSRF-TOKEN',
    token: 'browser-matrix-token',
  })));
  await page.route('**/auth/isMerchantUserLoggedIn**', (route) => route.fulfill(json({ code: '000', message: 'true' })));
  await page.route('**/api/v2/merchants/17/overview**', (route) => route.fulfill(json({
    entitlements: [
      { service_code: 'CPAY', status: 'ACTIVE' },
      { service_code: 'COMMUNICATIONS', status: 'ACTIVE' },
      { service_code: 'IDENTITY_SCORING', status: 'ACTIVE' },
      { service_code: 'VENDING', status: 'REQUESTED' },
      { service_code: 'BILLING', status: 'ACTIVE' },
      { service_code: 'INTEGRATIONS', status: 'ACTIVE' },
    ],
  })));
  await page.route('**/api/v2/merchant-self-service/cito/overview**', (route) => route.fulfill(json({
    features: [
      { serviceCode: 'CPAY', serviceName: 'Cito Payments', description: 'Collections and payouts', sandboxStatus: 'ACTIVE', productionStatus: 'ACTIVE' },
      { serviceCode: 'COMMUNICATIONS', serviceName: 'Communications', description: 'SMS WhatsApp and USSD', sandboxStatus: 'ACTIVE', productionStatus: 'REQUESTED' },
      { serviceCode: 'IDENTITY_SCORING', serviceName: 'Identity, Credit & Scoring', description: 'Identity and credit intelligence', sandboxStatus: 'ACTIVE', productionStatus: 'REQUESTED' },
    ],
    routing: { decisions: 0 },
    refunds: { openDisputes: 0 },
    marketplace: { pendingRecoveryEvents: 0 },
    recurring: { activeSubscriptions: 0 },
    developer: { activeProjects: 1 },
    integrations: { activeInstallations: 1 },
  })));
}

test('public service portfolio is responsive across browser engines', async ({ page }, testInfo) => {
  await page.goto('/', { waitUntil: 'domcontentloaded' });

  await expect(page.getByRole('heading', { level: 1, name: /your business.*better connected/i })).toBeVisible();
  await expect(page.locator('#service-communications').getByRole('heading', { name: 'Keep the conversation going.', exact: true })).toBeVisible();
  await expect(page.locator('#service-identity').getByRole('heading', { name: 'Make better-informed decisions.', exact: true })).toBeVisible();
  await expect(page.locator('#service-billing').getByRole('heading', { name: 'Turn usage into revenue.', exact: true })).toBeVisible();
  await assertNoDocumentOverflow(page);

  const mobileMenu = page.locator('.cito-mobile-menu');
  if (await mobileMenu.isVisible()) {
    await mobileMenu.locator('summary').evaluate((element) => element.click());
    await expect(page.locator('.cito-mobile-panel')).toBeVisible();
  } else {
    await expect(page.locator('.cito-nav')).toBeVisible();
  }

  await attachEvidence(page, testInfo, 'public-home');
});

test('admin risk, identity and scoring workspace remains usable at every viewport', async ({ page }, testInfo) => {
  await primeAdmin(page);
  await page.goto('/bo/admin/risk-compliance', { waitUntil: 'domcontentloaded' });

  await expect(page.getByRole('heading', { name: /protect the platform without hiding the work/i })).toBeVisible();
  await expect(page.getByRole('heading', { name: /identity, credit & scoring services/i })).toBeVisible();
  await page.getByRole('button', { name: 'Open review queue' }).evaluate((element) => element.click());
  await expect(page.getByText('CASE-1001')).toBeVisible();
  await expect(page.getByText(/internal application error/i)).toHaveCount(0);
  await assertTopbarWithinViewport(page);
  await assertNoDocumentOverflow(page);
  await openMobileNavigation(page);

  await attachEvidence(page, testInfo, 'admin-risk-compliance');
});

test('admin services workspace exposes all Cito service families', async ({ page }, testInfo) => {
  await primeAdmin(page);
  await page.goto('/bo/admin/platform', { waitUntil: 'domcontentloaded' });

  await expect(page.getByRole('heading', { level: 2, name: 'Services & Products' })).toBeVisible();
  for (const service of ['Payments', 'Communications', 'Identity, Credit & Scoring', 'Vending & Value-Added Services', 'Billing & Monetisation', 'Integrations & Automation']) {
    await expect(page.getByRole('heading', { name: service, exact: true })).toBeVisible();
  }
  await assertTopbarWithinViewport(page);
  await assertNoDocumentOverflow(page);

  await attachEvidence(page, testInfo, 'admin-services');
});

test('merchant service portfolio is responsive and entitlement-aware', async ({ page }, testInfo) => {
  await primeMerchant(page);
  await page.goto('/fo/services', { waitUntil: 'domcontentloaded' });

  await expect(page.getByRole('heading', { name: /use the services your business needs/i })).toBeVisible();
  await expect(page.locator('#service-communications').getByRole('heading', { name: 'Keep the conversation going.', exact: true })).toBeVisible();
  await expect(page.locator('#service-identity').getByRole('heading', { name: 'Make better-informed decisions.', exact: true })).toBeVisible();
  // Entitlement allows workspace access; it does not prove provider readiness or activation.
  const cards = page.locator('.cito-service-card');
  const payments = cards.filter({ has: page.getByRole('heading', { name: 'Payments', exact: true }) });
  const vending = cards.filter({ has: page.getByRole('heading', { name: 'Vending & Value-Added Services', exact: true }) });
  await expect(payments.getByText('Access granted · readiness separate', { exact: true })).toBeVisible();
  await expect(vending.getByText('Available by entitlement', { exact: true })).toBeVisible();
  await expect(page.getByText(/enabled for your account/i)).toHaveCount(0);
  await expect(page.getByText(/activated through entitlements/i)).toHaveCount(0);
  await expect(page.getByText(/checking access/i)).toHaveCount(0);
  await assertTopbarWithinViewport(page);
  await assertNoDocumentOverflow(page);
  await openMobileNavigation(page);

  await attachEvidence(page, testInfo, 'merchant-services');
});
test('merchant payment credentials preserve stored secrets at narrow and wide widths', async ({ page }, testInfo) => {
  await primeMerchant(page);
  const credentials = { baseUrl: 'https://openapiuat.airtel.africa', clientId: '****', clientSecret: '****', country: 'UG', currency: 'UGX', publicKey: 'PUBLIC KEY EXAMPLE' };
  await page.route('**/api/v2/merchant-self-service/environment', route => route.fulfill(json({ environment: 'SANDBOX' })));
  await page.route('**/api/v2/merchant-self-service/channels', route => route.fulfill(json([{ channelCode: 'airtel_open_api', displayName: 'Airtel OpenAPI', countryCode: 'UG', currencyCode: 'UGX', environments: { SANDBOX: { status: 'CONFIGURED', credentials, revision: 7 } } }])));
  await page.goto('/fo/channels', { waitUntil: 'domcontentloaded' });
  const secret = page.getByLabel('Client secret (stored; leave blank to keep)');
  await expect(secret).toBeVisible();
  await expect(secret).toHaveValue('');
  await expect(page.getByLabel('Airtel country code')).toHaveValue('UG');
  await expect(page.getByLabel('Airtel RSA public key')).toBeVisible();
  await assertNoDocumentOverflow(page);
  await attachEvidence(page, testInfo, 'merchant-payment-credentials');
});

test('admin can review a credential revision with a reason using the keyboard', async ({ page }, testInfo) => {
  await primeAdmin(page);
  await page.route('**/api/v2/admin/provider-treasury/**', route => route.fulfill(json([])));
  await page.route('**/api/v2/admin/shared-provider/**', route => route.fulfill(json([])));
  const review = { id: 1, merchantNumber: 'QA-MERCHANT', channelCode: 'mtn_momo', environment: 'PRODUCTION', revision: 7, status: 'SUBMITTED_FOR_APPROVAL', lastTestStatus: 'CONNECTIVITY_VERIFIED', requestedBy: 'maker@example.invalid' };
  await page.route('**/api/v2/admin/shared-provider/merchant-credentials', route => route.fulfill(json([review])));
  let decision;
  await page.route('**/api/v2/admin/shared-provider/merchant-credentials/1/decision', async route => { decision = route.request().postDataJSON(); review.status = decision.decision; await route.fulfill(json(review)); });
  await page.goto('/bo/provider-treasury', { waitUntil: 'domcontentloaded' });
  await expect(page.getByRole('heading', { name: 'Merchant credential reviews' })).toBeVisible();
  const approve = page.getByRole('button', { name: 'Approve', exact: true });
  await expect(approve).toBeDisabled();
  const reason = page.getByLabel('Decision reason');
  await reason.fill('Independent review of the current connection evidence');
  await approve.focus();
  await page.keyboard.press('Enter');
  await expect(page.getByText('Credential decision recorded.')).toBeVisible();
  expect(decision).toEqual({ revision: 7, decision: 'ACTIVE', reason: 'Independent review of the current connection evidence' });
  await assertNoDocumentOverflow(page);
  await attachEvidence(page, testInfo, 'admin-credential-review');
  if (testInfo.project.name === 'chrome-edge-1440') {
    await page.evaluate(() => { document.documentElement.style.zoom = '200%'; });
    await assertNoDocumentOverflow(page);
    await attachEvidence(page, testInfo, 'admin-credential-review-200-percent');
  }
});
