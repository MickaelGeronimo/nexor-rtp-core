package com.nexor.payments.infrastructure.adapter.out.persistence.jpa.entity;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "journal_entries")
public class JournalEntryJpaEntity {

    @Id
    @Column(name = "entry_id", length = 64)
    private String entryId;

    @Column(name = "reference_id", nullable = false, length = 64)
    private String referenceId;

    @Column(name = "timestamp", nullable = false)
    private Instant timestamp;

    @Column(name = "memo", nullable = false)
    private String memo;

    @OneToMany(mappedBy = "journalEntry", cascade = CascadeType.ALL, fetch = FetchType.EAGER, orphanRemoval = true)
    private List<PostingLegJpaEntity> legs = new ArrayList<>();

    public JournalEntryJpaEntity() {}

    public JournalEntryJpaEntity(String entryId, String referenceId, Instant timestamp, String memo, List<PostingLegJpaEntity> legs) {
        this.entryId = entryId;
        this.referenceId = referenceId;
        this.timestamp = timestamp;
        this.memo = memo;
        this.legs = legs;
    }

    public String getEntryId() { return entryId; }
    public String getReferenceId() { return referenceId; }
    public Instant getTimestamp() { return timestamp; }
    public String getMemo() { return memo; }
    public List<PostingLegJpaEntity> getLegs() { return legs; }
}
