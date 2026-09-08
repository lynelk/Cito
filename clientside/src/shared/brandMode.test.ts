import { afterEach, describe, expect, test } from 'vitest';
import {
  applyBrandMode,
  getStoredBrandMode,
  nextBrandMode,
  setBrandMode,
} from './brandMode';

afterEach(() => {
  localStorage.clear();
  document.documentElement.removeAttribute('data-brand-mode');
});

describe('Cito brand mode', () => {
  test('defaults to Cito and toggles to monochrome', () => {
    expect(getStoredBrandMode()).toBe('cito');
    expect(nextBrandMode('cito')).toBe('mono');
    expect(nextBrandMode('mono')).toBe('cito');
  });

  test('persists and applies the selected mode', () => {
    setBrandMode('mono');
    expect(localStorage.getItem('cito-brand-mode')).toBe('mono');
    expect(document.documentElement.getAttribute('data-brand-mode')).toBe('mono');

    applyBrandMode('cito');
    expect(document.documentElement.getAttribute('data-brand-mode')).toBe('cito');
  });
});
