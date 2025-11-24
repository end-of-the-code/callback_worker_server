package com.example.callback.worker.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;


public interface FailedBroadcastLogRepository extends JpaRepository<FailedBroadcastLog, Long> {
   List<FailedBroadcastLog> findByTargetUrlAndIsRetriedFalse(String targetUrl);
}