# XFF 전달 및 nginx 로그

Docker Compose의 `prod` BFF 경로에서 원래 호출자 IP를 전달하고 nginx 접근 로그에 기록한다.

```text
사용자 → nginx(storefront/backoffice) → BFF → nginx(api.yas.local) → API 서비스
```

외부 IP가 모든 요청에 존재하는 것은 아니다. 내부 작업에서 시작한 요청의 원래 호출자는 내부 주소다.

## 동작과 필드

입구 nginx는 사용자가 보낸 XFF·X-Real-IP를 실제 접속 주소로 교체하고, 대체 전달 헤더인 `Forwarded`를 제거한다. BFF는 기존 `SaveSession`을 유지하면서 HTTPS/로그인용 전달 헤더 처리를 수행한 뒤, nginx가 설정한 X-Real-IP를 XFF로 복원한다.

API nginx는 Docker의 `storefront`, `backoffice` 주소에서 온 XFF만 원래 호출자 주소로 인정한다. 다른 곳에서 API nginx로 직접 보낸 가짜 XFF는 호출자 주소를 바꾸지 못한다. API 서비스에는 확인한 주소 하나를 XFF와 X-Real-IP로 전달한다. 전체 프록시 체인을 누적하는 형식은 아니다.

접근 로그는 JSON Lines이며 `/var/log/nginx/access.log`에 기록한다. 공식 nginx Docker 이미지에서는 이 경로가 표준 출력에 연결되어 `docker compose logs nginx`로 볼 수 있다.

| 필드 | 의미 |
|---|---|
| `client_ip` | 해당 구간에서 확인한 원래 호출자 주소 |
| `peer_ip` | 실제로 연결한 바로 앞 주소. API 구간에서는 BFF 주소 |
| `x_forwarded_for` | 다음 서비스에 전달하는 정규화한 XFF 값 |
| `received_x_forwarded_for` | 정규화 전 수신 XFF. 사용자가 임의로 넣을 수 있으므로 신뢰 판정에 그대로 사용하지 않음 |
| `request_id`, `host`, `method`, `request_path`, `status`, `upstream` | 요청 및 라우팅 확인용 메타데이터 |

처음 들어오는 브라우저 요청에는 수신 XFF가 없어도 정상이다. nginx가 생성한 전달 값은 `x_forwarded_for`에 남는다. 쿠키·인증 헤더·쿼리 문자열·요청 본문은 이 로그 형식에 넣지 않는다.

## 적용

저장소 설정 변경만으로 이미 실행 중인 BFF 이미지가 바뀌지는 않는다. 수정한 소스로 두 BFF를 빌드하고 적용한다.

```sh
docker compose build storefront backoffice
docker compose up -d --no-deps storefront backoffice
# nginx template files are rendered only when its container starts.
docker compose up -d --no-deps --force-recreate nginx
docker compose exec nginx nginx -t
docker compose logs --tail=30 nginx
```

BFF만 나중에 재생성해서 Docker IP가 바뀐 경우 `docker compose exec nginx nginx -s reload`도 수행한다. `set_real_ip_from`의 호스트 이름은 설정을 읽을 때 주소로 해석된다. BFF 포트를 외부에 공개하지 않고, BFF의 직접 접근은 관리되는 내부 네트워크로 제한해야 한다.

Cloudflare Tunnel이나 다른 프록시를 nginx 앞에 추가하는 경우에는 해당 프록시만 신뢰하도록 **입구**의 real-IP 설정도 별도로 구성해야 한다. 기본 YAS 설정은 인터넷 전체의 XFF나 CF-Connecting-IP를 신뢰하지 않는다. PC1 전용 OpenResty/Lua 설정과 별개이며, 이 커밋이 PC1·PC4 서버를 자동 배포하지는 않는다.

검증 스크립트 `nginx/tests/verify_xff.py`는 실행 중인 서비스와 분리된 테스트 네트워크에서 nginx 구문, 두 BFF 입구의 위조 헤더 교체, 신뢰한 BFF 주소와 직접 호출 주소 구분, API로 전달한 헤더 및 JSON 로그를 확인한다. BFF는 헤더 전달을 흉내 낸 stub이므로 실제 Spring 앱 기동·로그인·세션 검증은 별도로 필요하다. Docker와 Python 3가 있는 Linux 호스트에서 실행한다.

```sh
python3 nginx/tests/verify_xff.py
```

참고: [nginx real-IP 모듈](https://nginx.org/en/docs/http/ngx_http_realip_module.html), [Spring 전달 헤더 처리](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/web/server/adapter/ForwardedHeaderTransformer.html).
