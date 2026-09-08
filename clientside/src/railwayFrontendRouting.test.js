import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const clientRoot = path.resolve(__dirname, '..');
const dockerfile = fs.readFileSync(path.join(clientRoot, 'Dockerfile'), 'utf8');
const startScript = fs.readFileSync(path.join(clientRoot, 'start.sh'), 'utf8');
const nginxTemplate = fs.readFileSync(path.join(clientRoot, 'default.conf.template'), 'utf8');

// Keep repository defaults aligned with the canonical Cito Backend private endpoint.
const canonicalBackend = 'cito-backend.railway.internal:8080';
const obsoleteBackend = 'cpay.railway.internal:8080';

describe('Railway frontend routing contract', () => {
  test('defaults both build and runtime configuration to the canonical backend service', () => {
    expect(dockerfile).toContain(`ENV BACKEND_UPSTREAM=${canonicalBackend}`);
    expect(startScript).toContain(`BACKEND_UPSTREAM:=${canonicalBackend}`);
  });

  test('migrates a stale CPay upstream override instead of accepting it', () => {
    expect(dockerfile).not.toContain(`ENV BACKEND_UPSTREAM=${obsoleteBackend}`);
    expect(startScript).not.toContain(`BACKEND_UPSTREAM:=${obsoleteBackend}`);
    expect(startScript).toContain('cpay.railway.internal|cpay.railway.internal:*');
    expect(startScript).toContain(`BACKEND_UPSTREAM="${canonicalBackend}"`);
  });

  test('keeps liveness, readiness, and SPA routing as separate contracts', () => {
    expect(nginxTemplate).toContain('location = /healthz');
    expect(nginxTemplate).toContain('location = /readyz');
    expect(nginxTemplate).toContain('proxy_pass http://cito-backend/status/health;');
    expect(nginxTemplate).toContain('try_files $uri $uri/ /index.html;');
  });
});
