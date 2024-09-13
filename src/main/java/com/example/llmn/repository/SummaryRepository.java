package com.example.llmn.repository;

import com.example.llmn.domain.Project;
import com.example.llmn.domain.Summary;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SummaryRepository extends JpaRepository<Summary, Long> {

    @Query("SELECT s.content FROM Summary s " +
            "JOIN s.project p " +
            "WHERE p = :project")
    Page<String> findLatestSummaryByProject(@Param("project") Project project, Pageable pageable);

    @Query("SELECT s FROM Summary s " +
            "JOIN s.project p " +
            "WHERE p.id = :id")
    Page<Summary> findSummaryById(@Param("id") Long id, Pageable pageable);
}
