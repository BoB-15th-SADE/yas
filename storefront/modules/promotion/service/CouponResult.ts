import { PromotionVerifyResult } from '../model/Promotion';

export async function readCouponResult(response: {
  ok: boolean; status: number; json(): Promise<any>;
}): Promise<PromotionVerifyResult> {
  if (!response.ok) {
    if (response.status === 401 || response.status === 403) {
      throw new Error('Please sign in again to verify your coupon.');
    }
    if (response.status === 400 || response.status === 404 || response.status === 422) {
      throw new Error('This coupon cannot be applied. Check the code, selected products and minimum amount.');
    }
    throw new Error('The coupon service is temporarily unavailable. Please try again later.');
  }
  let data: PromotionVerifyResult;
  try { data = await response.json(); }
  catch { throw new Error('The coupon service returned an invalid response. Please try again later.'); }
  if (!data || data.isValid !== true || !Number.isSafeInteger(data.productId) || data.productId <= 0 ||
      !data.couponCode || !['PERCENTAGE', 'FIXED'].includes(data.discountType) ||
      !Number.isFinite(data.discountValue) || data.discountValue < 0 ||
      (data.discountType === 'PERCENTAGE' && data.discountValue > 100)) {
    throw new Error('The coupon could not be verified. Please check the code and try again.');
  }
  return data;
}

export function couponDiscount(
  item: { productId: number; price: number; quantity: number },
  coupon?: PromotionVerifyResult
): number {
  if (!coupon?.isValid || coupon.productId !== item.productId) return 0;
  const amount = item.price * item.quantity;
  if (!Number.isFinite(amount) || amount < 0 || !Number.isFinite(coupon.discountValue) ||
      coupon.discountValue < 0) return 0;
  const discount = coupon.discountType === 'PERCENTAGE'
    ? amount * coupon.discountValue / 100 : coupon.discountType === 'FIXED' ? coupon.discountValue : 0;
  return Math.round(Math.min(amount, Math.max(0, discount)) * 100) / 100;
}
