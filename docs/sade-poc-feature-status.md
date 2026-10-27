# SADE PoC 기능·소스·이미지 점검 — 2026-10-27

점검 기준: `sade-poc@8cef400e3298f246e004a30acbe2fedb09e21908`. PC1 실제 적용 소스와 비교한 프런트엔드20개, promotion10개, XFF5개 파일이 모두 Git blob 내용 기준으로 일치했다. 쿠폰 서버 PR#1도 병합됐다. 아래의 소스 반영과 실제 업무 성공 여부는 서로 다른 항목이다.

| 개선 | YAS 소스 | 실제 확인 / 한계 |
| --- | --- | --- |
| 상품 검색·제안·필터·가격 정렬·페이지 | storefront/modules/search, pages/search | 기존 공개 product API를 쓰는 소규모 검색. iPhone4개/Galaxy3개/빈 결과 확인. Elasticsearch 전체 검색 인프라를 추가한 것은 아님 |
| 검색 화면 반복 로딩 | SearchSort.tsx | mount effect 제거, 사용자 정렬 변경 때만 상태 갱신. 실제 React mount/remount 회귀와 공개 화면 유지 확인 |
| 주소 국가·시/도·구 선택·저장 UX | AddressForm, country/district/state 서비스, pages/address/create | 잘못된 지역 조회·상위 변경 시 하위 선택·오류/로딩 표시·중복 제출 보완. 실제 주소 Create 저장 성공은 별도 미검증 |
| 장바구니·쿠폰 대상 상품 가격 | CartItem, pages/cart | 실제 로그인 장바구니 조회·상품 선택과 쿠폰 계산 확인. 상품 선택 변경 시 할인 초기화 |
| 쿠폰 응답 검증·오류 구분 | CouponResult, PromotionService | 인증/잘못된 쿠폰/서버 오류를 구분하고 잘못된 응답을 할인 성공으로 처리하지 않음 |
| 쿠폰 서버 상품 조회403·fallback | promotion | 고객 토큰으로 관리자 전용 상품 API를 부르던 경로를 공개 상품 조회로 변경. ADMIN 권한 설정 유지. 실제 SADE10 API3회200, $799 → $79.90 할인 → $719.10. 사용 횟수0 유지 |
| 체크아웃 상품 DTO 오류 | order/.../ProductCheckoutListVm.java | NoArgsConstructor/AllArgsConstructor 포함. 이전 체크아웃 생성500의 역직렬화 오류 수정 소스 존재. 주문·결제 전체 완료 검증을 의미하지 않음 |
| payment 빈 DB 초기화 오류 | payment/.../db/changelog/data/changelog-0001/0002-provider.sql | 두 INSERT는 enabled 컬럼 사용. 기존 DB의 Liquibase 경로/기록 조정은 운영 DB 작업이며 이미지 빌드가 재실행하지 않음 |
| BFF XFF 전달 | storefront-bff/backoffice-bff application-prod.yaml, nginx 설정, docs/xff-logging.md | YAS 설정 반영. lab OpenResty/Lua와 신뢰 경계 설정도 필요하며 Java 업무 소스 변경으로 구현한 것은 아님 |
| 공개 HTTPS 로그인·리다이렉트 | 실행 설정과 Keycloak 클라이언트 설정 | 공개 issuer/redirect URI/Cloudflare Tunnel/DNS는 배포·인증 설정. 소스 이미지 하나만 받는 것으로 복구되지 않음 |
| trace ID·OTel Java agent | lab common/yas-java.yaml 및 collector | agent jar 마운트와 JAVA_TOOL_OPTIONS/OTEL 설정을 lab에서 적용. 이미지에 데이터를 넣는 작업과 별개 |

## 데이터와 미완료 기능

- 한국17개 시·도/230개 하위 지역, 상품14개·브랜드4개·분류9개, SADE10/대상 상품 관계는 DB 데이터다. 새 이미지에 사용자 데이터나 DB 덤프를 포함하지 않는다.
- SADE10 적용은 검증했으나 관리자 화면에서 새 쿠폰 생성은 별도 미검증이다.
- 장바구니와 쿠폰이 성공해도 주문·결제 전체 완료가 검증된 것은 아니다.
- COD는 storefront 체크아웃에서 `COD payment feature is under construction`을 표시하는 미구현 경로다. 이전 COD 추가 개발은 취소됐으며 이번 작업에도 추가하지 않는다.
- PayPal 실제 승인/취소 흐름은 검증하지 않았다.
- 이전 API 점검에서 비로그인 order 구매완료 조회500과 없는 media 파일500을 발견했다. 이번 검색/쿠폰 수정이 이 두 문제까지 해결했다는 근거는 없다.

## 이미지로 배포되는 경로

기존 `sade-poc-images`는 order/payment만 빌드했다. 이제 storefront, promotion, storefront-bff, backoffice-bff도 대상에 포함하고 다음을 지킨다.

1. Java 서비스는 JDK25 Maven 단위 테스트 후 빌드한다. storefront Dockerfile은 검색·쿠폰·React 렌더 회귀와 Next.js 빌드를 실행한다.
2. GHCR에 commit tag와 sade-poc branch tag를 게시한다.
3. 각 빌드 job은 실제 push 결과의 digest와 source_revision을 `image-manifest-<service>` artifact에 기록한다.
4. `sade-poc-lab`은 성공한 job과 실제 pull 확인 결과의 digest로 compose/images.lock을 갱신한다. 실패·미게시 이미지의 태그나 digest를 추측해 기록하지 않는다.
5. 이미지 게시, lab 저장소 이미지 지정, 실제 VM 컨테이너 교체를 구분해서 보고한다. 다른 PC 컨테이너를 이 작업만으로 재시작하지 않는다.

게시 이름 참고: 기존 upstream 미러 yas-storefront/promotion/BFF 패키지는 별도의 접근 권한을 사용한다. fork 빌드는 yas-poc-storefront, yas-poc-promotion, yas-poc-storefront-bff, yas-poc-backoffice-bff에 게시하고 order/payment는 기존 repository-owned 이름을 유지한다. 미러 패키지 권한이나 가시성을 임의로 변경하지 않는다.
