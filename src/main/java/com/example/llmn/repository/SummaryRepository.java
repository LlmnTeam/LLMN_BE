package com.example.llmn.repository;

import com.example.llmn.domain.Project;
import com.example.llmn.domain.Summary;
import com.example.llmn.domain.SummaryType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface SummaryRepository extends JpaRepository<Summary, Long> {

    @Query("SELECT s FROM Summary s " +
            "JOIN s.project p " +
            "WHERE p = :project")
    Page<Summary> findLatestSummaryByProject(@Param("project") Project project, Pageable pageable);

    @Query("SELECT s FROM Summary s " +
            "JOIN s.project p " +
            "WHERE p.id = :projectId")
    Page<Summary> findSummaryByProjectId(@Param("projectId") Long projectId, Pageable pageable);

    @Query("SELECT s FROM Summary s " +
            "WHERE s.summaryType IN :types " +
            "AND s.id = (SELECT MAX(subS.id) FROM Summary subS WHERE subS.summaryType = s.summaryType)")
    List<Summary> findLatestByTypes(@Param("types") List<SummaryType> types);

    @Query("SELECT s FROM Summary s " +
            "WHERE s.summaryType = :type")
    Page<Summary> findSummaryByType(@Param("type") SummaryType type, Pageable pageable);

    @Query("SELECT s FROM Summary s WHERE s.summaryType IN :types AND s.createdDate >= :startOfDay")
    List<Summary> findByTypeWithinDate(@Param("types") List<SummaryType> types, @Param("startOfDay") LocalDateTime startOfDay);
}
