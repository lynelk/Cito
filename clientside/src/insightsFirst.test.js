import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const routers = fs.readFileSync(path.join(__dirname, 'Routers.tsx'), 'utf8');
const mainMenu = fs.readFileSync(path.join(__dirname, 'components/MainMenu.jsx'), 'utf8');
const insights = fs.readFileSync(path.join(__dirname, 'components/modules/ModuleInsights.jsx'), 'utf8');

describe('admin clean dashboard experience', () => {
  test('keeps canonical and legacy admin Dashboard entry points usable', () => {
    expect(routers).toContain('path="/bo/*" element={protectAdmin(<Layout />)}');
    expect(routers).toContain('path="/bo/admin/*" element={protectAdmin(<Layout />)}');
    expect(routers).toContain('path="/dashboard/*" element={<Navigate to="/bo/insights" replace />}');
  });

  test('makes Dashboard the first admin navigation destination', () => {
    expect(mainMenu).toMatch(/value: 'insights', text: 'Dashboard'/);
    expect(mainMenu.indexOf("value: 'insights'")).toBeLessThan(mainMenu.indexOf("value: 'money-operations'"));
  });

  test('keeps the overview focused on one trend, one status panel, recent activity and actions', () => {
    const renderedHeadings = [
      'title="Money movement"',
      'title="Needs attention"',
      'title="Recent activity"',
      'title="Quick actions"',
    ];
    let previous = -1;
    renderedHeadings.forEach((heading) => {
      const index = insights.indexOf(heading);
      expect(index).toBeGreaterThan(previous);
      previous = index;
    });
    expect(insights).toContain('const RECENT_ACTIVITY_LIMIT = 5;');
    expect(insights).toContain('<WorkspaceMetricGrid>');
    expect(insights).not.toContain('Cito Service Portfolio');
  });

  test('does not seed production-looking operational metrics in Dashboard', () => {
    expect(insights).not.toMatch(/3\.8\s*days|0\.7\s*days|98\.\d+%/);
    expect(insights).toContain("return 'No activity';");
    expect(insights).toContain('Cito has not substituted fallback values.');
  });
});
