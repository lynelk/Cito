import React from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import DeveloperQuickstart from './DeveloperQuickstart';
afterEach(cleanup);
describe('safe developer quickstart', () => {
  it('only requests a documentation filter and makes no network call', () => {
    const explore = vi.fn();
    const network = vi.spyOn(globalThis, 'fetch');
    try {
      render(<DeveloperQuickstart onExplore={explore} />);
      fireEvent.click(screen.getByRole('button', { name: 'Explore capability documentation' }));
      expect(explore).toHaveBeenCalledOnce();
      expect(network).not.toHaveBeenCalled();
      const handover = screen.getByRole('link', { name: /external developer handover kit/i });
      expect(handover.getAttribute('href')).toBe('https://github.com/lynelk/Cito/tree/main/Docs/Api/consumer');
      expect(handover.getAttribute('rel')).toContain('noopener');
      expect(screen.getByText(/does not create a sandbox/)).toBeTruthy();
    } finally { network.mockRestore(); }
  });
});
