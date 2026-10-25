# OpenAPI documents of the running services

One file per service, as each running YAS service publishes it (springdoc, `/<service>/v3/api-docs`):
`product.json`, `media.json`, `customer.json`, `cart.json`, `rating.json`, `order.json`, `payment.json`,
`promotion.json`, `tax.json`, `inventory.json`, `location.json`.

They are collected in the SADE PoC lab on PC1 with `scripts/collect-specs.sh` of
[BoB-15th-SADE/sade-poc-lab](https://github.com/BoB-15th-SADE/sade-poc-lab) (read straight from each service,
not through NGINX) and committed here, so SADE can compare **spec · code · logs** for the same services.

Each document describes the API as the service itself publishes it, including the context path
(`/product`, `/order`, ...) in its `servers` entry.
