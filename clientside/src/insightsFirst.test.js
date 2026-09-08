import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const routers = fs.readFileSync(path.join(__dirname, 'Routers.tsx'), 'utf8');
const mainMenu = fs.readFileSync(path.join(__dirname, 'components/MainMenu.jsx'), 'utf8');
const insights = fs.readFileSync(path.join(__dirname, 'components/modules/ModuleInsights.jsx'), 'utf8');

describe('admin insights-first experience', () => {
  test('routes legacy admin dashboard entry points to the canonical Insights path', () => {
    expect(routers).toContain('path="/bo/*" element={protectAdmin(<Layout />)}');
    expect(routers).toContain('path="/bo/admin/*" element={<Navigate to="/bo/insights" replace />}');
    expect(routers).toContain('path="/dashboard/*" element={<Navigate to="/bo/insights" replace />}');
  });

  test('makes Insights the first admin navigation destination', () => {
    expect(mainMenu).toMatch(/value: 'insights', text: 'Insights'/);
    expect(mainMenu.indexOf("value: 'insights'")).toBeLessThan(mainMenu.indexOf("value: 'merchants-accounts'"));
  });

  test('keeps the overview ordered around action, business, service portfolio, activity, and performance', () => {
    const headings = [
      'Needs Attention',
      'Today&apos;s Business',
      'Cito Service Portfolio',
      'Recent Activity',
      'Performance',
    ];
    let previous = -1;
    headings.forEach((heading) => {
      const index = insights.indexOf(heading);
      expect(index).toBeGreaterThan(previous);
      previous = index;
    });
  });

  test('does not seed production-looking operational metrics in Insights', () => {
    expect(insights).not.toMatch(/3\.8\s*days|0\.7\s*days|98\.\d+%/);
    expect(insights).toContain('Missing data is shown as no activity rather than being guessed.');
  });
});
