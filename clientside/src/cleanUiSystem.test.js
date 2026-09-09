import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const read = (relativePath) => fs.readFileSync(path.join(__dirname, relativePath), 'utf8');

describe('Cito clean product UI system', () => {
  test('loads the product-system layer after the approved brand baseline', () => {
    const entry = read('index.tsx');
    const brandIndex = entry.indexOf("import './styles/cito-brand.css';");
    const productIndex = entry.indexOf("import './styles/cito-product-system.css';");
    expect(brandIndex).toBeGreaterThan(-1);
    expect(productIndex).toBeGreaterThan(brandIndex);
  });

  test('codifies the shared density, card, control and responsive rules', () => {
    const css = read('styles/cito-product-system.css');
    expect(css).toContain('--cito-control-height: 44px;');
    expect(css).toContain('--cito-card-radius: 12px;');
    expect(css).toContain('--cito-card-shadow: 0 1px 2px');
    expect(css).toContain('grid-template-columns: repeat(4, minmax(0, 1fr));');
    expect(css).toContain('@media (max-width: 1024px)');
    expect(css).toContain('@media (max-width: 768px)');
    expect(css).toContain('@media (prefers-reduced-motion: reduce)');
  });

  test('uses the same page hierarchy in admin and merchant shells', () => {
    const admin = read('components/Layout.jsx');
    const merchant = read('components/LayoutMerchant.jsx');
    [admin, merchant].forEach((layout) => {
      expect(layout).toContain('className="cito-global-search-trigger"');
      expect(layout).toContain('className="cito-page-shell"');
      expect(layout).toContain('className="cito-page-heading"');
      expect(layout).toContain('<EnvironmentSwitcher');
      expect(layout).toContain('<ThemeToggle />');
    });
  });

  test('uses canonical /fo routes for merchant workspace navigation', () => {
    const merchant = read('components/LayoutMerchant.jsx');
    expect(merchant).toContain("home: '/fo/dashboard'");
    expect(merchant).toContain("payments: '/fo/payments'");
    expect(merchant).toContain("services: '/fo/services'");
    expect(merchant).toContain("const segment = pathname.replace(/^\\/(?:fo|bo\\/partner)\\/?/, '').split('/')[0];");
  });

  test('groups admin navigation by user intent rather than implementation detail', () => {
    const menu = read('components/MainMenu.jsx');
    ['Overview', 'Services', 'Business', 'Operations', 'Platform', 'Account'].forEach((group) => {
      expect(menu).toContain(`title: '${group}'`);
    });
    ['Payments', 'Communications', 'Vending & Utilities', 'KYC & Identity', 'Billing & BaaS'].forEach((service) => {
      expect(menu).toContain(`text: '${service}'`);
    });
  });

  test('applies reusable workspace primitives to the reference service screens', () => {
    const payments = read('components/modules/ModuleTransactions.jsx');
    const communications = read('components/modules/ModuleCommunicationRouting.tsx');
    const vending = read('components/modules/ModuleVending.tsx');
    [payments, communications, vending].forEach((module) => {
      expect(module).toContain('WorkspaceMetricGrid');
      expect(module).toContain('WorkspaceGrid');
      expect(module).toContain('WorkspacePanel');
    });
    expect(communications).toContain('WorkspaceDisclosure');
    expect(vending).toContain('WorkspaceDisclosure');
  });
});
