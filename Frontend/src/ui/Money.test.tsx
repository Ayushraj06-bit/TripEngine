import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { formatRupees, Money } from './Money';

describe('Money', () => {
  it.each([
    [8400, '₹84'],
    [124000, '₹1,240'],
    [12345600, '₹1,23,456'],
    [8450, '₹84.50'],
    [5, '₹0.05'],
    [0, '₹0'],
  ])('writes %i paise as %s', (paise, text) => {
    expect(formatRupees(paise)).toBe(text);
  });

  it('sets figures in tabular numerals', () => {
    render(<Money paise={14800} className="text-large" />);
    expect(screen.getByText('₹148')).toHaveClass('tabular-nums', 'text-large');
  });
});
