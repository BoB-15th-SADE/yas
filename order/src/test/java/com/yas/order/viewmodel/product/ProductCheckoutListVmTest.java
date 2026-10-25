package com.yas.order.viewmodel.product;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ProductCheckoutListVmTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void deserializesProductCheckoutResponse() {
        String response = """
                {"productCheckoutListVms":[
                    {"id":7,"name":"Checkout product","price":125.5,"taxClassId":2},
                    {"id":8,"name":"No tax product","price":0.0,"taxClassId":null}
                ],"pageNo":0,"pageSize":2,"totalElements":2,"totalPages":1,"isLast":true}
                """;

        ProductGetCheckoutListVm result = mapper.readValue(response, ProductGetCheckoutListVm.class);

        assertEquals(2, result.productCheckoutListVms().size());
        assertEquals(2, result.totalElements());
        assertTrue(result.isLast());
        ProductCheckoutListVm product = result.productCheckoutListVms().getFirst();
        assertEquals(Long.valueOf(7), product.getId());
        assertEquals("Checkout product", product.getName());
        assertEquals(Double.valueOf(125.5), product.getPrice());
        assertEquals(Long.valueOf(2), product.getTaxClassId());
        assertNull(result.productCheckoutListVms().get(1).getTaxClassId());
    }

    @Test
    void preservesBuilderAndJsonFields() {
        ProductCheckoutListVm product = ProductCheckoutListVm.builder()
                .id(7L)
                .name("Checkout product")
                .price(125.5)
                .taxClassId(2L)
                .build();

        ProductCheckoutListVm copy = product.toBuilder().name("Updated product").build();
        var json = mapper.valueToTree(copy);

        assertEquals(4, json.size());
        assertEquals(7L, json.get("id").longValue());
        assertEquals("Updated product", json.get("name").asString());
        assertEquals(125.5, json.get("price").doubleValue());
        assertEquals(2L, json.get("taxClassId").longValue());
        assertEquals(copy, mapper.treeToValue(json, ProductCheckoutListVm.class));
    }
}
