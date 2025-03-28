package com.example.llmn.domain.remote;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SshInfoRepository extends JpaRepository<SshInfo, Long> {

    @Query("SELECT s FROM SshInfo s WHERE s.user.id = :userId")
    List<SshInfo> findByUserId(@Param("userId") Long userId);

    @Query("SELECT s.remoteHost FROM SshInfo s WHERE s.id = :id")
    Optional<String> findHostById(@Param("id") Long id);

    @Modifying
    @Query("DELETE FROM SshInfo s WHERE s.user.id = :userId")
    void deleteByUserId(Long userId);
}
