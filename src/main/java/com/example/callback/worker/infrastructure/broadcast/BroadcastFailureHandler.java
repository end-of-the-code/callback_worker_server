package com.example.callback.worker.infrastructure.broadcast;

import com.example.callback.worker.infrastructure.queue.kafka.CallbackPayload;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class BroadcastFailureHandler {

  /**
   * 전송 실패 시 호출되는 메서드
   * @param targetUrl 실패한 서버 주소
   * @param payload 보내려던 데이터
   * @param errorMessage 에러 원인
   */
  public void handleFailure(String targetUrl, CallbackPayload payload, String errorMessage) {
    log.error("🚨 [Broadcast Error Handler] 장애 감지!");
    log.error("   - 대상 노드: {}", targetUrl);
    log.error("   - 원인: {}", errorMessage);

    // TODO: 여기에 '실패한 요청 저장소'에 저장하는 로직을 넣으면 됩니다.
    // failedJobRepository.save(new FailedJob(targetUrl, payload));

    log.info("   -> 실패 이력을 별도 저장소(파일/DB)에 백업했습니다. (구현 필요)");
  }
}