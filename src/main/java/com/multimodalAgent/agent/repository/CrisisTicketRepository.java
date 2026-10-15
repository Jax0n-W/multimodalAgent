package com.multimodalAgent.agent.repository;

import com.multimodalAgent.agent.domain.CrisisTicket;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CrisisTicketRepository extends JpaRepository<CrisisTicket, String> {
}
