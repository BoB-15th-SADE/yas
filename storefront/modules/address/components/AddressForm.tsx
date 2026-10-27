import { useEffect, useRef, useState } from 'react';
import { FieldErrorsImpl, UseFormRegister, UseFormSetValue } from 'react-hook-form';
import { Input } from '../../../common/items/Input';
import { OptionSelect } from '../../../common/items/OptionSelect';
import { Address } from '../../../modules/address/models/AddressModel';
import { Country } from '../../country/models/Country';
import { District } from '../../district/models/District';
import { StateOrProvince } from '../../stateAndProvince/models/StateOrProvince';
import { getCountries } from '../../country/services/CountryService';
import { getStatesOrProvinces } from '../../stateAndProvince/services/StatesOrProvicesService';
import { getDistricts } from '../../district/services/DistrictService';

type AddressFormProps = {
  handleSubmit: () => void;
  register: UseFormRegister<Address>;
  setValue: UseFormSetValue<Address>;
  errors: FieldErrorsImpl<Address>;
  address: Address | undefined;
  isDisplay?: boolean | true;
  buttonText?: string;
  isSubmitting?: boolean;
};

const AddressForm = ({
  handleSubmit,
  register,
  errors,
  address,
  isDisplay,
  setValue,
  buttonText,
  isSubmitting = false,
}: AddressFormProps) => {
  const [countries, setCountries] = useState<Country[]>([]);
  const [statesOrProvinces, setStatesOrProvinces] = useState<StateOrProvince[]>([]);
  const [districts, setDistricts] = useState<District[]>([]);
  const [regionMessage, setRegionMessage] = useState('');
  const [districtMessage, setDistrictMessage] = useState('');
  const countryRequest = useRef(0);
  const stateRequest = useRef(0);

  useEffect(() => {
    getCountries().then((data) => {
      setCountries(data);
    }).catch(() => setRegionMessage('Unable to load countries. Please try again.'));
  }, []);

  useEffect(() => {
    if (!address) return;
    let active = true;
    Promise.all([getStatesOrProvinces(address.countryId), getDistricts(address.stateOrProvinceId)])
      .then(([states, areas]) => {
        if (active) { setStatesOrProvinces(states); setDistricts(areas); }
      }).catch(() => { if (active) setRegionMessage('Unable to load regions. Please try again.'); });
    return () => { active = false; };
  }, [address?.countryId, address?.stateOrProvinceId]);

  const onCountryChange = async (event: any) => {
    const request = ++countryRequest.current;
    ++stateRequest.current;
    const countryId = event.target.value;
    setValue('countryName', event.target.selectedOptions[0].text);
    // HTML selects use empty strings although the API Address model uses numeric IDs.
    setValue('stateOrProvinceId', '' as unknown as number);
    setValue('districtId', '' as unknown as number);
    setValue('stateOrProvinceName', '');
    setValue('districtName', '');
    setStatesOrProvinces([]);
    setDistricts([]);
    setRegionMessage('Loading regions...');
    setDistrictMessage('Select a state or province first.');
    try {
      const data = await getStatesOrProvinces(countryId);
      if (request !== countryRequest.current) return;
      setStatesOrProvinces(data);
      setRegionMessage(data.length ? '' : 'No regions are available for this country.');
    } catch {
      if (request === countryRequest.current) setRegionMessage('Unable to load regions. Please try again.');
    }
  };

  const onStateOrProvinceChange = async (event: any) => {
    const request = ++stateRequest.current;
    const stateId = event.target.value;
    setValue('stateOrProvinceName', event.target.selectedOptions[0].text);
    setValue('districtId', '' as unknown as number);
    setValue('districtName', '');
    setDistricts([]);
    setDistrictMessage('Loading districts...');
    try {
      const data = await getDistricts(stateId);
      if (request !== stateRequest.current) return;
      setDistricts(data);
      setDistrictMessage(data.length ? '' : 'No districts are available for this region.');
    } catch {
      if (request === stateRequest.current) setDistrictMessage('Unable to load districts. Please try again.');
    }
  };

  return (
    <>
      {' '}
      <div className={`shipping_address_new ${isDisplay ? `` : `d-none`}`}>
        <div className="row">
          <div className="col-lg-6">
            <div className="checkout__input">
              <Input
                labelText="Contact name"
                register={register}
                field="contactName"
                error={errors.contactName?.message}
                registerOptions={{
                  required: { value: true, message: 'This feild is required' },
                }}
                defaultValue={address?.contactName}
              />
            </div>
          </div>
          <div className="col-lg-6">
            <div className="checkout__input">
              <Input
                labelText="Phone number"
                register={register}
                field="phone"
                error={errors.phone?.message}
                registerOptions={{
                  required: { value: true, message: 'This feild is required' },
                }}
                defaultValue={address?.phone}
              />
            </div>
          </div>
        </div>
        <div className="row">
          <div className="col-lg-4">
            <div className="checkout__input">
              <OptionSelect
                labelText="Country"
                field="countryId"
                placeholder="Select country"
                options={countries}
                register={register}
                registerOptions={{
                  required: { value: true, message: 'Please select country' },
                  onChange: onCountryChange,
                }}
                error={errors.countryId?.message}
                defaultValue={address?.countryId}
              />
            </div>
          </div>
          <div className="col-lg-8">
            <div className="checkout__input">
              <div className="mb-3">
                <OptionSelect
                  labelText="State Or Province"
                  register={register}
                  field="stateOrProvinceId"
                  error={errors.stateOrProvinceId?.message}
                  disabled={!statesOrProvinces.length}
                  options={statesOrProvinces}
                  placeholder="Select state or province"
                  defaultValue={address?.stateOrProvinceId}
                  registerOptions={{
                    required: { value: true, message: 'Please select state or province' },
                    onChange: onStateOrProvinceChange,
                  }}
                />
                {regionMessage && <p role="status" className="text-muted">{regionMessage}</p>}
              </div>
            </div>
          </div>
        </div>
        <div className="row">
          <div className="col-lg-6">
            <div className="checkout__input">
              <Input
                labelText="City"
                register={register}
                field="city"
                placeholder="Please skip this field if you are not in a city"
                defaultValue={address?.city}
              />
            </div>
          </div>
          <div className="col-lg-6">
            <div className="checkout__input">
              <OptionSelect
                labelText="District"
                register={register}
                field="districtId"
                error={errors.districtId?.message}
                disabled={!districts.length}
                options={districts}
                placeholder="Select district"
                defaultValue={address?.districtId}
                registerOptions={{
                  required: { value: true, message: 'Please select district' },
                  onChange: (event: any) => {
                    setValue('districtName', event.target.selectedOptions[0].text);
                  },
                }}
              />
              {districtMessage && <p role="status" className="text-muted">{districtMessage}</p>}
            </div>
          </div>
        </div>
        <div className="row">
          <div className="col-lg-10">
            <div className="checkout__input">
              <Input
                labelText="Address"
                register={register}
                field="addressLine1"
                error={errors.addressLine1?.message}
                registerOptions={{
                  required: { value: true, message: 'This feild is required' },
                }}
                defaultValue={address?.addressLine1}
              />
            </div>
          </div>
          <div className="col-lg-2">
            <div className="checkout__input">
              <Input
                labelText="Zip code"
                register={register}
                field="zipCode"
                error={errors.zipCode?.message}
                registerOptions={{
                  required: { value: true, message: 'This feild is required' },
                }}
                defaultValue={address?.zipCode}
              />
            </div>
          </div>
        </div>
        <div className="row mt-2">
          <div className="col-lg-12 d-flex justify-content-end">
            <button
              type="button"
              className="btn btn-outline-primary fw-bold px-2 py-2"
              onClick={handleSubmit}
              disabled={isSubmitting}
            >
              {buttonText ?? 'Create'}
            </button>
          </div>
        </div>
      </div>
    </>
  );
};

export default AddressForm;
