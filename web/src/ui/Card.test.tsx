import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { Card, ChoiceCard } from './Card';

function Tiers() {
  const [tier, setTier] = useState('auto');
  return (
    <div role="radiogroup" aria-label="Ride type">
      {['bike', 'auto', 'car'].map((id) => (
        <ChoiceCard
          key={id}
          selected={tier === id}
          onClick={() => {
            setTier(id);
          }}
        >
          {id}
        </ChoiceCard>
      ))}
    </div>
  );
}

describe('Card', () => {
  it('sits inset on wash, or outlined, never with a shadow', () => {
    render(
      <>
        <Card>Inset</Card>
        <Card outlined>Outlined</Card>
      </>,
    );
    expect(screen.getByText('Inset')).toHaveClass('bg-wash', 'rounded-card');
    expect(screen.getByText('Outlined')).toHaveClass('border', 'bg-paper');
    expect(screen.getByText('Inset').className).not.toMatch(/shadow/);
  });
});

describe('ChoiceCard', () => {
  it('is one choice in a group, and says which is selected', () => {
    render(<Tiers />);
    expect(screen.getByRole('radio', { name: 'auto' })).toHaveAttribute('aria-checked', 'true');
    expect(screen.getByRole('radio', { name: 'bike' })).toHaveAttribute('aria-checked', 'false');
  });

  it('outlines the selected choice in signal', async () => {
    render(<Tiers />);
    await userEvent.click(screen.getByRole('radio', { name: 'car' }));
    expect(screen.getByRole('radio', { name: 'car' })).toHaveClass('border-signal');
    expect(screen.getByRole('radio', { name: 'auto' })).toHaveClass('border-transparent');
  });

  it('can be chosen from the keyboard', async () => {
    render(<Tiers />);
    screen.getByRole('radio', { name: 'bike' }).focus();
    await userEvent.keyboard('{Enter}');
    expect(screen.getByRole('radio', { name: 'bike' })).toHaveAttribute('aria-checked', 'true');
  });

  it('cannot be chosen when unavailable', async () => {
    const onClick = vi.fn();
    render(
      <ChoiceCard selected={false} disabled onClick={onClick}>
        Unavailable
      </ChoiceCard>,
    );
    await userEvent.click(screen.getByRole('radio', { name: 'Unavailable' }));
    expect(onClick).not.toHaveBeenCalled();
  });
});
