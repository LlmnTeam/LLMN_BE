package com.example.llmn.repository;

import com.example.llmn.domain.Alarm;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AlarmRepository extends JpaRepository<Alarm, Long> {

    List<Alarm> findByReceiverId(Long receiverId);
}
