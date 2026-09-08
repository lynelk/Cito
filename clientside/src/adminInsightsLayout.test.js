import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const entrypoint = fs.readFileSync(path.join(__dirname, 'index.tsx'), 'utf8');
const insightsCss = fs.readFileSync(path.join(__dirname, 'styles/admin-insights.css'), 'utf8');

describe('admin Insights layout', () => {
  test('loads the Insights-specific layout corrections', () => {
    expect(entrypoint).toContain("import './styles/admin-insights.css';");
  });

  test('uses responsive metric cards instead of one-column-wide 12-track slivers', () => {
    expect(insightsCss).toContain("[data-testid='admin-insights'] .cpay-dashboard-snapshot-grid");
    expect(insightsCss).toContain('repeat(auto-fit, minmax(190px, 1fr))');
    expect(insightsCss).toContain('grid-column: auto');
  });

  test('keeps chart empty states in normal document flow', () => {
    expect(insightsCss).toContain("[data-testid='admin-insights'] .cpay-dashboard-panel-chart .cpay-dashboard-empty");
    expect(insightsCss).toContain('position: static');
    expect(insightsCss).toContain('min-height: 148px');
  });

  test('presents the two performance panels as a stable desktop pair', () => {
    expect(insightsCss).toContain('grid-template-columns: repeat(2, minmax(0, 1fr))');
    expect(insightsCss).toContain('@media (max-width: 980px)');
  });
});
