
-----

# 📄 [기능 명세서] Hub Server 브로드캐스팅 재시도 및 에러 처리 정책

## 1\. 개요

* **목표:** Kafka Consumer(Server 2)가 다수의 Leaf Node(Server 3, 4, 5...)로 메시지를 전파(Broadcasting)할 때, **불필요한 재시도로 인한 시스템 부하를 방지**하고, **복구 가능한 에러만 선별적으로 재시도**하여 전송 성공률을 높인다.
* **핵심 원칙:** **Fail Fast (빠른 실패)** & **Smart Retry (똑똑한 재시도)**

## 2\. 에러 유형별 대응 정책 (Retry Policy Matrix)

Leaf Node와의 통신 시 발생할 수 있는 에러를 4가지 카테고리로 분류하여 대응한다.

| 카테고리 | 상세 에러 / HTTP 상태 코드 | 재시도 여부 | 판단 근거 및 대응 |
| :--- | :--- | :--- | :--- |
| **A. 연결 불가**<br>(Network I/O) | **Connection Refused**<br>(서버 다운, 포트 닫힘) | **❌ 절대 안 함** | 서버가 물리적으로 꺼져 있거나 실행되지 않은 상태. 재시도해봤자 100% 실패하므로 즉시 자원 낭비를 막고 종료한다. |
| | **UnknownHostException**<br>(DNS 찾을 수 없음) | **❌ 절대 안 함** | 잘못된 도메인/IP 설정. 재시도해도 해결되지 않음. |
| | **Connect Timeout**<br>(연결 시간 초과) | **🔺 1회만** | 네트워크 일시적 지연일 수 있으나, 서버 과부하일 수도 있으므로 소극적 재시도. |
| **B. 서버 에러**<br>(Server Side) | **500 Internal Server Error** | **⭕ 재시도 함** | DB 락(Lock)이나 일시적 코드 오류. 잠시 후 해소될 가능성 있음. |
| | **502 Bad Gateway**<br>**503 Service Unavailable** | **⭕ 재시도 함** | 배포 중이거나 트래픽 폭주 상황. \*\*Backoff(대기 시간)\*\*를 두고 재시도 필요. |
| | **504 Gateway Timeout** | **⭕ 재시도 함** | Leaf Node 처리가 늦어지는 경우. |
| **C. 클라이언트 에러**<br>(Request Side) | **400 Bad Request**<br>**401 Unauthorized**<br>**403 Forbidden**<br>**404 Not Found** | **❌ 절대 안 함** | 요청 데이터나 URL이 잘못됨. 백만 번 다시 보내도 실패함. 즉시 에러 로그 기록. |
| **D. 과부하 제어**<br>(Rate Limit) | **429 Too Many Requests** | **⭕ 재시도 함** | **(중요)** 즉시 재시도가 아니라, 서버가 헤더에 준 시간만큼 대기 후 재시도해야 함. |

## 3\. 재시도 상세 메커니즘 (Retry Strategy)

### 3.1. 최대 재시도 횟수 (Max Attempts)

* **설정:** 최대 **3회** (최초 시도 1회 + 재시도 2회)
* **이유:** 3회 이상 실패하는 요청은 일시적 오류가 아닐 확률이 높으며, 그 이상은 전체 시스템(Hub)의 스레드를 점유하여 다른 정상적인 브로드캐스팅을 방해함.

### 3.2. 대기 시간 전략 (Backoff Strategy)

* **전략:** **Fixed Delay (고정 대기)** 또는 **Jitter(랜덤 대기)**
    * *권장:* 1초 간격 고정 대기 (`Fixed Delay 1s`)
    * *이유:* 3대 정도의 노드라면 복잡한 지수 백오프(Exponential Backoff)까지는 불필요하나, 노드가 50대 이상 늘어나면 Jitter(랜덤)를 도입하여 동시에 재시도 요청이 몰리는 것을 방지해야 함.

## 4\. 실패 처리 프로세스 (Fallback Flow)

모든 재시도(또는 즉시 실패) 후에도 전송이 실패한 경우, 아래 프로세스를 따른다.

1.  **로그 기록:** 에러 원인(Cause)을 명확히 남긴다. (단순 "실패"가 아닌 "Connection Refused" 인지 "500 Error" 인지 구분)
2.  **Dead Letter 처리:** 실패한 `Payload`와 `Target URL`을 별도의 저장소(DB 테이블 `failed_broadcast_logs` 또는 파일)에 저장한다.
3.  **알림 발송 (옵션):** 실패율이 일정 임계치(예: 5분간 10건 이상)를 넘으면 슬랙/이메일 알림을 발송한다.

## 5\. 데이터 흐름도 (Logic Flowchart)

```mermaid
graph TD
    A[Hub: 전송 시도] --> B{에러 발생?}
    B -- No (성공) --> C[✅ 성공 로그 및 종료]
    B -- Yes (에러) --> D{에러 타입 분석}
    
    D -- "서버 다운 / 4xx 에러 / DNS 오류" --> E[⛔ 즉시 실패 처리 (No Retry)]
    D -- "5xx 에러 / 타임아웃" --> F{재시도 횟수 남음?}
    
    F -- Yes --> G[⏳ 1초 대기 (Backoff)]
    G --> A
    F -- No (횟수 초과) --> E
    
    E --> H[📝 실패 핸들러 실행]
    H --> I[파일/DB에 실패 이력 저장]
```

-----
