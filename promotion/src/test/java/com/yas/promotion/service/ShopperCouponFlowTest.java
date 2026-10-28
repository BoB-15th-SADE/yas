package com.yas.promotion.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.yas.promotion.PromotionApplication;
import com.yas.promotion.model.Promotion;
import com.yas.promotion.model.PromotionApply;
import com.yas.promotion.model.enumeration.ApplyTo;
import com.yas.promotion.model.enumeration.DiscountType;
import com.yas.promotion.model.enumeration.UsageType;
import com.yas.promotion.repository.PromotionRepository;
import com.yas.promotion.repository.PromotionUsageRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestClient;

@SpringBootTest(classes = PromotionApplication.class,
        properties = "yas.services.product=http://product.test/product")
@AutoConfigureMockMvc
@Import(ShopperCouponFlowTest.HttpFixture.class)
class ShopperCouponFlowTest {
    @Autowired private MockMvc mvc;
    @Autowired private PromotionRepository promotions;
    @Autowired private PromotionUsageRepository usages;
    @Autowired private ProductHttpFixture fixture;

    @TestConfiguration
    static class HttpFixture {
        @Bean
        ProductHttpFixture productHttpFixture() {
            var builder = RestClient.builder();
            var server = MockRestServiceServer.bindTo(builder).build();
            return new ProductHttpFixture(server, builder.build());
        }

        @Bean
        @Primary
        RestClient shopperCouponClient(ProductHttpFixture fixture) {
            return fixture.client();
        }
    }

    record ProductHttpFixture(MockRestServiceServer server, RestClient client) { }

    @BeforeEach
    void seed() {
        usages.deleteAll();
        promotions.deleteAll();
        fixture.server().reset();
        var coupon = Promotion.builder().name("Shopper test").slug("shopper-test").couponCode("WELCOME10")
                .applyTo(ApplyTo.PRODUCT).usageType(UsageType.UNLIMITED).usageLimit(0)
                .discountType(DiscountType.PERCENTAGE).discountPercentage(10L).discountAmount(0L)
                .minimumOrderPurchaseAmount(0L).isActive(true).startDate(Instant.now().minusSeconds(60))
                .endDate(Instant.now().plusSeconds(3600)).build();
        coupon.setPromotionApplies(List.of(PromotionApply.builder().promotion(coupon).productId(1L).build()));
        promotions.saveAndFlush(coupon);
    }

    @AfterEach
    void clean() {
        fixture.server().verify();
        usages.deleteAll();
        promotions.deleteAll();
    }

    @Test
    void ordinaryShopperCanVerifyCouponWithoutAnAdminRoleOrConsumingIt() throws Exception {
        expectProduct(1, 799);
        expectProduct(2, 100); // cheaper but outside this coupon's eligibility
        mvc.perform(post("/storefront/promotions/verify").with(csrf())
                .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"couponCode\":\"WELCOME10\",\"orderPrice\":899,\"productIds\":[1,2]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.isValid").value(true))
                .andExpect(jsonPath("$.productId").value(1))
                .andExpect(jsonPath("$.discountValue").value(10));
        assertThat(usages.count()).isZero();
        assertThat(promotions.findByCouponCodeAndIsActiveTrue("WELCOME10").orElseThrow().getUsageCount()).isZero();
    }

    private void expectProduct(long id, int price) {
        fixture.server().expect(requestTo("http://product.test/product/storefront/products/detail/" + id))
                .andExpect(headerDoesNotExist("Authorization"))
                .andRespond(withSuccess("{\"id\":" + id + ",\"price\":" + price
                        + ",\"isPublished\":true,\"isAllowedToOrder\":true,\"brandId\":1,\"categories\":[]}",
                        MediaType.APPLICATION_JSON));
    }
}
