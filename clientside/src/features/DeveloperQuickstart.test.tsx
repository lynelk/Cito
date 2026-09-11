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
      expect(screen.getByText(/does not create a sandbox/)).toBeTruthy();
    } finally { network.mockRestore(); }
  });
});
