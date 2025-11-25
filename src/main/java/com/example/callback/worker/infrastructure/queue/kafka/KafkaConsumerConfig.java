package com.example.callback.worker.infrastructure.queue.kafka;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.util.backoff.FixedBackOff;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class KafkaConsumerConfig {

  @Value("${spring.kafka.bootstrap-servers}")
  private String bootstrapServers;

  @Value("${spring.kafka.consumer.group-id}")
  private String groupId;

  // [필수] DLQ 발행을 위해 KafkaTemplate 주입 (이게 있어야 실패 메시지를 딴 곳으로 보냄)
  private final KafkaTemplate<String, Object> kafkaTemplate;

  /**
   * 컨슈머 팩토리 설정
   * - YAML 설정보다 이 자바 코드가 우선순위가 높습니다.
   * - ErrorHandlingDeserializer를 강제로 적용하여 "독이 든 사과(JSON 파싱 에러)"를 방지합니다.
   */
  @Bean
  public ConsumerFactory<String, CallbackPayload> consumerFactory() {
    Map<String, Object> props = new HashMap<>();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

    // [필수 추가 1] 자동 커밋 끄기 (Java Config가 YML보다 우선하므로 여기서 명시해야 안전함)
    props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);

    // [튜닝] 리밸런싱 방지 설정 (YML과 동일하게 맞춤)
    props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 50);
    props.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 600000);

    // 역직렬화 설정 (헤더 무시 true)
    ErrorHandlingDeserializer<CallbackPayload> errorHandlingDeserializer = new ErrorHandlingDeserializer<>(
        new JsonDeserializer<>(CallbackPayload.class, false)
    );

    return new DefaultKafkaConsumerFactory<>(
        props,
        new StringDeserializer(),
        errorHandlingDeserializer
    );
  }

  /**
   * [핵심] 장애 대응 에러 핸들러 (Retry & DLQ)
   * 1. 메시지 처리 중 에러 발생 (예: DB 연결 실패, 로직 오류)
   * 2. 1초 간격으로 3번 재시도 (FixedBackOff)
   * 3. 그래도 실패하면? -> '토픽명.DLT'로 메시지를 발행하고 현재 토픽에서는 넘어가버림 (Commit)
   */
  @Bean
  public CommonErrorHandler errorHandler() {
    // 1. 죽은 메시지 처리기 (Recoverer): 실패하면 DLT 토픽으로 쏘는 역할
    DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
        (record, ex) -> {
          log.error("☠️ [DLQ] 메시지 최종 실패! DLT 토픽으로 이동합니다. Topic: {}, Offset: {}, Cause: {}",
              record.topic(), record.offset(), ex.getMessage());

          // 원래 토픽 이름 뒤에 .DLT를 붙여서 보냄 (예: callback-topic.DLT)
          return new TopicPartition(record.topic() + ".DLT", record.partition());
        });

    // 2. 에러 핸들러 생성 (3회 재시도 설정)
    // FixedBackOff(interval, maxAttempts) -> 1000ms 간격, 3번 시도
    DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 3L));

    return errorHandler;
  }

  @Bean
  public ConcurrentKafkaListenerContainerFactory<String, CallbackPayload> kafkaListenerContainerFactory() {
    ConcurrentKafkaListenerContainerFactory<String, CallbackPayload> factory =
        new ConcurrentKafkaListenerContainerFactory<>();

    factory.setConsumerFactory(consumerFactory());

    // [중요] 위에서 만든 에러 핸들러를 여기에 등록해야 동작함!
    factory.setCommonErrorHandler(errorHandler());

    // [필수 추가 2] AckMode 설정 (YML에 있어도 여기서 설정 안하면 BATCH가 될 수 있음)
    // RECORD 모드: 메시지 하나 처리할 때마다 커밋 (가장 안전, 속도는 느림)
    factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);

    return factory;
  }
}