package com.example.llmn.domain.metric;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface MetricRepository extends JpaRepository<Metric, Long> {
    @Query("SELECT m FROM Metric m WHERE m.createdDate >= :date AND m.serverInstance.id = :serverInstanceId")
    List<Metric> findMetricsAfter(@Param("date") LocalDateTime date, @Param("serverInstanceId") Long serverInstanceId);

    @Modifying
    @Query("DELETE FROM Metric m WHERE m.serverInstance.user = :userId")
    void deleteByUserId(Long userId);

    @Modifying
    @Query("DELETE FROM Metric m WHERE m.serverInstance.id = :serverInstanceId")
    void deleteByServerInstanceId(Long serverInstanceId);
}
