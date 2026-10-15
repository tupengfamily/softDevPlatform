package com.learning.springboot.repository;

import com.learning.springboot.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 用户 Repository(数据访问层)
 * <p>
 * 演示三种查询风格:
 * 1. 方法名派生 (Derived Query) — Spring Data 自动生成 SQL
 * 2. JPQL — 面向实体的查询语言
 * 3. 原生 SQL (nativeQuery = true) — 直接写 SQL,适合复杂查询
 * <p>
 * 自动提供的方法(JpaRepository):
 * - save(entity): 保存/更新
 * - findById(id): 按 ID 查
 * - findAll(): 查全部
 * - deleteById(id): 按 ID 删
 * - count(): 统计
 * - existsById(id): 判断是否存在
 */
@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    // ============ 派生方法 ============

    Optional<User> findByUsername(String username);

    Optional<User> findByEmail(String email);

    boolean existsByUsername(String username);

    List<User> findByStatus(Integer status);

    Page<User> findByStatus(Integer status, Pageable pageable);

    long countByUsername(String username);

    // ============ JPQL 查询 ============

    /**
     * 关联查询角色(避免 N+1)
     * JOIN FETCH: 一次查询把 roles 也加载出来
     */
    @Query("SELECT DISTINCT u FROM User u LEFT JOIN FETCH u.roles WHERE u.username = :username")
    Optional<User> findByUsernameWithRoles(@Param("username") String username);

    /**
     * 查询全部用户并预加载角色
     * <p>
     * 用于 /api/users 兼容接口: roles 是懒加载,直接 findAll() 会在序列化时
     * (事务已关闭)抛 LazyInitializationException,故用 JOIN FETCH 一次取全。
     */
    @Query("SELECT DISTINCT u FROM User u LEFT JOIN FETCH u.roles ORDER BY u.id")
    List<User> findAllWithRoles();

    /**
     * 多条件动态分页查询
     */
    @Query("""
        SELECT u FROM User u
        WHERE (:keyword IS NULL OR u.username LIKE %:keyword% OR u.email LIKE %:keyword%)
          AND (:status IS NULL OR u.status = :status)
        """)
    Page<User> search(@Param("keyword") String keyword,
                      @Param("status") Integer status,
                      Pageable pageable);

    /**
     * 批量修改状态
     */
    @Modifying
    @Query("UPDATE User u SET u.status = :status WHERE u.id IN :ids")
    int batchUpdateStatus(@Param("status") Integer status, @Param("ids") List<Long> ids);

    // ============ 自定义 SQL ============

    /**
     * 按用户名模糊查询(原生 SQL)
     */
    @Query(value = "SELECT * FROM t_user WHERE username LIKE %:keyword% ORDER BY id DESC",
            nativeQuery = true)
    List<User> searchByUsernameLike(@Param("keyword") String keyword);

    /**
     * 按年龄范围查询
     */
    List<User> findByAgeBetween(Integer startAge, Integer endAge);

    // ============ 原生 SQL ============

    /**
     * 按状态分组统计(原生 SQL)
     */
    @Query(value = "SELECT status, COUNT(*) AS cnt FROM t_user GROUP BY status", nativeQuery = true)
    List<Object[]> countGroupByStatus();

    /**
     * 按角色编码查找用户(原生 SQL 三表 JOIN)
     */
    @Query(value = """
        SELECT u.* FROM t_user u
        JOIN t_user_role ur ON u.id = ur.user_id
        JOIN t_role r ON ur.role_id = r.id
        WHERE r.code = :code
        """, nativeQuery = true)
    List<User> findByRoleCode(@Param("code") String code);

    /** 单字段查询: tokenVersion(SSO 失效比对,避免加载整个 User 实体) */
    @Query("SELECT u.tokenVersion FROM User u WHERE u.id = :id")
    Long findTokenVersionById(@Param("id") Long id);

    /** 递增 tokenVersion(SSO 单点登出 / 退出所有设备) */
    @Modifying
    @org.springframework.data.jpa.repository.Query("UPDATE User u SET u.tokenVersion = COALESCE(u.tokenVersion, 0) + 1, u.updateTime = :now WHERE u.id = :id")
    @org.springframework.transaction.annotation.Transactional
    int incrementTokenVersion(@Param("id") Long id, @Param("now") java.time.LocalDateTime now);
}