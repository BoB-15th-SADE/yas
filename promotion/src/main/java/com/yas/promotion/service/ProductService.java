package com.yas.promotion.service;

import com.yas.promotion.config.ServiceUrlConfig;
import com.yas.promotion.utils.AuthenticationUtils;
import com.yas.promotion.viewmodel.BrandVm;
import com.yas.promotion.viewmodel.CategoryGetVm;
import com.yas.promotion.viewmodel.ProductVm;
import com.yas.promotion.viewmodel.StorefrontProductVm;
import com.yas.commonlibrary.exception.BadRequestException;
import com.yas.promotion.utils.Constants;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import java.net.URI;
import java.util.List;
import java.util.ArrayList;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.util.UriComponentsBuilder;

@Service
@Transactional
@RequiredArgsConstructor
public class ProductService extends AbstractCircuitBreakFallbackHandler {
    private final RestClient restClient;
    private final ServiceUrlConfig serviceUrlConfig;

    @Retry(name = "restApi")
    @CircuitBreaker(name = "restCircuitBreaker", fallbackMethod = "handleFallback")
    public List<StorefrontProductVm> getStorefrontProductsByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty() || ids.size() > 100
                || ids.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new BadRequestException(Constants.ErrorCode.INVALID_PRODUCT_IDS);
        }
        List<StorefrontProductVm> products = new ArrayList<>();
        for (Long id : ids.stream().distinct().toList()) {
            URI url = UriComponentsBuilder.fromUriString(serviceUrlConfig.product())
                    .path("/storefront/products/detail/{productId}")
                    .buildAndExpand(id).toUri();
            try {
                // This is a public, published-product API. Do not relay the user's JWT
                // to an ADMIN-only endpoint to decide whether a shopper gets a discount.
                StorefrontProductVm product = restClient.get().uri(url).retrieve()
                        .body(StorefrontProductVm.class);
                if (product == null || !id.equals(product.id()) || product.price() == null
                        || !Double.isFinite(product.price()) || product.price() < 0) {
                    throw new RestClientException("Invalid storefront product response");
                }
                if (Boolean.TRUE.equals(product.isPublished())
                        && Boolean.TRUE.equals(product.isAllowedToOrder())) {
                    products.add(product);
                }
            } catch (HttpClientErrorException.NotFound missing) {
                // Missing/unpublished products cannot make a coupon eligible.
            }
        }
        return products;
    }

    @Retry(name = "restApi")
    @CircuitBreaker(name = "restCircuitBreaker", fallbackMethod = "handleFallback")
    public List<ProductVm> getProductByIds(List<Long> ids) {
        String jwt = AuthenticationUtils.extractJwt();
        final URI url = UriComponentsBuilder
            .fromUriString(serviceUrlConfig.product())
            .path("/backoffice/products/by-ids")
            .queryParams(createIdParams(ids))
            .build()
            .toUri();

        return restClient.get()
            .uri(url)
            .headers(h -> h.setBearerAuth(jwt))
            .retrieve()
            .toEntity(new ParameterizedTypeReference<List<ProductVm>>() {
            })
            .getBody();
    }

    @Retry(name = "restApi")
    @CircuitBreaker(name = "restCircuitBreaker", fallbackMethod = "handleFallback")
    public List<CategoryGetVm> getCategoryByIds(List<Long> ids) {
        String jwt = AuthenticationUtils.extractJwt();
        final URI url = UriComponentsBuilder
            .fromUriString(serviceUrlConfig.product())
            .path("/backoffice/categories/by-ids")
            .queryParams(createIdParams(ids))
            .build()
            .toUri();
        return restClient.get()
            .uri(url)
            .headers(h -> h.setBearerAuth(jwt))
            .retrieve()
            .toEntity(new ParameterizedTypeReference<List<CategoryGetVm>>() {
            })
            .getBody();
    }

    @Retry(name = "restApi")
    @CircuitBreaker(name = "restCircuitBreaker", fallbackMethod = "handleFallback")
    public List<BrandVm> getBrandByIds(List<Long> ids) {
        String jwt = AuthenticationUtils.extractJwt();
        final URI url = UriComponentsBuilder
            .fromUriString(serviceUrlConfig.product())
            .path("/backoffice/brands/by-ids")
            .queryParams(createIdParams(ids))
            .build()
            .toUri();
        return restClient.get()
            .uri(url)
            .headers(h -> h.setBearerAuth(jwt))
            .retrieve()
            .toEntity(new ParameterizedTypeReference<List<BrandVm>>() {
            })
            .getBody();
    }

    @Retry(name = "restApi")
    @CircuitBreaker(name = "restCircuitBreaker", fallbackMethod = "handleFallback")
    public List<ProductVm> getProductByCategoryIds(List<Long> categoryIds) {
        String jwt = AuthenticationUtils.extractJwt();
        final URI url = UriComponentsBuilder
            .fromUriString(serviceUrlConfig.product())
            .path("/backoffice/products/by-categories")
            .queryParams(createIdParams(categoryIds))
            .build()
            .toUri();
        return restClient.get()
            .uri(url)
            .headers(h -> h.setBearerAuth(jwt))
            .retrieve()
            .toEntity(new ParameterizedTypeReference<List<ProductVm>>() {
            })
            .getBody();
    }

    @Retry(name = "restApi")
    @CircuitBreaker(name = "restCircuitBreaker", fallbackMethod = "handleFallback")
    public List<ProductVm> getProductByBrandIds(List<Long> brandIds) {
        String jwt = AuthenticationUtils.extractJwt();
        final URI url = UriComponentsBuilder
            .fromUriString(serviceUrlConfig.product())
            .path("/backoffice/products/by-brands")
            .queryParams(createIdParams(brandIds))
            .build()
            .toUri();
        return restClient.get()
            .uri(url)
            .headers(h -> h.setBearerAuth(jwt))
            .retrieve()
            .toEntity(new ParameterizedTypeReference<List<ProductVm>>() {
            })
            .getBody();
    }

    private static MultiValueMap<String, String> createIdParams(List<Long> ids) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        ids.stream().map(Objects::toString).forEach(id -> params.add("ids", id));
        return params;
    }
}
