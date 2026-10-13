package com.learning.springboot.repository;

import com.learning.springboot.entity.UserDataRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 个人数据导入导出记录 Repository
 */
public interface UserDataRecordRepository extends JpaRepository<UserDataRecord, Long> {

    List<UserDataRecord> findByUserIdOrderByCreateTimeDescIdDesc(Long userId);

    Optional<UserDataRecord> findByIdAndUserId(Long id, Long userId);
}
