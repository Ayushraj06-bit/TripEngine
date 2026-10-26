import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { Icon, ICON_NAMES } from './Icon';
import { Skeleton } from './Skeleton';
import { Spinner } from './Spinner';

describe('Skeleton', () => {
  it('is hidden from screen readers and does not move', () => {
    const { container } = render(<Skeleton className="h-4 w-20" />);
    const block = container.firstElementChild;
    expect(block).toHaveAttribute('aria-hidden', 'true');
    expect(block).toHaveClass('bg-wash', 'h-4', 'w-20');
    expect(block?.className).not.toMatch(/animate/);
  });
});

describe('Spinner', () => {
  it('announces what is loading when it stands alone', () => {
    render(<Spinner label="Loading trips" />);
    expect(screen.getByRole('status')).toHaveTextContent('Loading trips');
  });

  it('stays silent inside something already marked busy', () => {
    const { container } = render(<Spinner />);
    expect(screen.queryByRole('status')).toBeNull();
    expect(container.querySelector('svg')).toHaveAttribute('aria-hidden', 'true');
  });

  it('only turns when motion is welcome', () => {
    const { container } = render(<Spinner />);
    expect(container.querySelector('svg')).toHaveClass('motion-safe:animate-spin');
  });
});

describe('Icon', () => {
  it.each(ICON_NAMES)('draws %s, hidden from screen readers', (name) => {
    const { container } = render(<Icon name={name} />);
    const svg = container.querySelector('svg');
    expect(svg).toHaveAttribute('data-icon', name);
    expect(svg).toHaveAttribute('aria-hidden', 'true');
  });
});
