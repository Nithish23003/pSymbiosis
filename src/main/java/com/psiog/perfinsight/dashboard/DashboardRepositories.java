package com.psiog.perfinsight.dashboard;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

interface DashUserRepository extends JpaRepository<DashUser, Long> {
    Optional<DashUser> findByEmailIgnoreCase(String email);
    List<DashUser> findByRoleIgnoreCase(String role);
}

interface JiraStoryRepository extends JpaRepository<JiraStory, Long> {
    Optional<JiraStory> findByStoryId(String storyId);
}

interface GithubActivityRepository extends JpaRepository<GithubActivity, Long> {
}