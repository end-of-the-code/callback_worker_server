package com.example.callback.worker.infrastructure.queue.kafka;

import com.example.callback.worker.worker.service.CallbackWorkerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class CallbackWorkerKafkaConsumer {

  private final CallbackWorkerService callbackWorkerService;

  /**
   * [역할 1: 구독 & 수신]
   * - 카프카 토픽을 계속 감시(Listening)합니다.
   * - 메시지가 오면 즉시 낚아채서 service.process()를 호출합니다.
   * - concurrency = "1" : 중복 실행 방지를 위해 컨슈머 스레드를 1개로 고정
   */
  @KafkaListener(
      topics = "${app.kafka.consumer-topic}",
      groupId = "worker-group",
      concurrency = "1"
  )
  public void consume(CallbackRequest payload) {
    log.info("==========================================");
    log.info(">>> [Consumer] 1. 카프카 메시지 수신 완료!");
    log.info(">>> Data: {}", payload);

    // 서비스에게 일감을 넘깁니다. (핵심)
    callbackWorkerService.process(payload);
  }
}