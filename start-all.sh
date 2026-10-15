#!/bin/bash
# ============================================
# 一键启动前后端项目
# ============================================
# 后端: Spring Boot (http://localhost:8080)
# 前端: Vue 3 + Vite (http://localhost:5173)

set -e

# 颜色输出
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
MAGENTA='\033[0;35m'
NC='\033[0m' # No Color

# 工作目录(脚本所在目录)
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

# 日志与 PID 文件
mkdir -p "$SCRIPT_DIR/.run"
BACKEND_LOG="/tmp/springboot.log"
FRONTEND_LOG="/tmp/frontend.log"
BACKEND_PID_FILE="$SCRIPT_DIR/.run/backend.pid"
FRONTEND_PID_FILE="$SCRIPT_DIR/.run/frontend.pid"

# 打印彩色输出
info()    { echo -e "${BLUE}[INFO]${NC} $1"; }
success() { echo -e "${GREEN}[✓]${NC} $1"; }
warn()    { echo -e "${YELLOW}[!]${NC} $1"; }
error()   { echo -e "${RED}[✗]${NC} $1"; }
step()    { echo -e "${MAGENTA}[STEP]${NC} $1"; }

# 失败计数
FAIL=0

# ===========================================
# 0) 第一步: 强制清理端口 + 残留进程(关键, 避免端口冲突)
#    - 后端 8080
#    - 前端 5173-5180(Vite 默认 5173, 但端口冲突/沙箱重映射时会顺延到 5174-5180)
#    - 同时按进程命令杀 java / node 派生,兜底杀掉不监听到端口但仍占资源的进程
#    SKIP_PORT_CLEAN=1 可跳过(紧急情况)
# ===========================================

# 安全杀指定 PID (避免 PID 1 / 自身 / shell 父进程)
_safe_kill() {
    local p=$1
    [ -z "$p" ] && return 0
    [ "$p" = "1" ] && return 0
    [ "$p" = "$$" ] && return 0
    [ "$p" = "$PPID" ] && return 0
    kill -0 "$p" 2>/dev/null || return 0
    kill -TERM "$p" 2>/dev/null
    return 0
}

# 杀单个端口的所有进程(TERM → 等 2s → KILL 顽固)
kill_port() {
    local port=$1
    local pids
    pids=$(lsof -ti :$port 2>/dev/null | tr '\n' ' ')
    [ -z "$pids" ] && return 0
    for p in $pids; do
        _safe_kill "$p"
        warn "  端口 $port 占用 PID=$p, kill -TERM ..."
    done
    sleep 2
    for p in $pids; do
        [ -z "$p" ] && continue
        [ "$p" = "1" ] && continue
        [ "$p" = "$$" ] && continue
        [ "$p" = "$PPID" ] && continue
        if kill -0 "$p" 2>/dev/null; then
            warn "  PID=$p 不响应 TERM, 强制 KILL -9"
            kill -KILL "$p" 2>/dev/null
        fi
    done
    sleep 1
}

# 按进程命令模式杀进程(兜底, 用于 lsof 抓不到但仍占资源的进程)
# 用法: kill_by_pattern <pattern> <description>
# 模式匹配的是 ps -o args 输出(包含命令行); 用 grep -E
kill_by_pattern() {
    local pattern="$1"
    local desc="$2"
    # 仅看自己的进程,避免误伤系统进程
    local pids
    pids=$(pgrep -fl "$pattern" 2>/dev/null | awk '{print $1}')
    [ -z "$pids" ] && return 0
    for p in $pids; do
        _safe_kill "$p"
        warn "  $desc 进程 PID=$p, kill -TERM ..."
    done
    sleep 2
    for p in $pids; do
        [ -z "$p" ] && continue
        [ "$p" = "1" ] && continue
        [ "$p" = "$$" ] && continue
        [ "$p" = "$PPID" ] && continue
        if kill -0 "$p" 2>/dev/null; then
            kill -KILL "$p" 2>/dev/null
        fi
    done
}

if [ "${SKIP_PORT_CLEAN:-0}" = "1" ]; then
    info "SKIP_PORT_CLEAN=1, 跳过端口清理"
else
    step "① 清理占用端口与残留进程(确保启动是干净状态)..."

    # 端口段: 5173-5180(Vite dev server 默认 + 重映射顺延)
    declare -a PORTS_TO_CLEAN=("8080" "5173" "5174" "5175" "5176" "5177" "5178" "5179" "5180")
    OCCUPIED=()
    for port in "${PORTS_TO_CLEAN[@]}"; do
        n=$(lsof -ti :$port 2>/dev/null | wc -l | tr -d ' ')
        if [ "$n" -gt 0 ]; then
            OCCUPIED+=("$port($n)")
            kill_port "$port"
        fi
    done

    # 兜底: 按进程命令杀 Vite / 后端 jar / mvn spring-boot 派生(覆盖 lsof 抓不到的情况)
    kill_by_pattern "spring-boot-learning-.*\\.jar" "后端 jar"
    kill_by_pattern "vite" "前端 vite"
    kill_by_pattern "node.*frontend-learning.*\\.bin" "前端 npm run dev"

    # 二次确认
    REMAINING=""
    for port in "${PORTS_TO_CLEAN[@]}"; do
        n=$(lsof -ti :$port 2>/dev/null | wc -l | tr -d ' ')
        [ "$n" -gt 0 ] && REMAINING="$REMAINING $port($n)"
    done
    if [ -z "$REMAINING" ]; then
        if [ ${#OCCUPIED[@]} -gt 0 ]; then
            success "端口清理完成(原占用: ${OCCUPIED[*]})"
        else
            success "所有端口空闲(8080 / 5173-5180)"
        fi
    else
        warn "仍有进程占用:$REMAINING, 启动可能失败"
    fi

    # 清理 .run/ 下历史 PID 文件
    rm -f "$SCRIPT_DIR/.run/backend.pid" "$SCRIPT_DIR/.run/frontend.pid" 2>/dev/null
    echo ""
fi

# 提取版本号中的主版本数字(支持 "21.0.3" / "v18.17.0" / "1.2.3" 等格式)
version_major() {
    echo "$1" | sed -E 's/^[vV]?//' | awk -F. '{print $1}'
}

# 比较两个数字大小: 第一个 >= 第二个 返回 0, 否则返回 1
ver_ge() {
    [ "$(version_major "$1")" -ge "$2" ]
}

# ===========================================
# 标题
# ===========================================
echo ""
echo -e "${CYAN}============================================${NC}"
echo -e "${CYAN}  Java 全栈项目 - 一键启动${NC}"
echo -e "${CYAN}============================================${NC}"
echo ""

# ===========================================
# 1) 基础环境检查(Java / Maven / Node / npm)
# ===========================================
step "① 检查基础运行环境..."

# java (输出格式: openjdk version "21.0.3" 2026-09-15; 第 3 个字段是带引号的版本号)
if command -v java >/dev/null 2>&1; then
    JAVA_VER=$(java -version 2>&1 | head -1 | awk '{print $3}' | tr -d '"')
    JAVA_MAJOR=$(version_major "$JAVA_VER")
    if ver_ge "$JAVA_VER" 17; then
        success "Java:   $JAVA_VER  (>= 17 ✓)"
    else
        error "Java:   $JAVA_VER  (需要 >= 17)"
        FAIL=1
    fi
else
    error "未找到 java,请安装 JDK 17+ (推荐 21 LTS)"
    FAIL=1
fi

# mvn
if command -v mvn >/dev/null 2>&1; then
    MVN_VER=$(mvn -version 2>&1 | head -1 | awk '{print $NF}')
    success "Maven:  $MVN_VER"
else
    error "未找到 mvn,请安装 Maven 3.6+"
    FAIL=1
fi

# node
if command -v node >/dev/null 2>&1; then
    NODE_VER=$(node -v)
    NODE_MAJOR=$(version_major "$NODE_VER")
    if ver_ge "$NODE_VER" 18; then
        success "Node:   $NODE_VER  (>= 18 ✓)"
    else
        error "Node:   $NODE_VER  (需要 >= 18)"
        FAIL=1
    fi
else
    error "未找到 node,请安装 Node.js 18+"
    FAIL=1
fi

# npm
if command -v npm >/dev/null 2>&1; then
    NPM_VER=$(npm -v)
    success "npm:    $NPM_VER"
else
    error "未找到 npm,请安装 Node.js"
    FAIL=1
fi

# sqlite3(可选, 用于调试)
if command -v sqlite3 >/dev/null 2>&1; then
    success "sqlite3:$(sqlite3 -version | awk '{print $1}')  (用于调试数据库)"
else
    warn "sqlite3 CLI 未安装(可选, 仅用于查看 data/admin.db)"
fi

if [ $FAIL -ne 0 ]; then
    echo ""
    error "基础环境检查失败,请先安装缺失的依赖"
    exit 1
fi
echo ""

# ===========================================
# 2) 后端依赖检查
# ===========================================
step "② 检查后端 Maven 依赖..."

cd "$SCRIPT_DIR/spring-boot-learning"

# pom.xml 必须存在
if [ ! -f "pom.xml" ]; then
    error "未找到 pom.xml, 当前目录不是 spring-boot-learning"
    exit 1
fi

# 检查 Maven 本地仓库中是否已下载过依赖
# 通过查看 ~/.m2/repository 下的关键依赖是否存在
MAVEN_REPO="${M2_HOME:-$HOME/.m2}/repository"
check_maven_dep() {
    local group_path="$1"
    local artifact="$2"
    local hint_version="$3"
    if [ -d "$MAVEN_REPO/$group_path/$artifact" ]; then
        return 0
    fi
    return 1
}

# 检查几个关键依赖
BACKEND_DEPS_OK=1
if ! check_maven_dep "org/springframework/boot/spring-boot-starter-web" "spring-boot-starter-web" "3.3.5"; then
    BACKEND_DEPS_OK=0
fi
if ! check_maven_dep "io/jsonwebtoken/jjwt-api" "jjwt-api" "0.12.6"; then
    BACKEND_DEPS_OK=0
fi
if ! check_maven_dep "org/xerial/sqlite-jdbc" "sqlite-jdbc" "3.46"; then
    BACKEND_DEPS_OK=0
fi

if [ $BACKEND_DEPS_OK -eq 1 ]; then
    success "后端依赖已就绪(关键包已在 ~/.m2/repository 中)"
else
    warn "后端 Maven 依赖未完全下载, 启动时将自动拉取"
    info "  首次运行可能需要 1-3 分钟, 请耐心等待"
fi

# ===========================================
# 3) 前端依赖检查
# ===========================================
step "③ 检查前端 npm 依赖..."

cd "$SCRIPT_DIR/frontend-learning"

if [ ! -f "package.json" ]; then
    error "未找到 package.json, 当前目录不是 frontend-learning"
    exit 1
fi

# 检查 node_modules 是否存在
if [ ! -d "node_modules" ]; then
    warn "node_modules 不存在,需要执行 npm install"
    INSTALL_FRONTEND=1
else
    INSTALL_FRONTEND=0

    # 检查关键依赖是否在 node_modules 中
    MISSING_DEPS=()
    for pkg in vue vue-router element-plus axios pinia; do
        if [ ! -d "node_modules/$pkg" ]; then
            MISSING_DEPS+=("$pkg")
        fi
    done

    if [ ${#MISSING_DEPS[@]} -gt 0 ]; then
        warn "以下关键包未找到: ${MISSING_DEPS[*]}"
        INSTALL_FRONTEND=1
    else
        success "前端依赖已就绪(vue / vue-router / element-plus / axios / pinia)"
    fi
fi

# 安装缺失的前端依赖
if [ $INSTALL_FRONTEND -eq 1 ]; then
    step "安装前端依赖..."
    if npm install --no-audit --no-fund; then
        success "前端依赖安装完成"
    else
        error "前端依赖安装失败,请检查 npm 配置或网络"
        echo ""
        echo "  常见解决方案:"
        echo "    1) 切换 npm 镜像: npm config set registry https://registry.npmmirror.com"
        echo "    2) 检查网络: ping registry.npmjs.org"
        echo "    3) 删除 node_modules 后手动: rm -rf node_modules && npm install"
        exit 1
    fi
fi
echo ""

# ===========================================
# 4) SQLite 数据目录检查
# ===========================================
step "④ 检查 SQLite 数据目录..."
mkdir -p "$SCRIPT_DIR/spring-boot-learning/data"
if [ -d "$SCRIPT_DIR/spring-boot-learning/data" ]; then
    success "数据目录就绪: spring-boot-learning/data/"
fi
echo ""

# ===========================================
# 5) 端口占用检查
# ===========================================
step "⑤ 检查端口占用..."

check_port() {
    local port=$1
    if lsof -ti :$port >/dev/null 2>&1; then
        echo "$port"
    fi
}

occupied=$(check_port 8080)
if [ -n "$occupied" ]; then
    warn "端口 8080 已被占用(PID: $occupied)"
    warn "  后端可能已在运行, 如需重启请执行: ./stop-all.sh"
fi

occupied=$(check_port 5173)
if [ -n "$occupied" ]; then
    warn "端口 5173 已被占用(PID: $occupied)"
    warn "  前端可能已在运行, 如需重启请执行: ./stop-all.sh"
fi

if [ -z "$(check_port 8080)" ] && [ -z "$(check_port 5173)" ]; then
    success "端口 8080 / 5173 均空闲"
fi
echo ""

# ===========================================
# 6) 检测并清理当前项目的遗留进程
# ===========================================
step "⑥ 检测本项目的遗留进程..."

# 收集本项目相关进程:
#   A) 启动方式是 "mvn spring-boot:run"
#   B) Java 主类是 SpringBootLearningApplication
#   C) node 进程的工作目录或命令包含 frontend-learning/vite
PROJECT_PIDS=""

# A) mvn spring-boot:run
while read -r pid; do
    [ -z "$pid" ] && continue
    PROJECT_PIDS="$PROJECT_PIDS $pid"
done < <(ps -axo pid,command | grep "spring-boot:run" | grep -v grep | awk '{print $1}')

# B) SpringBootLearningApplication 主类
while read -r pid; do
    [ -z "$pid" ] && continue
    # 排除重复
    case " $PROJECT_PIDS " in
        *" $pid "*) continue ;;
    esac
    PROJECT_PIDS="$PROJECT_PIDS $pid"
done < <(ps -axo pid,command | grep -E "SpringBootLearningApplication|spring-boot-learning" | grep -v grep | grep -v "spring-boot:run" | awk '{print $1}')

# C) Vite/Node 启动了 frontend-learning
while read -r pid; do
    [ -z "$pid" ] && continue
    case " $PROJECT_PIDS " in
        *" $pid "*) continue ;;
    esac
    # vite 直接的或 npm run dev 父进程
    if ps -p "$pid" -o command= 2>/dev/null | grep -q "frontend-learning"; then
        PROJECT_PIDS="$PROJECT_PIDS $pid"
    fi
done < <(ps -axo pid,command | grep -E "vite|npm run dev" | grep -v grep | awk '{print $1}')

# 去重 + 排序
PROJECT_PIDS=$(echo "$PROJECT_PIDS" | tr ' ' '\n' | grep -v '^$' | sort -un | tr '\n' ' ')

if [ -z "$(echo "$PROJECT_PIDS" | tr -d ' ')" ]; then
    success "未发现本项目遗留进程"
else
    PIDS_ARRAY=($PROJECT_PIDS)
    PROC_COUNT=${#PIDS_ARRAY[@]}

    warn "发现 $PROC_COUNT 个本项目遗留进程:"
    for pid in $PROJECT_PIDS; do
        # 查端口
        port=$(lsof -p "$pid" 2>/dev/null | grep LISTEN | awk '{print $9}' | grep -E ":[0-9]+" | head -1 | sed 's/.*://')
        # 查简短命令
        cmd=$(ps -p "$pid" -o command= 2>/dev/null | head -c 80 | tr -d '\n')
        if [ -n "$port" ]; then
            echo "    PID=$pid  port=$port  cmd=$cmd"
        else
            echo "    PID=$pid  cmd=$cmd"
        fi
    done

    # 默认清理(非交互式,避免卡住),除非显式 SKIP_CLEAN=1
    if [ "${SKIP_CLEAN:-0}" = "1" ]; then
        warn "SKIP_CLEAN=1 设置, 跳过自动 kill"
    else
        echo ""
        info "正在清理遗留进程..."
        for pid in $PROJECT_PIDS; do
            if kill -0 "$pid" 2>/dev/null; then
                kill -TERM "$pid" 2>/dev/null
            fi
        done

        # 等待最多 10 秒让进程退出
        for i in {1..10}; do
            sleep 1
            REMAINING=0
            for pid in $PROJECT_PIDS; do
                if kill -0 "$pid" 2>/dev/null; then
                    REMAINING=$((REMAINING + 1))
                fi
            done
            if [ $REMAINING -eq 0 ]; then
                success "所有遗留进程已退出"
                break
            fi
        done

        # 仍有顽固进程, 强制 KILL
        for pid in $PROJECT_PIDS; do
            if kill -0 "$pid" 2>/dev/null; then
                warn "  进程 $pid 不响应 TERM, 强制 KILL"
                kill -KILL "$pid" 2>/dev/null
            fi
        done
        sleep 1
        success "清理完毕"

        # 同时清理 PID 文件(可能是上次崩溃留下)
        rm -f "$BACKEND_PID_FILE" "$FRONTEND_PID_FILE" 2>/dev/null
    fi
fi
echo ""

# ===========================================
# 7) 启动后端
# ===========================================
step "⑦ 启动后端 Spring Boot..."
cd "$SCRIPT_DIR/spring-boot-learning"

# 后台启动 mvn(显式传 SERVER_PORT=8080, 覆盖沙箱可能注入的其他端口, 避免监听错位)
nohup env SERVER_PORT=8080 mvn spring-boot:run > "$BACKEND_LOG" 2>&1 &
BACKEND_PID=$!
echo $BACKEND_PID > "$BACKEND_PID_FILE"

info "后端进程 PID: $BACKEND_PID, 日志: $BACKEND_LOG"

# 等待后端就绪(最多 90 秒,首次启动要拉依赖)
info "等待后端启动(端口 8080, 首次启动可能需要 60-90 秒)..."
# 探活 URL 选 /api/auth/public-key(免鉴权 + 必存在)
HEALTH_URL="http://localhost:8080/api/auth/public-key"
HEALTH_OK=0
for i in {1..45}; do
    sleep 2
    if curl -sf "$HEALTH_URL" >/dev/null 2>&1; then
        success "后端启动成功 (用时约 $((i*2)) 秒)"
        HEALTH_OK=1
        break
    fi
    # 检查进程是否还在
    if ! kill -0 "$BACKEND_PID" 2>/dev/null; then
        error "后端进程已退出, 请查看日志: tail -50 $BACKEND_LOG"
        exit 1
    fi
    # 每 10 秒给个进度提示
    if [ $((i % 5)) -eq 0 ]; then
        info "  等待中 ($((i*2))/90 秒)..."
    fi
done

if [ $HEALTH_OK -ne 1 ]; then
    error "后端启动超时(90秒), 请查看日志: tail -50 $BACKEND_LOG"
    exit 1
fi
echo ""

# ===========================================
# 8) 启动前端
# ===========================================
step "⑧ 启动前端 Vue + Vite..."
cd "$SCRIPT_DIR/frontend-learning"

# 后台启动 Vite
# 显式传 BACKEND_PORT=8080, vite proxy 才能跟后端对齐
nohup env BACKEND_PORT=8080 npm run dev > "$FRONTEND_LOG" 2>&1 &
FRONTEND_PID=$!
echo $FRONTEND_PID > "$FRONTEND_PID_FILE"

info "前端进程 PID: $FRONTEND_PID, 日志: $FRONTEND_LOG"

# 等待前端就绪(最多 30 秒)
info "等待前端启动(端口 5173)..."
FRONT_OK=0
for i in {1..15}; do
    sleep 2
    if curl -sf http://localhost:5173 >/dev/null 2>&1; then
        success "前端启动成功 (用时约 $((i*2)) 秒)"
        FRONT_OK=1
        break
    fi
    if ! kill -0 "$FRONTEND_PID" 2>/dev/null; then
        error "前端进程已退出, 请查看日志: tail -50 $FRONTEND_LOG"
        exit 1
    fi
done

if [ $FRONT_OK -ne 1 ]; then
    error "前端启动超时(30秒), 请查看日志: tail -50 $FRONTEND_LOG"
    exit 1
fi
echo ""

# ===========================================
# 总结
# ===========================================
echo -e "${CYAN}============================================${NC}"
echo -e "${GREEN}  ✓ 前后端项目全部启动成功!${NC}"
echo -e "${CYAN}============================================${NC}"
echo ""
echo -e "  ${YELLOW}🎨 前端(Vue 3 + Element Plus + Pinia)${NC}"
echo -e "     http://localhost:5173"
echo ""
echo -e "  ${YELLOW}☕ 后端(Spring Boot 3 + JWT + SQLite)${NC}"
echo -e "     http://localhost:8080"
echo -e "     数据库: $SCRIPT_DIR/spring-boot-learning/data/admin.db"
echo ""
echo -e "  ${BLUE}🔐 默认账号${NC}"
echo -e "     admin / admin123  (ADMIN 角色,全部权限)"
echo -e "     user  / user123   (USER 角色,只读权限)"
echo ""
echo -e "  ${BLUE}📋 查看日志${NC}"
echo -e "     tail -f $BACKEND_LOG    (后端)"
echo -e "     tail -f $FRONTEND_LOG   (前端)"
echo ""
echo -e "  ${RED}🛑 停止服务${NC}"
echo -e "     ./stop-all.sh          (停止并保留数据)"
echo -e "     ./stop-all.sh --clean  (停止并清理 SQLite WAL/SHM)"
echo ""

# 询问是否打开浏览器
if command -v open >/dev/null 2>&1; then
    read -p "$(echo -e ${CYAN}是否打开浏览器? [y/N]: ${NC})" -n 1 -r
    echo ""
    if [[ $REPLY =~ ^[Yy]$ ]]; then
        open http://localhost:5173
    fi
fi