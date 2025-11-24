package com.example.callback.worker.infrastructure.broadcast;

import com.example.callback.worker.infrastructure.queue.kafka.CallbackPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

import java.net.ConnectException;
import java.time.Duration;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class HttpBroadcaster {

  private final NodeRegistry nodeRegistry;
  private final BroadcastFailureHandler failureHandler;

  // [성능 최적화] WebClient는 싱글톤으로 재사용
  private final WebClient webClient = WebClient.builder().build();

  public void broadcast(CallbackPayload payload) {
    List<String> targets = nodeRegistry.getTarget();
    log.info("📡 [Consumer] 브로드캐스팅 시작 (대상 {}개 노드)", targets.size());

    Flux.fromIterable(targets)
        .parallel() // 병렬 처리 (동시 전송)
        .runOn(Schedulers.boundedElastic()) // I/O 전용 스레드 풀 사용
        .flatMap(url -> sendRequest(url, payload))
        .sequential()
        .subscribe(); // Fire & Forget 실행
  }

  private Mono<String> sendRequest(String url, CallbackPayload payload) {
    return webClient.post()
        .uri(url + "/receive")
        .bodyValue(payload)
        .retrieve()

        // HTTP 상태 코드가 에러일 경우 예외(WebClientResponseException)를 던지게 함
        .onStatus(HttpStatusCode::isError, response -> response.createException())
        .bodyToMono(String.class)

        // [명세서 3항] 재시도 전략 적용
        .retryWhen(Retry.fixedDelay(3, Duration.ofSeconds(1)) // 최대 3회, 1초 간격
            .filter(ex -> shouldRetry(ex, url)) // ★ 재시도 여부 판단 (핵심)
            .doBeforeRetry(signal -> log.warn("⚠️ [Retry] {} 일시적 장애 감지. 재시도 {}/3회 진행...", url, signal.totalRetries() + 1)))

        .doOnSuccess(res -> log.info("✅ [Success] {} 전송 완료", url))

        // [명세서 4항] 최종 실패 핸들링
        .doOnError(ex -> {
          String reason = extractErrorReason(ex);
          failureHandler.handleFailure(url, payload, reason);
        })

        // 전체 플로우 중단 방지 (다른 노드 전송은 계속됨)
        .onErrorResume(ex -> Mono.empty());
  }

  /**
   * [명세서 2항] 에러 유형별 대응 정책 구현
   * @return true(재시도 함), false(즉시 실패)
   */
  private boolean shouldRetry(Throwable ex, String url) {
    // Case 1: 서버 응답은 왔으나 에러인 경우 (WebClientResponseException)
    if (ex instanceof WebClientResponseException responseEx) {
      int status = responseEx.getStatusCode().value();

      // [정책 B] 5xx 서버 에러 -> 재시도 O
      if (responseEx.getStatusCode().is5xxServerError()) {
        return true;
      }
      // [정책 D] 429 Too Many Requests -> 재시도 O
      if (status == 429) {
        return true;
      }
      // [정책 C] 4xx 클라이언트 에러 (400, 401, 404...) -> 재시도 X
      log.warn("⛔ [No Retry] {} 클라이언트 에러 ({}). 요청을 수정해야 합니다.", url, status);
      return false;
    }

    // Case 2: 아예 연결조차 안 된 경우 (WebClientRequestException)
    if (ex instanceof WebClientRequestException requestEx) {
      Throwable rootCause = requestEx.getRootCause();

      // [정책 A] Connection Refused (서버 다운/포트 닫힘) -> 재시도 X
      // (자바에서 Connection Refused는 보통 ConnectException으로 포장됨)
      if (isConnectionRefused(rootCause)) {
        log.warn("⛔ [No Retry] {} 서버 다운됨 (Connection Refused). 즉시 포기합니다.", url);
        return false;
      }

      // [정책 A-2] Timeout (연결 시간 초과) -> 재시도 O (일시적 네트워크 지연일 수 있음)
      return true;
    }

    // 그 외 알 수 없는 에러 -> 재시도 X
    return false;
  }

  // Connection Refused 인지 판별하는 헬퍼 메서드
  private boolean isConnectionRefused(Throwable rootCause) {
    if (rootCause instanceof ConnectException) {
      return true;
    }
    // 메시지로 한 번 더 확인 (확실하게 하기 위함)
    return rootCause != null && rootCause.getMessage() != null
        && rootCause.getMessage().contains("Connection refused");
  }

  // 에러 로그용 메시지 추출기
  private String extractErrorReason(Throwable ex) {
    if (ex instanceof WebClientResponseException resEx) {
      return "HTTP " + resEx.getStatusCode().value() + " " + resEx.getStatusText();
    } else if (ex instanceof WebClientRequestException reqEx) {
      return "Network Error: " + (reqEx.getRootCause() != null ? reqEx.getRootCause().getMessage() : reqEx.getMessage());
    }
    return ex.getMessage();
  }
}