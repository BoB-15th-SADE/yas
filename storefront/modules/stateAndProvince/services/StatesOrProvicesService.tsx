import apiClientService from '@/common/services/ApiClientService';

export async function getStatesOrProvinces(id: number) {
  const response = await apiClientService.get(`/api/location/storefront/state-or-provinces/${id}`);
  if (!response.ok) throw new Error('Unable to load regions');
  const data = await response.json();
  if (!Array.isArray(data)) throw new Error('Invalid regions response');
  return data;
}
