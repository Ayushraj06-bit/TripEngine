import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { OptionRow } from './OptionRow';

describe('OptionRow', () => {
  it('is one button, named by what it chooses', async () => {
    const onChoose = vi.fn();
    render(<OptionRow icon="drop" name="Indiranagar" detail="Bengaluru" onChoose={onChoose} />);
    const row = screen.getByRole('button', { name: 'Indiranagar, Bengaluru' });
    expect(row).toHaveClass('min-h-14');
    await userEvent.click(row);
    expect(onChoose).toHaveBeenCalledOnce();
  });

  it('stays in the list when it cannot be chosen, with the reason', async () => {
    const onChoose = vi.fn();
    render(
      <OptionRow
        icon="drop"
        name="Kempegowda International Airport"
        detail="Outside the area RideFlow serves"
        disabled
        onChoose={onChoose}
      />,
    );
    const row = screen.getByRole('button', { name: /Outside the area RideFlow serves/ });
    expect(row).toBeDisabled();
    await userEvent.click(row);
    expect(onChoose).not.toHaveBeenCalled();
  });
});
