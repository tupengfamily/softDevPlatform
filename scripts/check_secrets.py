#!/usr/bin/env python3
"""
密钥泄漏扫描

在提交前检查仓库里有没有「硬编码的第三方凭据」。
项目约定: API Key / Secret 一律走「系统管理 → API 密钥」页面配置(后端加密落库),
不允许出现在源码、配置或文档里。

用法:
    python3 scripts/check_secrets.py            # 扫描整个仓库
    python3 scripts/check_secrets.py --staged   # 只扫 git 已暂存的文件

退出码: 0 = 干净, 1 = 发现疑似泄漏
"""

import argparse
import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# 扫描时跳过的目录
SKIP_DIRS = {
    ".git", "node_modules", "target", "dist", "dist-check", "distcheck",
    ".idea", ".vscode", ".workbuddy", ".hsk", "data", "public",
    "logs", "coverage",
}

# 跳过的文件后缀(二进制/产物)
SKIP_SUFFIX = {
    ".png", ".jpg", ".jpeg", ".gif", ".svg", ".ico", ".webp", ".woff",
    ".woff2", ".ttf", ".eot", ".db", ".db-journal", ".db-wal", ".db-shm",
    ".class", ".jar", ".war", ".zip", ".gz", ".pdf", ".mp4", ".bin", ".key",
}

# 明确的白名单: 这些是示例/占位/公开标识, 不是真凭据
WHITELIST = {
    # 文档与注释里出现的示例值
    "01c5170a669fe01df1b425a4e6066d2e",   # 天地图浏览器端 key(仅示例说明)
    "bfed05dfdeb2325b1462be93eb1efb48",   # 天地图服务端 key(仅示例说明)
}

# 规则: (正则, 说明)
RULES = [
    # 天地图: 32 位十六进制
    (re.compile(r"(?i)tianditu\.gov\.cn[^\n]{0,200}?tk=([0-9a-f]{32})"), "天地图 tk 参数"),
    (re.compile(r"(?i)TIANDITU_SERVER_KEY\s*[:=]\s*['\"]?([0-9a-f]{32})"), "TIANDITU_SERVER_KEY 硬编码"),
    # 常见密钥前缀
    (re.compile(r"\bsk-[A-Za-z0-9]{20,}"), "OpenAI 风格密钥 (sk-)"),
    (re.compile(r"\bsk-ant-[A-Za-z0-9_-]{20,}"), "Anthropic 密钥 (sk-ant-)"),
    (re.compile(r"\bghp_[A-Za-z0-9]{30,}"), "GitHub token (ghp_)"),
    (re.compile(r"\bgithub_pat_[A-Za-z0-9_]{50,}"), "GitHub fine-grained token"),
    (re.compile(r"\bAKIA[0-9A-Z]{16}\b"), "AWS Access Key ID"),
    (re.compile(r"\bAIza[0-9A-Za-z_-]{35}\b"), "Google API Key (AIza)"),
    (re.compile(r"\bxox[baprs]-[A-Za-z0-9-]{10,}"), "Slack token"),
    (re.compile(r"\b(?:api[_-]?key|apikey|secret[_-]?key|access[_-]?key|client[_-]?secret|"
                r"app[_-]?secret|private[_-]?key|password)\s*[:=]\s*"
                r"['\"]([^'\"\s]{16,})['\"]", re.I), "疑似硬编码凭据"),
    # 私钥块
    (re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH |PGP )?PRIVATE KEY-----"), "私钥文件内容"),
]

# 命中后还要再排除的上下文(说明这是占位符/示例)
PLACEHOLDER_HINTS = (
    "your", "example", "placeholder", "changeme", "xxx", "todo",
    "<", "${", "{{", "fake", "dummy", "sample", "test-key", "none", "null",
)


def is_ignored(path: str) -> bool:
    parts = path.split(os.sep)
    if any(p in SKIP_DIRS for p in parts):
        return True
    if os.path.splitext(path)[1].lower() in SKIP_SUFFIX:
        return True
    return False


def looks_like_placeholder(value: str) -> bool:
    low = value.lower()
    return any(h in low for h in PLACEHOLDER_HINTS)


def scan_text(text: str, path: str, findings: list) -> None:
    for line_no, line in enumerate(text.splitlines(), 1):
        if len(line) > 4000:
            continue
        for pattern, desc in RULES:
            for m in pattern.finditer(line):
                value = m.group(1) if m.groups() else m.group(0)
                if not value:
                    continue
                if value in WHITELIST:
                    continue
                if looks_like_placeholder(value):
                    continue
                findings.append((path, line_no, desc, value[:12] + "…"))


def collect_files(staged: bool):
    if staged:
        out = subprocess.run(
            ["git", "diff", "--cached", "--name-only", "--diff-filter=ACMR"],
            cwd=ROOT, capture_output=True, text=True, check=False,
        ).stdout.split()
        return out
    files = []
    for dirpath, dirnames, filenames in os.walk(ROOT):
        dirnames[:] = [d for d in dirnames if d not in SKIP_DIRS]
        for fn in filenames:
            full = os.path.join(dirpath, fn)
            rel = os.path.relpath(full, ROOT)
            if is_ignored(rel):
                continue
            files.append(rel)
    return files


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--staged", action="store_true", help="只扫 git 已暂存的文件")
    args = parser.parse_args()

    files = collect_files(args.staged)
    findings = []
    scanned = 0
    for rel in files:
        full = os.path.join(ROOT, rel)
        try:
            with open(full, "r", encoding="utf-8") as f:
                text = f.read()
        except (UnicodeDecodeError, OSError):
            continue
        scanned += 1
        scan_text(text, rel, findings)

    scope = "已暂存文件" if args.staged else "整个仓库"
    if not findings:
        print(f"✅ 密钥扫描通过：{scope} 共检查 {scanned} 个文件，未发现硬编码凭据。")
        return 0

    print(f"❌ 发现 {len(findings)} 处疑似硬编码凭据（{scope}）：\n")
    for path, line_no, desc, preview in findings:
        print(f"  {path}:{line_no}  [{desc}]  {preview}")
    print("\n处理方式：把凭据移到「系统管理 → API 密钥」页面配置，"
          "或改用环境变量注入，不要写进源码。")
    return 1


if __name__ == "__main__":
    sys.exit(main())
