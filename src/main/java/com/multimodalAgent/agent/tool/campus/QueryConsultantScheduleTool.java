package com.multimodalAgent.agent.tool.campus;

import com.multimodalAgent.agent.domain.ConsultantScheduleSlot;
import com.multimodalAgent.agent.repository.ConsultantScheduleSlotRepository;
import com.multimodalAgent.agent.runtime.tool.AgentTool;
import com.multimodalAgent.agent.runtime.tool.ToolDescriptor;
import com.multimodalAgent.agent.runtime.tool.ToolRisk;
import com.multimodalAgent.agent.runtime.tool.UntrustedEvidenceTool;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class QueryConsultantScheduleTool implements
        AgentTool<QueryConsultantScheduleTool.Input, QueryConsultantScheduleTool.Output>,
        UntrustedEvidenceTool {

    public static final String NAME = "query_consultant_schedule";
    private static final ZoneId CAMPUS_ZONE = ZoneId.of("Asia/Shanghai");

    private final ConsultantScheduleSlotRepository schedules;
    private final ToolDescriptor<Input> descriptor;

    public QueryConsultantScheduleTool(
            ConsultantScheduleSlotRepository schedules,
            Duration timeout
    ) {
        this.schedules = Objects.requireNonNull(schedules, "schedules must not be null");
        descriptor = new ToolDescriptor<>(
                NAME,
                "Query real locally stored consultant availability for a bounded date range.",
                Input.class,
                ToolRisk.MEDIUM,
                true,
                true,
                false,
                timeout
        );
    }

    @Override
    public ToolDescriptor<Input> descriptor() {
        return descriptor;
    }

    @Override
    public Output execute(Input input) {
        LocalDate endDate = input.endDate() == null ? input.startDate() : input.endDate();
        Instant start = input.startDate().atStartOfDay(CAMPUS_ZONE).toInstant();
        Instant end = endDate.plusDays(1).atStartOfDay(CAMPUS_ZONE).toInstant();
        String modality = normalize(input.modality());
        String specialty = normalize(input.specialty());
        List<Slot> slots = schedules
                .findTop50ByAvailableTrueAndStartAtGreaterThanEqualAndStartAtLessThanOrderByStartAt(
                        start,
                        end
                )
                .stream()
                .filter(slot -> modality == null
                        || slot.getModality().toLowerCase(Locale.ROOT).equals(modality))
                .filter(slot -> specialty == null
                        || slot.getSpecialty().toLowerCase(Locale.ROOT).contains(specialty))
                .limit(input.effectiveLimit())
                .map(QueryConsultantScheduleTool::toSlot)
                .toList();
        return new Output(CAMPUS_ZONE.getId(), slots.isEmpty(), slots);
    }

    @Override
    public String evidenceKind() {
        return "consultant-schedule";
    }

    private static Slot toSlot(ConsultantScheduleSlot slot) {
        return new Slot(
                slot.getId(),
                slot.getConsultantDisplayName(),
                slot.getSpecialty(),
                slot.getStartAt(),
                slot.getEndAt(),
                slot.getModality(),
                slot.getLocation()
        );
    }

    private static String normalize(String value) {
        return value == null || value.isBlank()
                ? null
                : value.trim().toLowerCase(Locale.ROOT);
    }

    public record Input(
            @NotNull LocalDate startDate,
            LocalDate endDate,
            @Pattern(regexp = "(?i)^(online|in_person)$") String modality,
            @Size(max = 80) String specialty,
            @Min(1) @Max(20) Integer limit
    ) {
        public Input {
            if (startDate != null && endDate != null && endDate.isBefore(startDate)) {
                throw new IllegalArgumentException("endDate must not be before startDate");
            }
            if (startDate != null && endDate != null
                    && endDate.isAfter(startDate.plusDays(30))) {
                throw new IllegalArgumentException(
                        "schedule query range must not exceed 31 days"
                );
            }
        }

        int effectiveLimit() {
            return limit == null ? 10 : limit;
        }
    }

    public record Output(String timezone, boolean noConfiguredAvailability, List<Slot> slots) {
        public Output {
            slots = List.copyOf(slots);
        }
    }

    public record Slot(
            Long slotId,
            String consultant,
            String specialty,
            Instant startAt,
            Instant endAt,
            String modality,
            String location
    ) {
    }
}
