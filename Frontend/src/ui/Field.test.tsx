import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { Field } from './Field';
import { Input } from './Input';

describe('Field and Input', () => {
  it('labels the input', async () => {
    render(<Field label="Phone number">{(control) => <Input {...control} />}</Field>);
    const input = screen.getByLabelText('Phone number');
    await userEvent.type(input, '98765 43210');
    expect(input).toHaveValue('98765 43210');
  });

  it('states the rule up front, and announces it with the input', () => {
    render(
      <Field label="Password" hint="At least 8 characters.">
        {(control) => <Input {...control} type="password" />}
      </Field>,
    );
    const input = screen.getByLabelText('Password');
    expect(input).toHaveAccessibleDescription('At least 8 characters.');
    expect(input).not.toHaveAttribute('aria-invalid');
  });

  it('shows an error inline, marks the input invalid, and announces both', () => {
    render(
      <Field label="Password" hint="At least 8 characters." error="Use at least 8 characters.">
        {(control) => <Input {...control} type="password" />}
      </Field>,
    );
    const input = screen.getByLabelText('Password');
    expect(input).toHaveAttribute('aria-invalid', 'true');
    expect(input).toHaveClass('aria-[invalid=true]:border-stop');
    expect(input).toHaveAccessibleDescription('At least 8 characters. Use at least 8 characters.');
    expect(screen.getByText('Use at least 8 characters.')).toHaveClass('text-stop');
  });

  it('sits on ink with light text, and marks an error with a stop-red sign beside light text', () => {
    render(
      <Field label="Phone number" error="Enter your mobile number." surface="ink">
        {(control) => <Input {...control} surface="ink" />}
      </Field>,
    );
    expect(screen.getByLabelText('Phone number')).toHaveClass('bg-paper/10', 'text-paper');
    const error = screen.getByText('Enter your mobile number.');
    // Stop-red text on ink would fall short of the contrast small text needs.
    expect(error).toHaveClass('text-paper');
    expect(error.querySelector('svg')?.parentElement).toHaveClass('text-stop');
  });

  it('holds a mark before the text and a control after it', () => {
    render(
      <Field label="Pickup">
        {(control) => (
          <Input
            {...control}
            leading={<span data-testid="ring" />}
            trailing={<button type="button">Use my location</button>}
          />
        )}
      </Field>,
    );
    expect(screen.getByLabelText('Pickup')).toHaveClass('pl-11', 'pr-12');
    expect(screen.getByTestId('ring').parentElement).toHaveAttribute('aria-hidden', 'true');
    expect(screen.getByRole('button', { name: 'Use my location' })).toBeInTheDocument();
  });

  it('can be disabled', () => {
    render(<Field label="Plate">{(control) => <Input {...control} disabled />}</Field>);
    expect(screen.getByLabelText('Plate')).toBeDisabled();
  });

  it('shows a placeholder only as a hint, in the muted colour', () => {
    render(
      <Field label="Drop">{(control) => <Input {...control} placeholder="Where to?" />}</Field>,
    );
    const input = screen.getByLabelText('Drop');
    expect(input).toHaveAttribute('placeholder', 'Where to?');
    expect(input).toHaveClass('placeholder:text-mute', 'min-h-12');
  });
});
