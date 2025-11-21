package com.example.callback.worker.infrastructure.broadcast;

import com.example.callback.worker.infrastructure.queue.kafka.CallbackPayload;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

@Slf4j
@Component
@RequiredArgsConstructor
public class HttpBroadcaster {

  private final NodeRegistry nodeRegistry;
  private final WebClient webClient = WebClient.builder().build(); // 한번 만들고 재사용

  public void broadcast(CallbackPayload payload) {
    List<String> targets = nodeRegistry.getTarget();
    log.info("📡 [Hub] 브로드캐스팅 시작! 대상: {}개 노드", targets.size());

    // [핵심] 병렬(Parallel) 비동기 전송
    // for문으로 하나씩 보내면 느립니다. Flux를 써서 동시에 쏩니다.
    Flux.fromIterable(targets)
        .parallel() // 병렬 처리 시작
        .runOn(Schedulers.boundedElastic()) // 별도 스레드 풀 사용
        .flatMap(url -> sendRequest(url, payload)) // 각 주소로 전송
        .sequential() // 결과 취합
        .subscribe(); // 실행! (이게 없으면 발송 안 됨)
  }

  private reactor.core.publisher.Mono<String> sendRequest(String url, CallbackPayload payload) {
    return webClient.post()
        .uri(url + "/receive") // Leaf 서버의 API 경로 (/receive)
        .bodyValue(payload)
        .retrieve()
        .bodyToMono(String.class)
        .doOnSuccess(res -> log.info("[SUCCESS] - 전송 성공: {}", url))
        .doOnError(ex -> log.error("[ERROR] - 전송 실패: {} (사유: {})", url, ex.getMessage()))
        .onErrorResume(ex -> reactor.core.publisher.Mono.empty()); // 실패해도 멈추지 않고 다음 서버로 진행
  }
}
