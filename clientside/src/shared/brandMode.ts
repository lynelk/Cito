export type BrandMode = 'cito' | 'mono';

const STORAGE_KEY = 'cito-brand-mode';

export function getStoredBrandMode(): BrandMode {
  try {
    return localStorage.getItem(STORAGE_KEY) === 'mono' ? 'mono' : 'cito';
  } catch {
    return 'cito';
  }
}

export function applyBrandMode(mode: BrandMode): void {
  document.documentElement.setAttribute('data-brand-mode', mode);
}

export function setBrandMode(mode: BrandMode): void {
  try {
    localStorage.setItem(STORAGE_KEY, mode);
  } catch {
    /* Storage can be unavailable in private/locked-down browser contexts. */
  }
  applyBrandMode(mode);
}

export function nextBrandMode(mode: BrandMode): BrandMode {
  return mode === 'cito' ? 'mono' : 'cito';
}

export function initBrandMode(): void {
  applyBrandMode(getStoredBrandMode());
}
