package com.learning.springboot.service;

import com.learning.springboot.dto.ReleaseNoteDTO;
import com.learning.springboot.entity.ReleaseNote;

import java.util.List;

/**
 * 版本更新公告服务接口
 */
public interface ReleaseNoteService {

    /** 全部公告(最新在前) */
    List<ReleaseNote> list();

    /** 最新一条公告(新版本弹窗用) */
    ReleaseNote latest();

    /** 发布新版本公告 */
    ReleaseNote create(ReleaseNoteDTO dto);

    /** 编辑公告 */
    ReleaseNote update(Long id, ReleaseNoteDTO dto);

    /** 删除公告 */
    void delete(Long id);
}
