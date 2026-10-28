import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it } from 'vitest';
import { resetApp } from '@/test/app';
import { addressIs, AIRPORT, at } from '@/test/backend';
import { ChooseOnMap } from './ChooseOnMap';

beforeEach(resetApp);

describe('the sheet while choosing on the map', () => {
  function renderChoosing(pin: { lat: number; lng: number }) {
    render(
      <QueryClientProvider
        client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}
      >
        <ChooseOnMap kind="drop" at={pin} onConfirm={() => undefined} />
      </QueryClientProvider>,
    );
  }

  it('will not take a pin outside the service area', async () => {
    addressIs(AIRPORT);
    renderChoosing(at(AIRPORT));
    expect(
      await screen.findByText('Outside the area RideFlow serves. Move the pin into Bengaluru.'),
    ).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Confirm drop' })).toBeDisabled();
  });

  it('falls back to the pin’s position when there is no address', async () => {
    addressIs(null);
    renderChoosing({ lat: 12.97161, lng: 77.59457 });
    expect(await screen.findByText('12.9716° N, 77.5946° E')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Confirm drop' })).toBeEnabled();
  });
});
