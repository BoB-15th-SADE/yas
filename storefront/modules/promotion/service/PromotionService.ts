import apiClientService from '@/common/services/ApiClientService';
import { PromotionVerifyRequest, PromotionVerifyResult } from '../model/Promotion';
import { readCouponResult } from './CouponResult';

export async function verifyPromotion(
  request: PromotionVerifyRequest
): Promise<PromotionVerifyResult> {
  return readCouponResult(
    await apiClientService.post(
      '/api/promotion/storefront/promotions/verify',
      JSON.stringify(request)
    )
  );
}
