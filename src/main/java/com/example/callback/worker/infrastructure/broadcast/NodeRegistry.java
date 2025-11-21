package com.example.callback.worker.infrastructure.broadcast;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class NodeRegistry {

  // YML에 적은 주소들을 리스트로 가져옵니다.
//  @Value("${app.broadcast.target}")
  private List<String> target = List.of("http://localhost:8083", "http://localhost:8084", "http://localhost:8085");

  public List<String> getTarget() {
    return target;
  }
}