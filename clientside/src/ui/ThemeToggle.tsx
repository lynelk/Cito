import React from 'react';
import { getStoredTheme, nextTheme, setTheme, type ThemePreference } from '../shared/theme';
import {
  getStoredBrandMode,
  nextBrandMode,
  setBrandMode,
  type BrandMode,
} from '../shared/brandMode';
import { SunIcon, MoonIcon, AutoThemeIcon } from './Icons';

const ICON: Record<ThemePreference, React.ReactElement> = {
  light: <SunIcon size={18} />,
  dark: <MoonIcon size={18} />,
  system: <AutoThemeIcon size={18} />,
};
const NEXT_LABEL: Record<ThemePreference, string> = {
  light: 'Switch to dark appearance',
  dark: 'Use system appearance',
  system: 'Switch to light appearance',
};
const BRAND_LABEL: Record<BrandMode, string> = {
  cito: 'Switch to monochrome theme',
  mono: 'Switch to Cito brand theme',
};

/** Top-bar controls for independent appearance and brand presentation. */
export function ThemeToggle(): React.ReactElement {
  const [pref, setPref] = React.useState<ThemePreference>(getStoredTheme);
  const [brandMode, setBrandModeState] = React.useState<BrandMode>(getStoredBrandMode);

  function cycleAppearance() {
    const next = nextTheme(pref);
    setTheme(next);
    setPref(next);
  }

  function cycleBrandMode() {
    const next = nextBrandMode(brandMode);
    setBrandMode(next);
    setBrandModeState(next);
  }

  return (
    <div className="cito-theme-controls" aria-label="Theme controls">
      <button
        type="button"
        className="ios-icon-btn"
        onClick={cycleAppearance}
        aria-label={NEXT_LABEL[pref]}
        title={`Appearance: ${pref}`}
      >
        {ICON[pref]}
      </button>
      <button
        type="button"
        className="ios-icon-btn cito-brand-mode-toggle"
        onClick={cycleBrandMode}
        aria-label={BRAND_LABEL[brandMode]}
        title={brandMode === 'cito' ? 'Cito brand theme' : 'Monochrome theme'}
      >
        <span aria-hidden="true">{brandMode === 'cito' ? 'Cito' : 'Mono'}</span>
      </button>
    </div>
  );
}
