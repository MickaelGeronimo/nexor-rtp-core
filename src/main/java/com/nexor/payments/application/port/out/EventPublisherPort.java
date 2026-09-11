package com.nexor.payments.application.port.out;

import java.util.Map;

public interface EventPublisherPort {
    void publishOutboxEvent(String aggregateType, String aggregateId, String eventType, Map<String, Object> payload);
}
