import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const read = relativePath => fs.readFileSync(path.join(__dirname, relativePath), 'utf8');

describe('Cito brand toolkit v1.2 integration', () => {
  test('loads the approved Cito palette and type roles', () => {
    const css = read('styles/cito-brand.css');
    expect(css).toContain('--cito-primary: #0066FF;');
    expect(css).toContain('--cito-deep-blue: #0047B3;');
    expect(css).toContain('--cito-sky: #2EA3FF;');
    expect(css).toContain('--cito-ice: #E6F2FF;');
    expect(css).toContain('--cito-navy: #0B2545;');
    expect(css).toContain('--cito-slate: #94A3B8;');
    expect(css).toContain('"Space Grotesk", Inter');
  });

  test('supports an explicit monochrome brand mode without replacing status semantics', () => {
    const css = read('styles/cito-brand.css');
    const toggle = read('ui/ThemeToggle.tsx');
    const controller = read('shared/brandMode.ts');
    expect(css).toContain("html[data-brand-mode='mono']");
    expect(css).toContain("content: url('../media/images/cito-mark-mono.svg')");
    expect(toggle).toContain('Switch to monochrome theme');
    expect(toggle).toContain('Switch to Cito brand theme');
    expect(controller).toContain("export type BrandMode = 'cito' | 'mono'");
  });

  test('applies the Cito identity to authentication and browser surfaces', () => {
    const auth = read('ui/AuthLayout.tsx');
    const locale = read('components/locale.js');
    const html = read('../index.html');
    expect(auth).toContain("import Logo from '../media/images/cito-mark.svg';");
    expect(auth).toContain('alt="Cito"');
    expect(locale).toContain('portal_title: "Cito: Admin Portal"');
    expect(html).toContain('href="/favicon.svg"');
    expect(html).toContain('content="#0066FF"');
  });

  test('initialises the brand mode before the application renders', () => {
    const entry = read('index.tsx');
    expect(entry).toContain("import './styles/cito-brand.css';");
    expect(entry).toContain("import { initBrandMode } from './shared/brandMode';");
    expect(entry.indexOf('initBrandMode();')).toBeLessThan(entry.indexOf('createRoot(container).render'));
  });
});
