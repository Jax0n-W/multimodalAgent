package com.multimodalAgent.agent.persistence.repository;

import com.multimodalAgent.agent.persistence.entity.ChatHistoryProjectionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatHistoryProjectionRepository
        extends JpaRepository<ChatHistoryProjectionEntity, String> {

    long countBySessionId(String sessionId);
}
