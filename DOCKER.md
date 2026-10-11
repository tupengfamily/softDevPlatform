# Docker 一键部署

一条命令启动整个平台(后端 Spring Boot + 前端 nginx + 反向代理):

```bash
docker compose up -d --build
```

启动完成后访问 **http://localhost** 即可使用(默认端口 80)。

## 目录结构

| 文件 | 作用 |
| --- | --- |
| `docker-compose.yml` | 编排入口: backend + frontend 两个服务 |
| `spring-boot-learning/Dockerfile` | 后端镜像(多阶段: Maven 构建 → JDK21 运行, 含在线沙箱编译能力) |
| `frontend-learning/Dockerfile` | 前端镜像(多阶段: vite 构建 → nginx 托管) |
| `frontend-learning/nginx.conf` | nginx 配置: SPA 路由回退 + `/api` 反代后端 |

## 常用操作

```bash
# 查看运行状态
docker compose ps

# 查看后端日志(种子数据 / 启动信息)
docker compose logs -f backend

# 停止
docker compose down

# 重新构建并启动(发布新版本后)
docker compose up -d --build
```

## 数据持久化

SQLite 数据库文件位于宿主机 `./data` 目录,已挂载到容器 `/app/data`:

- `sandbox.db` — 主数据库(用户/角色/课程/进度/更新公告/反馈等)
- 教学沙箱库 — SQL 练习用独立数据文件

`docker compose down` 后再 `up` 数据不丢;只有删除 `./data` 目录才会重置(首次启动会自动建表并写入种子数据)。

## 自定义端口

宿主机 80 被占用时,指定其他端口启动:

```bash
HTTP_PORT=8088 docker compose up -d --build
```

## 默认账号

首次启动自动初始化:

| 账号 | 密码 | 角色 |
| --- | --- | --- |
| `admin` | `admin123` | 管理员 |
| `user` | `user123` | 普通用户 |

> 生产环境请立即修改默认密码,并为 JWT 配置独立密钥(环境变量覆盖 `jwt.secret`)。
