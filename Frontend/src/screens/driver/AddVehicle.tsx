import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useState, type SubmitEvent } from 'react';
import { useNavigate } from 'react-router';
import { api } from '@/api/client';
import { describeError } from '@/api/errors';
import { ApiError } from '@/api/problem';
import type { Schemas } from '@/api/types';
import { Splash } from '@/auth/guards';
import { Button } from '@/ui/Button';
import { Field } from '@/ui/Field';
import { Input } from '@/ui/Input';
import { SegmentedControl } from '@/ui/SegmentedControl';
import { DarkScreen, FormAlert } from '../common';
import { SignOutButton } from '../SignOutButton';
import { DRIVER_PROFILE, useDriverProfile } from '../queries';

type VehicleType = 'BIKE' | 'AUTO' | 'CAR';
type Fields = 'plateNumber' | 'model' | 'colour';

const LIMITS: Record<Fields, number> = { plateNumber: 20, model: 60, colour: 30 };

/** Adding a vehicle, or changing it: the profile is loaded first, so the form starts filled in. */
export function AddVehicle() {
  const profile = useDriverProfile();
  if (profile.isPending) return <Splash />;
  if (profile.isError) {
    return (
      <DarkScreen title="Your vehicle" actions={<SignOutButton />}>
        <FormAlert message={describeError(profile.error).message} />
        <Button
          surface="ink"
          variant="secondary"
          onClick={() => {
            void profile.refetch();
          }}
        >
          Try again
        </Button>
      </DarkScreen>
    );
  }
  return (
    <VehicleForm
      current={profile.data.vehicle}
      approved={profile.data.approvalStatus === 'APPROVED'}
    />
  );
}

function VehicleForm({
  current,
  approved,
}: {
  current: Schemas['VehicleView'] | undefined;
  approved: boolean;
}) {
  const navigate = useNavigate();
  const queries = useQueryClient();
  const [type, setType] = useState<VehicleType>(current?.type ?? 'CAR');
  const [values, setValues] = useState<Record<Fields, string>>({
    plateNumber: current?.plateNumber ?? '',
    model: current?.model ?? '',
    colour: current?.colour ?? '',
  });
  const [errors, setErrors] = useState<Partial<Record<Fields, string>>>({});
  const [failure, setFailure] = useState<string>();

  const save = useMutation({
    mutationFn: () =>
      api.put('/api/v1/drivers/me/vehicle', {
        body: {
          type,
          plateNumber: values.plateNumber.trim(),
          model: values.model.trim(),
          colour: values.colour.trim(),
        },
      }),
    onSuccess: async () => {
      await queries.invalidateQueries({ queryKey: DRIVER_PROFILE });
      await navigate(approved ? '/driver' : '/driver/approval', { replace: true });
    },
    onError: (error) => {
      const described = describeError(error);
      if (
        described.code === 'PLATE_NUMBER_INVALID' ||
        described.code === 'PLATE_ALREADY_REGISTERED'
      ) {
        setErrors({ plateNumber: described.message });
      } else if (error instanceof ApiError && described.code === 'VALIDATION_FAILED') {
        setFailure('Some details need another look. Check each field.');
      } else {
        setFailure(described.message);
      }
    },
  });

  function submit(event: SubmitEvent<HTMLFormElement>) {
    event.preventDefault();
    setFailure(undefined);
    const found: Partial<Record<Fields, string>> = {};
    for (const field of Object.keys(LIMITS) as Fields[]) {
      const value = values[field].trim();
      if (!value) found[field] = 'Fill this in.';
      else if (value.length > LIMITS[field])
        found[field] = `Use at most ${LIMITS[field]} characters.`;
    }
    setErrors(found);
    if (Object.keys(found).length === 0) save.mutate();
  }

  function field(name: Fields) {
    return {
      value: values[name],
      onChange: (event: { target: { value: string } }) => {
        setValues({ ...values, [name]: event.target.value });
      },
    };
  }

  return (
    <DarkScreen
      title={current ? 'Your vehicle' : 'Add your vehicle'}
      lead="Riders see the plate, model and colour, so they find the right one at the kerb."
      actions={<SignOutButton />}
    >
      <form onSubmit={submit} noValidate className="flex flex-col gap-5">
        <SegmentedControl
          label="Vehicle type"
          surface="ink"
          value={type}
          onChange={setType}
          segments={[
            { value: 'BIKE', label: 'Bike' },
            { value: 'AUTO', label: 'Auto' },
            { value: 'CAR', label: 'Car' },
          ]}
        />
        {failure && <FormAlert message={failure} />}
        <Field
          label="Plate number"
          hint="As printed, such as KA 01 AB 1234."
          error={errors.plateNumber}
          surface="ink"
        >
          {(control) => (
            <Input
              {...control}
              {...field('plateNumber')}
              surface="ink"
              autoCapitalize="characters"
              autoComplete="off"
            />
          )}
        </Field>
        <Field label="Model" hint="Such as Maruti Swift." error={errors.model} surface="ink">
          {(control) => <Input {...control} {...field('model')} surface="ink" autoComplete="off" />}
        </Field>
        <Field label="Colour" error={errors.colour} surface="ink">
          {(control) => (
            <Input {...control} {...field('colour')} surface="ink" autoComplete="off" />
          )}
        </Field>
        <Button type="submit" surface="ink" size="large" block loading={save.isPending}>
          {current ? 'Save vehicle' : 'Add vehicle'}
        </Button>
      </form>
    </DarkScreen>
  );
}
