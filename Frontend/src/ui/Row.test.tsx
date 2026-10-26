import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { Money } from './Money';
import { Row } from './Row';
import { Skeleton } from './Skeleton';

describe('Row', () => {
  it('shows a label and its value', () => {
    render(<Row label="Pickup" value="Connaught Place" />);
    expect(screen.getByText('Pickup')).toBeInTheDocument();
    expect(screen.getByText('Connaught Place')).toHaveClass('truncate');
  });

  it('sets changing figures in tabular numerals', () => {
    render(<Row label="Wallet" value={<Money paise={124000} />} numeric />);
    expect(screen.getByText('₹1,240').parentElement).toHaveClass('tabular-nums');
  });

  it('carries a trailing action', () => {
    render(<Row label="Drop" value="Cyber Hub" action={<button type="button">Change</button>} />);
    expect(screen.getByRole('button', { name: 'Change' })).toBeInTheDocument();
  });

  it('shows a value that is not known yet as a skeleton, not a made-up number', () => {
    const { container } = render(<Row label="Arrives" value={<Skeleton className="h-4 w-20" />} />);
    expect(container.querySelector('[aria-hidden="true"]')).toHaveClass('bg-wash');
    expect(container).not.toHaveTextContent(/\d/);
  });
});
