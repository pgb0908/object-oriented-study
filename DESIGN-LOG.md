# 설계 로그

매일 **코드를 고치기 전에** 그날의 중심 질문에 답을 쓴다. 양식은 [PLAN.md](./PLAN.md) 맨 아래 참고.

---

## Day 0 — 환자 만들기 (완료)

`legacy/ApiGateway.handle()` 하나에 게이트웨이의 전 과정이 들어있다.
실행해서 동작을 먼저 확인한다. **이 동작이 7일간 리팩터링의 기준이다. 동작이 바뀌면 리팩터링이 아니다.**

```bash
mvn -q compile
java -cp target/classes gateway.legacy.LegacyDemo
```

확인되는 동작 9가지: 인증 없는 라우트 통과 / 정상 프록시 / 라운드로빈 분산 /
키 누락 401 / 라우트 없음 404 / 503 두 번 후 재시도 성공 / rps 제한 내 2건 통과 / rps 초과 429.

### 이 코드에 대해 지금 알고 있는 것

- `handle()` 한 메서드가 라우팅·인증·레이트리밋·로드밸런싱·재시도·응답변환·감사로그를 모두 수행한다.
- 요청의 상태는 `Map<String, Object> exchange`에 문자열 키로 담겨 있고, 상태 전이는
  `exchange.put("state", "RETRYING")` 처럼 외부에서 문자열을 꽂아 이뤄진다.
- 라우트 설정은 `ROUTE_CONFIG` 문자열이며, 요청마다 `handle()` 안에서 다시 파싱된다.
- 경로 매칭 로직이 `handle()` 안에 두 번 등장한다. (인증 설정을 찾을 때, 엔드포인트를 찾을 때)
- 레이트리밋 버킷과 라운드로빈 인덱스가 `static` 필드다.
- `System.currentTimeMillis()`와 `Thread.sleep(100)`을 직접 호출한다.
- `new LegacyHttpTransport()`를 메서드 안에서 직접 생성한다.

### Day 0 시점의 측정값 (Day 7에 다시 잰다)

| 지표 | 값 |
|---|---|
| 프로덕션 클래스 수 | 2 (`ApiGateway`, `LegacyHttpTransport`) |
| 가장 긴 메서드 | `handle()` — 약 190줄 |
| 단위 테스트 수 | 0 |
| 테스트를 실행하려면 | 실제 HTTP 서버 3개를 띄워야 한다 |

마지막 줄이 Day 6에서 다룰 문제다. **지금은 게이트웨이의 어떤 규칙도 단독으로 검증할 수 없다.**

---

## Day 1 — 의미 있는 개념을 찾는다

### 할 일
`handle()`을 읽으며 등장하는 명사를 **전부** 아래에 적는다. 판단하지 말고 먼저 다 적는다.
그 다음 각각에 대해 두 질문으로 판정한다.

- 지속적인 **상태**를 갖는가?
- 스스로 지켜야 할 **규칙**이 있는가?

둘 다 "아니오"면 클래스가 아니다. 탈락시키고 **이유를 쓴다.**

근거 칸의 숫자는 `ApiGateway.java`의 줄 번호다.

| 명사 | 상태를 갖는가 | 규칙이 있는가 | 판정 | 근거 |
|---|---|---|---|---|
| ROUTE | O (접두사, 엔드포인트 목록, 설정 묶음) | O ("이 경로가 내 것인가") | **생존** → `Route` | 같은 설정 줄을 찾는 루프가 44행, 94행에 두 번 나온다. 한 세트로 다니는 값들인데 이름이 없어서 매번 다시 찾는다. |
| auth | 약함 (`apikey`/`none`) | O ("이 요청을 허용하는가") | **생존** → `AuthPolicy` | 67~84행에 허용 여부 판단이 있다. 보안 요구가 바뀔 때만 바뀐다. `API_KEYS`(16행)는 이 개념이 가진 데이터다. |
| retry | 약함 (`3`) | O (대상 상태코드, 최대 횟수, 대기 시간) | **생존** → `RetryPolicy` | 169~175행에 재시도 규칙 3개가 있다. 업스트림 장애 양상이 바뀔 때만 바뀐다. |
| exchange | O (`state`, `retryCount`, `authUser`, `endpoint`) | O (상태 전이 규칙) | **생존** → `GatewayExchange` | 30~37행에서 `Map`에 문자열 키로 흩어져 있다. 규칙이 없는 게 아니라 규칙이 객체 밖(`handle()`)에 있다. 59, 70, 78, 153, 171, 185, 190행에서 외부가 `put("state", ...)`로 전이시킨다. Day 2의 대상이다. |
| rps | O (`rateLimitBuckets`의 시간 창) | O ("이 요청을 허용하는가") | **생존** → `RateLimitPolicy` | 118~130행에서 최근 1초의 요청 시각을 세어 `kept.size() >= rps`면 거부한다. `retry`와 같은 구조다. 토큰 `rps=5`는 숫자지만 그 뒤 코드에 판단과 상태가 있다. |
| roundRobinIndex | O (`idx`) | O ("다음 엔드포인트를 고른다") | **생존** → `LoadBalancer` | 146~151행의 `idx % endpoints.size()`가 선택 판단이다. 선택 방식(라운드로빈, 가중치 등)이 바뀔 이유가 독립적으로 있다. |
| endpoint | 현재는 X (`String`) | 현재는 X | **생존(조건부)** → `Endpoint` | 지금 코드만 보면 값이다. 그러나 Day 6 요구사항 "5회 연속 실패한 엔드포인트는 30초간 제외"가 들어오면 연속 실패 횟수와 제외 시각이라는 상태가 붙는다. 변경이 올 방향을 보고 미리 타입을 준다. |
| API_KEYS | O | X | **탈락 (흡수)** | 16~20행. 독립 개념이 아니라 `AuthPolicy`가 가진 데이터다. 키가 어디에 저장되든 판단하는 쪽은 `AuthPolicy`이므로 따로 클래스로 올릴 이유가 없다. |
| LegacyHttpTransport | **X** (필드가 하나도 없음) | 약함 (타임아웃 값) | **생존** → `UpstreamClient` | 두 질문에는 맞지 않는다. 필요한 이유는 상태나 규칙이 아니라 **외부 시스템과의 경계**다. HTTP를 gRPC로 바꿀 수 있어야 한다는 변경 가능성이 근거다. 155행에서 `new`로 직접 생성하고 있어서 바꿀 수 없다. 이 판정은 오늘의 두 질문이 못 잡아내는 종류의 개념이라는 한계의 사례다. |
| response | X | X | **탈락** | 처리 결과로 지나가는 값이다. 요청 하나가 끝나면 의미가 없어진다. |
| auditLog | O (쌓이는 목록) | O ("무엇을 기록하고 무엇을 가릴지") | **생존** → `AuditLog` (보류에서 변경) | 63, 74, 135, 218행 네 곳이 각자 `add`하고 형식이 제각각이다(실패는 짧은 형식, 성공은 상세 형식). 74행은 요청으로 들어온 API 키 원문을 그대로 기록한다. "무엇을 가릴지"라는 규칙이 이미 필요한데 아무도 소유하지 않는다. 읽는 사람이 운영자라서 클라이언트 쪽 `ResponsePolicy`와 변경 이유가 다르다. |
| 여러 Route 중 선택 (`ROUTE_CONFIG` 전체의 순서) | O (Route 목록) | O ("여러 Route 중 어느 것이 이 요청을 맡는가") | **생존** → `Router` | 44~54행, 94~111행의 `break`로 "첫 번째로 일치하는 줄이 이긴다"는 규칙이 암묵적으로 들어 있다. `/users`와 `/users/admin`이 둘 다 있으면 줄 순서가 결과를 바꾼다. 개별 `Route`는 자기 자신만 알아서 이 판단을 할 수 없다. `handle()`에 두면 조정자가 도메인 규칙을 갖게 된다. |
| totalRequests, totalFailures | O (카운터) | X | **탈락 (흡수)** | 60, 71, 84, 132, 186행에서 `state = FAILED`로 바꾸는 줄 바로 옆에서 `totalFailures++`를 한다. 같은 사실("실패했다")을 두 번 기록하는 중복이다. 실패 여부는 `GatewayExchange`의 최종 상태가 이미 답한다. 카운터 자체는 판단이 없는 파생 데이터다. |

**요약**: 생존 10개(`Route`, `Router`, `AuthPolicy`, `RetryPolicy`, `GatewayExchange`, `RateLimitPolicy`, `LoadBalancer`, `UpstreamClient`, `ResponsePolicy`, `AuditLog`), 조건부 1개(`Endpoint`), 탈락 3개(`API_KEYS`와 카운터는 흡수, `response`는 값). `ResponsePolicy`는 변경 시험에서, `Router`는 404 판정에서 발견했다.

### 책임 배분표
살아남은 개념마다 한 문장. 「이 객체는 ___을 알고 있고, ___을 결정한다」

- "알고 있다" = 그 개념이 소유하는 상태와 지식
- "결정한다" = 그 개념이 내리는 판단 (예/아니오, 선택). "처리한다", "관리한다"는 쓰지 않는다.
- 문장에 "그리고"가 들어가면 책임이 둘이라는 신호다.

| 개념 | 알고 있는 것 | 결정하는 것 (질문 형태) | 근거 코드 (줄 번호) |
|---|---|---|---|
| `Route` | 경로 접두사, 엔드포인트 목록, 이 경로에 연결된 정책들(어떤 정책을 쓰는지만 알고 정책의 규칙은 모른다) | 이 요청 경로가 내 것인가? | 44~56, 94~113 |
| `AuthPolicy` | 인증 방식(`apikey`/`none`), 인증 헤더 이름, 키와 사용자의 대응표 | 이 요청을 허용하는가? 허용한다면 누구인가? | 67~88, 16~20 |
| `RetryPolicy` | 재시도 대상 상태코드, 최대 횟수, 대기 시간 | 이 응답을 재시도할지 | 169~175 |
| `GatewayExchange` | 요청 ID, 메서드, 경로, 현재 상태, 재시도 횟수, 인증된 사용자, 선택된 엔드포인트, 시작 시각, 실패했다면 그 사유(지금 코드에는 없고 응답 코드로만 흩어져 있다) | 현재 상태에서 이 상태 전이가 허용되는가? (예: 완료된 교환은 재시도할 수 없다) | 30~37, 114, 152 (전이 시도는 59, 70, 78, 131, 153, 171, 185, 190에 흩어져 있고, 허용 여부를 검사하는 코드는 없다) |
| `RateLimitPolicy` | 초당 허용 건수(`rps`), 시간 창(1초), (사용자, 라우트)별 최근 요청 시각들 | 이 요청을 지금 허용하는가? | 116~139 |
| `LoadBalancer` | 라우트별 순번(`idx`), 후보 엔드포인트 목록 | 다음에 호출할 엔드포인트는 무엇인가? | 146~151 |
| `UpstreamClient` | 연결·읽기 타임아웃, 업스트림과 통신하는 방법 | 통신이 성공했는가, 실패했는가? (전송 실패와 업스트림이 돌려준 오류 응답을 구분) | 155~167, `LegacyHttpTransport` 전체 |
| `ResponsePolicy` | 클라이언트에게 노출하지 않을 응답 헤더(`Server`, `Set-Cookie`, `Transfer-Encoding`), 덧붙일 헤더(`X-Request-Id`, `X-Upstream`), 본문 최대 크기(10000자), 실패 사유별로 보여줄 응답 형태(404, 401, 429, 502, 500) | 응답을 클라이언트에게 어떤 형태로 내보낼 것인가? (이 헤더를 내보내도 되는가? 이 본문을 잘라야 하는가? 이 본문을 압축해서 내보내는가? 이 실패 사유는 어떤 응답으로 보여줄 것인가?) | 58~65, 69~75, 82~87, 130~137, 184~199, 201~215 |
| `Router` | 이 게이트웨이의 Route 목록과 그 순서 | 여러 Route 중 어느 것이 이 요청을 맡는가? (맡을 Route가 없다는 것도 하나의 답이다) | 44~54, 94~111 |
| `AuditLog` | 기록된 항목들, 기록 형식, 가려야 할 값(인증 키, 인증 헤더) | 이 교환에서 무엇을 기록하고 무엇을 가릴 것인가? | 63, 74, 135, 218~225 |
| `Endpoint` (조건부) | 주소(호스트와 포트) | 지금은 없음 (값). 조건부 생존이므로 "지금 호출 가능한가?"는 Day 6 요구사항이 올 때 생긴다 | 100~102, 150 |

#### 겹침 점검
두 개념의 "결정하는 것"에 같은 판단이 적혀 있지 않은지 확인한다. 겹치면 누구 것인지 정하고 이유를 쓴다.

1. **재시도 횟수: `RetryPolicy` vs `GatewayExchange`**
   170행은 "최대 횟수를 넘었는가"를 판단하고, 횟수 자체는 172~173행에서 `exchange`가 센다. 게다가 141행의 지역 변수 `attempt`와 `retryCount`가 같은 것을 두 번 센다.
   정리: **횟수(상태)는 `GatewayExchange`가 소유하고, `RetryPolicy`는 횟수를 입력으로 받아 판단만 한다.** 규칙은 정책에, 상태는 교환에 둔다.
2. **인증된 사용자: `AuthPolicy` vs `RateLimitPolicy`**
   `authUser`는 `AuthPolicy`가 정하지만(77, 80행) `RateLimitPolicy`가 버킷 키로 쓴다(116행).
   정리: 인증 결과는 `GatewayExchange`에 기록되고, `RateLimitPolicy`는 읽기만 한다. 의존 방향이 `RateLimitPolicy → GatewayExchange`로 정해진다.
3. **통신 실패의 표현: `UpstreamClient` vs `RetryPolicy`**
   159~164행에서 `handle()`이 예외를 상태코드 599로 바꿔 만들고, 169행의 재시도 규칙이 그 599를 안다. 한 개념이 만든 암호를 다른 개념이 알고 있다.
   정리: 통신 실패의 의미는 `UpstreamClient`가 정한 어휘로 통일하고 `RetryPolicy`가 599라는 숫자를 몰라도 되게 한다 (Day 4 대상).
4. **경로 매칭: 두 번 중복**
   44행과 94행. `Route`만 "내 것인가"를 판단하는 것으로 정리하면 해소된다.
5. **헤더 거르기: `ResponsePolicy` vs `UpstreamClient`**
   `Transfer-Encoding`을 거르는 것(207~208행)은 HTTP 전송 방식의 사정이라 `UpstreamClient` 쪽 일처럼 보이고, `Server`, `Set-Cookie`를 거르는 것은 클라이언트에게 무엇을 보여줄지의 정책이다. 한 `if`에 두 종류의 이유가 섞여 있다.
   정리: 전송 방식 때문에 필요한 것은 `UpstreamClient`가 응답을 넘기기 전에 정리하고, 노출 정책은 `ResponsePolicy`가 정한다 (Day 4에서 다시 본다).
6. **실패 사유: 거부한 쪽 vs `ResponsePolicy`**
   401(72행)은 인증 쪽 지식이고 429(133행)는 속도 제한 쪽 지식인데, 상태코드 숫자와 JSON 본문을 `handle()`이 직접 조립한다.
   정리: 거부한 쪽은 **사유**(인증 실패, 한도 초과, 경로 없음, 업스트림 장애)만 `GatewayExchange`에 남긴다. 그 사유를 어떤 HTTP 응답으로 보일지는 `ResponsePolicy`가 정한다. `AuthPolicy`가 401이라는 숫자를 알 이유가 없고, 응답 규약이 바뀌는 이유와 인증 정책이 바뀌는 이유는 다르다.
7. **여러 Route 중 선택: `Route` vs `Router`**
   `Route`는 "나는 이 요청의 것인가?"만 답한다. "여러 개가 다 맞을 때 누가 이기는가"는 개별 `Route`가 알 수 없다. 이 판단을 `Router`가 소유한다. 또 "맞는 Route가 없다"(58행 404)도 `Router`의 답이다.
8. **감사 로그와 응답: `AuditLog` vs `ResponsePolicy`**
   둘 다 "무엇을 가릴지"를 다루지만 대상이 다르다. 클라이언트에게 보이는 것은 `ResponsePolicy`, 운영자가 보는 것은 `AuditLog`이다. 74행처럼 응답에는 안 나가는데 로그에는 인증 키가 남는 일이 이 둘을 같은 개념으로 묶으면 안 되는 증거다.

#### 표에 자리가 없는 책임 (귀속처 없음)
아래 판단과 동작은 어느 개념에도 넣을 수 없었다. 지금은 전부 `handle()`이 쥐고 있다.

네 항목 모두 귀속처를 정했다.

| 항목 | 귀속처 | 판정 |
|---|---|---|
| 실패를 어떤 HTTP 응답으로 만들지 (404, 401, 429, 502) | `ResponsePolicy` (사유는 `GatewayExchange`가 기록) | 기존 개념에 흡수. 겹침 점검 6번 |
| 업스트림 응답을 돌려줄 형태로 바꾸는 일 (헤더, 본문 절단) | `ResponsePolicy` | 변경 시험에서 새 개념으로 발견 |
| 감사 로그를 언제 무엇으로 남길지 | `AuditLog` | 보류에서 생존으로 변경. 겹침 점검 8번 |
| 총 요청 수, 실패 수 집계 | 독립 개념 아님 | 탈락. 파생 데이터이고 `GatewayExchange` 상태와 중복 |

이 과정에서 404(맞는 Route가 없다)를 누가 판단하는지 따지다가 `Router`도 발견했다.

### 변경 시험 — "응답 본문을 gzip으로 압축해 반환해야 한다"
이 책임은 어느 개념의 것인가? 표에 자리가 없다면 개념을 놓친 것이다.

**처음 답**: `UpstreamClient`이 가장 가깝다. → **틀림**. 표에 자리가 없다는 직감은 맞았다.

**왜 `UpstreamClient`가 아닌가**
- 방향이 다르다. `UpstreamClient`는 게이트웨이와 **업스트림** 사이를 담당하고, gzip 압축은 게이트웨이와 **클라이언트** 사이의 일이다. 압축 여부는 `Accept-Encoding` 같은 앞쪽 사정으로 정해진다.
- 변경 이유가 다르다. 압축을 `UpstreamClient`에 넣으면 Day 4의 `GrpcUpstreamClient`가 추가될 때 구현마다 압축 코드를 갖거나 공통 부모가 필요해진다. 통신 방식이 바뀔 때마다 압축 규칙이 따라 움직인다.

**정답: 개념을 놓쳤다.** 표에 자리가 없었다. 귀속처 없음 목록의 "응답을 돌려줄 형태로 바꾸는 일"이 이것이다. 헤더 거르기(201~212행), 본문 절단(195~197행)과 같은 이유로 바뀌는 규칙이므로 한 개념으로 묶어 `ResponsePolicy`를 추가했다.

**이름을 `ResponsePolicy`로 한 이유**: `AuthPolicy`, `RetryPolicy`, `RateLimitPolicy`와 같은 족속이다. "어떤 영역에 대한 규칙을 소유하고 판단하는 개념"이라는 뜻이 이름에 드러난다. `ResponseConverter`나 `ResponseHandler`는 하는 일을 이름으로 옮긴 것이라 쓰지 않았다.

**이제 이 요구사항이 들어오면**: `ResponsePolicy`에 "이 본문을 압축해서 내보내는가?"라는 판단이 하나 추가될 뿐이다. 다른 개념은 열지 않는다.

**남는 의심**: `ResponsePolicy`가 헤더, 크기, 압축 세 가지를 한꺼번에 품고 있다. 셋 다 "클라이언트에게 보이는 응답 형태"가 바뀔 때 바뀌므로 지금은 한 개념으로 둔다. 압축 규칙이 헤더 규칙과 따로 바뀌기 시작하면 그때 쪼갠다.
-

### 오늘 틀렸던 것

> 초안: Claude가 오늘 대화를 근거로 작성했다. 읽고 **내 말로 고쳐 쓴다.** 고치지 않은 항목은 내 것이 아니다.

1. **중복 코드를 보고 `RouteParser`를 골랐다.**
   경로 매칭 루프가 두 번 나오는 것을 "파싱하는 일"로 읽었다. 하는 일을 클래스 이름으로 옮긴 것이다.
   교훈: 중복된 코드를 보면 "함수로 뽑자"가 아니라 **"이 코드가 같이 다니는 데이터는 무엇인가"**를 묻는다. 정답은 `Route`였다.
2. **`exchange`의 규칙이 "없다"고 썼다.**
   규칙이 없는 게 아니라 객체 밖(`handle()`)에 흩어져 있는 것이었다. 허용 여부를 검사하는 코드가 없다는 사실이 곧 Day 2의 숙제다.
3. **탈락 근거 6개를 "단순 값만 갖는다"로 복사했다.**
   `retry=3`도 숫자 하나였는데 코드를 읽고 살렸다. `rps`, `roundRobinIndex`에는 같은 기준을 적용하지 않았다.
   교훈: 설정 문자열의 토큰이 아니라 **`handle()`의 코드**를 읽고 판정한다.
4. **`LegacyHttpTransport`에 "상태가 있다"고 썼다.**
   필드가 하나도 없었다. 확인하지 않고 썼다. 이 개념이 필요한 진짜 이유는 상태나 규칙이 아니라 **외부 시스템과의 경계**(교체 가능성)였다.
5. **책임 배분표에서 "결정하는 것"을 동작으로 썼다.**
   "분기 처리", "auth 처리", "제한"은 질문으로 바꿀 수 없다. `GatewayExchange` 칸에 `AuthPolicy`의 책임을 적었고, `RateLimitPolicy`는 "ratelimit 조건"이라는 순환 설명이었다.
   교훈: 결정은 **"~인가?"로 끝나는 질문**으로 쓴다. 근거 코드 줄 번호를 먼저 찾으면 막히지 않는다.
6. **gzip 변경 시험에서 `UpstreamClient`를 골랐다.**
   방향을 놓쳤다(업스트림 쪽이 아니라 클라이언트 쪽). 다만 "가장 가깝다"는 표현에서 표에 딱 맞는 자리가 없다는 감각은 정확했다. 그 감각이 `ResponsePolicy`를 발견하게 했다.

**공통 패턴**: 코드를 읽기 전에 이름과 인상으로 판정했다. 근거 칸에 줄 번호를 적으면 이 실수가 줄어든다.

**내 말로 한 줄**:
-

---

## Day 2 — 상태와 규칙의 소유권

### 중심 질문
> 이 **상태와 규칙을 누가 소유해야 하는가?**

### 질문에 대한 답 (코드 작성 전에 쓴다)

**1. 상태 목록과 전이표** — `handle()`에서 `exchange.put("state", ...)`가 나오는 모든 줄을 읽고 채운다. 줄 번호가 근거다.

| 이전 상태 | 다음 상태 | 언제 | 근거 코드 (줄 번호) |
|---|---|---|---|
| (생성) | `RECEIVED` | 요청이 들어와 교환이 만들어질 때 | 36 |
| `RECEIVED` | `FAILED` | 맞는 Route가 없다 (404) | 59 |
| `RECEIVED` | `FAILED` | 인증 실패 (401) | 70 |
| `RECEIVED` | `FAILED` | 알 수 없는 인증 설정 (500) | 83 |
| `RECEIVED` | `AUTHENTICATED` | API 키가 유효하다 | 78 |
| `RECEIVED` | `AUTHENTICATED` | 인증이 필요 없는 Route다 (`auth=none`) | 81 |
| `AUTHENTICATED` | `FAILED` | 속도 제한 초과 (429) | 131 |
| `AUTHENTICATED` | `CALLING` | 속도 제한을 통과해 첫 호출을 시작한다 | 153 |
| `CALLING` | `RETRYING` | 재시도 대상 응답이고 횟수가 남았다 | 171 |
| `RETRYING` | `CALLING` | 대기 후 `continue`로 루프 처음으로 돌아가 다음 호출을 시작한다 | 153 (같은 줄, 반복 진입) |
| `CALLING` | `FAILED` | 응답이 5xx이고 재시도가 끝났다 (502) | 185 |
| `CALLING` | `COMPLETED` | 응답이 5xx가 아니다 | 190 |

**표를 만들며 알게 된 것**

- 상태는 6개다: `RECEIVED`, `AUTHENTICATED`, `CALLING`, `RETRYING`, `COMPLETED`, `FAILED`.
- **종료 상태는 `COMPLETED`와 `FAILED`** 두 개다. 코드에서 이 둘에서 나가는 전이는 없다.
- `FAILED`로 가는 길이 **5개**이고 이전 상태는 `RECEIVED`(3곳), `AUTHENTICATED`, `CALLING` 세 종류다.
- `RETRYING`은 머무는 상태가 아니다. 171행에서 설정하고 곧바로(대기 100ms 후) 153행의 `CALLING`으로 돌아간다. 이 상태가 "있어야 하는 상태"인지는 불변식을 정할 때 다시 따져야 한다. → **결정: 제거한다** (아래 "결정과 근거").
- 153행은 두 곳에서 도달한다(`AUTHENTICATED`와 `RETRYING`에서). 같은 줄이 서로 다른 이전 상태를 가진다는 건, 줄 번호만으로는 전이를 정확히 읽을 수 없다는 뜻이다.
- 상태를 읽는 곳은 223행(로그) 한 곳뿐이다. **어디서도 "지금 이 전이가 허용되는가"를 확인하지 않는다.**
- 코드에는 없지만 있어야 할 것: `RECEIVED → CALLING`(인증과 속도 제한을 건너뜀), `COMPLETED → RETRYING`, `FAILED → 무엇이든`은 지금 코드상 우연히 일어나지 않을 뿐 막는 코드가 없다.

**설계 전이표 (`RETRYING` 제거 후)** — 위 표는 현재 코드의 기록이라 그대로 두고, 설계는 5개 상태 기준으로 다시 쓴다. 레거시의 171행(`CALLING→RETRYING`)과 153행(`RETRYING→CALLING`) 두 행은 `CALLING→CALLING` 하나로 합쳐진다.

| 이전 상태 | 다음 상태 | 호출 | 언제 |
|---|---|---|---|
| (생성) | `RECEIVED` | 생성자 | 요청이 들어온다 |
| `RECEIVED` | `AUTHENTICATED` | `authenticate(user)` | 인증을 통과했다 (API 키 유효, 또는 인증 불필요) |
| `RECEIVED` | `FAILED` | `fail(reason)` | 경로 없음, 인증 실패, 인증 설정 오류 |
| `AUTHENTICATED` | `CALLING` | `startCall(endpoint)` | 속도 제한을 통과해 첫 호출을 시작한다 |
| `AUTHENTICATED` | `FAILED` | `fail(reason)` | 속도 제한 초과 |
| `CALLING` | `CALLING` | `retryCall(endpoint)` | 재시도 대상 응답이고 횟수가 남아 다음 호출을 시작한다 |
| `CALLING` | `COMPLETED` | `complete()` | 응답이 5xx가 아니다 |
| `CALLING` | `FAILED` | `fail(reason)` | 5xx이고 재시도가 끝났다. 대기 중 중단도 여기에 해당한다 |

**2. 불변식** — `GatewayExchange`가 만들어진 순간부터 항상 참이어야 하는 것들. (예: 재시도 횟수는 0 이상)

불변식은 세 종류로 나눈다. (가) 생성 후 바뀌지 않는 값, (나) 상태와 연동된 값, (다) 변화 규칙. 근거는 코드 줄 번호나 전이표다.

**(가) 생성 후 바뀌지 않는 값**

| # | 불변식 | 근거 |
|---|---|---|
| A1 | `requestId`는 비어 있지 않고, 생성 후 바뀌지 않는다 | 31 |
| A2 | `method`, `path`는 null이 아니고, 생성 후 바뀌지 않는다 | 32~33 |
| A3 | `startedAt`은 생성 시각이고, 바뀌지 않는다 | 38 |
| A4 | 요청 원본(`headers`, `body`)은 생성 후 바깥에서 바꿔도 교환이 영향받지 않는다 | 34~35. 지금은 `Map` 참조를 그대로 들고 있어서 바깥에서 바꿀 수 있다 |

**(나) 상태와 연동된 값** — "언제부터 존재하는가"가 핵심이다.

| # | 불변식 | 근거 |
|---|---|---|
| B1 | `authUser`가 있다 ⟺ 교환이 `AUTHENTICATED`를 거쳤다. 값은 키가 아니라 **사용자 이름**(`alice`, `bob`, `anonymous`)이고, 정해진 뒤에는 바뀌지 않는다 | 77, 80 (각각 78, 81 직전). 59, 70, 83으로 `FAILED`가 된 교환에는 끝까지 없고, 131로 `FAILED`가 된 교환에는 있다 |
| B2 | `endpoint`가 있다 ⟺ 교환이 `CALLING`을 한 번이라도 거쳤다. B1과 달리 재시도마다 **바뀔 수 있다** | 152 (153 직전). 루프마다 150~151에서 다시 고른다 |
| B3 | `FAILED` ⟺ 실패 사유가 정확히 하나 있다. 그 외 상태에는 사유가 없다. 사유는 5개다: 경로 없음, 인증 실패, 인증 설정 오류, 속도 제한, 업스트림 장애 | 전이표의 `FAILED` 5행(59, 70, 83, 131, 185). Day 1 겹침 점검 6번. 지금 코드에는 사유 필드가 없고 응답 코드로만 흩어져 있다 |

**(다) 변화 규칙**

| # | 불변식 | 근거 |
|---|---|---|
| C1 | `state`는 항상 5개 중 하나이고, 설계 전이표의 **7가지 전이**로만 바뀐다 | 설계 전이표 (생성 행 제외). 같은 전이가 사유별로 여러 행이던 것을 묶었고, `RETRYING` 두 행은 `CALLING→CALLING`으로 합쳐졌다 |
| C2 | 종료 상태(`COMPLETED`, `FAILED`)에 들어간 뒤에는 어떤 필드도 바뀌지 않는다 | 전이표에서 이 둘에서 나가는 행이 없다. 190행 이후 코드는 `exchange`를 읽기만 한다 (213~225) |
| C3 | `retryCount`는 0에서 시작하고 `retryCall`로만 정확히 1 증가한다. 줄어들지 않는다 | 37, 172 |
| C4 | ~~`RETRYING` 전이와 `retryCount` 증가는 하나의 동작이다~~ **삭제** | `RETRYING`을 제거해서 소멸했다. 바뀌는 값이 `retryCount` 하나뿐이라 어긋날 일이 없다. 레거시에서는 171, 172가 별개의 `put` 두 줄이라 그 사이에 어긋난 순간이 있었다 |
| C5 | ~~`retryCount`는 허용된 최대 재시도 횟수를 넘지 않는다~~ **`GatewayExchange`의 불변식이 아니다** | 최대값을 모르는 객체는 이 규칙을 지킬 수 없다. `RetryPolicy`의 판단으로 옮겼다 (아래 변경 시험). 횟수는 교환이 소유하고 정책은 그 값을 입력으로 받는다 (Day 1 겹침 점검 1번). 레거시는 170행에서 `attempt < maxRetry`를 비교하고, 지역 변수 `attempt`(141, 173)와 `retryCount`가 같은 값을 두 번 센다 |

**생성 직후의 조건 (불변식이 아니다)**: `state`는 `RECEIVED`(36), `retryCount`는 0(37), `authUser`, `endpoint`, 실패 사유는 없다. 생성이 끝난 순간에만 참이고 다음 전이에서 거짓이 되므로 불변식이 아니라 생성자가 보장할 조건이다.

**수정 이력**: 처음 쓴 "초기 state는 RECEIVED"는 위 생성 직후 조건으로 옮겼다. "authUser는 api-key나 anonymous"는 키가 아니라 사용자 이름이고 인증 전에는 없으므로 B1로 고쳤다. `RETRYING`을 제거하면서 C1, C3을 고치고 C4를 삭제했다. 변경 시험의 결과로 C5는 `GatewayExchange`의 불변식에서 빠졌다.

**3. 허용되지 않는 전이** — 위 전이표에 없는 전이 중 막아야 하는 것과 이유.

5개 상태 사이의 전이는 25개(자기 자신 5개 포함)이고, 설계 전이표가 허용하는 것은 **7가지**다. 나머지 **18개는 전부 막는다.** 허용 7가지 중 `CALLING→CALLING`만 자기 자신으로 가는 전이이고, 이름 있는 메서드(`retryCall`)로만 가능하다.

**허용 7가지**: `RECEIVED→AUTHENTICATED`, `RECEIVED→FAILED`, `AUTHENTICATED→CALLING`, `AUTHENTICATED→FAILED`, `CALLING→CALLING`, `CALLING→COMPLETED`, `CALLING→FAILED`

**막는 18개**

| 묶음 | 막는 전이 | 개수 | 이유 (근거 불변식) |
|---|---|---|---|
| 종료 상태에서 나가기 | `COMPLETED`→{`RECEIVED`, `AUTHENTICATED`, `CALLING`, `FAILED`}, `FAILED`→{`RECEIVED`, `AUTHENTICATED`, `CALLING`, `COMPLETED`} | 8 | C2. 끝난 요청은 다시 열리지 않는다. 특히 `COMPLETED→CALLING`은 이미 응답을 돌려준 요청을 다시 호출하게 만든다 |
| 단계 건너뛰기 | `RECEIVED→CALLING`, `RECEIVED→COMPLETED`, `AUTHENTICATED→COMPLETED` | 3 | 인증과 속도 제한을 건너뛰거나(B1), 호출한 적이 없는데 완료된다(B2). 지금 코드가 이걸 일으키지 않는 것은 `handle()`의 줄 순서가 우연히 맞아서다 |
| 뒤로 가기 | `AUTHENTICATED→RECEIVED`, `CALLING→RECEIVED`, `CALLING→AUTHENTICATED` | 3 | 요청은 앞으로만 진행한다. 제자리 반복은 `CALLING→CALLING`(재시도) 하나뿐이다(C1). 재인증이라는 개념이 없고 `authUser`는 한번 정해지면 바뀌지 않는다(B1) |
| 의미 없는 자기 전이 | `RECEIVED→RECEIVED`, `AUTHENTICATED→AUTHENTICATED`, `COMPLETED→COMPLETED`, `FAILED→FAILED` | 4 | 상태가 바뀌지 않는 전이 호출은 호출하는 쪽의 착오다. 종료 상태의 두 개는 C2다 |

**이전 열린 질문의 결과**: `RETRYING→FAILED`를 막아도 되는가 하는 질문은 `RETRYING`이 사라지면서 소멸했다. 대기 중 `InterruptedException`(176~177행에서 삼키고 있다)을 어떻게 다룰지는 이제 교환 밖의 일이고, 게이트웨이가 `CALLING` 상태에서 `fail(reason)`을 부르면 된다. 그 경로는 이미 허용 전이다.

**4. 이 객체가 거부해야 하는 호출 5가지** — `GatewayExchange`에게 어떤 메서드를 어떤 상태에서 부르면 예외로 거부해야 하는가?

`startCall()`처럼 전이마다 이름이 있는 메서드를 둔다고 가정하고, 각 메서드가 어떤 상태에서 불리면 거부해야 하는지를 불변식에서 도출했다. `setState`와 `setRetryCount`는 없다.

| # | 호출 | 거부하는 조건 | 어긴 불변식 | 예외 |
|---|---|---|---|---|
| 1 | `retryCall(endpoint)` | `CALLING`이 아닌 모든 상태 (특히 `COMPLETED`, `FAILED`) | C1, C2 | `IllegalStateException` |
| 2 | `startCall(endpoint)` | `AUTHENTICATED`가 아닌 상태 (특히 `RECEIVED`, 즉 인증 전. `CALLING`에서는 `retryCall`을 써야 한다) | B1, C1 | `IllegalStateException` |
| 3 | `complete()` | `CALLING`이 아닌 상태 (호출한 적이 없거나 이미 끝났다) | B2, C2 | `IllegalStateException` |
| 4 | `fail(reason)` | `COMPLETED`, `FAILED`이거나 `reason`이 비어 있다. 두 번 실패하면 사유가 둘이 된다 | B3, C2 | 상태 위반은 `IllegalStateException`, 사유 누락은 `IllegalArgumentException` |
| 5 | `authenticate(user)` | `RECEIVED`가 아닌 상태이거나 `user`가 비어 있다 | B1 (한번 정해지면 바뀌지 않는다) | 상태 위반은 `IllegalStateException`, 값 누락은 `IllegalArgumentException` |

**거부하지 않는 것 (정책의 몫)**: 재시도 횟수가 최대값에 도달했는지는 `GatewayExchange`가 거부하지 않는다. 최대값을 모르기 때문이다. `RetryPolicy`가 판단하고, 정책 확인 없이 `retryCall`을 부르는 코드는 교환이 막아 주지 못한다 (아래 변경 시험).

**5가지 외에 거부할 것**
- 생성자: `requestId`, `method`, `path`가 null이거나 비어 있다 (A1, A2)
- `state`, `retryCount`를 직접 쓰는 길: 거부가 아니라 **표현 자체가 불가능**해야 한다. setter가 없다.

**메서드와 전이의 대응 (코드의 사실상 API 스케치)**

| 메서드 | 출발 상태 | 도착 상태 | 함께 정해지는 값 |
|---|---|---|---|
| `authenticate(user)` | `RECEIVED` | `AUTHENTICATED` | `authUser` (B1) |
| `startCall(endpoint)` | `AUTHENTICATED` | `CALLING` | `endpoint` (B2) |
| `retryCall(endpoint)` | `CALLING` | `CALLING` | `retryCount + 1` (C3), 새 `endpoint` (B2) |
| `complete()` | `CALLING` | `COMPLETED` | 없음 |
| `fail(reason)` | `RECEIVED`, `AUTHENTICATED`, `CALLING` | `FAILED` | 실패 사유 (B3) |

허용 7가지 전이가 메서드 5개로 모두 덮인다. 상태와 연동된 값(B1~B3, C3)이 **전이 메서드의 인자나 부수 효과**로 같이 바뀌므로 상태와 값이 어긋난 순간이 생기지 않는다.

### 결정과 근거

- **결정**: `RETRYING` 상태를 제거한다. 상태는 5개(`RECEIVED`, `AUTHENTICATED`, `CALLING`, `COMPLETED`, `FAILED`)다. 재시도는 `retryCall(endpoint)` 한 동작(`CALLING→CALLING`, `retryCount + 1`, 새 `endpoint`)이다. 재시도 **여부**는 `RetryPolicy`가, **대기**는 게이트웨이가, **횟수 기록**은 `GatewayExchange`가 맡는다.
- **다른 선택지**:
  1. `RETRYING`을 유지한다 (`startRetry()`로 들어가고 `startCall()`로 나온다).
  2. `RETRYING`은 없애되 재시도를 두 단계(`startRetry()` 후 `startCall()`)로 유지한다. 그러면 `startRetry()` 이후 호출이 일어나지 않았는데 상태가 `CALLING`이라 `complete()`가 호출 없이 허용되는 구멍이 생긴다.
- **그걸 버린 이유** (상태 판정 기준 4가지를 적용했다):
  - 읽는 쪽이 없다. 상태를 읽는 곳은 223행 로그뿐이고 최종 상태만 찍는다. `RETRYING`은 100ms짜리 순간이다.
  - `RETRYING`이면 `retryCount ≥ 1`이라는 중복 정보다. 이 중복이 불변식 C4를 만들었다.
  - `RETRYING`과 `CALLING`의 허용 동작이 다른 것은 재시도를 두 단계로 쪼갰기 때문이다. 한 단계로 합치면 사이 구간과 그 차이가 함께 사라진다.
  - 상태를 줄이니 불변식 하나(C4)가 사라졌고, 허용 전이는 8가지에서 7가지, 막는 전이는 22개에서 18개로 줄었다.
- **되살릴 조건**: Day 3의 지수 백오프처럼 대기가 길어지고, "대기 중인 요청"을 구분해서 읽는 소비자(운영 대시보드, 총 대기시간 한도 등)가 생기면 그때 `RETRYING`을 되살린다. 지금 필요 없다는 판단이지 영원히 필요 없다는 판단이 아니다.

**상태를 정하는 기준 (오늘 정리)**
1. 허용되는 동작이 달라지는가. 같으면 합친다.
2. 다른 필드에서 유도할 수 있는가. 가능하면 상태로 두지 않는다.
3. 밖에서 구분해서 읽는 쪽이 있는가. 아무도 없으면 내부 구현이다.
4. 크기(횟수, 시각)는 필드이고 단계(phase)가 상태다.
"적을수록 좋다"가 아니라 **불가능한 상태를 표현할 수 없게** 하는 것이 목표다.

### 변경 시험 — "재시도 최대 횟수를 라우트별로 다르게 설정해야 한다"
이 규칙을 아는 것은 `GatewayExchange`인가 `Route`인가? 답과 근거, 수정한 파일 수를 센다.

**답**: 최대값 자체는 **`RetryPolicy`**가, 그 정책을 어느 경로가 쓰는지는 **`Route`**가 안다. `GatewayExchange`는 둘 다 모른다. ("`Route` 혹은 `RetryPolicy`"라는 처음 답이 맞았다. 둘의 역할이 다르다.)

| 객체 | 아는 것 |
|---|---|
| `RetryPolicy` | 최대 횟수 값, 대상 상태코드, 대기 시간 |
| `Route` | 이 경로는 어느 `RetryPolicy`를 쓰는가 (정책의 규칙은 모른다) |
| `GatewayExchange` | 현재 `retryCount`와 상태 변경 규칙. 최대값은 모른다 |

**교정**: 처음 답의 "`GatewayExchange`의 책임은 각 동작에 대한 상태 저장"은 "**자기 상태의 변경 규칙을 지킨다. 단, 그 변경을 언제 할지에 대한 정책은 모른다**"로 고친다. 저장만 하는 객체는 `setState`가 하는 일이고, 규칙이 객체 밖에 있는 Day 0 상태(빈약한 도메인 모델)다. 같은 `retryCall()`에서도 거부 조건의 주인이 갈린다.

| 거부 조건 | 주인 |
|---|---|
| `CALLING`이 아닌 상태에서 호출 | `GatewayExchange` (자기 상태 규칙, C1·C2) |
| 횟수가 최대값에 도달 | `RetryPolicy` (정책) |

한 문장: `GatewayExchange`는 **무엇이 허용되는 변화인지**를 알고, 정책은 **변화를 해야 하는지**를 안다.

**협력 구조**: `retryPolicy.shouldRetry(response, exchange.retryCount())`가 참일 때만 `exchange.retryCall(endpoint)`를 부른다. 횟수는 교환이 소유하고 정책은 입력으로 받는다 (Day 1 겹침 점검 1번과 일치).

**비용**: 규칙이 두 객체에 걸쳐 있어서 어느 한쪽이 혼자 지킬 수 없다. 정책 확인 없이 `retryCall`을 부르는 코드를 교환이 막지 못하고, 두 객체를 엮는 조정자(`handle()`)와 Day 6 테스트가 그 책임을 진다.

**수정한 파일 수 (설계상 예상, 구현 후 실측한다)**: `GatewayExchange` 0, `RetryPolicy` 0 (라우트마다 다른 설정의 인스턴스를 만들 뿐), `Route` 0~1 (정책 연결), 조립 지점 1. 레거시도 라우트별 값(`retry=3`, `retry=2`: 105~106행)은 이미 있어서 요구사항 자체는 데이터로는 새롭지 않다. 이 시험이 실제로 재는 것은 **교환이 `Route`나 최대값에 의존하게 되는가**이고, 답은 "의존하면 안 된다"이다. 교환이 최대값을 받는 순간 생성자가 `Route`에 의존한다.

### 구현 결과

`src/main/java/gateway/GatewayExchange.java`, `FailureReason.java` 추가. 테스트 20개 통과 (`GatewayExchangeTest`, 네트워크 없이 0.07초).

- 상태(`State`)는 private enum이다. 외부에는 `isCompleted()`, `isFailed()`, `isFinished()`만 열려 있어 `getState()`로 분기할 수 없다. setter 없음.
- 거부 조건 5가지와 생성자 검증(A1, A2)을 테스트로 확인했다. 허용 7가지 전이도 각각 확인했다.
- 요청 헤더는 `Map.copyOf`로 복사해 A4를 지킨다. 본문은 `String`이라 불변이다.
- 검증이 상태 변경보다 앞서므로, 잘못된 인자로 불러도 상태가 바뀌지 않는다 (테스트로 확인).
- `FailureReason`은 B3의 사유 5개를 enum으로 둔 것이다. HTTP 상태코드는 모른다 (Day 1 겹침 점검 6번).
- `endpoint`는 아직 `String`이다. `Endpoint` 타입은 Day 6 요구사항이 오기 전까지 미룬다.
- `startedAt`은 `Instant`로 생성자에서 받는다. `System.currentTimeMillis()`를 객체 안에서 부르지 않는다 (Day 6 대비).

**변경 시험 실측 ("라우트별 재시도 횟수")**: `GatewayExchange`는 최대값을 받는 곳이 없어서 수정 0개로 확인됐다. `RetryPolicy`와 `Route`는 아직 없으므로 나머지 수치는 Day 3에서 측정한다.

**통과 기준 확인**
- `set~` 호출 불가능: 확인 (setter 없음).
- 전이 규칙은 한 군데: `GatewayExchange`의 `requireState`와 `fail`.
- 외부에서 상태로 분기하는 코드: 없음.
- 레거시 `ApiGateway.handle()`은 아직 새 객체를 쓰지 않는다. 연결은 Day 3의 일이다.

### 오늘 틀렸던 것

-
