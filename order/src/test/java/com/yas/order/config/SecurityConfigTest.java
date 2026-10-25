package com.yas.order.config;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.yas.order.controller.OrderController;
import com.yas.order.service.OrderService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@WebMvcTest(OrderController.class)
@Import({SecurityConfig.class, SecurityConfigTest.PublicStorefrontController.class})
class SecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrderService orderService;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void myOrdersWithoutAuthenticationReturnsUnauthorizedBeforeCallingService() throws Exception {
        mockMvc.perform(get("/storefront/orders/my-orders").param("productName", ""))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(orderService);
    }

    @Test
    void myOrdersWithValidBearerTokenReturnsOrders() throws Exception {
        Instant now = Instant.now();
        Jwt jwt = Jwt.withTokenValue("valid-test-token")
                .header("alg", "RS256")
                .subject("test-customer")
                .issuedAt(now.minusSeconds(30))
                .expiresAt(now.plusSeconds(60))
                .claim("realm_access", Map.of("roles", List.of("CUSTOMER")))
                .build();
        when(jwtDecoder.decode("valid-test-token")).thenReturn(jwt);
        when(orderService.getMyOrders("", null)).thenReturn(List.of());

        mockMvc.perform(get("/storefront/orders/my-orders")
                        .param("productName", "")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer valid-test-token"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));

        verify(orderService).getMyOrders("", null);
    }

    @Test
    void otherStorefrontEndpointsRemainAccessibleWithoutAuthentication() throws Exception {
        mockMvc.perform(get("/storefront/public-security-probe"))
                .andExpect(status().isOk())
                .andExpect(content().string("public"));

        verifyNoInteractions(orderService);
    }

    @RestController
    static class PublicStorefrontController {
        @GetMapping("/storefront/public-security-probe")
        String publicEndpoint() {
            return "public";
        }
    }
}
