package com.learning.springboot.repository;

import com.learning.springboot.entity.GomokuRoom;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface GomokuRoomRepository extends JpaRepository<GomokuRoom, Long> {

    Optional<GomokuRoom> findByRoomCode(String roomCode);

    /** 列出当前对局中的房间(供大厅列表) */
    List<GomokuRoom> findByStatusInOrderByIdDesc(List<Integer> statuses, org.springframework.data.domain.Pageable pageable);
}