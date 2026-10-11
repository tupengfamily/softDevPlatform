package com.learning.springboot.repository;

import com.learning.springboot.entity.ReleaseNote;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 版本更新公告 Repository
 */
public interface ReleaseNoteRepository extends JpaRepository<ReleaseNote, Long> {

    /** 全部公告,按发布日期倒序(新版本在前) */
    List<ReleaseNote> findAllByOrderByReleaseDateDescIdDesc();

    /** 最新一条公告(用于新版本弹窗) */
    Optional<ReleaseNote> findTopByOrderByReleaseDateDescIdDesc();

    /** 按版本号查(种子幂等 / 唯一性校验) */
    Optional<ReleaseNote> findByVersion(String version);
}
