package com.yas.promotion.viewmodel;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** Only the published storefront fields needed to verify a shopper's coupon. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StorefrontProductVm(Long id, Double price, Boolean isPublished,
                                  Boolean isAllowedToOrder, Long brandId,
                                  List<CategoryRef> categories) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CategoryRef(Long id) {
    }
}
