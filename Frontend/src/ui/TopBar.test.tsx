import { render, screen } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { describe, expect, it } from 'vitest';
import { TopBar } from './TopBar';

function renderBar(surface: 'paper' | 'ink') {
  const router = createMemoryRouter([
    {
      path: '/',
      element: <TopBar surface={surface} actions={<button type="button">Sign in</button>} />,
    },
  ]);
  return render(<RouterProvider router={router} />);
}

describe('TopBar', () => {
  it('is frosted glass on ink, with the name linking home and room for actions', async () => {
    renderBar('ink');
    const bar = await screen.findByRole('banner');
    expect(bar).toHaveClass('material-ink', 'sticky', 'top-0');
    expect(screen.getByRole('link', { name: 'RideFlow' })).toHaveAttribute('href', '/welcome');
    expect(screen.getByRole('button', { name: 'Sign in' })).toBeInTheDocument();
  });

  it('is light frosted glass over the map', async () => {
    renderBar('paper');
    expect(await screen.findByRole('banner')).toHaveClass('material', 'text-ink');
  });
});
