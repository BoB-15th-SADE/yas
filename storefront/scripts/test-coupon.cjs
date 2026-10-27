const assert = require('node:assert/strict');
const { execFileSync } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const temporary = fs.mkdtempSync(path.join(os.tmpdir(), 'yas-coupon-test-'));
execFileSync(process.execPath, [require.resolve('typescript/bin/tsc'),
  '--target', 'ES5', '--lib', 'ES2020,DOM', '--module', 'commonjs', '--skipLibCheck', '--rootDir', '.',
  '--outDir', temporary, 'modules/promotion/service/CouponResult.ts'], { stdio: 'inherit' });
const { couponDiscount, readCouponResult } = require(path.join(temporary, 'modules/promotion/service/CouponResult.js'));
async function main() {
  const coupon = { isValid: true, productId: 1, couponCode: 'SADE10', discountType: 'PERCENTAGE', discountValue: 10 };
  const target = { productId: 1, price: 799, quantity: 2 };
  const unrelated = { productId: 2, price: 899, quantity: 1 };
  assert.equal(couponDiscount(target, coupon), 159.8, 'percentage uses the target line quantity');
  assert.equal(couponDiscount(unrelated, coupon), 0, 'one-product coupon does not discount unrelated lines');
  assert.equal(couponDiscount(target), 0);
  assert.equal(couponDiscount(target, { ...coupon, isValid: false }), 0);
  assert.equal(couponDiscount(target, { ...coupon, discountType: 'FIXED', discountValue: 2000 }), 1598, 'cannot exceed the eligible line amount');
  const good = await readCouponResult({ ok: true, status: 200, json: async () => coupon });
  assert.equal(good.couponCode, 'SADE10');
  await assert.rejects(readCouponResult({ ok: false, status: 500, json: async () => { throw Error('must not parse an error as a coupon'); } }), /temporarily unavailable/);
  await assert.rejects(readCouponResult({ ok: false, status: 403, json: async () => ({}) }), /sign in/);
  await assert.rejects(readCouponResult({ ok: false, status: 404, json: async () => ({}) }), /cannot be applied/);
  await assert.rejects(readCouponResult({ ok: true, status: 200, json: async () => ({ status: 500 }) }), /could not be verified/);
  await assert.rejects(readCouponResult({ ok: true, status: 200, json: async () => ({ ...coupon, discountValue: 101 }) }), /could not be verified/);
  console.log('Coupon checks passed: server errors, authentication, malformed response, eligible product, quantity and discount cap.');
}
main().catch(error => { console.error(error); process.exitCode = 1; }).finally(() => {
  if (path.dirname(temporary) === path.resolve(os.tmpdir()) && path.basename(temporary).startsWith('yas-coupon-test-')) {
    fs.rmSync(temporary, { recursive: true });
  }
});
