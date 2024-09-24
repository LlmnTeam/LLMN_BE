package com.example.llmn.repository;

import com.example.llmn.domain.SshInfo;
import com.example.llmn.domain.User;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    @Query("SELECT u FROM User u WHERE u.email = :email")
    Optional<User> findByEmail(@Param("email") String email);

    @EntityGraph("sshInfo")
    @Query("SELECT u FROM User u WHERE u.id = :id")
    Optional<User> findByIdWithSshInfo(@Param("id") Long id);

    @Query("SELECT u.id FROM User u")
    List<Long> findIds();

    @Query("SELECT u.sshInfo FROM User u WHERE u.id = :id")
    Optional<SshInfo> findSshInfoById(@Param("id") Long id);

    @Query("SELECT COUNT(u) > 0 FROM User u WHERE u.email = :email")
    boolean existsByEmail(@Param("email") String email);

    @Query("SELECT COUNT(u) > 0 FROM User u WHERE u.nickName = :nickName")
    boolean existsByNickname(@Param("nickName") String nickName);

    @Query("SELECT COUNT(u) > 0 FROM User u WHERE u.email = :email")
    boolean existsByEmailWithRemoved(@Param("email") String email);
}
