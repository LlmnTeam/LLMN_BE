package com.example.llmn.repository;

import com.example.llmn.domain.Project;
import com.example.llmn.domain.Summary;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SummaryRepository extends JpaRepository<Summary, Long> {

    @Query("SELECT s.content FROM Summary s WHERE s.project = :project ORDER BY s.id DESC")
    Page<String> findLatestSummaryByProject(@Param("project") Project project, Pageable pageable);
}
