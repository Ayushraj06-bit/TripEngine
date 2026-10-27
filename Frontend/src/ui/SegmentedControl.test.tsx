import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { describe, expect, it } from 'vitest';
import { SegmentedControl } from './SegmentedControl';

function AccountType({ surface }: { surface?: 'paper' | 'ink' }) {
  const [value, setValue] = useState<'RIDER' | 'DRIVER'>('RIDER');
  return (
    <SegmentedControl
      label="Account type"
      surface={surface}
      value={value}
      onChange={setValue}
      segments={[
        { value: 'RIDER', label: 'Rider' },
        { value: 'DRIVER', label: 'Driver' },
      ]}
    />
  );
}

describe('SegmentedControl', () => {
  it('is a labelled radio group that says which segment is chosen', () => {
    render(<AccountType />);
    expect(screen.getByRole('radiogroup', { name: 'Account type' })).toHaveClass('rounded-full');
    expect(screen.getByRole('radio', { name: 'Rider' })).toHaveAttribute('aria-checked', 'true');
    expect(screen.getByRole('radio', { name: 'Driver' })).toHaveAttribute('aria-checked', 'false');
  });

  it('chooses a segment when it is pressed', async () => {
    render(<AccountType />);
    await userEvent.click(screen.getByRole('radio', { name: 'Driver' }));
    expect(screen.getByRole('radio', { name: 'Driver' })).toHaveAttribute('aria-checked', 'true');
  });

  it('is one tab stop, and the arrow keys move and choose', async () => {
    render(<AccountType />);
    expect(screen.getByRole('radio', { name: 'Rider' })).toHaveAttribute('tabindex', '0');
    expect(screen.getByRole('radio', { name: 'Driver' })).toHaveAttribute('tabindex', '-1');

    await userEvent.tab();
    expect(screen.getByRole('radio', { name: 'Rider' })).toHaveFocus();
    await userEvent.keyboard('{ArrowRight}');
    expect(screen.getByRole('radio', { name: 'Driver' })).toHaveFocus();
    expect(screen.getByRole('radio', { name: 'Driver' })).toHaveAttribute('aria-checked', 'true');
    // Round the end, back to the start.
    await userEvent.keyboard('{ArrowRight}');
    expect(screen.getByRole('radio', { name: 'Rider' })).toHaveAttribute('aria-checked', 'true');
  });

  it('marks the chosen segment in paper on ink', () => {
    render(<AccountType surface="ink" />);
    expect(screen.getByRole('radio', { name: 'Rider' })).toHaveClass('bg-paper', 'text-ink');
    expect(screen.getByRole('radio', { name: 'Driver' })).toHaveClass('text-paper/80');
  });

  it('is at least 44 px tall', () => {
    render(<AccountType />);
    for (const radio of screen.getAllByRole('radio')) expect(radio).toHaveClass('min-h-11');
  });
});
