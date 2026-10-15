package com.multimodalAgent.agent.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "consultant_schedule_slots")
public class ConsultantScheduleSlot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "consultant_display_name", nullable = false, length = 120)
    private String consultantDisplayName;

    @Column(nullable = false, length = 160)
    private String specialty;

    @Column(name = "start_at", nullable = false)
    private Instant startAt;

    @Column(name = "end_at", nullable = false)
    private Instant endAt;

    @Column(nullable = false, length = 32)
    private String modality;

    @Column(length = 160)
    private String location;

    @Column(nullable = false)
    private boolean available = true;

    public Long getId() { return id; }
    public String getConsultantDisplayName() { return consultantDisplayName; }
    public void setConsultantDisplayName(String value) { consultantDisplayName = value; }
    public String getSpecialty() { return specialty; }
    public void setSpecialty(String value) { specialty = value; }
    public Instant getStartAt() { return startAt; }
    public void setStartAt(Instant value) { startAt = value; }
    public Instant getEndAt() { return endAt; }
    public void setEndAt(Instant value) { endAt = value; }
    public String getModality() { return modality; }
    public void setModality(String value) { modality = value; }
    public String getLocation() { return location; }
    public void setLocation(String value) { location = value; }
    public boolean isAvailable() { return available; }
    public void setAvailable(boolean value) { available = value; }
}
