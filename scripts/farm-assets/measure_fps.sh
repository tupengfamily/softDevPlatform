#!/bin/bash
# 四档画质 FPS 实测（无头 Chrome）
# 🔴 铁律：必须从页面上的 .wq-fps 元素读（引擎自己采样 WebGL 循环），
#    不能用截图取像素（截图是合成帧，不代表真实渲染帧率）。
# 🔴 切档必须走 localStorage + reload（quality.js 在初始化时读一次）。
set -u
BROWSER=${BROWSER:-agent-browser}

measure() {
  local q=$1
  echo "--- $q ---"
  $BROWSER eval "(() => { localStorage.setItem('farm.quality','$q'); return 'ok'; })()" >/dev/null 2>&1
  $BROWSER reload >/dev/null 2>&1
  # 等待场景重建 + FPS 采样窗口积累
  sleep 14
  for i in 1 2 3; do
    $BROWSER eval "(() => { const el=document.querySelector('.wq-fps'); return el? el.textContent.trim():'NA'; })()" 2>&1 | tail -1
    sleep 2
  done
}

for q in "$@"; do
  measure "$q"
done
