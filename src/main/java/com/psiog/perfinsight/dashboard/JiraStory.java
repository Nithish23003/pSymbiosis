package com.psiog.perfinsight.dashboard;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

@Entity
@Table(name = "jira_story")
@Getter @Setter @NoArgsConstructor
public class JiraStory {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "story_id", length = 50)
    private String storyId;

    @Column(name = "story_title")
    private String storyTitle;

    @Column(name = "story_points")
    private Integer storyPoints;

    /** DONE | IN_PROGRESS | TO_DO */
    @Column(length = 50)
    private String status;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "developer_id")
    private DashUser developer;

    @Column(name = "created_date")
    private LocalDate createdDate;

    @Column(name = "completed_date")
    private LocalDate completedDate;
}