package com.yas.order.viewmodel.product;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Builder(toBuilder = true)
@Data
@NoArgsConstructor
@AllArgsConstructor(access = AccessLevel.PACKAGE)
public class ProductCheckoutListVm {
    Long id;
    String name;
    Double price;
    Long taxClassId;
}
