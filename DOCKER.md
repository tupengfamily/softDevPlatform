# Docker 一键部署

一条命令启动整个平台(后端 Spring Boot + 前端 nginx + 反向代理):

```bash
docker compose up -d --build
```

启动完成后访问 **http://localhost** 即可使用(默认端口 80)。

## 已集成的游戏模块

| 模块 | 路由 | 后端类 | 备注 |
| --- | --- | --- | --- |
| 2048 | `/games/2048` | `GameController` | 原生 |
| 五子棋 | `/games/gomoku` | `GomokuController` | 联机对战(短轮询) |
| 猜数字 / 数独 / 一笔画 | `/games/*` | `GameController` | 原生 |
| **稼穑** | **`/games/farm`** | **`FarmController`** | **🟢 2026-10-18 集成** |

### 稼穑小游戏说明

`/api/games/farm/*` 提供四个端点:

| 端点 | 作用 |
| --- | --- |
| `GET /state` | 当前农田物候 / 天气 / 产量预览 |
| `POST /advance?days=N` | 演示模式: 推进 N 天(**仅在 `farm.demo-mode=true` 时可用**) |
| `POST /harvest` | 收割并记录到游戏中心成绩 |
| `POST /restart` | 放弃本季、重开 |

引擎位于 `spring-boot-learning/src/main/java/com/learning/springboot/farm/engine/`,演示天气为 `src/main/resources/farm/weather-nanchang.json`(2019 年南昌实测,按日历日重放,非实时天气)。

`farm.demo-mode` 在 `application.yml` 中,默认 `true`(内测用)。**正式部署必须改为 `false`**: 1:1 真实时间下"推进时间"违反设计基线 D2。

### Dockerfile 影响

由于稼穑引擎是 `spring-boot-learning` 模块的一部分,**多阶段 Dockerfile 行为不变**:
- 第一阶段 `mvn package` 会自动把新加的 farm 模块一起打包
- 第二阶段 `java -jar` 启动时由 Spring Boot 组件扫描发现 `FarmController` 并自动挂载
- 演示天气 JSON 会被 `mvn` 资源处理器打到 jar 内的 `BOOT-INF/classes/farm/`

无需修改 Dockerfile 与 docker-compose.yml。

### 本地非 Docker 启动

```bash
# 项目根目录
./start-all.sh         # 一键启动前后端（脚本里包含稼穑模块自检）

# 单独验证
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
    -H "Content-Type: application/json" \
    -d '{"username":"admin","password":"admin123"}' \
    | /Users/tupengfamily/.workbuddy/binaries/node/versions/22.22.2-6/bin/node -e "let d='';process.stdin.on('data',c=>d+=c).on('end',()=>console.log(JSON.parse(d).data.token))")
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/games/farm/state
```

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
