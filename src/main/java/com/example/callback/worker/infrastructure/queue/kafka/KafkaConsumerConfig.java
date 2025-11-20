package com.example.callback.worker.infrastructure.queue.kafka;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class KafkaConsumerConfig {

  @Value("${spring.kafka.bootstrap-servers}")
  private String bootstrapServers;

  @Value("${spring.kafka.consumer.group-id}")
  private String groupId;

  /**
   * YAML 설정 대신 Java 코드로 설정을 잡으면,
   * 'CallbackPayload.class'를 직접 참조하므로 오타/경로 에러가 원천 차단됩니다.
   */
  @Bean
  public ConsumerFactory<String, CallbackRequest> consumerFactory() {
    Map<String, Object> props = new HashMap<>();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
    props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

    // 1. ErrorHandlingDeserializer 설정
    ErrorHandlingDeserializer<CallbackRequest> errorHandlingDeserializer = new ErrorHandlingDeserializer<>(
        new JsonDeserializer<>(CallbackRequest.class, false) // false: 헤더 무시 설정
    );

    // 2. Key: String, Value: ErrorHandler(Json)
    return new DefaultKafkaConsumerFactory<>(
        props,
        new StringDeserializer(),
        errorHandlingDeserializer
    );
  }

  @Bean
  public ConcurrentKafkaListenerContainerFactory<String, CallbackRequest> kafkaListenerContainerFactory() {
    ConcurrentKafkaListenerContainerFactory<String, CallbackRequest> factory =
        new ConcurrentKafkaListenerContainerFactory<>();
    factory.setConsumerFactory(consumerFactory());
    return factory;
  }
}