import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { fitPadding, MapCanvas } from './MapCanvas';
import { MapPin } from './MapPin';

describe('MapCanvas', () => {
  it('keeps pins clear of the sheet, the attribution and the toasts', () => {
    expect(fitPadding({ bottom: 473, left: 0 })).toEqual({
      paddingTopLeft: [48, 88],
      paddingBottomRight: [48, 521],
    });
    expect(fitPadding({ bottom: 0, left: 436 })).toEqual({
      paddingTopLeft: [484, 88],
      paddingBottomRight: [48, 48],
    });
  });

  it('is a labelled region behind everything', () => {
    render(<MapCanvas label="Map of your pickup and drop" />);
    const region = screen.getByRole('region', { name: 'Map of your pickup and drop' });
    expect(region).toHaveClass('fixed', 'inset-0', 'z-0', 'rf-map');
  });

  it('credits OpenStreetMap, at the top where the sheet never covers it', () => {
    const { container } = render(<MapCanvas label="Map" />);
    const link = screen.getByRole('link', { name: 'OpenStreetMap' });
    expect(link).toHaveAttribute('href', 'https://www.openstreetmap.org/copyright');
    expect(container.querySelector('.leaflet-top.leaflet-right')).toContainElement(link);
  });

  it('draws the marker layers it is given', () => {
    render(
      <MapCanvas
        label="Map"
        fit={[
          { lat: 12.97, lng: 77.6 },
          { lat: 12.98, lng: 77.64 },
        ]}
      >
        <MapPin kind="pickup" at={{ lat: 12.97, lng: 77.6 }} />
        <MapPin kind="drop" at={{ lat: 12.98, lng: 77.64 }} />
        <MapPin kind="driver" at={{ lat: 12.975, lng: 77.62 }} />
      </MapCanvas>,
    );
    expect(screen.getByTitle('Pickup')).toBeInTheDocument();
    expect(screen.getByTitle('Drop')).toBeInTheDocument();
    expect(screen.getByTitle('Your driver').innerHTML).toContain('bg-signal');
  });
});
