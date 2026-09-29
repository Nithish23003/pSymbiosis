package com.psiog.perfinsight.ingest;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "sync_run", indexes = @Index(columnList = "toolConfigId"))
@Getter @Setter @NoArgsConstructor
public class SyncRun {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long toolConfigId;
    private String triggeredBy;
    /** SCHEDULED | MANUAL | FILE_IMPORT */
    @Column(name = "sync_trigger")
    private String trigger;
    private Instant startedAt;
    private Instant finishedAt;
    /** RUNNING | SUCCESS | FAILED */
    private String status;
    @Column(length = 4000)
    private String cursorBefore;
    @Column(length = 4000)
    private String cursorAfter;
    private int recordsFetched;
    private int rawStored;
    private int activitiesCreated;
    private int activitiesUpdated;
    @Column(length = 4000)
    private String message;
}
