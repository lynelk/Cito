import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const clientRoot = path.resolve(__dirname, '..');
const dockerfile = fs.readFileSync(path.join(clientRoot, 'Dockerfile'), 'utf8');
const startScript = fs.readFileSync(path.join(clientRoot, 'start.sh'), 'utf8');
const nginxTemplate = fs.readFileSync(path.join(clientRoot, 'default.conf.template'), 'utf8');

// Keep the repository default aligned with the canonical Railway service name.
const canonicalBackend = 'cito-backend.railway.internal:8080';
const obsoleteBackend = 'cpay.railway.internal:8080';

describe('Railway frontend routing contract', () => {
  test('defaults both build and runtime configuration to the canonical backend service', () => {
    expect(dockerfile).toContain(`ENV BACKEND_UPSTREAM=${canonicalBackend}`);
    expect(startScript).toContain(`BACKEND_UPSTREAM:=${canonicalBackend}`);
  });

  test('does not retain the obsolete CPay private hostname', () => {
    expect(dockerfile).not.toContain(obsoleteBackend);
    expect(startScript).not.toContain(obsoleteBackend);
  });

  test('keeps liveness, readiness, and SPA routing as separate contracts', () => {
    expect(nginxTemplate).toContain('location = /healthz');
    expect(nginxTemplate).toContain('location = /readyz');
    expect(nginxTemplate).toContain('proxy_pass http://cito-backend/status/health;');
    expect(nginxTemplate).toContain('try_files $uri $uri/ /index.html;');
  });
});
