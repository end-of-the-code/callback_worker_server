package com.example.callback.worker.infrastructure.queue.kafka;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

@Getter
@NoArgsConstructor
@ToString // 로그 찍을 때 객체 내용을 보기 위해 필수!
public class CallbackRequest {
  private String userId;
  private String message;
  // 필요한 필드들...
}
