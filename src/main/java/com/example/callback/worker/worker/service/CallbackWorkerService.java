package com.example.callback.worker.worker.service;

import com.example.callback.worker.infrastructure.queue.kafka.CallbackRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class CallbackWorkerService {

  // DB 처리가 필요하다면 Repository 주입
  // private final CallbackLogRepository callbackLogRepository;

  /**
   * [역할 2: 실제 비즈니스 로직 처리]
   * - [중요] KafkaListener 어노테이션을 제거했습니다.
   * - 오직 데이터를 받아서 DB에 저장하거나 처리하는 일만 수행합니다.
   */
  @Transactional
  public void process(CallbackRequest payload) {
    log.info(">>> [Service]  2. 비즈니스 로직 시작 (DB 저장 등)");

    // payload에서 필요한 데이터 꺼내기 (Null 체크 등은 필요에 따라 추가)
    String userId = payload.getUserId();
    String message = payload.getMessage();

    log.info(">>> 처리할 데이터: userId={}, message={}", userId, message);

    /* * [DB 저장 예시 코드]
     * * CallbackLog logEntity = CallbackLog.builder()
     * .userId(userId)
     * .message(message)
     * .build();
     * * callbackLogRepository.save(logEntity);
     */

    log.info(">>> [Service]  3. 처리 완료!");
    log.info("==========================================");
  }
}