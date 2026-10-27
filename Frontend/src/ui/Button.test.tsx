import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { Button } from './Button';

describe('Button', () => {
  it('is a 44 px tap target that does not submit forms by accident', () => {
    render(<Button>Confirm Auto</Button>);
    const button = screen.getByRole('button', { name: 'Confirm Auto' });
    expect(button).toHaveClass('min-h-11');
    expect(button).toHaveAttribute('type', 'button');
  });

  it('is ink on the sheet and signal on ink surfaces', () => {
    render(
      <>
        <Button>On paper</Button>
        <Button surface="ink">On ink</Button>
      </>,
    );
    expect(screen.getByRole('button', { name: 'On paper' })).toHaveClass('bg-ink', 'text-paper');
    expect(screen.getByRole('button', { name: 'On ink' })).toHaveClass('bg-signal', 'text-ink');
  });

  it.each([
    ['secondary', 'bg-wash'],
    ['quiet', 'bg-transparent'],
  ] as const)('has a %s variant', (variant, marker) => {
    render(<Button variant={variant}>Change</Button>);
    expect(screen.getByRole('button', { name: 'Change' })).toHaveClass(marker);
  });

  it('runs its action when pressed', async () => {
    const onClick = vi.fn();
    render(<Button onClick={onClick}>Confirm</Button>);
    await userEvent.click(screen.getByRole('button', { name: 'Confirm' }));
    expect(onClick).toHaveBeenCalledTimes(1);
  });

  it('cannot be pressed when disabled', async () => {
    const onClick = vi.fn();
    render(
      <Button disabled onClick={onClick}>
        Confirm
      </Button>,
    );
    const button = screen.getByRole('button', { name: 'Confirm' });
    expect(button).toBeDisabled();
    expect(button).toHaveClass('disabled:bg-wash', 'disabled:text-mute');
    await userEvent.click(button);
    expect(onClick).not.toHaveBeenCalled();
  });

  it('keeps its label and colour while loading, says it is busy, and cannot be pressed twice', async () => {
    const onClick = vi.fn();
    render(
      <Button loading onClick={onClick}>
        Confirm Auto
      </Button>,
    );
    const button = screen.getByRole('button', { name: 'Confirm Auto' });
    expect(button).toHaveAttribute('aria-busy', 'true');
    expect(button).toBeDisabled();
    // Busy is not unavailable: no greyed-out styling.
    expect(button).toHaveClass('bg-ink');
    expect(button.className).not.toMatch(/disabled:bg-wash/);
    await userEvent.click(button);
    expect(onClick).not.toHaveBeenCalled();
  });

  it('is 50 px tall when it is the one button pinned to the bottom', () => {
    render(<Button size="large">Confirm Auto</Button>);
    expect(screen.getByRole('button')).toHaveClass('min-h-12.5');
  });

  it('can be a capsule', () => {
    render(<Button capsule>Pickup now</Button>);
    expect(screen.getByRole('button')).toHaveClass('rounded-full');
  });

  it('uses Apple’s grey fill for a secondary button on ink', () => {
    render(
      <Button surface="ink" variant="secondary">
        Decline
      </Button>,
    );
    expect(screen.getByRole('button')).toHaveClass('bg-paper/12', 'text-paper');
  });

  it('can fill the width of the sheet', () => {
    render(<Button block>Confirm</Button>);
    expect(screen.getByRole('button')).toHaveClass('w-full');
  });
});
