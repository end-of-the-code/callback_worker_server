package com.example.callback.worker.infrastructure.broadcast;

import com.example.callback.worker.domain.FailedBroadcastLog;
import com.example.callback.worker.domain.FailedBroadcastLogRepository;
import com.example.callback.worker.infrastructure.queue.kafka.CallbackPayload;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
public class BroadcastFailureHandler {

  private final FailedBroadcastLogRepository failedBroadcastLogRepository;
  private final ObjectMapper objectMapper; // [추가] 객체 -> JSON 변환기

  /**
   * [명세서 4항] 실패 처리 프로세스
   * 모든 재시도 전략이 끝난 후에도 실패했을 때 호출됩니다.
   */
  public void handleFailure(String targetUrl, CallbackPayload payload, String errorReason) {
    log.error("🚨 [Broadcast Fatal Error] 전송 최종 실패 -> 저장소 백업 시도");

    // 저장 로직 호출
    saveToDeadLetterStorage(targetUrl, payload, errorReason);
  }

  private void saveToDeadLetterStorage(String url, CallbackPayload payload, String reason) {
    try {
      // 1. Payload 객체를 JSON 문자열로 변환
      String payloadJson = objectMapper.writeValueAsString(payload);

      // 2. 엔티티 생성 (Builder 패턴 사용)
      FailedBroadcastLog failLog = FailedBroadcastLog.builder()
          .targetUrl(url)
          .payloadJson(payloadJson)
          .errorReason(reason)
          .isRetried(false) // 아직 재시도 안 함
          .createdAt(LocalDateTime.now())
          .build();

      // 3. [핵심] DB에 저장 (INSERT)
      failedBroadcastLogRepository.save(failLog);

      log.info("✅ [DB Saved] 실패 내역이 DB에 안전하게 저장되었습니다. (ID: {})", failLog.getId());

    } catch (Exception e) {
      // 4. [대안] DB 저장조차 실패했을 때 -> 최후의 수단 (로그 파일)
      // DB가 죽었거나 네트워크가 끊겼을 때, 데이터 유실을 막기 위해 로그로 남깁니다.
      log.error("😱 [Critical] DB 저장 실패! 비상 로그를 남깁니다. (이 로그를 모니터링 알람으로 연결하세요)");
      log.error("   >> TARGET: {}", url);
      log.error("   >> REASON: {}", reason);
      log.error("   >> PAYLOAD: {}", payload); // toString()으로 출력됨
      log.error("   >> DB_ERROR: {}", e.getMessage());
    }
  }
}