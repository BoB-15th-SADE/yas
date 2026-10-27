import { Country } from '../models/Country';
import apiClientService from '@/common/services/ApiClientService';

export async function getCountries(): Promise<Country[]> {
  const response = await apiClientService.get(`/api/location/storefront/countries`);
  if (!response.ok) throw new Error('Unable to load countries');
  const data = await response.json();
  if (!Array.isArray(data)) throw new Error('Invalid countries response');
  return data;
}
