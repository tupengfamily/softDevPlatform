#!/bin/bash
# ============================================
# 一键停止前后端项目 + 清理所有残留进程/端口
# ============================================
# 设计要点:
# - 按端口杀(8080 后端, 5173-5180 前端含 Vite 端口重映射)
# - 按命令模式杀兜底(后端 jar / Vite / npm run dev)
# - 优雅停止(TERM) → 等 2s → 顽固 KILL(-9)
# - 清理 .run/*.pid / tmp 日志
# --clean 选项额外清理 SQLite WAL/SHM
# --force 跳过确认(脚本/CI 场景)
#
# 集成说明:
# - 后端模块列表包含原 spring-boot-learning 全部功能 + 稼穑小游戏（farm-game）
#   - 稼穑引擎位于 src/main/java/com/learning/springboot/farm/engine/
#   - 演示天气位于 src/main/resources/farm/weather-nanchang.json
#   - 启动后通过 GET /api/games/farm/state 验证模块已挂载
# - 8080 端口的 Spring Boot jar 进程即「通用学习平台后端」+「稼穑小游戏后端」

# 颜色
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

info()    { echo -e "${BLUE}[INFO]${NC} $1"; }
success() { echo -e "${GREEN}[✓]${NC} $1"; }
warn()    { echo -e "${YELLOW}[!]${NC} $1"; }
error()   { echo -e "${RED}[✗]${NC} $1"; }

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"
mkdir -p "$SCRIPT_DIR/.run"

# 安全杀单 PID
_safe_kill() {
    local p=$1
    [ -z "$p" ] && return 0
    [ "$p" = "1" ] && return 0
    [ "$p" = "$$" ] && return 0
    [ "$p" = "$PPID" ] && return 0
    kill -0 "$p" 2>/dev/null || return 0
    kill -TERM "$p" 2>/dev/null
}

# 按端口杀进程
kill_port() {
    local port=$1
    local label=$2
    local pids
    pids=$(lsof -ti :$port 2>/dev/null | tr '\n' ' ')
    if [ -z "$pids" ]; then
        info "  端口 $port 未占用 ($label 未运行)"
        return 0
    fi
    warn "  端口 $port 占用 PID=$pids, kill -TERM ..."
    for p in $pids; do _safe_kill "$p"; done
    sleep 2
    # 顽固 -KILL
    for p in $pids; do
        [ -z "$p" ] && continue
        [ "$p" = "1" ] && continue
        [ "$p" = "$$" ] && continue
        [ "$p" = "$PPID" ] && continue
        if kill -0 "$p" 2>/dev/null; then
            kill -KILL "$p" 2>/dev/null
        fi
    done
    success "  端口 $port 已释放 ($label)"
}

# 按命令模式杀进程(兜底)
kill_by_pattern() {
    local pattern="$1"
    local desc="$2"
    local pids
    pids=$(pgrep -fl "$pattern" 2>/dev/null | awk '{print $1}')
    if [ -z "$pids" ]; then
        return 0
    fi
    for p in $pids; do _safe_kill "$p"; done
    warn "  $desc 进程 PID=$pids, kill -TERM ..."
    sleep 1
    for p in $pids; do
        [ -z "$p" ] && continue
        [ "$p" = "1" ] && continue
        [ "$p" = "$$" ] && continue
        [ "$p" = "$PPID" ] && continue
        if kill -0 "$p" 2>/dev/null; then
            kill -KILL "$p" 2>/dev/null
        fi
    done
    success "  $desc 兜底清理完成"
}

echo ""
echo -e "${BLUE}============================================${NC}"
echo -e "${BLUE}  停止前后端项目${NC}"
echo -e "${BLUE}============================================${NC}"
echo ""

# 1) 端口清理: 后端 8080 + 前端 5173-5180(Vite 重映射范围)
info "[1/3] 端口清理 (8080 + 5173-5180)..."
kill_port 8080 "后端"
kill_port 5173 "前端 vite"
kill_port 5174 "前端 vite"
kill_port 5175 "前端 vite"
kill_port 5176 "前端 vite"
kill_port 5177 "前端 vite"
kill_port 5178 "前端 vite"
kill_port 5179 "前端 vite"
kill_port 5180 "前端 vite"

# 2) 按命令模式杀兜底(防 lsof 漏抓)
info "[2/3] 命令模式兜底清理..."
kill_by_pattern "spring-boot-learning-.*\\.jar" "后端 jar"
kill_by_pattern "spring-boot:run" "mvn spring-boot"
kill_by_pattern "vite" "前端 vite"
kill_by_pattern "node.*frontend-learning.*\\.bin" "前端 npm run dev"

# 3) PID 文件 + 日志清理
info "[3/3] 清理 PID 文件 + 日志..."
[ -f "$SCRIPT_DIR/.run/backend.pid" ] && rm -f "$SCRIPT_DIR/.run/backend.pid" && success "  backend.pid 已清理"
[ -f "$SCRIPT_DIR/.run/frontend.pid" ] && rm -f "$SCRIPT_DIR/.run/frontend.pid" && success "  frontend.pid 已清理"
# /tmp 下的日志(只有自己启动的)
[ -f /tmp/springboot.log ] && rm -f /tmp/springboot.log
[ -f /tmp/frontend.log ] && rm -f /tmp/frontend.log

echo ""
echo -e "${GREEN}============================================${NC}"
echo -e "${GREEN}  ✓ 所有服务已停止${NC}"
echo -e "${GREEN}============================================${NC}"
echo ""

# --clean 清理 SQLite 临时
if [ "$1" = "--clean" ]; then
    warn "清理 SQLite 临时文件 (data/*-wal, *-shm, *-journal)..."
    rm -f "$SCRIPT_DIR/spring-boot-learning/data/"*-wal \
          "$SCRIPT_DIR/spring-boot-learning/data/"*-shm \
          "$SCRIPT_DIR/spring-boot-learning/data/"*-journal 2>/dev/null
    success "SQLite 临时文件已清理"
fi