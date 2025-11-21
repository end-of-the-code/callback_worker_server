package com.example.callback.worker.infrastructure.broadcast;

import com.example.callback.worker.infrastructure.queue.kafka.CallbackPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class HttpBroadcaster {

  private final NodeRegistry nodeRegistry;
  private final BroadcastFailureHandler failureHandler;
  private final WebClient webClient = WebClient.builder().build();

  public void broadcast(CallbackPayload payload) {
    List<String> targets = nodeRegistry.getTarget();
    log.info("📡 [Hub] 브로드캐스팅 시작 (대상 {}개)", targets.size());

    Flux.fromIterable(targets)
        .parallel()
        .runOn(Schedulers.boundedElastic())
        .flatMap(url -> sendRequest(url, payload))
        .sequential()
        .subscribe();
  }

  private Mono<String> sendRequest(String url, CallbackPayload payload) {
    return webClient.post()
        .uri(url + "/receive")
        .bodyValue(payload)
        .retrieve()
        // [변경 1] onStatus를 제거합니다.
        // WebClient는 기본적으로 4xx, 5xx 에러 시 WebClientResponseException을 던집니다.
        // 이 예외를 그대로 살려서 아래 retryWhen에서 필터링 재료로 씁니다.
        .bodyToMono(String.class)

        // [변경 2] 스마트 재시도 로직 적용
        .retryWhen(Retry.fixedDelay(3, Duration.ofSeconds(1))
            // ★ 핵심: 이 에러가 재시도할 만한 가치가 있는지 검사합니다.
            .filter(this::shouldRetry)
            .doBeforeRetry(signal -> log.warn("⚠️ [Retry] {} 전송 실패 ({}), 재시도 중...", url, signal.failure().getMessage())))

        .doOnSuccess(res -> log.info("✅ [Success] {} 전송 완료", url))

        // 최종 실패 시 처리
        .doOnError(ex -> {
          // 에러 로그를 좀 더 자세히 남깁니다.
          String reason = ex instanceof WebClientResponseException webEx
              ? "HTTP " + webEx.getStatusCode()
              : ex.getMessage();

          log.error("[Fail] {} 최종 실패 - 원인: {}", url, reason);
          failureHandler.handleFailure(url, payload, reason);
        })
        .onErrorResume(ex -> Mono.empty());
  }

  /**
   * [재시도 판단 로직 수정]
   * - 5xx 에러 (서버가 살아있으나 내부 문제): 재시도 O
   * - 연결 불가 (서버 다운, Connection Refused): 재시도 X (바로 실패 처리)
   * - 4xx 에러 (잘못된 요청): 재시도 X
   */
  private boolean shouldRetry(Throwable ex) {
    // 1. 서버가 응답은 줬는데 5xx(서버 내부 에러)인 경우 -> 재시도 O
    // (이건 서버가 켜져는 있다는 뜻이고, 잠깐 과부하일 수 있으므로 재시도 가치가 있음)
    if (ex instanceof WebClientResponseException responseEx) {
      return responseEx.getStatusCode().is5xxServerError();
    }

    // 2. [수정됨] 아예 연결조차 안 된 경우 (서버 다운, Connection Refused) -> 재시도 X
    // 사용자님 의도대로, 서버가 꺼져있으면 굳이 다시 시도하지 않고 바로 실패 처리합니다.
    if (ex instanceof WebClientRequestException) {
      log.warn("⛔ [Stop Retry] 서버 연결 불가 (Connection Refused). 재시도하지 않습니다.");
      return false; // <--- true에서 false로 변경!
    }

    // 3. 그 외 (4xx 클라이언트 에러 등) -> 재시도 X
    return false;
  }
}