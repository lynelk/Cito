/** Export the public presentation from its real React source, without live forms or private portals. */
import { build } from "vite";
import react from "@vitejs/plugin-react";
import fs from "node:fs/promises";
import path from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const output = process.argv[2];
if (!output || !path.isAbsolute(output))
  throw new Error("Supply an absolute, empty output directory.");
const existing = await fs.readdir(output).catch(() => []);
if (existing.length)
  throw new Error("Output must be empty; retain previous exports separately.");
const scratch = path.join(root, ".sites-runtime/public-render");
await fs.mkdir(scratch, { recursive: true });
await fs.writeFile(
  path.join(scratch, "entry.tsx"),
  `
import React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { StaticRouter } from 'react-router-dom';
import CitoLandingPage from '../../src/components/CitoLandingPage';
import { PublicProductPage } from '../../src/components/PublicExperiencePages';
export const pages = ['payments', 'payouts', 'billing', 'operations', 'developer-platform', 'about', 'security'];
export function render(page) { return renderToStaticMarkup(<StaticRouter location={page ? '/' + page : '/'}>{page ? <PublicProductPage page={page} /> : <CitoLandingPage />}</StaticRouter>); }
`,
);
await build({
  root,
  configFile: false,
  plugins: [react()],
  logLevel: "warn",
  build: {
    ssr: path.join(scratch, "entry.tsx"),
    outDir: path.join(scratch, "bundle"),
    rollupOptions: { output: { entryFileNames: "render.mjs" } },
  },
  define: { "process.env.REACT_APP_API_BASE": '""' },
});
const { render, pages } = await import(
  pathToFileURL(path.join(scratch, "bundle/render.mjs"))
);
await fs.mkdir(output, { recursive: true });
let css =
  (await fs.readFile(path.join(root, "src/styles/cito-brand.css"), "utf8")) +
  "\n" +
  (await fs.readFile(path.join(root, "src/styles/cito-landing.css"), "utf8"));
for (const name of ["cito-mark.svg", "cito-mark-mono.svg"]) {
  const contents = await fs.readFile(
    path.join(root, "src/media/images", name),
    "utf8",
  );
  css = css.replaceAll(
    `../media/images/${name}`,
    "data:image/svg+xml," + encodeURIComponent(contents),
  );
}
await fs.writeFile(
  path.join(output, "site.css"),
  "body{margin:0}html{scroll-behavior:auto}\n" + css,
);
await fs.copyFile(
  path.join(root, "public/favicon.svg"),
  path.join(output, "favicon.svg"),
);
const js = `
const menu = document.querySelector('.cito-mobile-menu');
menu?.addEventListener('keydown', event => { if (event.key === 'Escape') { menu.open = false; menu.querySelector('summary').focus(); } });
menu?.querySelectorAll('a').forEach(link => link.addEventListener('click', () => { menu.open = false; }));
const input = document.querySelector('.cito-public-api-topics input');
if (input) {
  const list = input.closest('.cito-public-api-topics').querySelector('ul');
  const empty = document.createElement('p'); empty.textContent = 'No matching topics. Open the merchant developer reference for endpoint details.'; empty.hidden = true; empty.setAttribute('role', 'status'); list.after(empty);
  input.addEventListener('input', () => { let count = 0; list.querySelectorAll('li').forEach(item => { item.hidden = !item.textContent.toLowerCase().includes(input.value.toLowerCase()); if (!item.hidden) count++; }); empty.hidden = count > 0; });
}
`;
await fs.writeFile(path.join(output, "site.js"), js);
const origin = "https://cito.coresynergi.es";
for (const page of ["", ...pages]) {
  let body = render(page);
  // Operational journeys stay on Cito's existing application and backend.
  body = body.replace(
    /href="(\/(?:login|signup|contact|status|fo)(?:[^\"]*))"/g,
    (_, href) => `href="${origin}${href}"`,
  );
  const title = page
    ? page
        .split("-")
        .map((x) => x[0].toUpperCase() + x.slice(1))
        .join(" ") + " | Cito"
    : "Business, connected | Cito";
  const html = `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="robots" content="noindex,nofollow"><meta name="theme-color" content="#0066FF"><meta name="description" content="Connect payments, communications, identity, vending and billing through one Cito account."><title>${title}</title><link rel="canonical" href="${origin}/${page}"><link rel="icon" href="/favicon.svg" type="image/svg+xml"><meta property="og:image" content="/favicon.svg"><link rel="stylesheet" href="/site.css"><script src="/site.js" defer></script></head><body>${body}</body></html>`;
  const folder = page ? path.join(output, page) : output;
  await fs.mkdir(folder, { recursive: true });
  await fs.writeFile(path.join(folder, "index.html"), html);
}
await fs.writeFile(
  path.join(output, "404.html"),
  '<!doctype html><html lang="en"><head><meta charset="utf-8"><title>Page not found | Cito</title><link rel="stylesheet" href="/site.css"></head><body><main class="cito-landing cito-section"><h1>Page not found</h1><p><a href="/">Return to Cito</a></p></main></body></html>',
);
console.log(
  `Exported ${pages.length + 1} public pages to ${output}. Account, sales, status and reference links use the existing Cito application.`,
);
