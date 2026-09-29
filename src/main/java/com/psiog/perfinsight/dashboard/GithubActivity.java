package com.psiog.perfinsight.dashboard;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

@Entity
@Table(name = "github_activity")
@Getter @Setter @NoArgsConstructor
public class GithubActivity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "repository_name", length = 100)
    private String repositoryName;

    @Column(name = "pull_request_id", length = 50)
    private String pullRequestId;

    @Column(name = "commits_count")
    private Integer commitsCount;

    @Column(name = "lines_added")
    private Integer linesAdded;

    @Column(name = "lines_removed")
    private Integer linesRemoved;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "developer_id")
    private DashUser developer;

    private Boolean merged;

    @Column(name = "activity_date")
    private LocalDate activityDate;
}