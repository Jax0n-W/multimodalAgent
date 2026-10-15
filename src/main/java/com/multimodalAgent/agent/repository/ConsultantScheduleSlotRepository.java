package com.multimodalAgent.agent.repository;

import com.multimodalAgent.agent.domain.ConsultantScheduleSlot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface ConsultantScheduleSlotRepository
        extends JpaRepository<ConsultantScheduleSlot, Long> {

    List<ConsultantScheduleSlot> findTop50ByAvailableTrueAndStartAtGreaterThanEqualAndStartAtLessThanOrderByStartAt(
            Instant startInclusive,
            Instant endExclusive
    );
}
