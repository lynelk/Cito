import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const nginx = fs.readFileSync(path.join(__dirname, '../default.conf.template'), 'utf8');
const dockerfile = fs.readFileSync(path.join(__dirname, '../Dockerfile'), 'utf8');
const releaseWorkflow = fs.readFileSync(
  path.join(__dirname, '../../.github/workflows/cito-correct-everything-20260903.yml'),
  'utf8',
);

describe('frontend nginx route ownership', () => {
  test('keeps backend health probes while reserving /status for the public SPA', () => {
    expect(nginx).toMatch(/location = \/status\/health\s*\{[\s\S]*proxy_pass http:\/\/cito-backend\/status\/health;/);
    expect(nginx).not.toMatch(/location ~ \^\/\([^)]*\bstatus\b[^)]*\)/);
    expect(nginx).toMatch(/location \/\s*\{\s*try_files \$uri \$uri\/ \/index\.html;/);
  });

  test('attests the frontend build separately from proxied backend readiness', () => {
    expect(dockerfile).toMatch(/ARG RAILWAY_GIT_COMMIT_SHA=/);
    expect(dockerfile).toMatch(/> \/app\/build\/releasez/);
    expect(nginx).toMatch(/location = \/releasez\s*\{[\s\S]*try_files \$uri =404;/);
    expect(releaseWorkflow).toMatch(/def valid_release\(/);
    expect(releaseWorkflow).toContain("'/healthz', '/releasez', '/readyz'");
  });
});
