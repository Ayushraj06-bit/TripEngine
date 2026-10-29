import { act, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { describe, expect, it } from 'vitest';
import { Toaster } from '@/ui/Toast';
import { useToasts } from '@/ui/toasts';
import { DesignSystem } from './DesignSystem';

function renderPage() {
  const router = createMemoryRouter([{ path: '/', Component: DesignSystem }]);
  return render(
    <>
      <RouterProvider router={router} />
      <Toaster />
    </>,
  );
}

describe('the design system page', () => {
  it('shows every primitive', async () => {
    renderPage();
    for (const section of [
      'Colour',
      'Type',
      'Button',
      'Field',
      'Segmented control',
      'Option row',
      'Bar and materials',
      'Row',
      'Card',
      'Fare breakdown',
      'Skeleton',
      'Spinner',
      'Toast',
      'Money and time',
      'Icon',
    ]) {
      expect(await screen.findByRole('heading', { name: section })).toBeInTheDocument();
    }
  });

  it('lets a tier be chosen', async () => {
    renderPage();
    const tiers = await screen.findByRole('radiogroup', { name: 'Sample ride tiers' });
    await userEvent.click(within(tiers).getByRole('radio', { name: /Car/ }));
    expect(within(tiers).getByRole('radio', { name: /Car/ })).toHaveAttribute(
      'aria-checked',
      'true',
    );
  });

  it('raises each kind of toast', async () => {
    act(() => {
      useToasts.setState({ toasts: [] });
    });
    renderPage();
    await userEvent.click(await screen.findByRole('button', { name: 'Error with action' }));
    expect(screen.getByRole('alert')).toHaveTextContent('No connection.');
  });
});
