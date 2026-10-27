package com.yas.promotion.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.yas.promotion.config.ServiceUrlConfig;
import io.github.resilience4j.spring6.fallback.FallbackMethod;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

class StorefrontCouponProductServiceTest {
    private MockRestServiceServer server;
    private ProductService service;
    private static final String URL = "http://product.test/product";

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        service = new ProductService(builder.build(), new ServiceUrlConfig(URL));
    }

    @AfterEach
    void verify() {
        server.verify();
        SecurityContextHolder.clearContext();
    }

    @Test
    void readsPublishedProductWithoutAdminCredentialsAndDeserializesRealStorefrontShape() {
        server.expect(requestTo(URL + "/storefront/products/detail/1"))
                .andExpect(headerDoesNotExist("Authorization"))
                .andRespond(withSuccess("""
                    {"id":1,"name":"iPhone 15","price":799,"isPublished":true,
                     "isAllowedToOrder":true,"brandId":1,"taxClassId":1,
                     "categories":[{"id":2,"name":"Phone","children":[]}],
                     "thumbnail":{"id":7,"url":"https://images.test/7"}}
                    """, MediaType.APPLICATION_JSON));
        var products = service.getStorefrontProductsByIds(List.of(1L, 1L));
        assertThat(products).hasSize(1);
        assertThat(products.getFirst().price()).isEqualTo(799);
        assertThat(products.getFirst().categories().getFirst().id()).isEqualTo(2);
    }

    @Test
    void missingAndUnpublishedProductsCannotBecomeEligible() {
        server.expect(requestTo(URL + "/storefront/products/detail/1"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(requestTo(URL + "/storefront/products/detail/2"))
                .andRespond(withSuccess("""
                    {"id":2,"price":10,"isPublished":false,"isAllowedToOrder":true}
                    """, MediaType.APPLICATION_JSON));
        assertThat(service.getStorefrontProductsByIds(List.of(1L, 2L))).isEmpty();
    }

    @Test
    void dependencyForbiddenIsNotConvertedIntoAnEmptyProductList() {
        server.expect(requestTo(URL + "/storefront/products/detail/1"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));
        assertThatThrownBy(() -> service.getStorefrontProductsByIds(List.of(1L)))
                .isInstanceOf(HttpClientErrorException.Forbidden.class);
    }

    @Test
    void invalidPriceResponseIsAServiceFailureRatherThanAFreeProduct() {
        server.expect(requestTo(URL + "/storefront/products/detail/1"))
                .andRespond(withSuccess("{\"id\":1,\"isPublished\":true}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> service.getStorefrontProductsByIds(List.of(1L)))
                .isInstanceOf(RestClientException.class);
    }

    @Test
    void fallbackMatchesEveryListReturningMethodAndPreservesTheOriginalFailure() throws Throwable {
        var failure = new RestClientException("dependency unavailable");
        for (String name : List.of("getStorefrontProductsByIds", "getProductByIds", "getCategoryByIds",
                "getBrandByIds", "getProductByCategoryIds", "getProductByBrandIds")) {
            var method = ProductService.class.getMethod(name, List.class);
            var fallback = FallbackMethod.create("handleFallback", method,
                    new Object[] {List.of(1L)}, service, service);
            assertThatThrownBy(() -> fallback.fallback(failure)).isSameAs(failure);
        }
    }
}
