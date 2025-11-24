package com.example.callback.worker.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;

@Getter
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "failed_broadcast_logs")
public class FailedBroadcastLog {

  @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private String targetUrl;   // 실패한 서버 (예: http://localhost:8081)

  @Lob // 긴 내용 저장용
  private String payloadJson; // 데이터 본문

  private String errorReason; // 실패 원인

  private LocalDateTime createdAt; // 발생 시간

  private boolean isRetried;  // 재시도 성공 여부
}