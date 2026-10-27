import Link from 'next/link';
import { useCallback, useEffect, useRef, useState } from 'react';
import { Button } from 'react-bootstrap';

import ConfirmationDialog from '@/common/components/dialog/ConfirmationDialog';
import * as CartService from '@/modules/cart/services/CartService';
import { formatPrice } from 'utils/formatPrice';
import { toastError } from '@/modules/catalog/services/ToastService';
import { CartItemGetDetailsVm } from '@/modules/cart/models/CartItemGetVm';
import { Checkout } from '@/modules/order/models/Checkout';
import { useUserInfoContext } from '@/context/UserInfoContext';
import { createCheckout } from '@/modules/order/services/OrderService';
import { useRouter } from 'next/router';
import { CheckoutItem } from '@/modules/order/models/CheckoutItem';
import { useCartContext } from '@/context/CartContext';
import { PromotionVerifyResult } from '@/modules/promotion/model/Promotion';
import { verifyPromotion } from '@/modules/promotion/service/PromotionService';
import { couponDiscount } from '@/modules/promotion/service/CouponResult';
import { CartItemPutVm } from '@/modules/cart/models/CartItemPutVm';
import CartItem from '@/modules/cart/components/CartItem';

const Cart = () => {
  const router = useRouter();

  const [cartItems, setCartItems] = useState<CartItemGetDetailsVm[]>([]);

  const [selectedProductIds, setSelectedProductIds] = useState<Set<number>>(new Set());

  const [loadingItems, setLoadingItems] = useState<Set<number>>(new Set());

  const [totalPrice, setTotalPrice] = useState(0);

  const [isDeleteConfirmationModalOpened, setIsDeleteConfirmationModalOpened] = useState(false);

  const [productIdToRemove, setProductIdToRemove] = useState<number>(0);

  const { email } = useUserInfoContext();

  const { fetchNumberCartItems } = useCartContext();

  const [couponCode, setCouponCode] = useState<string>('');

  const [promotionApply, setPromotionApply] = useState<PromotionVerifyResult>();
  const [couponError, setCouponError] = useState('');
  const [isApplyingCoupon, setIsApplyingCoupon] = useState(false);
  const couponRequest = useRef(0);

  useEffect(() => {
    ++couponRequest.current;
    setPromotionApply(undefined);
    setCouponError('');
    setIsApplyingCoupon(false);
  }, [cartItems, selectedProductIds]);

  const [discountMoney, setDiscountMoney] = useState<number>(0);
  const [subTotalPrice, setSubTotalPrice] = useState<number>(0);

  useEffect(() => {
    loadCartItems();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const getSelectedCartItems = useCallback(() => {
    return cartItems.filter((cartItem) => selectedProductIds.has(cartItem.productId));
  }, [cartItems, selectedProductIds]);

  useEffect(() => {
    const selectedItems = getSelectedCartItems();
    // Calculate sub total price
    const newTotalPrice = selectedItems.reduce((accumulator, item) => {
      return accumulator + item.price * item.quantity;
    }, 0);

    setSubTotalPrice(newTotalPrice);
    // Calculate total discount
    const newDiscountMoney = selectedItems.reduce(
      (total, item) => total + couponDiscount(item, promotionApply), 0);
    setDiscountMoney(newDiscountMoney);

    // Calculate total price
    const totalPriceLast = newTotalPrice - newDiscountMoney;
    setTotalPrice(totalPriceLast);
  }, [cartItems, selectedProductIds, promotionApply, getSelectedCartItems]);

  const loadCartItems = async () => {
    try {
      const newCartItems = await CartService.getDetailedCartItems();
      setCartItems(newCartItems);
      fetchNumberCartItems();
    } catch (error) {
      return [];
    }
  };

  const handleSelectAllCartItemsChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    if (e.target.checked) {
      const allProductIds = cartItems.map((cartItem) => cartItem.productId);
      setSelectedProductIds(new Set(allProductIds));
    } else {
      setSelectedProductIds(new Set());
    }
  };

  const handleSelectCartItemChange = (productId: number) => {
    setSelectedProductIds((prevSelectedProductIds) => {
      const newSelectedProductIds = new Set(prevSelectedProductIds);
      if (newSelectedProductIds.has(productId)) {
        newSelectedProductIds.delete(productId);
      } else {
        newSelectedProductIds.add(productId);
      }
      return newSelectedProductIds;
    });
  };

  const handleCartItemQuantityOnBlur = async (
    productId: number,
    event: React.FocusEvent<HTMLInputElement>
  ) => {
    const newQuantity = parseInt(event.target.value);
    const product = cartItems.find((item) => item.productId === productId);
    if (!product || newQuantity === product.quantity) {
      return;
    }
    await handleUpdateCartItemQuantity(productId, newQuantity);
  };

  const handleCartItemQuantityKeyDown = async (
    productId: number,
    event: React.KeyboardEvent<HTMLInputElement>
  ) => {
    const allowedKeys = ['Backspace', 'ArrowLeft', 'ArrowRight', 'Delete', 'Tab', 'Enter'];
    const digitKeyPattern = /^\d$/;

    if (!allowedKeys.includes(event.key) && !digitKeyPattern.test(event.key)) {
      event.preventDefault();
      return;
    }

    if (event.key === 'Enter') {
      const newQuantity = parseInt(event.currentTarget.value);
      await handleUpdateCartItemQuantity(productId, newQuantity);
    }
  };

  const handleIncreaseQuantity = async (productId: number) => {
    const cartItem = cartItems.find((item) => item.productId === productId);
    if (!cartItem) {
      return;
    }
    const newQuantity = cartItem.quantity + 1;
    await handleUpdateCartItemQuantity(productId, newQuantity);
  };

  const handleDecreaseQuantity = async (productId: number) => {
    const cartItem = cartItems.find((item) => item.productId === productId);
    if (!cartItem) {
      return;
    }
    const newQuantity = cartItem.quantity - 1;
    if (newQuantity < 1) {
      handleOpenDeleteConfirmationModal(productId);
    } else {
      await handleUpdateCartItemQuantity(productId, newQuantity);
    }
  };

  const handleUpdateCartItemQuantity = async (productId: number, quantity: number) => {
    setLoadingItems((prevLoadingItems) => new Set(prevLoadingItems).add(productId));
    try {
      const payload: CartItemPutVm = {
        quantity: quantity,
      };
      await CartService.updateCartItem(productId, payload);
      loadCartItems();
    } catch (error) {
      toastErrorWithDetails('Failed to update cart item quantity', error);
    } finally {
      setLoadingItems((prevLoadingItems) => {
        const newLoadingItems = new Set(prevLoadingItems);
        newLoadingItems.delete(productId);
        return newLoadingItems;
      });
    }
  };

  const handleOpenDeleteConfirmationModal = (productId: number) => {
    setProductIdToRemove(productId);
    setIsDeleteConfirmationModalOpened(true);
  };

  const handleDeleteCartItem = async (productId: number) => {
    try {
      await CartService.deleteCartItem(productId);
    } catch (error) {
      toastError('Failed to delete cart item');
    }
    loadCartItems();
    setIsDeleteConfirmationModalOpened(false);
    setProductIdToRemove(0);
  };

  const handleCheckout = () => {
    const selectedItems = getSelectedCartItems();
    const checkoutItems = selectedItems.map((item) => convertItemToCheckoutItem(item));

    let checkout: Checkout = {
      email: email,
      note: '',
      couponCode: promotionApply?.couponCode,
      totalAmount: totalPrice,
      totalDiscountAmount: discountMoney,
      checkoutItemPostVms: checkoutItems,
    };
    createCheckout(checkout)
      .then((res) => {
        router.push(`/checkout/${res?.id}`); //NOSONAR
      })
      .catch((err) => {
        if (err == 403) toastError('Please login to checkout!');
      });
  };

  const convertItemToCheckoutItem = (item: CartItemGetDetailsVm): CheckoutItem => {
    return {
      productId: item.productId,
      productName: item.productName,
      quantity: item.quantity,
      productPrice: item.price,
      discountAmount: couponDiscount(item, promotionApply),
    };
  };

  const applyCopounCode = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (isApplyingCoupon) return;
    setCouponError('');
    setPromotionApply(undefined);
    const selected = getSelectedCartItems();
    const amount = selected.reduce((sum, item) => sum + item.price * item.quantity, 0);
    if (!selected.length || amount <= 0) {
      setCouponError('Select a product before applying a coupon.');
      return;
    }
    if (!couponCode.trim()) {
      setCouponError('Enter a coupon code.');
      return;
    }
    const request = ++couponRequest.current;
    setIsApplyingCoupon(true);
    try {
      const result = await verifyPromotion({
        couponCode: couponCode.trim(), orderPrice: amount,
        productIds: selected.map((item) => item.productId),
      });
      if (!selected.some((item) => item.productId === result.productId)) {
        throw new Error('This coupon does not apply to the selected products.');
      }
      if (request === couponRequest.current) setPromotionApply(result);
    } catch (error) {
      if (request === couponRequest.current) setCouponError(error instanceof Error
        ? error.message : 'Unable to verify the coupon. Please try again.');
    } finally {
      if (request === couponRequest.current) setIsApplyingCoupon(false);
    }
  };

  const removeCouponCode = () => {
    ++couponRequest.current;
    setPromotionApply(undefined);
    setCouponError('');
    setIsApplyingCoupon(false);
  };

  const toastErrorWithDetails = (message: string, error: any) => {
    if (error instanceof Error) {
      const reason = error.message;
      toastError(`${message}: ${reason}`);
    } else {
      toastError(message);
    }
  };

  return (
    <section className="shop-cart spad">
      <div className="container">
        <div className="row">
          <div className="col-lg-12">
            <div className="shop__cart__table">
              {cartItems.length === 0 ? (
                <h4>There are no items in this cart.</h4>
              ) : (
                <table>
                  <thead>
                    <tr>
                      <th>
                        <label className="item-checkbox-label" htmlFor="select-all-checkbox">
                          {''}
                          <input
                            id="select-all-checkbox"
                            type="checkbox"
                            className="form-check-input item-checkbox"
                            onChange={handleSelectAllCartItemsChange}
                            checked={selectedProductIds.size === cartItems.length}
                          />
                        </label>
                      </th>
                      <th>Product</th>
                      <th>Price</th>
                      <th>Quantity</th>
                      <th>Total</th>
                      <th></th>
                    </tr>
                  </thead>
                  <tbody>
                    {cartItems.map((item) => {
                      return (
                        <CartItem
                          key={item.productId}
                          item={item}
                          isLoading={loadingItems.has(item.productId)}
                          isSelected={selectedProductIds.has(item.productId)}
                          promotionApply={promotionApply}
                          handleSelectCartItemChange={handleSelectCartItemChange}
                          handleDecreaseQuantity={handleDecreaseQuantity}
                          handleIncreaseQuantity={handleIncreaseQuantity}
                          handleCartItemQuantityOnBlur={handleCartItemQuantityOnBlur}
                          handleCartItemQuantityKeyDown={handleCartItemQuantityKeyDown}
                          handleOpenDeleteConfirmationModal={handleOpenDeleteConfirmationModal}
                        />
                      );
                    })}
                  </tbody>
                </table>
              )}
            </div>
          </div>
        </div>
        {couponError && <p role="alert" className="text-danger mt-3">{couponError}</p>}
        {promotionApply && (
          <div className="mt-5 mb-5">
            <div className="row">
              <div className="col">
                {promotionApply.couponCode ? (
                  <span className="coupon-code-apply" aria-hidden="true" onClick={removeCouponCode}>
                    <i className="bi bi-receipt"></i>
                    {promotionApply?.discountType === 'PERCENTAGE' ? (
                      <> You got a {promotionApply.discountValue}% discount on one product!</>
                    ) : (
                      <> You got a {promotionApply.discountValue}$ discount on one product!</>
                    )}
                  </span>
                ) : (
                  <span
                    className="invalid-coupon-code"
                    style={{ color: 'red', fontWeight: 'bold' }}
                    aria-hidden="true"
                    onClick={removeCouponCode}
                  >
                    <i className="bi bi-receipt"></i> Coupon code not valid!
                  </span>
                )}
              </div>
            </div>
          </div>
        )}
        <div className="row">
          <div className="col-lg-6 col-md-6 col-sm-6">
            <div>
              <Link href={'/'}>
                <Button className="cart__btn2">
                  <i className="bi bi-house-fill"></i> CONTINUE SHOPPING
                </Button>
              </Link>
            </div>
          </div>
        </div>
        <div className="row">
          <div className="col-lg-6">
            <div className="discount__content">
              <h6>Discount codes</h6>
              <form onSubmit={applyCopounCode}>
                <input
                  type="text"
                  placeholder="Enter your coupon code"
                  value={couponCode}
                  onChange={(e) => { setCouponCode(e.target.value); removeCouponCode(); }}
                />
                <button
                  className="site-btn primary-btn btn btn-primary"
                  type="submit"
                  disabled={selectedProductIds.size === 0 || isApplyingCoupon}
                >
                  {isApplyingCoupon ? 'Checking...' : 'Apply'}
                </button>
              </form>
            </div>
          </div>
          <div className="col-lg-4 offset-lg-2">
            <div className="cart__total__procced">
              <h6>Cart total</h6>
              <ul>
                <li>
                  Subtotal <span>{formatPrice(subTotalPrice)}</span>
                </li>
                <li>
                  Discount <span>{formatPrice(discountMoney)}</span>
                </li>
                <li>
                  Total <span>{formatPrice(totalPrice)}</span>
                </li>
              </ul>

              <Button
                className="primary-btn"
                onClick={handleCheckout}
                disabled={selectedProductIds?.size === 0 || isApplyingCoupon}
              >
                Proceed to checkout
              </Button>
            </div>
          </div>
        </div>
        <ConfirmationDialog
          isOpen={isDeleteConfirmationModalOpened}
          okText="Remove"
          cancelText="Cancel"
          cancel={() => setIsDeleteConfirmationModalOpened(false)}
          ok={() => handleDeleteCartItem(productIdToRemove)}
        >
          <p>Do you want to remove this Product from the cart ?</p>
        </ConfirmationDialog>
      </div>
    </section>
  );
};

export default Cart;
