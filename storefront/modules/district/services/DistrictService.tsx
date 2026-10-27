import apiClientService from '@/common/services/ApiClientService';

export async function getDistricts(id: number) {
  const response = await apiClientService.get(`/api/location/storefront/district/${id}`);
  if (!response.ok) throw new Error('Unable to load districts');
  const data = await response.json();
  if (!Array.isArray(data)) throw new Error('Invalid districts response');
  return data;
}
