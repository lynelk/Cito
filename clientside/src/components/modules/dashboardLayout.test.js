import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

const read = relativePath => fs.readFileSync(path.join(__dirname, '..', '..', relativePath), 'utf8');

describe('admin dashboard card layout', () => {
  test('uses dashboard cards instead of fixed rc-easyui chart panels', () => {
    const dashboard = read('components/modules/ModuleDashboard.jsx');

    expect(dashboard).toContain('cpay-dashboard');
    expect(dashboard).toContain('cpay-dashboard-card');
    expect(dashboard).toContain('Customize cards');
    expect(dashboard).not.toContain('Add Snapshot');
    expect(dashboard).not.toMatch(/<Panel\b/);
    expect(dashboard).not.toContain('dashboardChartPanel');
  });

  test('defines the compact operational surfaces and optional snapshot library', () => {
    const dashboard = read('components/modules/ModuleDashboard.jsx');

    expect(dashboard).toContain('Processed Value vs Failed Amount Held');
    expect(dashboard).toContain('Float Runway by Channel');
    expect(dashboard).toContain('Action Center');
    expect(dashboard).toContain('Failure Analysis');
    expect(dashboard).toContain('Channel Health');
    expect(dashboard).toContain('Quick Actions');
    expect(dashboard).toContain('availableSnapshotCards');
  });

  test('keeps custom snapshot cards closed by default until users add them', () => {
    const dashboard = read('components/modules/ModuleDashboard.jsx');

    expect(dashboard).toContain('export const defaultSnapshotCards = []');
    expect(dashboard).toContain('cpay-admin-dashboard-snapshots-v2');
    expect(dashboard).toContain('visibleSnapshotCards.length > 0');
    expect(dashboard).toContain('visibleSnapshotCards.map(cardId => renderSnapshotCard(cardId))');
  });

  test('uses an adaptive dashboard canvas with scroll-safe cards', () => {
    const css = read('index.css');

    expect(css).toMatch(/\.cpay-main-dashboard\s*\{[^}]*min-height:\s*100vh;[^}]*overflow:\s*visible;/s);
    expect(css).toMatch(/\.cpay-dashboard\s*\{[^}]*grid-template-rows:\s*auto auto;[^}]*overflow:\s*visible;/s);
    expect(css).toMatch(/\.cpay-dashboard-grid\s*\{[^}]*grid-template-columns:\s*repeat\(12, minmax\(0, 1fr\)\);[^}]*grid-auto-rows:\s*minmax\(220px, auto\);[^}]*overflow:\s*visible;/s);
    expect(css).toMatch(/\.cpay-dashboard-panel-chart\s*\{[^}]*grid-column:\s*span 5;[^}]*min-height:\s*300px;/s);
    expect(css).toMatch(/\.cpay-dashboard-panel-runway\s*\{[^}]*grid-column:\s*span 4;[^}]*min-height:\s*300px;/s);
    expect(css).toMatch(/\.cpay-dashboard-panel-actions\s*\{[^}]*grid-column:\s*span 3;[^}]*min-height:\s*300px;/s);
    expect(css).toContain('.cpay-runway-table');
    expect(css).toContain('.cpay-failure-summary');
    expect(css).toMatch(/@media \(max-width:\s*760px\)[\s\S]*\.cpay-dashboard-grid\s*\{[\s\S]*grid-template-columns:\s*1fr;/);
    expect(css).toMatch(/\.cpay-dashboard-chart-shell\s*\{[\s\S]*overflow:\s*hidden;/);
  });

  test('snapshot picker can toggle cards on and off', () => {
    const dashboard = read('components/modules/ModuleDashboard.jsx');

    expect(dashboard).toContain('toggleSnapshotCard');
    expect(dashboard).toContain('cpay-dashboard-picker-option-active');
    expect(dashboard).toContain('aria-pressed={isActive}');
  });
});

describe('shared chart cleanup', () => {
  test('merchant chart reuses the shared LinearChart implementation', () => {
    const merchantChart = read('components/modules/merchant/LinearChart.jsx');

    expect(merchantChart).toContain("from '../LinearChart'");
    expect(merchantChart).not.toContain("chart.js/auto");
  });
});

describe('merchant dashboard card layout', () => {
  test('merchant dashboard uses cards instead of fixed rc-easyui chart panels', () => {
    const dashboard = read('components/modules/merchant/MerchantModuleDashboard.jsx');

    expect(dashboard).toContain('cpay-dashboard');
    expect(dashboard).toContain('cpay-dashboard-card');
    expect(dashboard).toContain('Customize cards');
    expect(dashboard).not.toContain('Add Snapshot');
    expect(dashboard).toContain('toggleSnapshotCard');
    expect(dashboard).not.toMatch(/<Panel\b/);
    expect(dashboard).not.toContain('dashboardChartPanel');
  });

  test('merchant custom snapshots are also closed by default', () => {
    const dashboard = read('components/modules/merchant/MerchantModuleDashboard.jsx');

    expect(dashboard).toContain('const merchantDefaultSnapshotCards = []');
    expect(dashboard).toContain('cpay-merchant-dashboard-snapshots-v2');
  });
});

describe('application shell layout', () => {
  test('admin and merchant layouts share the same clean product shell and page hierarchy', () => {
    const adminLayout = read('components/Layout.jsx');
    const merchantLayout = read('components/LayoutMerchant.jsx');
    const productCss = read('styles/cito-product-system.css');

    [adminLayout, merchantLayout].forEach((layout) => {
      expect(layout).toContain('cito-global-search-trigger');
      expect(layout).toContain('cito-page-shell');
      expect(layout).toContain('cito-page-heading');
      expect(layout).not.toContain('<PageHeader');
    });
    expect(productCss).toMatch(/\.ios-sidebar\s*\{[^}]*width:\s*244px;/s);
    expect(productCss).toMatch(/\.ios-topbar\s*\{[^}]*min-height:\s*64px;/s);
    expect(productCss).toContain('--cito-control-height: 44px;');
    expect(productCss).toContain('--cito-card-radius: 12px;');
  });
});
