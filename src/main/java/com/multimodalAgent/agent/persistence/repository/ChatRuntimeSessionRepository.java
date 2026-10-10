package com.multimodalAgent.agent.persistence.repository;

import com.multimodalAgent.agent.persistence.entity.ChatRuntimeSessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ChatRuntimeSessionRepository
        extends JpaRepository<ChatRuntimeSessionEntity, String> {

    Optional<ChatRuntimeSessionEntity> findBySessionIdAndUserId(String sessionId, Long userId);
}
