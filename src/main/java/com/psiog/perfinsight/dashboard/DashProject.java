package com.psiog.perfinsight.dashboard;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "projects")
@Getter @Setter @NoArgsConstructor
public class DashProject {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_code", length = 20, nullable = false, unique = true)
    private String projectCode;

    @Column(name = "project_name", length = 100, nullable = false)
    private String projectName;

    @Column(name = "client_name", length = 100)
    private String clientName;

    @Column(name = "offering", length = 100)
    private String offering;

    @Column(name = "project_manager", length = 100)
    private String projectManager;

    /** IN_PROGRESS | PLANNING | COMPLETED | ... */
    @Column(name = "status", length = 30)
    private String status;

    /** HIGH | MEDIUM | LOW */
    @Column(name = "priority", length = 20)
    private String priority;

    /** planned team size */
    @Column(name = "team_size")
    private Integer teamSize;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    /** % complete as reported by the PM (manual) */
    @Column(name = "completion_percentage", precision = 5, scale = 2)
    private BigDecimal completionPercentage;

    /** health as reported by the PM (manual), e.g. GREEN / AMBER / RED or ON_TRACK / AT_RISK */
    @Column(name = "health_status", length = 20)
    private String healthStatus;

    @Column(name = "project_description", length = 500)
    private String projectDescription;

    private Boolean active = true;

    @CreationTimestamp
    @Column(name = "created_date", updatable = false)
    private LocalDateTime createdDate;

    @UpdateTimestamp
    @Column(name = "updated_date")
    private LocalDateTime updatedDate;
}