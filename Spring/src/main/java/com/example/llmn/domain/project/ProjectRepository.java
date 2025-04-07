package com.example.llmn.domain.project;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProjectRepository extends JpaRepository<Project, Long> {

    @EntityGraph(attributePaths = {"serverInstance"})
    @Query("SELECT p FROM Project p")
    List<Project> findAllWithServerInstance();

    @EntityGraph(attributePaths = {"user", "serverInstance"})
    @Query("SELECT p FROM Project p WHERE p.id = :projectId")
    Optional<Project> findByIdWithUserAndServerInstance(@Param("projectId") Long projectId);

    @Query("SELECT p FROM Project p WHERE p.id = :projectId")
    Optional<Project> findByIdWithServerInstance(@Param("projectId") Long projectId);

    @Query("SELECT p FROM Project p WHERE p.user.id = :userId")
    List<Project> findByUserId(@Param("userId") Long userId);

    @EntityGraph(attributePaths = {"serverInstance"})
    @Query("SELECT p FROM Project p WHERE p.user.id = :userId")
    List<Project> findByUserIdWithServerInstance(@Param("userId") Long userId);

    @Query("SELECT s.id FROM Project p " +
            "JOIN p.serverInstance s " +
            "WHERE p.id = :projectId")
    Optional<Long> findServerInstanceId(@Param("projectId") Long projectId);

    @Modifying
    @Query("DELETE FROM Project p WHERE p.user.id = :userId")
    void deleteByUserId(Long userId);
}
