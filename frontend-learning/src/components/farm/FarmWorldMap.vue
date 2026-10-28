<template>
  <div class="wm-page" :class="{ grabbing: dragging, 'tile-on': baseLayer !== 'vector', dist: hasDistricts }">
    <!-- 顶部工具条 -->
    <header class="wm-head">
      <button class="wm-back" @click="$emit('close')">
        <span class="wm-arrow">←</span> 返回农场
      </button>
      <div class="wm-title">
        <b>中国农场地图</b>
        <span class="wm-sub">全国 → 省 → 区县</span>
      </div>

      <div class="wm-crumb">
        <button class="crumb" :class="{ on: !centerProvince }" @click="backToCountry">全国</button>
        <template v-if="centerProvince">
          <i class="crumb-sep">/</i>
          <b
            class="crumb-cur clickable"
            :title="pinnedCityId ? '退回省级视图' : ''"
            @click="pinnedCityId ? backToProvince() : null"
          >{{ centerProvince.name }}</b>
        </template>
        <template v-if="pinnedCity">
          <i class="crumb-sep">/</i>
          <b class="crumb-cur">{{ pinnedCity.name }}</b>
        </template>
        <template v-if="detail">
          <i class="crumb-sep">/</i>
          <b class="crumb-cur">{{ detail.name }}</b>
        </template>
      </div>

      <!--
        🔴 搜索框必须套一层自己的 wrapper（2026-10-22 实测踩坑）。

        <p>直接给 `<el-autocomplete class="wm-search">` 加 class 是不行的：
        Element Plus 会在组件内部**再渲染一层** `.el-input.wm-search`，
        而 Vue 的 scoped CSS 只把 `data-v-xxx` 加在**组件根元素**上，
        子组件内部渲染的节点拿不到这个属性 → 选择器 `.wm-search[data-v-xxx]`
        一个都匹配不上 → 样式整条失效（实际表现：搜索框仍是 804px 宽的默认值）。

        <p>套 wrapper 后 `data-v-xxx` 落在 wrapper 上，`:deep()` 就能稳定命中内部的
        `.el-input__wrapper` / `.el-input__inner`。
      -->
      <div class="wm-search">
        <el-autocomplete
          v-model="keyword"
          :fetch-suggestions="suggest"
          placeholder="搜索区县 / 地点"
          :trigger-on-focus="false"
          value-key="value"
          clearable
          @select="onSuggestSelect"
        >
          <template #default="{ item }">
            <div class="sug">
              <b>{{ item.name }}</b>
              <span>{{ item.province }} {{ item.city !== item.name ? item.city : '' }}</span>
              <i class="sug-tag" :class="'t-' + (item.source || 'local')">{{ item.typeLabel || '区县' }}</i>
            </div>
          </template>
        </el-autocomplete>
      </div>

      <div class="wm-stat">
        我的农场 <b>{{ allFarms.length }}</b>
        <span class="dot-sep">·</span>
        地块 <b>{{ totalPlots }}</b>
      </div>

      <!-- 仓库入口（S25）：地图旁边直接看仓库里的作物 + 农资 -->
      <button class="wm-warehouse" :class="{ 'has-warn': warehouseWarn > 0 }" @click="$emit('open-warehouse')">
        <span class="wh-ico">📦</span>
        <span class="wh-txt">仓库</span>
        <i v-if="warehouseWarn > 0" class="wh-dot">{{ warehouseWarn }}</i>
      </button>

      <!-- 🔴 商店入口（需求 R）：紧挨仓库——仓库看已有货，商店买新货 -->
      <button class="wm-warehouse wm-shop" :class="{ 'has-warn': shopShort > 0 }" @click="$emit('open-shop')">
        <span class="wh-ico">🛒</span>
        <span class="wh-txt">商店</span>
        <i v-if="shopShort > 0" class="wh-dot">{{ shopShort }}</i>
      </button>

      <!-- S26 成就 / 图鉴入口：地图是玩家的落地页，成就面板必须从这里能进 -->
      <button class="wm-warehouse wm-ach" @click="$emit('open-achievement')">
        <span class="wh-ico">🏅</span>
        <span class="wh-txt">成就</span>
      </button>

      <!-- 图层切换：底图 + 注记 -->
      <div class="wm-layer-ctl">
        <button class="wm-base" :class="{ on: baseLayer !== 'vector' }" @click="layerPanelOpen = !layerPanelOpen">
          <i class="bs-dot"></i>{{ baseLayerName }}
          <span class="bs-caret">▾</span>
        </button>
        <div v-if="layerPanelOpen" class="wm-layer-panel">
          <div class="lp-sec">
            <div class="lp-t">底图</div>
            <button
              v-for="l in BASE_LAYERS"
              :key="l.id"
              class="lp-item"
              :class="{ on: baseLayer === l.id }"
              @click="setBaseLayer(l.id); layerPanelOpen = false"
            >
              <i class="lp-radio"></i>
              <b>{{ l.name }}</b>
              <span>{{ l.desc }}</span>
            </button>
          </div>
          <div class="lp-sec">
            <div class="lp-t">注记</div>
            <button
              v-for="l in LABEL_LAYERS"
              :key="l.id"
              class="lp-item"
              :class="{ on: labelLayer === l.id }"
              @click="setLabelLayer(l.id); layerPanelOpen = false"
            >
              <i class="lp-radio"></i>
              <b>{{ l.name }}</b>
              <span>{{ l.desc }}</span>
            </button>
          </div>
          <div class="lp-sec">
            <div class="lp-t">数据图层</div>
            <button
              class="lp-item"
              :class="{ on: cropLayerOn }"
              @click="toggleCropLayer(); layerPanelOpen = false"
            >
              <i class="lp-radio"></i>
              <b>作物分布</b>
              <span>按播种面积/产量着色</span>
            </button>
          </div>
          <div class="lp-foot">
            <span class="mono">{{ tileCount }} 张瓦片</span>
            <span v-if="tdtKeyMissing" class="lp-warn">后端未配置天地图 key</span>
          </div>
        </div>
      </div>

      <!-- 测量工具 -->
      <div class="wm-measure-ctl">
        <button
          class="wm-mbtn"
          :class="{ on: measureMode === 'distance' }"
          title="测距：点两个点"
          @click="toggleMeasure('distance')"
        >测距</button>
        <button
          class="wm-mbtn"
          :class="{ on: measureMode === 'area' }"
          title="测面：点三个以上点，双击结束"
          @click="toggleMeasure('area')"
        >测面</button>
        <button
          v-if="measureMode"
          class="wm-mbtn clear"
          title="清除测量"
          @click="clearMeasure"
        >清除</button>
      </div>
    </header>

    <div class="wm-body">
      <!-- 地图 -->
      <div class="wm-canvas" ref="canvasEl">
        <svg
          v-if="ready"
          ref="svgEl"
          class="wm-svg"
          :viewBox="vbNow"
          preserveAspectRatio="xMidYMid meet"
          @pointerdown="onPointerDown"
          @pointermove="onPointerMove"
          @pointerup="onPointerUp"
          @pointercancel="onPointerUp"
          @click="onMapClick"
          @dblclick="onDblClick"
          @mouseleave="hoverName = null"
        >
          <defs>
            <radialGradient id="wmSea" gradientUnits="userSpaceOnUse" :cx="sea.cx" :cy="sea.cy" :r="sea.r">
              <stop offset="0%" stop-color="#1d3328" />
              <stop offset="58%" stop-color="#12211a" />
              <stop offset="100%" stop-color="#0b1410" />
            </radialGradient>
          </defs>

          <!-- 海面：容器比中国轮廓更宽时，两侧留白也是海，不是空洞 -->
          <rect
            class="wm-sea"
            :x="vbBox.x - 2"
            :y="vbBox.y - 2"
            :width="vbBox.w + 4"
            :height="vbBox.h + 4"
            fill="url(#wmSea)"
          />
          <!-- 底图瓦片（天地图走后端代理，浏览器不持有 key） -->
          <g v-if="baseTiles.length" class="wm-base-layer">
            <image
              v-for="t in baseTiles"
              :key="'b' + t.key"
              :href="t.url"
              :x="t.sx"
              :y="t.sy"
              :width="t.s"
              :height="t.s"
              preserveAspectRatio="none"
              class="wm-tile"
              @error="onTileError"
            />
          </g>

          <!--
            注记层瓦片（cva/cia/cta）。
            z 序刻意放在「底图之上、省界矢量层之下」：
            这样天地图的地名标注压在省界下面，不会盖住我们自己画的边界和区县色块，
            但仍然可见（省界是半透明描边，不至于把注记完全挡住）。
          -->
          <g v-if="labelTiles.length" class="wm-label-tiles">
            <image
              v-for="t in labelTiles"
              :key="'l' + t.key"
              :href="t.url"
              :x="t.sx"
              :y="t.sy"
              :width="t.s"
              :height="t.s"
              preserveAspectRatio="none"
              class="wm-tile"
              @error="onTileError"
            />
          </g>

          <!-- 省界：始终在场，是缩放时的底图 -->
          <g class="wm-prov-layer">
            <path
              v-for="p in provShapes"
              :key="'p' + p.adcode"
              :d="p.d"
              :data-name="p.name"
              :data-type="'province'"
              :data-adcode="p.adcode"
              class="wm-region wm-prov"
              :class="{ on: p.name === hoverName, mine: !!p.farm }"
              :fill="p.fill"
              vector-effect="non-scaling-stroke"
              @click="onRegionClick(p)"
              @mouseenter="onHover(p, $event)"
              @mousemove="onHover(p, $event)"
            />
          </g>

          <!-- 地级市 / 区县：缩放到一定比例后按视野自动加载并叠加 -->
          <g v-if="districtItems.length" class="wm-dist-layer">
            <path
              v-for="d in districtItems"
              :key="d.type + d.adcode"
              :d="d.d"
              :data-name="d.name"
              :data-type="d.type"
              :data-adcode="d.adcode"
              class="wm-region wm-dist"
              :class="{ on: d.name === hoverName, mine: !!d.farm, city: d.type === 'city' }"
              :fill="d.fill"
              vector-effect="non-scaling-stroke"
              @click="onRegionClick(d)"
              @mouseenter="onHover(d, $event)"
              @mousemove="onHover(d, $event)"
            />
          </g>

          <!-- 省名标注：视野跨度在 3.5°–26° 之间时显示（正好是「看得到省、看不到县」的尺度） -->
          <g v-if="provinceLabels.length" class="wm-label-layer">
            <text
              v-for="l in provinceLabels"
              :key="'pt' + l.adcode"
              :x="l.x"
              :y="l.y"
              class="wm-plabel"
              :transform="`translate(${l.x} ${l.y}) scale(${1 / zoom}) translate(${-l.x} ${-l.y})`"
            >{{ l.name }}</text>
          </g>

          <!-- 地级市名标注：省级视图的中间尺度 -->
          <g v-if="cityLabels.length" class="wm-label-layer">
            <text
              v-for="l in cityLabels"
              :key="'ct' + l.adcode"
              :x="l.x"
              :y="l.y"
              class="wm-dlabel"
              :class="{ big: l.big }"
              :transform="`translate(${l.x} ${l.y}) scale(${1 / zoom}) translate(${-l.x} ${-l.y})`"
            >{{ l.name }}</text>
          </g>

          <!-- 区县级标注：放大后才有意义，否则全国视图会糊成一片字 -->
          <g v-if="districtLabels.length" class="wm-label-layer">
            <text
              v-for="l in districtLabels"
              :key="'t' + l.adcode"
              :x="l.x"
              :y="l.y"
              class="wm-dlabel"
              :class="{ big: l.big }"
              :transform="`translate(${l.x} ${l.y}) scale(${1 / zoom}) translate(${-l.x} ${-l.y})`"
            >{{ l.name }}</text>
          </g>

          <!-- 经纬网 -->
          <g class="wm-grat">
            <line
              v-for="l in grat.v"
              :key="'gv' + l.x"
              :x1="l.x"
              :y1="vbBox.y"
              :x2="l.x"
              :y2="vbBox.y + vbBox.h"
            />
            <line
              v-for="l in grat.h"
              :key="'gh' + l.y"
              :x1="vbBox.x"
              :y1="l.y"
              :x2="vbBox.x + vbBox.w"
              :y2="l.y"
            />
          </g>

          <!-- 我的农场标记：与地图同一套投影，位置天然对齐 -->
          <g
            v-for="m in markers"
            :key="'m' + m.farmId"
            class="wm-marker"
            :class="{ active: activeFarmId === m.farmId }"
            @click.stop="onMarkerClick(m.farmId)"
            @mouseenter="onFarmHover(m, $event)"
            @mouseleave="onFarmLeave"
          >
            <circle :cx="m.x" :cy="m.y" :r="markerR * 2.6 / zoom" class="halo" />
            <circle :cx="m.x" :cy="m.y" :r="markerR / zoom" class="dot" vector-effect="non-scaling-stroke" />
            <title>{{ m.name }}</title>
          </g>
          <!--
            测量层：测距 / 测面。
            放在最上面（区县层之上）—— 测量是用户当前的主动操作，
            图形必须始终可见，不能被省界/区县色块盖住。
          -->
          <g v-if="measureMode" class="wm-measure-layer">
            <!-- 测面：多边形 -->
            <polygon
              v-if="measureMode === 'area' && measurePoints.length > 2"
              :points="measurePolyPoints"
              class="wm-measure-area"
            />
            <!-- 测距：两点之间的直线；测面：首尾相连的折线 -->
            <polyline
              v-if="measureLinePoints"
              :points="measureLinePoints"
              class="wm-measure-line"
              :class="measureMode"
            />
            <!--
              顶点标记。
              整组套一层 scale(1/zoom) 逆缩放：SVG 的 viewBox 宽度是 fit.w/zoom，
              1 个用户单位在屏幕上会随 zoom 线性放大。用固定用户单位画点，
              放大 20 倍后圆点就成了一个巨大的色饼、序号和读数糊成一团。
              逆缩放后圆点半径、序号字号、读数字号在任意缩放级别下都是恒定的屏幕尺寸。
              注意：圆点的 vector-effect=non-scaling-stroke 让描边不跟着缩放，
              文字描边（paint-order:stroke）则会随组一起缩放 —— 这正是想要的，
              文字的白色描边必须始终和字形保持固定比例。
            -->
            <g
              v-for="(p, i) in measurePoints"
              :key="'mp' + i"
              :transform="`translate(${p.x} ${p.y}) scale(${1 / zoom})`"
            >
              <circle :cx="0" :cy="0" :r="measureR" class="wm-measure-dot" :class="measureMode" />
              <text
                :x="0"
                :y="-measureR * 1.8"
                class="wm-measure-idx"
                :class="measureMode"
              >{{ i + 1 }}</text>
            </g>
            <!-- 实时读数：测距显示到第一个点，测面显示当前闭合面积 -->
            <g
              v-if="measureReadout"
              class="wm-measure-readout"
              :transform="`translate(${measureReadout.x} ${measureReadout.y}) scale(${1 / zoom})`"
            >
              <text :x="0" :y="measureR * 3.4" class="wm-measure-text" :class="measureMode">
                {{ measureReadout.text }}
              </text>
            </g>
          </g>

          <!-- 逆地理编码：点击空隙处打点 + 显示地址气泡 -->
          <g v-if="reversePin" class="wm-reverse-pin">
            <circle :cx="reversePin.x" :cy="reversePin.y" :r="markerR / zoom" class="rp-dot" vector-effect="non-scaling-stroke" />
            <circle :cx="reversePin.x" :cy="reversePin.y" :r="markerR * 2.2 / zoom" class="rp-halo" />
          </g>
        </svg>

        <!-- 逆地理编码气泡：显示天地图返回的完整地址 -->
        <div
          v-if="reverseInfo"
          class="wm-reverse-tip"
          :style="{ left: reverseInfo.left + 'px', top: reverseInfo.top + 'px' }"
        >
          <button class="rt-close" @click="reverseInfo = null; reversePin = null">×</button>
          <b>{{ reverseInfo.title }}</b>
          <span v-if="reverseInfo.detail">{{ reverseInfo.detail }}</span>
          <span v-if="reverseInfo.poi" class="rt-poi">附近：{{ reverseInfo.poi }}</span>
          <span class="rt-coord mono">{{ reverseInfo.coord }}</span>
        </div>

        <!-- 测量读数面板 -->
        <div v-if="measureMode" class="wm-measure-panel">
          <div class="mp-title">{{ measureMode === 'distance' ? '距离测量' : '面积测算' }}</div>
          <div class="mp-hint">
            {{ measureMode === 'distance'
              ? (measurePoints.length === 0 ? '在地图上点第一个点' : '再点一个点测出直线距离')
              : (measurePoints.length < 3 ? '点三个以上的点围成多边形' : '双击结束测量') }}
          </div>
          <div v-if="measureResult" class="mp-result">
            <b>{{ measureResult.text }}</b>
            <span>{{ measureResult.sub }}</span>
          </div>
          <div class="mp-acts">
            <button class="mp-btn" @click="clearMeasure">清除</button>
            <button class="mp-btn ghost" @click="measureMode = null; clearMeasure()">退出测量</button>
          </div>
        </div>

        <div v-if="loading" class="wm-loading">
          <div class="spin"></div>
          <span>{{ loadingText }}</span>
        </div>

        <!--
          农场标记悬浮预览卡片：概览 + 农田列表 + 基础操作 + 进入农场。
          目标：不进农场详情也能完成「看状态 / 浇水 / 收割」这些基础操作。
        -->
        <div
          v-if="farmHover"
          class="farm-card"
          :class="{ wide: (farmHover.plots || []).length }"
          :style="{ left: farmHoverPos.x + 'px', top: farmHoverPos.y + 'px' }"
          @click.stop
          @mouseenter="onFarmCardEnter"
          @mouseleave="onFarmCardLeave"
        >
          <div class="fc-head">
            <b class="fc-name">{{ farmHover.name }}</b>
            <span v-if="farmHover.zoneName" class="fc-zone">{{ farmHover.zoneName }}</span>
          </div>
          <div class="fc-loc">{{ farmHover.location || '—' }}</div>

          <!--
            天气区（2026-10-22 需求 O）：玩家决定「要不要现在浇水 / 打药」时，
            第一眼要看的就是天气。⚠️ 展示的是**今天**的实测/推算值，不是预报——
            下面的 source 就是数据来源，必须如实显示。
          -->
          <div v-if="farmHover.weather" class="fc-weather">
            <span class="fw-icon" aria-hidden="true">{{ weatherIcon(farmHover.weather) }}</span>
            <span class="fw-temp">{{ Math.round(farmHover.weather.tmax) }}℃</span>
            <span class="fw-low">/{{ Math.round(farmHover.weather.tmin) }}℃</span>
            <span class="fw-rain">
              {{ farmHover.weather.precip > 0 ? '降水 ' + farmHover.weather.precip + 'mm' : '无降水' }}
            </span>
            <span class="fw-src" :title="farmHover.weather.source">{{ farmHover.weather.source }}</span>
          </div>

          <div class="fc-stats">
            <span><i>{{ farmHover.plotCount }}</i> 块地</span>
            <span v-if="farmHover.growingCount"><i>{{ farmHover.growingCount }}</i> 生长中</span>
            <span v-if="farmHover.readyCount"><i class="ok">{{ farmHover.readyCount }}</i> 可收割</span>
          </div>
          <!-- 还没有地块的农场：说清楚状态，别让玩家以为卡片是坏的 -->
          <div v-if="!farmHover.plotCount" class="fc-empty">
            还没有地块，进入后开垦第一块地
          </div>
          <div v-if="farmHover.crops && farmHover.crops.length" class="fc-crops">
            <!-- 后端 /board 返回的 crops 是「作物名字符串数组」，
                 不是对象数组（曾经按 c.cropId || c.name 取值 → 永远是空白）。 -->
            <span v-for="(c, ci) in farmHover.crops.slice(0, 4)" :key="ci" class="fc-crop">
              {{ typeof c === 'string' ? c : (c.shortName || c.name || '') }}
            </span>
            <span v-if="farmHover.crops.length > 4" class="fc-crop more">
              +{{ farmHover.crops.length - 4 }}
            </span>
          </div>

          <!-- 农田列表：每块田的作物 / 状态 / 基础操作 -->
          <div v-if="(farmHover.plots || []).length" class="fc-plots">
            <div class="fpl-head">
              <span>农田</span>
              <span class="fpl-tip">点地块行可直接进入该地块</span>
            </div>
            <div class="fpl-list">
              <!--
                地块行整体可点 → 直接进该地块详情（需求 O）。
                里面的按钮用 @click.stop 阻止冒泡，避免「点浇水」变成「进地块」。
              -->
              <div
                v-for="p in farmHover.plots"
                :key="p.slot"
                class="fpl-row"
                :class="{ empty: !p.chosen, ready: p.chosen && isHarvestable(p) }"
                role="button"
                tabindex="0"
                :title="'进入 ' + (p.fieldName || ('第 ' + (p.slot + 1) + ' 号田'))"
                @click="enterHoverPlot(p.slot)"
                @keydown.enter="enterHoverPlot(p.slot)"
              >
                <i class="fpl-dot" :class="'pc-' + plotDotClass(p)"></i>
                <span class="fpl-main">
                  <span class="fpl-name" :title="p.fieldName || ('第 ' + (p.slot + 1) + ' 号田')">
                    {{ p.fieldName || ('第 ' + (p.slot + 1) + ' 号田') }}
                  </span>
                  <span class="fpl-meta">
                    <span class="fpl-crop">{{ plotCropShort(p.cropName) || '空地' }}</span>
                    <span class="fpl-stage">
                      {{ p.chosen ? p.stage : (p.awaitingSowing ? '待播种' : '待开垦') }}
                    </span>
                    <!-- 播种日（需求 O）：玩家判断「种了多久 / 还差几天能收」 -->
                    <span v-if="p.sowDate" class="fpl-sow" :title="'播种于 ' + p.sowDate">
                      播 {{ shortDate(p.sowDate) }}
                    </span>
                  </span>
                </span>
                <!-- 异常徽标：枯死 / 出苗不全 / 杂草成灾（与农场详情面板一致） -->
                <span v-if="p.withered" class="fpl-flag bad" title="苗期枯死">枯</span>
                <span
                  v-else-if="p.chosen && p.germination != null && p.germination < 0.85"
                  class="fpl-flag warn"
                  :title="'出苗率 ' + Math.round(p.germination * 100) + '%'"
                >缺苗</span>
                <span
                  v-if="p.weedLevel === 'SEVERE'"
                  class="fpl-flag bad"
                  :title="'杂草成灾，正在争水争肥，产量已少 ' + p.weedLossPct + '%'"
                >草</span>
                <span
                  v-else-if="p.weedLevel === 'MEDIUM'"
                  class="fpl-flag warn"
                  :title="p.weedLevelName + '，产量已少 ' + p.weedLossPct + '%'"
                >草</span>
                <span class="fpl-ops">
                  <template v-if="p.chosen">
                    <button
                      class="fpl-btn"
                      :disabled="busyPlot === p.slot"
                      title="浇水"
                      @click.stop="hoverPlotAction(p.slot, 'IRRIGATE')"
                    >浇</button>
                    <button
                      class="fpl-btn"
                      :disabled="busyPlot === p.slot"
                      :title="'人工除草（当前：' + p.weedLevelName + '）'"
                      @click.stop="hoverPlotAction(p.slot, 'WEED')"
                    >除</button>
                    <button
                      class="fpl-btn"
                      :disabled="busyPlot === p.slot"
                      title="打药 / 施肥（走农资通道，需要选具体药剂）"
                      @click.stop="openHoverInputs(p.slot)"
                    >药</button>
                    <button
                      class="fpl-btn ok"
                      :disabled="busyPlot === p.slot || !isHarvestable(p)"
                      title="收割"
                      @click.stop="hoverPlotHarvest(p.slot)"
                    >收</button>
                  </template>
                  <!-- 待播种的地块：不能浇水也不能收割，但要说清楚下一步是什么 -->
                  <button
                    v-else-if="p.awaitingSowing"
                    class="fpl-btn ghost"
                    :disabled="busyPlot === p.slot"
                    title="选下一季种什么"
                    @click.stop="enterHoverPlot(p.slot)"
                  >选种</button>
                </span>
              </div>
            </div>
          </div>

          <div class="fc-acts">
            <button class="fc-enter" @click.stop="enterFarmScene(farmHover.farmId)">
              进入农场 →
            </button>
          </div>
        </div>

        <div v-if="hoverName" class="wm-tip" :style="{ left: tip.x + 'px', top: tip.y + 'px' }">
          <b>{{ hoverName }}</b>
          <span v-if="hoverInfo && hoverInfo.line1">{{ hoverInfo.line1 }}</span>
          <span v-if="hoverInfo && hoverInfo.line2">{{ hoverInfo.line2 }}</span>
        </div>

        <div class="wm-legend">
          <template v-if="cropLayerOn">
            <div class="lg-row lg-title">{{ cropDistLegend.title }}</div>
            <div class="lg-row" v-for="l in cropDistLegend.bands" :key="l.label">
              <i class="lg-box" :style="{ background: l.color }"></i>{{ l.label }}
            </div>
            <div class="lg-row"><i class="lg-dot"></i>我的农场</div>
            <div class="lg-tip">{{ cropDistLegend.tip }}</div>
          </template>
          <template v-else-if="hasDistricts">
            <div class="lg-grid">
              <div class="lg-row" v-for="l in cropLegend" :key="l.id">
                <i class="lg-box" :style="{ background: l.color }"></i>{{ l.name }}
              </div>
            </div>
            <div class="lg-row"><i class="lg-dot"></i>我的农场</div>
            <div class="lg-tip">点区县看气候与主栽作物</div>
          </template>
          <template v-else>
            <div class="lg-row"><i class="lg-box lg-farm"></i>已有农场</div>
            <div class="lg-row"><i class="lg-box lg-none"></i>未建农场</div>
            <div class="lg-tip">滚轮放大即可看到区县级</div>
          </template>
          <div class="lg-gesture">滚轮缩放 · 拖拽平移 · 双击放大</div>
        </div>

        <!--
          作物分布筛选面板。
          独立于图层切换面板：筛选器需要常驻可调（切完还要调阈值），
          塞进一次性展开的图层面板里用起来很别扭。
        -->
        <div v-if="cropLayerOn" class="wm-crop-panel">
          <div class="cp-head">
            <b>作物分布筛选</b>
            <button class="cp-close" title="关闭图层" @click="toggleCropLayer">×</button>
          </div>

          <div class="cp-sec">
            <div class="cp-t">
              指标
              <span class="cp-hint">着色依据</span>
            </div>
            <div class="cp-metric">
              <button
                v-for="m in CROP_METRICS"
                :key="m.id"
                class="cp-mbtn"
                :class="{ on: cropMetric === m.id }"
                @click="setCropMetric(m.id)"
              >{{ m.name }}</button>
            </div>
          </div>

          <div class="cp-sec">
            <div class="cp-t">
              作物
              <span class="cp-hint">{{ cropSel.length ? '已选 ' + cropSel.length : '未选（全部）' }}</span>
            </div>
            <div class="cp-crop-groups">
              <div v-for="g in cropGroups" :key="g.name" class="cp-group">
                <div class="cp-gname">{{ g.name }}</div>
                <div class="cp-chips">
                  <button
                    v-for="c in g.items"
                    :key="c.cropId"
                    class="cp-chip"
                    :class="{ on: cropSel.includes(c.cropId) }"
                    :title="c.name + ' · ' + c.group"
                    @click="toggleCrop(c.cropId)"
                  >{{ c.name }}</button>
                </div>
              </div>
            </div>
            <div class="cp-acts">
              <button class="cp-link" @click="selectAllCrops">全选</button>
              <button class="cp-link" @click="cropSel = defaultCropSel.slice(); loadCropDistribution()">常用</button>
              <button class="cp-link" @click="cropSel = []; loadCropDistribution()">清空</button>
            </div>
          </div>

          <div class="cp-sec">
            <div class="cp-t">
              {{ cropMetric === 'yield' ? '单产筛选' : cropMetric === 'output' ? '产量筛选' : '面积筛选' }}
              <span class="cp-hint">{{ cropRangeLabel }}</span>
            </div>            <div class="cp-range">
              <label class="cp-rl">下限</label>
              <el-input-number
                v-model="cropRange.min"
                :min="0"
                :max="cropRangeMax"
                :step="cropRangeStep"
                size="small"
                controls-position="right"
                @change="onCropRangeChange"
              />
              <label class="cp-rl">上限</label>
              <el-input-number
                v-model="cropRange.max"
                :min="0"
                :max="cropRangeMax"
                :step="cropRangeStep"
                size="small"
                controls-position="right"
                @change="onCropRangeChange"
              />
            </div>
            <el-slider
              v-model="cropRangeSlider"
              range
              :min="0"
              :max="cropRangeMax"
              :step="cropRangeStep"
              :format-tooltip="fmtRangeTip"
              @change="onCropRangeSlider"
            />
            <div class="cp-quick">
              <button
                v-for="q in CROP_RANGE_QUICK"
                :key="q.label"
                class="cp-qbtn"
                @click="applyQuickRange(q)"
              >{{ q.label }}</button>
            </div>
          </div>

          <!--
            移动端色带图例。
            桌面端的 .wm-legend 在 860px 以下会隐藏（没有位置放），
            但色带是「数值大小」的唯一解码方式，缺了玩家看不懂颜色 ——
            所以在筛选面板里补一份，只在窄屏显示。
          -->
          <div class="cp-legend">
            <div class="cp-lgtitle">{{ cropDistLegend.title }}</div>
            <div class="cp-lgrow" v-for="l in cropDistLegend.bands" :key="l.label">
              <i class="cp-lgbox" :style="{ background: l.color }"></i>{{ l.label }}
            </div>
          </div>

          <div class="cp-foot">
            <span class="cp-src">数据：{{ cropMeta.source || '国家统计局统计年鉴' }}</span>
            <span class="cp-note">
              当前着色层级：{{ cropLevelName }}。
              {{ cropLevel === 'province' ? '省级为统计年鉴实测值' : '市/区县为按省值与面积推算的分布示意' }}
            </span>
          </div>
        </div>

        <!-- 缩放控件 -->
        <div class="wm-zoomctl">
          <button title="放大" @click="zoomStep(1.5)">＋</button>
          <button title="缩小" @click="zoomStep(1 / 1.5)">−</button>
          <button title="复位" class="zr" @click="backToCountry">⤢</button>
          <span class="zc-lv mono">{{ zoom.toFixed(1) }}×</span>
        </div>

        <!-- 当前层级指示 -->
        <div class="wm-level">
          <span class="wl-tag">{{ levelName }}</span>
          <span v-if="distLoading" class="wl-load mono">加载区划 {{ distLoading }}</span>
          <span v-else-if="levelHint" class="wl-hint">{{ levelHint }}</span>
        </div>
      </div>

      <!-- 右侧：概览 / 区县 / 农场 -->
      <aside class="wm-side" :class="{ empty: !detail && !farmDetail && !allFarms.length, folded: sideFolded }">
        <!-- 折叠按钮：收起态贴右侧边缘竖排，展开态在面板左上角 -->
        <button
          class="side-fold-btn"
          :class="{ out: sideFolded }"
          :title="sideFolded ? '展开看板' : '收起看板'"
          :aria-label="sideFolded ? '展开看板' : '收起看板'"
          @click="toggleSide"
        >{{ sideFolded ? '‹' : '›' }}</button>

        <!-- 收起态：竖排摘要条，告诉玩家「里面有什么」 -->
        <div v-if="sideFolded" class="side-fold-strip">
          <span class="fs-title">看板</span>
          <span class="fs-line">{{ sideFoldSummary }}</span>
        </div>

        <!-- 未选中区域：全部农场概览；选中区域：该区域概览 -->
        <FarmOverviewPanel
          v-if="!detail && !farmDetail && !sideFolded"
          :scope="scope"
          :farms="scopeFarms"
          :unassigned="unassignedPlots"
          :region-crops="provinceCrops"
          :active-farm-id="activeFarmId"
          :scope-mode="scopeMode"
          @select="onOverviewSelect"
          @enter="onOverviewEnter"
          @enter-plot="onOverviewEnterPlot"
          @scope-mode="scopeMode = $event"
        />

        <!-- 农场详情 -->
        <template v-else-if="farmDetail && !sideFolded">
          <div class="side-head">
            <button class="side-close" @click="farmDetail = null; activeFarmId = null">×</button>
            <h3>{{ farmDetail.name }}</h3>
            <p class="side-loc">{{ farmDetail.location }}</p>
            <div class="side-tags">
              <span class="tag zone">{{ farmDetail.zoneName }}</span>
              <span class="tag">{{ farmDetail.plotCount }} 块地</span>
            </div>
          </div>

          <div class="side-scroll">
            <div class="sec">
              <div class="sec-t">
                地块
                <span class="sec-tip">每块地 {{ MAX_PLOTS_PER_FARM }} 块上限</span>
              </div>
              <div v-if="!(farmDetail.plots || []).length" class="sec-empty">
                还没有地块。进入农场后开垦第一块地。
              </div>
              <div
                v-for="p in farmDetail.plots"
                :key="p.slot"
                class="plot-row"
                :class="{ awaiting: p.awaitingSowing }"
                @click="enterPlot(p.slot)"
              >
                <span class="pr-idx">{{ p.slot + 1 }}</span>
                <span class="pr-name">{{ p.fieldName || ('第 ' + (p.slot + 1) + ' 号田') }}</span>
                <span class="pr-crop">{{ shortCrop(p.cropName) || '—' }}</span>
                <span class="pr-stage">{{ p.chosen ? p.stage : (p.awaitingSowing ? '待播种' : '待开垦') }}</span>
                <span class="pr-dvs mono">{{ p.chosen ? p.dvs.toFixed(2) : '—' }}</span>
                <!-- 异常标记：枯死 / 出苗不全 -->
                <span
                  v-if="p.withered"
                  class="pr-bad"
                  :title="p.failureNote || '苗期枯死'"
                >枯</span>
                <span
                  v-else-if="p.chosen && p.germination != null && p.germination < 0.85"
                  class="pr-warn"
                  :title="'出苗率 ' + Math.round(p.germination * 100) + '%'"
                >缺苗</span>
              </div>
            </div>

            <!-- 种植与收获记录（2026-10-22） -->
            <div class="sec">
              <div class="sec-t">
                种植与收获记录
                <span class="sec-tip">{{ farmSeasons.length }} 条</span>
              </div>
              <div v-if="!farmSeasons.length" class="sec-empty">
                还没有收成记录。收割一季后，这里会留下这一季的作物、播期与产量。
              </div>
              <div v-for="g in farmSeasonGroups" :key="g.slot" class="seasons-block">
                <div class="sb-head">
                  <span class="sb-idx">{{ g.slot + 1 }}</span>
                  <span class="sb-name">{{ g.plotName }}</span>
                  <span class="sb-count mono">{{ g.rows.length }} 季</span>
                </div>
                <div v-for="r in g.rows" :key="r.id" class="season-row" :class="'st-' + r.status">
                  <div class="sr-line1">
                    <b>{{ shortCrop(r.cropName) || '—' }}</b>
                    <span class="sr-season mono">第 {{ r.seasonNo }} 季</span>
                    <span class="sr-status">{{ seasonStatusText(r.status) }}</span>
                  </div>
                  <div class="sr-line2">
                    <span class="mono">播 {{ r.sowDate || '—' }}</span>
                    <span class="mono">{{ r.harvestDate ? '收 ' + r.harvestDate : '未收' }}</span>
                    <span class="mono" :class="{ bad: r.status === 'FAILED' }">
                      {{ r.yieldKgPerMu != null ? r.yieldKgPerMu.toFixed(1) + ' ' + $t('farm.unit.perMu') : '—' }}
                    </span>
                    <span
                      v-if="r.germination != null"
                      class="mono"
                      :class="{ warn: r.germination < 0.85 }"
                    >出苗 {{ Math.round(r.germination * 100) }}%</span>
                  </div>
                  <p v-if="r.failureNote" class="sr-note">{{ r.failureNote }}</p>
                </div>
              </div>
            </div>

            <div class="sec" v-if="farmDetail.district">
              <div class="sec-t">这里适合种什么</div>
              <div class="crop-list">
                <div
                  v-for="c in farmDetail.district.mainCrops"
                  :key="c.cropId"
                  class="crop-card"
                >
                  <div class="cc-top">
                    <b>{{ c.shortName }}</b>
                    <span class="lv" :class="'lv-' + c.level">{{ c.levelName }}</span>
                  </div>
                  <div class="cc-meta">
                    <span>{{ c.stage }}</span>
                    <span class="mono">达标率 {{ Math.round((c.pPass || 0) * 100) }}%</span>
                  </div>
                </div>
              </div>
            </div>
          </div>

          <div class="side-foot">
            <button class="btn primary" @click="enterFarm(farmDetail)">进入农场</button>
            <button class="btn ghost" @click="openDistrict(farmDetail.adcode)">看区县</button>
          </div>
        </template>

        <!-- 区县详情 -->
        <template v-else-if="detail && !sideFolded">
          <div class="side-head">
            <button class="side-close" @click="detail = null">×</button>
            <h3>{{ detail.fullName || detail.name }}</h3>
            <p class="side-loc">
              <span class="mono">{{ detail.lat.toFixed(2) }}°N {{ detail.lng.toFixed(2) }}°E</span>
              <span class="dot-sep">·</span>{{ detail.climate.zoneName }}
            </p>
            <div class="side-tags">
              <span class="tag zone">{{ detail.climate.region }}</span>
              <span class="tag" v-if="detail.myPlots">{{ detail.myPlots.length }} 块地</span>
            </div>
          </div>

          <div class="side-scroll">
            <div class="notice">
              {{ detail.climate.notice }}；主导作物按「省域主栽先验 + 本地十年气候回放」推导。
            </div>

            <div class="sec">
              <div class="sec-t">当前主要农作物</div>
              <div class="crop-list">
                <div
                  v-for="c in detail.mainCrops"
                  :key="c.cropId"
                  class="crop-card"
                  :style="{ borderLeftColor: cropColor(c.visual) }"
                >
                  <div class="cc-top">
                    <b>{{ c.shortName }}</b>
                    <span class="lv" :class="'lv-' + c.level">{{ c.levelName }}</span>
                  </div>
                  <div class="cc-meta">
                    <span>{{ seasonText(c) }}</span>
                    <span class="mono">{{ c.stage }}</span>
                  </div>
                  <div class="cc-bar">
                    <i :style="{ width: Math.max(6, Math.round((1 - c.level / 5) * 100)) + '%' }"></i>
                  </div>
                  <div class="cc-foot">
                    <span>推荐播期 {{ c.sowDate }}</span>
                    <span class="mono">达标率 {{ Math.round((c.pPass || 0) * 100) }}%</span>
                  </div>
                </div>
              </div>
            </div>

            <div class="sec">
              <div class="sec-t">作物评级</div>
              <div class="rate-grid">
                <div v-for="c in detail.crops" :key="c.cropId" class="rate-cell">
                  <span class="rc-name">{{ c.shortName }}</span>
                  <span class="rc-lv" :class="'lv-' + c.level">{{ c.levelName }}</span>
                </div>
              </div>
            </div>

            <div class="sec" v-if="detail.farmId">
              <div class="sec-t">我的农场</div>
              <div class="my-farm" @click="openFarm(detail.farmId)">
                <b>{{ detail.farmName }}</b>
                <span>{{ detail.myPlots ? detail.myPlots.length : 0 }} 块地</span>
              </div>
            </div>
          </div>

          <div class="side-foot">
            <button v-if="!detail.farmId" class="btn primary" @click="buildFarm">
              在这里建农场
            </button>
            <button v-else class="btn primary" @click="openFarm(detail.farmId)">
              进入我的农场
            </button>
          </div>
        </template>
      </aside>
    </div>

    <!-- 建农场弹窗（S25）：名称 + 开垦日期（不能早于今天） -->
    <el-dialog
      v-model="buildOpen"
      title="建农场"
      width="380px"
      class="wm-build-dlg"
      :close-on-click-modal="false"
      append-to-body
    >
      <div class="wb-form">
        <label class="wb-label">位置</label>
        <div class="wb-static">{{ buildForm.district }}</div>

        <label class="wb-label">农场名</label>
        <el-input v-model="buildForm.name" maxlength="20" placeholder="1–20 字" />

        <label class="wb-label">开垦日期</label>
        <el-date-picker
          v-model="buildForm.openedOn"
          type="date"
          value-format="YYYY-MM-DD"
          format="YYYY-MM-DD"
          placeholder="选择开垦日期"
          :disabled-date="disabledOpenedOn"
          :clearable="false"
          style="width: 100%"
        />
        <p class="wb-hint">不能早于今天（{{ worldDate || '—' }}）。开垦日期决定这块地的起始赛季。</p>
      </div>
      <template #footer>
        <button class="wb-btn ghost" @click="buildOpen = false">取消</button>
        <button class="wb-btn primary" :disabled="buildBusy" @click="submitBuildFarm">建场</button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { useI18n } from 'vue-i18n'
import {
  getFarmMapDistricts, getFarmMapDistrict, getFarmCropDistribution,
  getFarmCropDistributionProvinces,
  searchFarmDistricts, createFarmStead, getFarmStead, getFarmBoard,
  performFarmAction, harvestFarm, getFarmSeasons
} from '../../api/farm'
import {
  getMapTdtStatus, searchMapPlaces, reverseGeocode, tdtTilePath, probeMapTdtTile
} from '../../api/map'
import { isHarvestable } from '../../utils/farmLabels'
// 🔴 2026-10-28：作物常量与派生函数收进 utils/farmCrops.js（唯一实现）。
import {
  CROP_COLOR, CROP_NAME, LEGEND_CROPS,
  cropColor, cropName, shortCrop, plotDotState
} from '../../utils/farmCrops'
// 🔴 2026-10-28：投影与几何计算抽到 map/geo.js —— 纯函数、零 Vue 依赖。
// 投影基准 K 是全局常量（不随显示哪一层而变），一旦出现第二份就会分叉。
import {
  PROJ, DEG_PER_UNIT,
  worldX, worldY, invWorldX, invWorldY,
  ringPath, featurePath,
  shade, shadeA,
  geometryBbox, pointInRing, pointInGeometry, bboxHit
} from './map/geo'
// 🔴 2026-10-28：量算的数学部分抽到 map/measure.js —— 纯函数、零 Vue 依赖。
// 落点/清除/模式切换这些要动组件状态的交互仍留在组件里。
import {
  EARTH_R, haversine, shoelaceArea, formatDistance, formatArea
} from './map/measure'
import FarmOverviewPanel from './FarmOverviewPanel.vue'

// 🔴 2026-10-27：本组件整体仍是硬编码中文（6712 行，i18n 化是独立任务），
//    但「亩」这个单位必须走 i18n —— 否则英文界面的地图浮层会露中文。
//    用 useI18n 局部取 t()，不与组件内既有的中文文案混为一谈。
const { t } = useI18n()

const emit = defineEmits(['close', 'enter-plot', 'state-changed', 'open-inputs', 'open-warehouse', 'open-shop', 'open-achievement'])

/**
 * 仓库角标（S25）：临期 + 已变质的批次数。
 *
 * <p>由父组件 {@code GameFarm.vue} 传入 —— 角标的数据源和顶部那个「仓库」chip
 * 是同一份（{@code refreshWarehouseBadge()}），**不能在这里自己再拉一次接口**：
 * 仓库面板的 {@code getFarmWarehouse()} 会顺带跑一次临期/变质检查并写站内通知，
 * 重复调用会产生重复通知。
 */
const props = defineProps({
  warehouseWarn: { type: Number, default: 0 },
  /**
   * 商店角标（需求 R）：「缺货提醒」条数。
   *
   * <p>语义与仓库角标相反：仓库角标是「有坏货要处理」，
   * 商店角标是「有缺口要补」——比如没有种子、没有灌溉水。
   * 同样由父组件传入，地图自己不拉接口。
   */
  shopShort: { type: Number, default: 0 }
})

const loading = ref(false)
const loadingText = ref('')
const ready = ref(false)

/** 视野中心所在的省——随镜头自动变化，用于面包屑、层级提示与右侧面板范围 */
const centerProvince = ref(null)
/**
 * 右侧面板的范围模式。
 *
 * <p>'all'（默认）：锁定「全部农场」，不管镜头在哪都显示全部农场。
 * 'auto'：跟随镜头中心（镜头移到哪个省就显示哪个省的农场）——
 * 地图位置和面板内容永远一致。
 *
 * <p>为什么要这个开关（2026-10-21）：面板范围原本纯跟随镜头，
 * 玩家在卡片列表里单击一张卡片定位农场，镜头一动面板就切成
 * 「XX省概览」，列表内容凭空少一大半——刚点的那张卡可能直接消失。
 * 卡片列表是一个「浏览全部」的界面，浏览途中被镜头劫持范围是不能接受的。
 *
 * <p>默认给 'all'：面板标题就叫「全部农场」，就该显示全部农场；
 * 玩家想收窄到本省时再切「跟随镜头」。
 */
const scopeMode = ref('all')

/** 选中的区县详情 */
const detail = ref(null)
/** 选中的农场详情 */
const farmDetail = ref(null)
/**
 * 农场详情里的「种植与收获记录」（2026-10-22）。
 *
 * <p>按地块分组：一块地一季一行，含作物、播期、收获日、产量、出苗率、
 * 最终状态（正常收获 / 失败枯死 / 放弃）与失败原因。
 */
const farmSeasons = ref([])
/**
 * 季节记录按地块分组后的结构（computed，避免模板里做 reduce）。
 *
 * <p>🔴 2026-10-22 加了两道过滤：
 * <ul>
 *   <li><b>farmId 过滤</b>——只留当前农场的记录，防止别的农场的档案漏进来；</li>
 *   <li><b>slot 白名单</b>——只留当前农场**现在拥有**的地块。
 *       地块被转到别的农场后，它的旧档案仍挂在本地块上（历史应当跟随地块），
 *       但如果这个 slot 已经不属于本农场了，就不该出现在本农场的详情里。</li>
 * </ul>
 */
const farmSeasonGroups = computed(() => {
  const fid = activeFarmId.value
  // 本农场当前拥有的地块 slot → 名称（用 /board 的最新数据，权威）
  const slotMap = new Map()
  for (const p of (farmDetail.value?.plots || [])) {
    slotMap.set(p.slot, p.fieldName || ('第 ' + (p.slot + 1) + ' 号田'))
  }
  const map = new Map()
  for (const s of farmSeasons.value) {
    // 只留当前农场的
    if (fid != null && s.farmId != null && Number(s.farmId) !== Number(fid)) continue
    const key = s.slot
    // 只留当前农场现在拥有的地块
    if (slotMap.size && !slotMap.has(key)) continue
    if (!map.has(key)) {
      map.set(key, { slot: key, plotName: slotMap.get(key) || s.fieldName || ('第 ' + (key + 1) + ' 号田'), rows: [] })
    }
    map.get(key).rows.push(s)
  }
  // 组内按季次倒序（最近一季在最上面）
  const groups = [...map.values()]
  groups.forEach((g) => g.rows.sort((a, b) => (b.seasonNo || 0) - (a.seasonNo || 0)))
  groups.sort((a, b) => a.slot - b.slot)
  return groups
})
const keyword = ref('')
/** 全部农场（来自 /board，带地块与物候） */
const allFarms = ref([])
/** 还没归属农场的老地块（早期建田没有 farmId） */
const unassignedPlots = ref([])
/**
 * 世界日期（S25）—— 用户级「今天」。
 *
 * <p>🔴 **不能用 `new Date()`**：演示模式下用户可推进时间轴，
 * 世界日期被推进过（可能已经是 2027 年）。日期选择器禁用「今天之前」
 * 必须按世界日期算，否则玩家会被禁掉一大片合法日期（或反过来能选过去）。
 */
const worldDate = ref('')
/** 建农场弹窗（S25） */
const buildOpen = ref(false)
const buildBusy = ref(false)
const buildForm = reactive({ name: '', openedOn: '', district: '' })
/** 已加载的省份区县图层：provAdcode -> { items, districts } */
const districtLayers = ref({})
/** 已加载的地级市图层：cityAdcode -> { items }（该市下辖区县边界） */
const cityLayers = ref({})
/** 视野内需要加载区县的省份 */
const visibleProvIds = ref([])
/** 玩家点选的省——它的区县层永远显示，不受视野跨度限制 */
const pinnedProvId = ref(null)
/** 玩家点选的地级市——它的区县层永远显示（省级视图下钻到市） */
const pinnedCityId = ref(null)
/** 正在加载区划的省份数量（给 UI 一个轻量提示） */
const distLoading = ref(0)
/**
 * 视野内「已加载区县层」的地级市（2026-10-21）。
 *
 * <p>镜头进入市级尺度后自动加载这些市的区县，面板上显示的应该是
 * 区县面而不是市面。这些市是「跟着镜头走」的自动加载结果，
 * 与玩家主动点选下钻的 {@link pinnedCityId} 是两条并行的路。
 */
const autoCityIds = ref([])
const activeFarmId = ref(null)
const hoverName = ref(null)
const hoverInfo = ref(null)
/**
 * 农场标记悬浮预览卡片。
 *
 * <p>与区域悬浮提示（hoverInfo）分开：农场卡片内容多、尺寸大，
 * 用独立的定位与样式，不跟区域提示抢同一个位置。
 */
const farmHover = ref(null)
const farmHoverPos = reactive({ x: 0, y: 0 })
/**
 * 卡片是否被「钉住」。
 *
 * <p>钉住 = 卡片不随 mouseleave 自动关闭。点击标记点时用：
 * 点击会让镜头移动，标记点从鼠标下移走必然触发 mouseleave，
 * 不钉住的话卡片刚亮出来就被自己关掉（触屏玩家根本没法用）。
 */
const farmHoverPinned = ref(false)
/**
 * 关闭悬浮卡片的延时器。
 *
 * <p>卡片是**跟随鼠标**的，鼠标从标记点移向卡片时中间有十几像素的间隙，
 * 这段间隙会触发标记的 @mouseleave，卡片当场消失 —— 玩家的鼠标永远
 * 够不到「进入农场」按钮。
 *
 * <p>标准做法是**延迟关闭**：离开标记后等一小会儿，期间若鼠标进了卡片就取消关闭。
 */
let farmHoverCloseTimer = null
const FARM_HOVER_CLOSE_DELAY = 220
/** 图层切换面板展开状态 */
const layerPanelOpen = ref(false)
const tip = reactive({ x: 0, y: 0 })

/** 右侧看板是否收起 */
const sideFolded = ref(false)

/**
 * 切换右侧看板收起 / 展开。
 *
 * <p>状态存 localStorage：玩家收起来看地图，下次进来还应该是收起的。
 */
function toggleSide() {
  sideFolded.value = !sideFolded.value
  try {
    localStorage.setItem('farm.sideFolded', sideFolded.value ? '1' : '0')
  } catch (e) {
    // 隐私模式下 localStorage 不可用，忽略即可（折叠状态不持久化）
  }
}

/** 收起态摘要条上显示的一行字 */
const sideFoldSummary = computed(() => {
  const n = allFarms.value.length
  const plots = allFarms.value.reduce((s, f) => s + (f.plotCount || 0), 0)
  if (!n) return '暂无农场'
  return `${n} 个农场 · ${plots} 块地`
})

const svgEl = ref(null)
const canvasEl = ref(null)

/**
 * 单个农场的地块上限（2026-10-22）。
 *
 * <p>与后端 {@code FarmPlayService.MAX_PLOTS_PER_FARM} 保持一致。
 * 后端才是硬闸门，这里只是让玩家在建田前就看见限制。
 */
const MAX_PLOTS_PER_FARM = 12

/**
 * 季节记录的最终状态文案（2026-10-22）。
 *
 * <p>GROWING 出现在「刚建田还没收」的记录上——正常情况下它不会出现在已归档的档案里，
 * 但玩家刚建田还没推进时能看到「在长」。
 */
const SEASON_STATUS_TEXT = {
  GROWING: '在长',
  HARVESTED: '已收获',
  FAILED: '失败',
  ABANDONED: '已放弃'
}
function seasonStatusText(status) {
  return SEASON_STATUS_TEXT[status] || status || '—'
}


// 🔴 2026-10-28：作物常量表（CROP_COLOR / CROP_NAME / CROP_FAMILY /
// LEGACY_VISUAL / LEGEND_CROPS）与 cropColor / cropName / shortCrop
// 已全部收进 utils/farmCrops.js（唯一实现）。
// 原因：这些表曾在 4 个文件各写一份且已分叉 —— 最典型的是
// cropColor() 的兜底色，地图用 #33452f、概览面板用 #8ba07c，
// 同一个 visual 在两个界面显示成两种颜色。

function seasonText(c) {
  if (c.seasonState === 'TO_SOW') return `${c.daysToSow} 天后播种`
  if (c.seasonState === 'RIPE') return '已成熟'
  if (c.seasonState === 'IDLE') return '本季已收'
  return '生长中'
}

// ==================================================================
//  投影：全局固定的 Web Mercator（EPSG:3857）
// ==================================================================

// 🔴 2026-10-28：投影与几何计算已抽到 components/farm/map/geo.js（纯函数，零 Vue 依赖）。
// 抽出来的原因不是「文件太长」，而是这些函数根本不依赖组件 ——
// 一旦出现第二份投影，「点江西命中新疆」这类 bug 立刻回来。
// 详细设计说明见 geo.js 的文件头注释。

// ==================================================================
//  缩放 / 平移
// ==================================================================

/**
 * 最大缩放倍数。
 *
 * <p>全国适配时视野跨度约 63°，40× 对应 1.6°——已经能看清一个县内部的
 * 区划边界，再往里放也只剩几个像素的色块，没有信息增量。
 */
const MAX_ZOOM = 40
const zoom = ref(1)
const pan = reactive({ x: 0, y: 0 })
/** zoom=1 时的可视尺寸（按容器宽高比向外扩展，保证无留白） */
const fit = reactive({ w: 1000, h: 600 })
const dragging = ref(false)

const vbNow = computed(() => {
  const base = PROJ.base
  const w = fit.w / zoom.value
  const h = fit.h / zoom.value
  const cx = base.w / 2 + pan.x
  const cy = base.h / 2 + pan.y
  return `${(cx - w / 2).toFixed(2)} ${(cy - h / 2).toFixed(2)} ${w.toFixed(2)} ${h.toFixed(2)}`
})

const vbBox = computed(() => {
  const p = vbNow.value.split(' ').map(Number)
  return { x: p[0], y: p[1], w: p[2], h: p[3] }
})

/** 当前视野覆盖的经纬度范围 */
const viewBounds = computed(() => {
  const b = vbBox.value
  const [lng0, lat0] = PROJ.unproject(b.x, b.y)
  const [lng1, lat1] = PROJ.unproject(b.x + b.w, b.y + b.h)
  return {
    minLng: Math.min(lng0, lng1),
    maxLng: Math.max(lng0, lng1),
    minLat: Math.min(lat0, lat1),
    maxLat: Math.max(lat0, lat1)
  }
})

/** 视野经度跨度（度）——比 zoom 数字更能表达「看得多细」 */
const spanDeg = computed(() => vbBox.value.w * DEG_PER_UNIT)

/** 视野中心经纬度 */
const viewCenter = computed(() => {
  const b = vbBox.value
  return PROJ.unproject(b.x + b.w / 2, b.y + b.h / 2)
})

/**
 * 海面光晕固定在「地图内容坐标系」而不是视口坐标系。
 *
 * <p>如果让它跟着视口走（objectBoundingBox），放大到 5 倍时整个视口只剩渐变的最外圈，
 * 会黑得像没加载；固定在世界坐标后，放大只是靠近光晕中心，颜色连续。
 */
const sea = computed(() => {
  const base = PROJ.base
  return { cx: base.w / 2, cy: base.h / 2, r: Math.max(base.w, base.h) * 0.78 }
})

/**
 * 经纬网：每 10°（放大后 5°）一条。
 *
 * <p>不是为了精确读坐标，而是给留白一个参照——地图被缩放到只剩一个县时，
 * 没有网格很容易失去方位感。
 */
const grat = computed(() => {
  const box = vbBox.value
  const out = { v: [], h: [] }
  // 网格密度跟着视野走：固定 10° 在全国视图只有几条线，放大后又一条都没有
  const STEPS = [0.25, 0.5, 1, 2, 5, 10, 20, 30]
  const step = STEPS.find((s) => spanDeg.value / s <= 9) || 30
  for (let lng = -180; lng <= 180; lng += step) {
    const [x] = PROJ.project(lng, 0)
    if (x < box.x - 1 || x > box.x + box.w + 1) continue
    out.v.push({ x })
  }
  for (let lat = -60; lat <= 80; lat += step) {
    const [, y] = PROJ.project(0, lat)
    if (y < box.y - 1 || y > box.y + box.h + 1) continue
    out.h.push({ y })
  }
  return out
})

function refit() {
  const base = PROJ.base
  const el = svgEl.value
  if (!base || !el) return
  const r = el.getBoundingClientRect()
  if (!r.width || !r.height) return
  const elAspect = r.width / r.height
  const vbAspect = base.w / base.h
  if (elAspect >= vbAspect) {
    fit.h = base.h
    fit.w = base.h * elAspect
  } else {
    fit.w = base.w
    fit.h = base.w / elAspect
  }
  clampPan()
}

/**
 * 平移边界。
 *
 * <p>按「世界范围」（含南海诸岛）夹，而不是按「陆域基准」夹：
 * 否则海南的岛礁永远平移不到视野里，看着像数据缺失。
 */
function clampPan() {
  const w = PROJ.world
  const mx = Math.max(0, w.w / 2 - fit.w / (2 * zoom.value))
  const my = Math.max(0, w.h / 2 - fit.h / (2 * zoom.value))
  pan.x = Math.max(-mx, Math.min(mx, pan.x))
  pan.y = Math.max(-my, Math.min(my, pan.y))
}

function toSvg(clientX, clientY) {
  const el = svgEl.value
  if (!el) return { x: 0, y: 0 }
  const ctm = el.getScreenCTM()
  if (!ctm) return { x: 0, y: 0 }
  const pt = new DOMPoint(clientX, clientY).matrixTransform(ctm.inverse())
  return { x: pt.x, y: pt.y }
}

/**
 * 以某个屏幕点为锚点缩放。
 *
 * <p>推导：屏幕点对应的世界坐标 s 必须保持不动。设当前可视宽 vbW、鼠标在
 * 可视区内的相对位置 t，则缩放比 f 后中心点需要平移 vbW·(1−1/f)·(t−0.5)。
 * 这样滚轮放大时光标下的那个县始终留在光标下，不会「越缩越飘」。
 */
function zoomAt(factor, clientX, clientY) {
  const base = PROJ.base
  const s = toSvg(clientX, clientY)
  const vbW = fit.w / zoom.value
  const vbH = fit.h / zoom.value
  const minX = base.w / 2 + pan.x - vbW / 2
  const minY = base.h / 2 + pan.y - vbH / 2
  const tx = (s.x - minX) / vbW
  const ty = (s.y - minY) / vbH
  const nz = Math.min(MAX_ZOOM, Math.max(1, zoom.value * factor))
  const f = nz / zoom.value
  if (Math.abs(f - 1) < 1e-6) return
  pan.x += vbW * (1 - 1 / f) * (tx - 0.5)
  pan.y += vbH * (1 - 1 / f) * (ty - 0.5)
  zoom.value = nz
  clampPan()
}

function zoomStep(factor) {
  const el = svgEl.value
  if (!el) return
  const r = el.getBoundingClientRect()
  zoomAt(factor, r.left + r.width / 2, r.top + r.height / 2)
}

function resetView() {
  zoom.value = 1
  pan.x = 0
  pan.y = 0
  refit()
}

function onDblClick(e) {
  // 测量模式下双击是「结束测量」，不缩放也不聚焦
  if (measureMode.value) {
    if (measureMode.value === 'area') {
      e.preventDefault()
      finishAreaMeasure()
    }
    return
  }
  if (e.target.closest && e.target.closest('.wm-marker')) return
  const t = e.target.closest && e.target.closest('[data-type]')
  if (t) {
    // 双击省/区县 = 直接聚焦到它，而不是无脑放大
    const ad = Number(t.getAttribute('data-adcode'))
    const type = t.getAttribute('data-type')
    if (type === 'district') focusDistrict(ad)
    else focusProvince(ad)
    return
  }
  zoomAt(e.shiftKey ? 1 / 1.9 : 1.9, e.clientX, e.clientY)
}

const drag = { on: false, moved: false, sx: 0, sy: 0, px: 0, py: 0, k: 1 }

/**
 * 多点触控状态（移动端捏合缩放）。
 *
 * <p>触屏没有滚轮，不实现捏合的话手机上就只剩「点省」一种放大方式，
 * 少了一大半交互。跟踪活跃触点：≥2 个触点时按两指距离比值缩放，
 * 并以两指中点为锚点（和滚轮缩放同一套锚点逻辑）。
 */
const touch = {
  /** pointerId -> { x, y } */
  points: new Map(),
  /** 捏合起始时的双指距离与缩放级别 */
  startDist: 0,
  startZoom: 1,
  /** 捏合过程中是否已经移动（用于吞掉随后的 click） */
  pinching: false
}

function touchDistance() {
  const p = [...touch.points.values()]
  if (p.length < 2) return 0
  return Math.hypot(p[0].x - p[1].x, p[0].y - p[1].y)
}

function touchCenter() {
  const p = [...touch.points.values()]
  if (!p.length) return null
  let x = 0
  let y = 0
  for (const q of p) {
    x += q.x
    y += q.y
  }
  return { x: x / p.length, y: y / p.length }
}

function onPointerDown(e) {
  if (e.pointerType === 'touch') {
    touch.points.set(e.pointerId, { x: e.clientX, y: e.clientY })
    if (touch.points.size === 2) {
      // 第二根手指落下：记录捏合基准
      touch.startDist = touchDistance()
      touch.startZoom = zoom.value
      touch.pinching = true
      drag.on = false
      dragging.value = false
    }
    return
  }
  if (e.button !== 0 && e.pointerType === 'mouse') return
  hoverName.value = null
  const ctm = svgEl.value?.getScreenCTM()
  drag.k = ctm && ctm.a ? 1 / ctm.a : 1
  drag.on = true
  drag.moved = false
  drag.sx = e.clientX
  drag.sy = e.clientY
  drag.px = pan.x
  drag.py = pan.y
}

function onPointerMove(e) {
  if (e.pointerType === 'touch') {
    if (!touch.points.has(e.pointerId)) return
    touch.points.set(e.pointerId, { x: e.clientX, y: e.clientY })
    if (touch.points.size >= 2 && touch.startDist > 0) {
      const d = touchDistance()
      if (d <= 0) return
      const f = d / touch.startDist
      if (Math.abs(f - 1) < 1e-4) return
      const c = touchCenter()
      // 以两指中点为锚点缩放：把「捏合前的中点」保持在「捏合后的中点」上
      const target = clampZoom(touch.startZoom * f)
      const prev = zoom.value
      zoom.value = target
      if (prev !== target) {
        const ctm = svgEl.value?.getScreenCTM()
        const k = ctm && ctm.a ? 1 / ctm.a : 1
        const vbW = fit.w / prev
        const vbH = fit.h / prev
        const cx = baseCenterX()
        const cy = baseCenterY()
        const sx = cx - vbW / 2
        const sy = cy - vbH / 2
        const tx = (c.x * k - sx) / vbW
        const ty = (c.y * k - sy) / vbH
        const vbW2 = fit.w / target
        const vbH2 = fit.h / target
        pan.x += vbW2 * (1 - target / prev) * (tx - 0.5)
        pan.y += vbH2 * (1 - target / prev) * (ty - 0.5)
        clampPan()
      }
    }
    return
  }
  if (!drag.on) return
  const dx = e.clientX - drag.sx
  const dy = e.clientY - drag.sy
  if (!drag.moved) {
    if (Math.hypot(dx, dy) <= 4) return
    drag.moved = true
    dragging.value = true
  }
  pan.x = drag.px - dx * drag.k
  pan.y = drag.py - dy * drag.k
  clampPan()
}

function onPointerUp(e) {
  if (e.pointerType === 'touch') {
    touch.points.delete(e.pointerId)
    if (touch.points.size < 2) {
      // 捏合结束：重置基准，避免残留状态影响下一次；
      // 同时保留 pinching 一小段时间，吞掉浏览器补发的 click
      touch.startDist = 0
      setTimeout(() => { touch.pinching = false }, 350)
    }
    return
  }
  if (!drag.on) return
  drag.on = false
  dragging.value = false
  if (drag.moved) {
    // 拖拽结束后紧跟的 click 要吞掉，否则一拖就误建/误选
    setTimeout(() => { drag.moved = false }, 0)
  }
}

/**
 * 地图背景点击。
 *
 * <p>三种情形，按优先级：
 * <ol>
 *   <li><b>测量模式</b>：任何位置都落一个测量点（测量时不点区县）</li>
 *   <li><b>命中省/区县</b>：交给各自的 @click 处理（这里直接返回）</li>
 *   <li><b>空隙处</b>：发起逆地理编码，弹地址气泡</li>
 * </ol>
 *
 * <p><b>手势守卫</b>：复用 {@code drag.moved / touch.pinching} —— 拖拽平移或
 * 双指捏合结束后浏览器补发的 click 必须吞掉，否则「拖一下地图」会顺带
 * 在手指底下弹一个逆地理编码气泡。
 *
 * <p>注意：@click 挂在 SVG 根元素上，省/区县的 @click 会<b>先</b>触发并冒泡上来，
 * 所以这里用 {@code e.target.closest('[data-type]')} 判断是否命中了区划。
 */
function onMapClick(e) {
  // 拖拽 / 捏合刚结束，浏览器补的 click —— 吞掉
  if (drag.moved || touch.pinching) return
  // 测量模式：点击一律落点
  if (measureMode.value) {
    addMeasurePoint(e.clientX, e.clientY)
    return
  }
  // 命中省/区县：它们的 @click 已经处理过了，这里不重复
  if (e.target.closest && e.target.closest('[data-type]')) return
  // 命中农场标记：同理
  if (e.target.closest && e.target.closest('.wm-marker')) return
  // 空隙处：逆地理编码
  doReverseGeocode(e.clientX, e.clientY)
}

function onWheel(e) {
  e.preventDefault()
  const f = Math.exp(-e.deltaY * 0.0016)
  zoomAt(f, e.clientX, e.clientY)
}

/** 把地图移到指定经纬度并放大到可读级别 */
function flyTo(lng, lat, targetZoom) {
  if (!Number.isFinite(lng) || !Number.isFinite(lat)) return
  const [x, y] = PROJ.project(lng, lat)
  zoom.value = Math.min(MAX_ZOOM, Math.max(zoom.value, targetZoom))
  pan.x = x - PROJ.base.w / 2
  pan.y = y - PROJ.base.h / 2
  clampPan()
}

// ==================================================================
//  底图 / 注记图层（天地图，走后端代理，浏览器不持有 key）
// ==================================================================

/**
 * 图层模型。
 *
 * <p><b>为什么拆成「底图 + 注记」两层</b>：天地图把内容拆成独立的瓦片图层，
 * 矢量底图（vec_w）本身不含地名，注记是单独的透明瓦片（cva_w / cia_w / cta_w）。
 * 不拆的话，卫星底图就永远没有地名，矢量底图就永远没有 POI 名。
 *
 * <p><b>为什么走后端代理</b>：天地图的 key 分浏览器端 / 服务端两种，
 * 浏览器端 key 放在前端等于公开（DevTools 一看即得）；服务端 key 写进 JS 也是公开。
 * 所以由后端持 key 代调，浏览器只跟自己的后端说话 —— 前端连 key 都不需要知道。
 */
const BASE_STORE = 'farm.basemap'
const LABEL_STORE = 'farm.labelmap'

/** 底图三选一 */
const BASE_LAYERS = [
  { id: 'vector', name: '矢量', layer: 'vec_w', desc: '标准电子地图' },
  { id: 'satellite', name: '卫星', layer: 'img_w', desc: '影像航拍' },
  { id: 'terrain', name: '地形', layer: 'ter_w', desc: '地形晕渲' }
]

/** 注记四选一 */
const LABEL_LAYERS = [
  { id: 'none', name: '关闭', layer: '', desc: '不显示注记' },
  { id: 'cva', name: '中文矢量', layer: 'cva_w', desc: '配合矢量底图' },
  { id: 'cia', name: '中文影像', layer: 'cia_w', desc: '配合卫星底图' },
  { id: 'cta', name: '中文地形', layer: 'cta_w', desc: '配合地形底图' }
]

/**
 * 底图与注记的推荐搭配。
 *
 * <p>矢量底图配 cva_w、影像配 cia_w、地形配 cta_w —— 三套注记是同一套要素的不同渲染，
 * 交叉搭配（比如影像 + 矢量注记）不会出错，但配色不搭（中文字是深色描边的矢量注记，
 * 压在卫星图上会糊）。用户可以手动覆盖，这里只提供默认值。
 */
const DEFAULT_LABEL_FOR_BASE = {
  vector: 'cva',
  satellite: 'cia',
  terrain: 'cta'
}

const baseLayer = ref('vector')
const labelLayer = ref('cva')
const tileErr = ref(0)
/** 后端是否配好了服务端 key（前端拿不到 key 本身，只能问后端要一个布尔值） */
const tdtAvailable = ref(true)
/** 后端明确说「没配 key」时置 false，图层切换器给出手动提示 */
const tdtKeyMissing = ref(false)
/**
 * 最近一次瓦片失败的**具体原因**（后端返回的 message）。
 *
 * <p>以前这里只有一句「未配置 key 或网络不可达」，把三种完全不同的故障揉成一句话：
 * 没配 key / key 类型不对(403 权限类型错误) / 上游网络不通，用户只能自己猜。
 * 现在把后端返回的原文带出来，提示才有指向性。
 */
const tileErrReason = ref('')

const baseLayerType = computed(
  () => BASE_LAYERS.find((l) => l.id === baseLayer.value)?.layer || 'vec_w'
)
const labelLayerType = computed(
  () => LABEL_LAYERS.find((l) => l.id === labelLayer.value)?.layer || ''
)
const baseLayerName = computed(
  () => BASE_LAYERS.find((l) => l.id === baseLayer.value)?.name || '矢量'
)
const labelLayerName = computed(
  () => LABEL_LAYERS.find((l) => l.id === labelLayer.value)?.name || '关闭'
)

/** 瓦片层级：3（全国）~ 18（街区）。天地图在 18 级以上返回空白瓦片 */
const TDT_MIN_Z = 3
const TDT_MAX_Z = 18
/** 多层叠加后的瓦片总上限：底图 + 注记两张图，每张最多 200 张 */
const TILE_LIMIT = 400

/**
 * 瓦片网格（z/x/y 列表 + 每张瓦片的 SVG 位置）。
 *
 * <p>底图层和注记层<b>共用同一份网格</b>：天地图的 cva/cia/cta 与 vec/img/ter
 * 是同一套切片矩阵（同一 z 下 x/y 完全对齐），如果各自算一遍，
 * 两个 layerType 的瓦片可能落在不同的格子上，注记就会和底图错位。
 * 所以这里只算一次网格，两层各自套上不同的 URL。
 */
const tileGrid = computed(() => {
  const parts = vbNow.value.split(' ').map(Number)
  const [vx, vy, vw, vh] = parts
  const K = PROJ.K
  const wx0 = vx / K + PROJ.wx0
  const wx1 = (vx + vw) / K + PROJ.wx0
  const wy0 = vy / K + PROJ.wy0
  const wy1 = (vy + vh) / K + PROJ.wy0

  const el = svgEl.value
  const cssW = el ? el.getBoundingClientRect().width : 1200
  const pxPerUnit = cssW / vw
  let z = Math.round(Math.log2((K * pxPerUnit) / 256))
  // 天地图有效范围 3~18，超出范围上游返回空白瓦片
  z = Math.max(TDT_MIN_Z, Math.min(TDT_MAX_Z, z))

  const n = 2 ** z
  const size = K / n
  const x0 = Math.floor(wx0 * n)
  const x1 = Math.floor(wx1 * n)
  const y0 = Math.floor(wy0 * n)
  const y1 = Math.floor(wy1 * n)
  const out = []
  // 单层上限 200：两层叠加最多 400 张。超出视野的部分直接不画，
  // 宁可少画几格也不要让浏览器同时拉几百张图把首屏卡住。
  for (let tx = x0; tx <= x1 && out.length < 200; tx++) {
    for (let ty = y0; ty <= y1 && out.length < 200; ty++) {
      if (ty < 0 || ty >= n) continue
      out.push({
        key: `${z}/${tx}/${ty}`,
        z,
        x: tx,
        y: ty,
        sx: (tx / n - PROJ.wx0) * K,
        sy: (ty / n - PROJ.wy0) * K,
        s: size
      })
    }
  }
  return out
})

/** 底图层瓦片（底图本身） */
const baseTiles = computed(() =>
  tileGrid.value.map((t) => ({
    ...t,
    url: tdtTilePath(baseLayerType.value, t.z, t.x, t.y)
  }))
)

/**
 * 注记层瓦片。
 *
 * <p>与底图<b>共用 z/x/y</b>（同一瓦片网格），所以位置天然对齐；
 * 注记瓦片是透明 PNG，叠在底图上只多出文字和边界标注。
 */
const labelTiles = computed(() => {
  const type = labelLayerType.value
  if (!type) return []
  return tileGrid.value.map((t) => ({
    ...t,
    url: tdtTilePath(type, t.z, t.x, t.y)
  }))
})

/** 底图和注记合计的瓦片数（给 UI 一个直观指标） */
const tileCount = computed(() => baseTiles.value.length + labelTiles.value.length)

/**
 * 瓦片加载失败的处理。
 *
 * <p><b>为什么要探针</b>：瓦片是 SVG {@code <image>} 加载的，失败只给一个 error 事件，
 * 拿不到响应体。不知道后端到底为什么失败，提示就只能写成「未配置 key 或网络不可达」
 * 这种两头堵的话。现在用 fetch 单独探一次瓦片端点，拿到后端的原文 message 再分类提示。
 */
function onTileError() {
  tileErr.value++
  if (tileErr.value !== 4) return
  if (tdtKeyMissing.value) {
    // 后端 status 已经明确说了没配 key，不需要再探
    ElMessage.warning('后端未配置天地图 key，已切回矢量底图')
    fallbackToVector()
    return
  }
  diagnoseTileFailure()
}

/** 切回矢量底图（失败时的兜底，保证地图仍可用） */
function fallbackToVector() {
  baseLayer.value = 'vector'
  labelLayer.value = 'cva'
}

/** 探一次瓦片端点，按后端返回的具体原因给出可执行的提示 */
async function diagnoseTileFailure() {
  const probe = await probeMapTdtTile(baseLayerType.value)
  let msg
  if (probe.status === 0) {
    // 连后端都没连上
    msg = '无法连接后端服务，已切回矢量底图'
  } else if (probe.status === 503) {
    // 后端明确说没配 key
    tdtKeyMissing.value = true
    msg = '后端未配置天地图 key，已切回矢量底图'
  } else if (probe.status === 403 || /权限类型|301012|301013/.test(probe.message)) {
    // key 类型与后端调用方式不匹配（浏览器端 key 配服务端 UA 等）
    msg = `天地图 key 权限不匹配：${probe.message || 'key 类型与服务端调用方式不符'}。请在「系统管理 → API 密钥」改用服务端 key`
  } else if (probe.status === 429) {
    msg = '天地图调用被限流，请稍后重试，已切回矢量底图'
  } else if (probe.status === 502 || probe.status === 504) {
    msg = `天地图服务不可达：${probe.message || '上游返回异常'}，已切回矢量底图`
  } else {
    msg = `天地图瓦片加载失败（${probe.status}）：${probe.message || '未知原因'}，已切回矢量底图`
  }
  tileErrReason.value = msg
  ElMessage.warning(msg)
  fallbackToVector()
}

/** 切底图：同时把注记切到配套的那一套（用户之后仍可手动覆盖） */
function setBaseLayer(id) {
  if (!BASE_LAYERS.some((l) => l.id === id)) return
  baseLayer.value = id
  labelLayer.value = DEFAULT_LABEL_FOR_BASE[id] || 'none'
  tileErr.value = 0
  localStorage.setItem(BASE_STORE, id)
}

/** 只切注记：不动底图 */
function setLabelLayer(id) {
  if (!LABEL_LAYERS.some((l) => l.id === id)) return
  labelLayer.value = id
  tileErr.value = 0
  localStorage.setItem(LABEL_STORE, id)
}

/**
 * 问后端「天地图准备好了吗」。
 *
 * <p>前端拿不到 key 也不该拿，只能问后端要一个布尔状态（后端不会把 key 下发）。
 * 没配 key 时图层切换器给出手动提示，而不是让用户对着 4 张破图猜原因。
 */
async function probeTdtStatus() {
  try {
    const res = await getMapTdtStatus()
    const st = res?.data || {}
    tdtAvailable.value = st.enabled !== false
    tdtKeyMissing.value = st.serverKeyConfigured === false
    if (st.serverKeyConfigured === false) {
      tdtKeyMissing.value = true
    }
  } catch (e) {
    // 后端未部署 / 网络不通：不断言，交给瓦片错误兜底提示
    tdtAvailable.value = true
  }
}

// ==================================================================
//  作物分布图层（统计年鉴口径的播种面积 / 产量）
// ==================================================================

/**
 * 着色指标三选一。
 *
 * <p>面积、产量、亩产（单产）回答的是三个不同问题：
 * 「种了多少地」「收了多少粮」「每亩打多少斤」，缺一不可。
 */
const CROP_METRICS = [
  { id: 'area', name: '播种面积', unit: '千公顷' },
  { id: 'output', name: '总产量', unit: '万吨' },
  { id: 'yield', name: '单产', unit: '公斤/公顷' }
]

/** 产量筛选的快捷档位（按当前指标单位自适应） */
const CROP_RANGE_QUICK = [
  { label: '不限', min: 0, max: -1 },
  { label: '低产', min: 0, max: 0.25 },
  { label: '中产', min: 0.25, max: 0.6 },
  { label: '高产', min: 0.6, max: 1 }
]

/** 作物分布图层是否开启 */
const cropLayerOn = ref(false)
/** 着色指标：area | output | yield */
const cropMetric = ref('area')
/** 已选作物 id 列表；空 = 全部 */
const cropSel = ref([])
/** 产量筛选区间（按当前指标的单位） */
const cropRange = reactive({ min: 0, max: 0 })
/** 区间滑块（双端），与 cropRange 同步 */
const cropRangeSlider = ref([0, 0])
/** 后端返回的作物元信息（名称 / 分组 / 展示顺序） */
const cropMetas = ref([])
/** 后端返回的 meta（数据来源、口径声明） */
const cropMeta = ref({})
/** adcode -> { cropId -> {area, output, yield} } */
const cropDistData = ref({})
/**
 * 省级作物实测值（adcode -> { cropId -> {area,output,yield} }）。
 *
 * <p>全国视图里画的是**省界**，没有区县层可着色——如果只做区县着色，
 * 玩家一打开图层看到的是「一片空白 + 迷你图例」，完全不知道分布长什么样。
 * 所以全国视图按省着色（下钻到市/县后再换成区县派发值）。
 */
const cropProvData = ref({})
/** 正在拉取分布数据的标识，避免重复请求 */
const cropLoading = ref(false)

/** 常用作物（筛选面板的「常用」快捷档） */
const DEFAULT_CROP_IDS = ['grain', 'rice', 'wheat', 'corn', 'soybean', 'vegetable', 'fruit', 'cotton']
const defaultCropSel = DEFAULT_CROP_IDS

/** 作物按分组归类（UI 分组展示） */
const cropGroups = computed(() => {
  const order = ['粮食', '经济作物', '蔬菜', '水果', '其他']
  const map = new Map()
  for (const c of cropMetas.value) {
    const g = c.group || '其他'
    if (!map.has(g)) map.set(g, [])
    map.get(g).push(c)
  }
  return order
    .filter((g) => map.has(g))
    .map((g) => ({ name: g, items: map.get(g) }))
})

/**
 * 区间上限。
 *
 * <p>按当前视图实际出现的最大值定上限，而不是写死常数——
 * 粮食面积最大到 14754 千公顷，水果产量只有几千万吨，
 * 写死一个上限会让另一个指标的滑块全程拖到底都没反应。
 */
const cropRangeMax = computed(() => {
  let max = 0
  const aggIds = cropAggIds.value
  // 全国视图用省级数据，下钻后用区县数据 —— 两边都要参与上限计算，
  // 否则玩家从全国切到某个县时，区间上限会突然跳变。
  const sources = [cropDistData.value, cropProvData.value]
  for (const src of sources) {
    for (const d of Object.values(src)) {
      const crops = d.crops || {}
      const w = Number(d.areaKm2) || 0
      let sum = 0
      let wsum = 0
      let ww = 0
      let n = 0
      let areaSum = 0
      let outSum = 0
      for (const cid of aggIds) {
        const c = crops[cid]
        if (!c) continue
        const v = c[cropMetric.value]
        if (typeof v !== 'number') continue
        sum += v
        wsum += v * w
        ww += w
        n++
        if (cropMetric.value === 'yield') {
          areaSum += typeof c.area === 'number' ? c.area : 0
          outSum += typeof c.output === 'number' ? c.output : 0
        }
      }
      if (!n) continue
      let v
      if (cropMetric.value === 'yield') {
        // 综合单产 = 合并总产量 × 10000 / 合并总面积
        v = (areaSum > 0 && outSum > 0)
          ? (outSum * 10000) / areaSum
          : (ww > 0 ? wsum / ww : sum / n)
      } else {
        v = sum
      }
      if (v > max) max = v
    }
  }
  return max > 0 ? Math.ceil(max) : 100
})

const cropRangeStep = computed(() => {
  const m = cropRangeMax.value
  if (m > 5000) return 100
  if (m > 500) return 10
  if (m > 50) return 1
  return 0.1
})

const cropRangeLabel = computed(() => {
  const unit = CROP_METRICS.find((m) => m.id === cropMetric.value)?.unit || ''
  const { min, max } = cropRange
  if (max <= 0 || max >= cropRangeMax.value) return `≥ ${min} ${unit}`
  return `${min} ~ ${max} ${unit}`
})

function fmtRangeTip(v) {
  const unit = CROP_METRICS.find((m) => m.id === cropMetric.value)?.unit || ''
  return `${v} ${unit}`
}

/** 切换图层开关 */
function toggleCropLayer() {
  cropLayerOn.value = !cropLayerOn.value
  if (cropLayerOn.value) {
    loadCropMeta()
    loadCropDistribution()
  }
  // 开关都要重算一次自动加载：
  // 开启时作物图层要画「市」这一级的聚合面 → 必须清掉自动加载的区县层；
  // 关闭时区县面可以回来了 → 重新按当前视野接上。
  syncAutoCityLayers()
}

/** 切换指标：重置区间（不同指标单位不同，旧区间无意义） */
/**
 * 切换指标：重置区间（不同指标单位不同，旧区间无意义）并**立刻重算着色**。
 *
 * <p>必须重算：色阶上下界是按当前指标算出来的，不重算的话
 * 地图上的颜色还是上一个指标（比如从「面积」切到「单产」，
 * 图例刻度还是千公顷，色块也按面积分的档）。
 */
function setCropMetric(id) {
  if (cropMetric.value === id) return
  cropMetric.value = id
  cropRange.min = 0
  cropRange.max = 0
  cropRangeSlider.value = [0, cropRangeMax.value]
  applyCropFilter()
}

/**
 * 作物多选：再点一次取消。
 *
 * <p>勾选作物要**重新拉数据**：后端按 crop 参数过滤，
 * 只返回选中作物的指标 —— 前端本地过滤也行，但那样每次勾选都要
 * 把 31 省 × 34 作物全量传下来，得不偿失。
 */
function toggleCrop(cropId) {
  const i = cropSel.value.indexOf(cropId)
  if (i >= 0) {
    cropSel.value.splice(i, 1)
  } else {
    cropSel.value = [...cropSel.value, cropId]
  }
  loadCropDistribution()
}

/**
 * 全选所有作物。
 *
 * <p>排除「农作物总播种面积」：它是**独立口径**（含复种面积），
 * 和其它作物一起累加没有意义。
 */
function selectAllCrops() {
  const total = cropMeta.value?.totalAreaCrop || 'total_area'
  cropSel.value = cropMetas.value.map((m) => m.cropId).filter((id) => id !== total)
  loadCropDistribution()
}

/** 当前着色层级的中文名（UI 提示用） */
const cropLevelName = computed(() => (
  { province: '省级', city: '地级市', district: '区县级' }[cropLevel.value] || '省级'
))

function onCropRangeChange() {
  if (cropRange.max < cropRange.min) {
    const t = cropRange.min
    cropRange.min = cropRange.max
    cropRange.max = t
  }
  cropRangeSlider.value = [cropRange.min, cropRange.max || cropRangeMax.value]
  applyCropFilter()
}

function onCropRangeSlider(v) {
  cropRange.min = v[0]
  cropRange.max = v[1] >= cropRangeMax.value ? 0 : v[1]
  applyCropFilter()
}

function applyQuickRange(q) {
  const max = cropRangeMax.value
  if (q.max < 0) {
    cropRange.min = 0
    cropRange.max = 0
  } else {
    cropRange.min = Math.round(max * q.min * 10) / 10
    cropRange.max = Math.round(max * q.max * 10) / 10
  }
  cropRangeSlider.value = [cropRange.min, cropRange.max || cropRangeMax.value]
  applyCropFilter()
}

/** 拉一次后端的作物元信息（名称 / 分组 / 口径声明） */
async function loadCropMeta() {
  if (cropMetas.value.length) return
  try {
    const res = await getFarmCropDistributionProvinces([])
    const d = res?.data || {}
    cropMetas.value = d.cropMetas || []
    cropMeta.value = d.meta || {}
  } catch (e) {
    console.warn('[farm-map] 作物元信息加载失败', e)
  }
}

/**
 * 拉取作物分布数据。
 *
 * <p>按当前地图层级取对应量级的数据：
 * <ul>
 *   <li><b>全国视图</b>（跨度 > 26°）：拉<b>省级</b>实测值，直接给省界着色；</li>
 *   <li><b>省级视图</b>：拉视野内各省的<b>区县</b>派发值（前端再聚合成市）；</li>
 *   <li><b>下钻到市</b>：只拉该市的<b>区县</b>派发值。</li>
 * </ul>
 *
 * <p>后端是「按省值派发到区县」的，跨省没有意义，所以范围一变就得重拉。
 */
async function loadCropDistribution() {
  if (!cropLayerOn.value) return
  cropLoading.value = true
  try {
    if (cropLevel.value === 'province') {
      // 全国视图：省级实测值（一次请求拿全 31 省）
      const res = await getFarmCropDistributionProvinces(cropSel.value)
      const d = res?.data || {}
      cropMetas.value = d.cropMetas && d.cropMetas.length ? d.cropMetas : cropMetas.value
      cropMeta.value = d.meta || cropMeta.value
      const map = {}
      for (const row of d.provinces || []) {
        map[row.adcode] = row
      }
      cropProvData.value = map
      cropDistData.value = {}
    } else {
      // 省级/市级视图：区县派发值（可能涉及多个省）
      const scopes = cropScopes.value
      const distMap = {}
      let metas = []
      let meta = cropMeta.value
      for (const scope of scopes) {
        const res = await getFarmCropDistribution(scope, cropSel.value)
        const d = res?.data || {}
        if (d.cropMetas && d.cropMetas.length) metas = d.cropMetas
        if (d.meta) meta = d.meta
        for (const row of d.districts || []) {
          distMap[row.adcode] = row
        }
      }
      if (metas.length) cropMetas.value = metas
      cropMeta.value = meta
      cropDistData.value = distMap
      cropProvData.value = {}
    }
    // 区间上限随数据变化
    cropRangeSlider.value = [cropRange.min, cropRangeMax.value || 1]
    applyCropFilter()
  } catch (e) {
    console.warn('[farm-map] 作物分布加载失败', e)
  } finally {
    cropLoading.value = false
  }
}

/**
 * 参与聚合的作物集合（已做父子去重）。
 *
 * <p><b>为什么必须去重</b>：年鉴里的作物是**嵌套**的——
 * 「粮食」= 谷物 + 豆类 + 薯类，「谷物」= 稻谷 + 小麦 + 玉米。
 * 直接把选中的作物相加，「粮食 + 稻谷 + 小麦 + 玉米」会把同一块地数四遍，
 * 图例上的数字完全失真（实测全国视图会显示 59.9k 千公顷，实际只有 ~16k）。
 *
 * <p>规则：选了父作物就不再累加它的子孙；选了子作物但没选父作物，
 * 则子作物独立计入（父作物没被选，就不该由它代表）。
 *
 * <p>「农作物总播种面积」是**独立口径**（含复种面积），既不是别的作物的父项，
 * 也不能和别的作物相加——它只能单独作为一个筛选目标。
 */
const cropAggIds = computed(() => {
  const hier = cropMeta.value?.hierarchy || {}
  const sel = cropSel.value.length
    ? cropSel.value.slice()
    : (cropMeta.value?.rootCrops || []).slice()
  if (!sel.length) return []

  const selSet = new Set(sel)
  const isTotalArea = cropMeta.value?.totalAreaCrop || 'total_area'

  // 反向索引：child -> [parent]
  const parentOf = new Map()
  for (const [parent, kids] of Object.entries(hier)) {
    for (const k of kids || []) {
      if (!parentOf.has(k)) parentOf.set(k, [])
      parentOf.get(k).push(parent)
    }
  }

  /**
   * 某作物是否被某个「已选中的祖先」覆盖。
   *
   * <p>方向很重要：选了「粮食」和「稻谷」时，要丢掉的是**稻谷**（子），
   * 因为稻谷的面积已经含在粮食里了。反过来把粮食丢掉就全错了。
   */
  const covered = (cid) => {
    const stack = [...(parentOf.get(cid) || [])]
    const seen = new Set()
    while (stack.length) {
      const cur = stack.pop()
      if (seen.has(cur)) continue
      seen.add(cur)
      if (selSet.has(cur)) return true
      stack.push(...(parentOf.get(cur) || []))
    }
    return false
  }

  const out = []
  for (const cid of sel) {
    if (cid === isTotalArea) {
      // 「农作物总播种面积」含复种面积，与其它作物不是同一口径，
      // 只能单独选；和其它作物一起累加会得出没有意义的数
      if (sel.length > 1) continue
      out.push(cid)
      continue
    }
    if (covered(cid)) continue
    out.push(cid)
  }
  return out
})

/**
 * 把筛选结果落到地图图层的 fill 上。
 *
 * <p>做法：算出每个行政单元在当前指标下是否命中筛选（数值落在区间内），
 * 命中的用**色阶**（按数值大小分级着色），未命中的压成灰暗色。
 * 这样「筛出高产县」在地图上直接看得见，而不是把其它县藏起来。
 *
 * <p><b>层级与数值口径必须对齐</b>：地图上画的是哪个面，着色值就是哪个量级的值。
 * <ul>
 *   <li>全国视图画省界 → 用<b>省级实测值</b>（千公顷级，最大几万）；</li>
 *   <li>省级视图画市面 → 用<b>该市下辖区县加总</b>（与省值同量级）；</li>
 *   <li>下钻到市 → 画区县面 → 用<b>区县派发值</b>。</li>
 * </ul>
 * 混用会导致「一个市只有几十千公顷」这种明显错位的数字。
 */
function computeCropValues() {
  const metric = cropMetric.value
  const { min, max } = cropRange
  const upper = max > 0 ? max : Infinity
  const aggIds = cropAggIds.value

  /**
   * 汇总器：adcode -> 累加值。
   *
   * <p><b>单产的合并方式与面积/产量不同</b>：单产不能直接平均——
   * 「小麦 6000 + 玉米 5000」的平均 5500 没有物理意义。
   * 正确的合并口径是「合并后的总产量 ÷ 合并后的总面积」，
   * 也就是**按面积加权的综合单产**。所以单产分支要同时累加
   * 产量与面积，最后再相除。
   */
  const build = (rows, keyOf) => {
    const map = new Map()
    for (const row of rows) {
      const key = keyOf(row)
      if (key == null) continue
      if (!map.has(key)) map.set(key, { sum: 0, wsum: 0, w: 0, n: 0, outSum: 0, areaSum: 0 })
      const e = map.get(key)
      const crops = row.crops || {}
      const w = Number(row.areaKm2) || 0
      for (const cid of aggIds) {
        const c = crops[cid]
        if (!c) continue
        const v = c[metric]
        if (typeof v !== 'number') continue
        e.sum += v
        e.wsum += v * w
        e.w += w
        e.n++
        if (metric === 'yield') {
          // 单产：按「产量/面积」口径累加，最后相除
          const a = typeof c.area === 'number' ? c.area : 0
          const o = typeof c.output === 'number' ? c.output : 0
          e.areaSum += a
          e.outSum += o
        }
      }
    }
    return map
  }

  const finish = (map) => {
    const out = {}
    for (const [adcode, e] of map) {
      if (!e.n) {
        out[adcode] = { hit: false, value: null }
        continue
      }
      let value
      if (metric === 'yield') {
        // 综合单产 = 合并总产量(万吨) × 10000 / 合并总面积(千公顷) = 公斤/公顷
        if (e.areaSum > 0 && e.outSum > 0) {
          value = (e.outSum * 10000) / e.areaSum
        } else {
          // 缺产量数据时退化为面积加权平均
          value = e.w > 0 ? e.wsum / e.w : e.sum / e.n
        }
      } else {
        value = e.sum
      }
      out[adcode] = { hit: value >= min && value <= upper, value }
    }
    return out
  }

  // 全国视图：省级实测值
  if (cropLevel.value === 'province' && Object.keys(cropProvData.value).length) {
    return {
      province: finish(build(Object.values(cropProvData.value), (r) => r.adcode)),
      city: {},
      district: {}
    }
  }

  // 已下钻：区县派发值（省级视图时还要聚合成市）
  const districtRows = Object.values(cropDistData.value)
  return {
    province: {},
    city: finish(build(districtRows, (r) => r.cityAdcode)),
    district: finish(build(districtRows, (r) => r.adcode))
  }
}

/** 当前筛选下命中的着色结果：{ province, city, district } 各自 adcode -> { hit, value } */
const cropFilterResult = ref({ province: {}, city: {}, district: {} })
/** 色阶的归一化上下界 */
const cropFilterScale = ref({ min: 0, max: 1 })

/**
 * 当前着色层级：province（全国视图，只有省界）| city（省级视图，画市面）| district（下钻到市，画区县面）。
 *
 * <p>决定依据是「地图上现在画的是什么面」，不是「数据来自哪一级」——
 * 层级和数值口径必须对齐，否则会出现「一个市只有几十千公顷」的错位数字。
 */
const cropLevel = computed(() => {
  if (!cropLayerOn.value) return 'province'
  if (pinnedCityId.value) return 'district'
  // 视野跨度过大 = 全国视图，只有省界
  if (spanDeg.value > DISTRICT_SPAN) return 'province'
  return 'city'
})

/**
 * 当前着色层级：兼容旧引用名。
 *
 * <p>保留这个名字是因为图例、悬浮提示都要按层级取不同的值池。
 */
const cropColorLevel = computed(() => cropLevel.value)

/**
 * 需要拉取区县数据的范围（省级视图下拉视野内各省，下钻时只拉该市）。
 *
 * <p>全国视图不拉区县数据——那时画的是省界，用省级实测值就够了。
 */
const cropScopes = computed(() => {
  if (!cropLayerOn.value) return []
  if (pinnedCityId.value) return [pinnedCityId.value]
  if (cropLevel.value === 'province') return []
  const ids = new Set(visibleProvIds.value)
  if (pinnedProvId.value) ids.add(pinnedProvId.value)
  return [...ids]
})

/**
 * 重算筛选结果并同步色阶上下界。
 *
 * <p>色阶上下界取**当前视图层级实际显示的那些面**的值域——
 * 全国视图用省值（几万千公顷），市级视图用市值（几千千公顷），
 * 两套值域差一个数量级，共用一套色阶会让其中一层全部撞在同一个色档。
 */
function applyCropFilter() {
  const res = computeCropValues()
  cropFilterResult.value = res
  const level = cropColorLevel.value
  const pool = level === 'province' ? res.province : level === 'city' ? res.city : res.district
  const hits = Object.values(pool)
    .filter((x) => x.hit && x.value != null)
    .map((x) => x.value)
  cropFilterScale.value = {
    min: hits.length ? Math.min(...hits) : 0,
    max: hits.length ? Math.max(...hits) : 1
  }
}

/**
 * 作物分布着色的色阶。
 *
 * <p>用固定色带而不是「主栽作物色」：分布图要表达的是**数值大小**，
 * 用作物色会让人以为「黄色 = 玉米」。这里用绿→黄→橙→红的连续色带，
 * 数值越大越暖。
 */
const CROP_SCALE = ['#1d4d3a', '#3f7a3a', '#8fae35', '#d4a032', '#d4702a', '#b8391f']

/** 按数值在色带上取色 */
function cropScaleColor(value) {
  const { min, max } = cropFilterScale.value
  if (value == null || max <= min) return CROP_SCALE[0]
  const t = (value - min) / (max - min)
  const i = Math.min(CROP_SCALE.length - 1, Math.max(0, Math.round(t * (CROP_SCALE.length - 1))))
  return CROP_SCALE[i]
}

/** 作物分布图例（数值分级） */
const cropDistLegend = computed(() => {
  if (!cropLayerOn.value) return { title: '', bands: [], tip: '' }
  const metric = CROP_METRICS.find((m) => m.id === cropMetric.value) || CROP_METRICS[0]
  const { min, max } = cropFilterScale.value
  const bands = CROP_SCALE.map((color, i) => {
    const t = CROP_SCALE.length === 1 ? 0 : i / (CROP_SCALE.length - 1)
    const v = min + (max - min) * t
    return { color, label: `${v >= 1000 ? (v / 1000).toFixed(1) + 'k' : Math.round(v * 10) / 10} ${metric.unit}` }
  })
  // 图例标题要标明着色层级，否则玩家会以为看到的是全国数值，
  // 实际可能正在看某个市的数值
  const levelName = { province: '省级', city: '地级市', district: '区县级' }[cropLevel.value] || '省级'
  return {
    title: `${levelName}·${metric.name}（${metric.unit}）`,
    bands,
    tip: cropRangeLabel.value
  }
})

// ==================================================================
//  距离测量 / 面积测算（纯前端计算，不请求后端）
// ==================================================================

/**
 * 为什么不走后端 GeoUtil：
 * 后端已有 {@code GeoUtil}（Haversine 距离 + Shoelace 面积），但没暴露 HTTP 端点。
 * 测量是纯本地交互，每次点一下都打一次网络往返既慢又没意义，
 * 所以前端自己算 —— 公式与后端一致（都是 Haversine / Shoelace）。
 *
 * 🔴 2026-10-28：计算部分已抽到 map/measure.js（纯函数）。这里只留交互。
 */

/** 测量模式：null | 'distance' | 'area' */
const measureMode = ref(null)
/** 已落的测量点（SVG 坐标 + 经纬度） */
const measurePoints = ref([])
/** 测量结果读数 */
const measureResult = ref(null)
/** 上一次落点的时间/位置 —— 用来识别「双击的第二次 click」 */
const lastMeasureClick = { t: 0, x: 0, y: 0 }

/**
 * 顶点圆点半径。
 *
 * <p>这个值是「屏幕视觉半径」而不是 SVG 用户单位：测量层整组套了
 * `scale(1/zoom)` 逆缩放（见模板），组内 1 个单位在屏幕上约等于 1 像素，
 * 所以这里写 4.5 就是「屏幕上半径 4.5 像素」，任何缩放级别下都保持不变。
 */
const measureR = 4.5

/**
 * 两点球面距离（Haversine）、多边形面积、格式化 —— 已抽到 map/measure.js。
 */

/** 折线 / 多边形的 SVG points 属性 */
const measureLinePoints = computed(() => {
  const pts = measurePoints.value
  if (!pts.length) return ''
  if (measureMode.value === 'distance') {
    // 测距：连第一个点到最后一个点（最多两点）
    if (pts.length < 2) return ''
    return `${pts[0].x},${pts[0].y} ${pts[pts.length - 1].x},${pts[pts.length - 1].y}`
  }
  // 测面：首尾相连的折线（最后一段不自动闭合，双击时才闭合）
  return pts.map((p) => `${p.x},${p.y}`).join(' ')
})

/** 测面多边形的 SVG points（闭合） */
const measurePolyPoints = computed(() => {
  const pts = measurePoints.value
  if (pts.length < 3) return ''
  return pts.map((p) => `${p.x},${p.y}`).join(' ') + ` ${pts[0].x},${pts[0].y}`
})

/**
 * 实时读数（跟着最后一个点走）。
 *
 * <p>测距：显示「最后一个点到第一个点」的距离；
 * 测面：显示当前多边形的面积（未闭合时按开口多边形算，最后一点到第一点自动闭合）。
 */
const measureReadout = computed(() => {
  const pts = measurePoints.value
  if (!pts.length) return null
  const last = pts[pts.length - 1]
  if (measureMode.value === 'distance') {
    if (pts.length < 2) return { x: last.x, y: last.y, text: '再点一个点' }
    const d = haversine(pts[0].lng, pts[0].lat, last.lng, last.lat)
    return { x: last.x, y: last.y, text: formatDistance(d) }
  }
  if (pts.length < 3) return { x: last.x, y: last.y, text: '继续加点' }
  const a = shoelaceArea(pts.map((p) => ({ lng: p.lng, lat: p.lat })))
  return { x: last.x, y: last.y, text: formatArea(a, t('farm.unit.mu')).text }
})

/** 切换测量模式 */
function toggleMeasure(mode) {
  if (measureMode.value === mode) {
    measureMode.value = null
    clearMeasure()
    return
  }
  measureMode.value = mode
  clearMeasure()
  ElMessage.info(mode === 'distance' ? '测距模式：点两个点' : '测面模式：点三个以上的点，双击结束')
}

/** 清空测量结果（保留当前模式） */
function clearMeasure() {
  measurePoints.value = []
  measureResult.value = null
}

/**
 * 在地图上落一个测量点。
 *
 * @param {number} clientX
 * @param {number} clientY
 */
function addMeasurePoint(clientX, clientY) {
  const s = toSvg(clientX, clientY)
  const [lng, lat] = PROJ.unproject(s.x, s.y)
  if (!Number.isFinite(lng) || !Number.isFinite(lat)) return
  if (measureMode.value === 'distance') {
    // 测距只保留两个点：第三点视为重新开始
    if (measurePoints.value.length >= 2) measurePoints.value = []
  }
  measurePoints.value = [...measurePoints.value, { x: s.x, y: s.y, lng, lat }]
  updateMeasureResult()
}

/** 根据当前点集刷新结果读数 */
function updateMeasureResult() {
  const pts = measurePoints.value
  if (measureMode.value === 'distance') {
    if (pts.length < 2) {
      measureResult.value = null
      return
    }
    const d = haversine(pts[0].lng, pts[0].lat, pts[1].lng, pts[1].lat)
    measureResult.value = { text: formatDistance(d), sub: `Haversine · R=${EARTH_R}m` }
    return
  }
  if (pts.length < 3) {
    measureResult.value = null
    return
  }
  const a = shoelaceArea(pts.map((p) => ({ lng: p.lng, lat: p.lat })))
  measureResult.value = formatArea(a, t('farm.unit.mu'))
}

/**
 * 测面双击结束。
 *
 * <p><b>双击的麻烦</b>：浏览器双击会先发两次 click 再发 dblclick，
 * 所以「双击结束」这个动作在 click 层面看起来就是「又点了两次」。
 * 这里的处理办法是：onDblClick 里发现点已经够多，就<b>回退掉双击补出来的那一个点</b>，
 * 再算闭合面积。回退数量按「双击时间窗内连续点的个数」估，最多多退 1 个。
 */
function finishAreaMeasure() {
  if (measureMode.value !== 'area') return
  const pts = measurePoints.value
  if (pts.length < 3) return
  // 双击补发的第二次 click 会多落 1 个点，回退掉（但至少保留 3 个点）
  if (pts.length > 3) measurePoints.value = pts.slice(0, pts.length - 1)
  updateMeasureResult()
  const r = measureResult.value
  ElMessage.success(r ? `面积 ${r.text}` : '测量完成')
}

// ==================================================================
//  逆地理编码（坐标 → 地址）
// ==================================================================

/** 逆地理编码打点（SVG 坐标） */
const reversePin = ref(null)
/** 逆地理编码结果气泡 */
const reverseInfo = ref(null)

/**
 * 发起逆地理编码。
 *
 * @param {number} clientX
 * @param {number} clientY
 */
async function doReverseGeocode(clientX, clientY) {
  const s = toSvg(clientX, clientY)
  const [lng, lat] = PROJ.unproject(s.x, s.y)
  if (!Number.isFinite(lng) || !Number.isFinite(lat)) return
  reversePin.value = { x: s.x, y: s.y, lng, lat }
  try {
    const res = await reverseGeocode(lng, lat)
    const raw = res?.data || {}
    const r = raw.result || {}
    const comp = r.addressComponent || {}
    // 天地图的 formatted_address 并非总是有值：点边境/境外时它会返回空串，
    // 但 addressComponent 里往往还有 poi / 镇名。此时不该整个丢掉气泡 ——
    // 退化成「省·市·县 + 附近 POI」照样有用，比什么都不显示强。
    const addr = r.formatted_address || ''
    const fallback = [comp.province, comp.city, comp.county, comp.town, comp.poi]
      .filter(Boolean)
      .join(' · ')
    if (!addr && !fallback) {
      reverseInfo.value = null
      reversePin.value = null
      return
    }
    // 气泡定位：屏幕坐标（相对 .wm-canvas）
    const host = canvasEl.value
    let left = 16
    let top = 16
    if (host) {
      const rect = host.getBoundingClientRect()
      const s2 = toSvg(clientX, clientY)
      const vb = vbBox.value
      // viewBox → 屏幕的缩放比
      const el = svgEl.value
      const r2 = el ? el.getBoundingClientRect() : { width: 1, height: 1 }
      const kx = r2.width / vb.w
      const ky = r2.height / vb.h
      left = Math.max(8, Math.min(rect.width - 260, (s2.x - vb.x) * kx + 14))
      top = Math.max(8, (s2.y - vb.y) * ky - 10)
    }
    const title = [comp.province, comp.city, comp.county].filter(Boolean).join(' · ') || addr || fallback
    reverseInfo.value = {
      title,
      detail: addr || fallback,
      poi: comp.poi || comp.town || '',
      coord: `${lng.toFixed(5)}°E ${lat.toFixed(5)}°N`,
      left,
      top
    }
  } catch (e) {
    // 静默降级：地图本身不依赖逆地理编码，失败不该打断用户。
    // 打点也要撤掉 —— 留一个没有地址的标记比什么都不显示更让人困惑。
    reverseInfo.value = null
    reversePin.value = null
  }
}

// ==================================================================
//  图层：省界 + 区县（按视野自动加载）
// ==================================================================

/** 全国省界要素（含原始几何，供点在多边形判定用） */
const provFeatures = ref([])

const farmByAdcode = computed(() => {
  const m = new Map()
  for (const f of allFarms.value) m.set(f.adcode, f)
  return m
})

/**
 * 省界 SVG 路径（常驻底图）。
 *
 * <p>省界是缩放时的底图：放大到区县级时它仍然在下面托着——玩家能看出
 * 「我现在看的这个县属于哪个省」，不会迷失在全国色块里。
 *
 * <p><b>矢量底图下必须用透明填充</b>（2026-10-21 修复）：
 * 天地图矢量底图本身就画了完整的省界和区县界线。之前这里用
 * `rgba(118,184,86,0.96)` 的近实色填充，等于拿一整片不透明的绿膜
 * 压在底图上，把天地图原生界线全遮了——下钻到市级时，玩家只看到
 * 一小块深绿色（自己画的区县面）漂在一片深绿（省面）上，
 * 底图里清晰的县市界线一条都看不见，就像「下一级区划被蒙层挡住了」。
 *
 * <p>卫星/地形底图同理：省级大色块会把航拍影像整片盖住。
 */
const provShapes = computed(() => {
  const mine = farmByAdcode.value
  const provCount = new Map()
  for (const f of allFarms.value) {
    const p = Math.floor(f.adcode / 10000) * 10000
    provCount.set(p, (provCount.get(p) || 0) + 1)
  }
  // 卫星/地形底图上，省级大色块会把影像整片盖住 —— 只留淡淡的着色层，
  // 让瓦片透出来，边界和「有我的农场」的高亮仍然保留。
  // 透明度压得比区县层更低：省界在区县层下面，两层叠加会连乘，
  // 省 0.14 × 县 0.24 ≈ 0.17，影像仍能透出七八成。
  const onTiles = baseLayer.value !== 'vector'
  // 作物分布图层开启时，省界按省级实测值着色（全国视图的主视图）
  const useCrop = cropLayerOn.value
  const filt = cropFilterResult.value.province || {}
  return provFeatures.value.map((p) => {
    const base = {
      type: 'province',
      adcode: p.adcode,
      name: p.name,
      d: featurePath(p.geometry, PROJ.project),
      farm: provCount.has(p.adcode) ? { count: provCount.get(p.adcode) } : null
    }
    if (useCrop) {
      const fr = filt[p.adcode]
      const color = fr && fr.hit ? cropScaleColor(fr.value) : '#2a322c'
      return {
        ...base,
        cropValue: fr ? fr.value : null,
        cropHit: fr ? fr.hit : false,
        // 矢量底图下也保持透明：作物色阶靠区县层表达，
        // 省面用实色会把底图原生界线盖掉。
        fill: onTiles ? shadeA(color, 1, fr && fr.hit ? 0.5 : 0.22) : shadeA(color, 1, 0.22)
      }
    }
    return {
      ...base,
      cropValue: null,
      cropHit: false,
      // 矢量底图：透明填充，界线交给天地图原生图层画。
      // 自己的 stroke 仍然保留，鼠标 hover 高亮照常工作。
      fill: provCount.has(p.adcode)
        ? (onTiles ? 'rgba(140, 205, 96, 0.26)' : 'rgba(140, 205, 96, 0.10)')
        : (onTiles ? 'rgba(30, 52, 38, 0.14)' : 'rgba(30, 52, 38, 0.06)')
    }
  })
})

/** 我的农场标记：与地图同一套投影，位置天然对齐 */
const markers = computed(() =>
  allFarms.value.map((f) => {
    const [x, y] = PROJ.project(f.lng, f.lat)
    return {
      farmId: f.farmId,
      name: f.name,
      x,
      y,
      // 悬浮卡片用的快照：来自 /board，已经带好了地块与作物
      location: f.location || '',
      zoneName: f.zoneName || '',
      plotCount: f.plotCount || 0,
      readyCount: f.readyCount || 0,
      growingCount: Math.max(0, (f.chosenCount || 0) - (f.readyCount || 0)),
      crops: f.crops || [],
      /**
       * 农场当前天气（2026-10-22 需求 O）。
       *
       * <p>后端 {@code /board} 的 farms[].weather 给了今天的气温 / 降水 / 数据来源。
       * 悬浮面板要显示它 —— 玩家决定「要不要现在浇水」时，第一眼要看的就是天气。
       *
       * <p>⚠️ 口径：这是**今天**的实测/推算值，不是预报。source 字段带着来源说明，
       * UI 必须如实展示，不能让玩家以为这是天气预报。
       */
      weather: f.weather || null,
      /**
       * 地块明细：悬浮面板里要列出「每块田的作物与状态」，
       * 并直接给出浇水/收割等基础操作。
       *
       * <p>数据来自 /board 的 farms[].plots（已带 slot/fieldName/cropName/
       * visual/stage/dvs/chosen），不需要额外请求。
       */
      plots: (f.plots || []).map((p) => ({
        slot: p.slot,
        fieldName: p.fieldName || '',
        cropName: p.cropName || '',
        visual: p.visual || '',
        stage: p.stage || '',
        dvs: Number(p.dvs) || 0,
        chosen: !!p.chosen,
        seasons: p.seasons || 0,
        lastYield: p.lastYield,
        bestYield: p.bestYield,
        /**
         * 播种日（2026-10-22 需求 O）。
         *
         * <p>悬浮面板要显示「作物种植时间」——这是玩家判断「这块地种了多久、
         * 还差几天能收」的依据。后端 {@code /board} 的 plots 里已经带了 sowDate，
         * 这里只是没往下传。
         */
        sowDate: p.sowDate || null,
        /**
         * 待播种标志（2026-10-22）：上一季已结束、等玩家选下一季。
         *
         * <p>🔴 之前漏了这个字段 → 悬浮面板把「待播种」的地块显示成「待开垦」，
         * 而且「选种」按钮永远不出现（v-else-if="p.awaitingSowing" 判定不到）。
         */
        awaitingSowing: !!p.awaitingSowing,
        /** 苗期枯死标记：悬浮面板也要标出来，和详情面板一致 */
        withered: !!p.withered,
        /** 出苗率：低于 0.85 要标「缺苗」 */
        germination: p.germination == null ? null : Number(p.germination),
        /**
         * 杂草压力（2026-10-22 需求 O）。
         *
         * <p>驱动悬浮面板的「除草」按钮：有草才让除草，没草时按钮置灰。
         */
        weedPressure: Number(p.weedPressure) || 0,
        weedLevel: p.weedLevel || 'NONE',
        weedLevelName: p.weedLevelName || '无杂草',
        weedLossPct: Number(p.weedLossPct) || 0
      }))
    }
  })
)

/**
 * 已加载的区县图层合成一份渲染列表。
 *
 * <p>fill 在这里现算而不是烘焙进缓存：底图可以随时切换，
 * 卫星/地形底图下必须降到半透明，否则实色块会把航拍影像整片盖住。
 *
 * <p><b>矢量底图下也留 alpha</b>（2026-10-21 修复）：天地图矢量底图自带
 * 完整的区县界线，画实色块会把那些原生界线盖掉，玩家看到的就只是
 * 一坨没有边界的色块。留 0.55 的 alpha 让底图界线透上来，
 * 同时主栽作物的分区色仍然清晰可辨。
 *
 * <p><b>作物分布图层开启时改用色阶着色</b>：主栽作物色表达的是「这里种什么」，
 * 而分布图要表达的是「数值有多大」，两套语义不能混。
 * 筛选未命中的区县压成灰暗色（而不是隐藏）——隐藏会让玩家以为地图缺数据。
 */
const districtItems = computed(() => {
  const out = []
  const layers = districtLayers.value
  const ids = new Set(visibleProvIds.value)
  // 点选的省始终显示：玩家明确要看这个省，不该被视野跨度裁掉
  if (pinnedProvId.value) ids.add(pinnedProvId.value)
  const onTiles = baseLayer.value !== 'vector'
  const useCrop = cropLayerOn.value
  const filtD = cropFilterResult.value.district || {}
  const filtC = cropFilterResult.value.city || {}

  // 已下钻到地级市（市层被钉住）→ 显示该市的区县，隐藏市面
  const cityId = pinnedCityId.value
  const cityLayer = cityId ? cityLayers.value[cityId] : null

  const push = (it) => {
    if (useCrop) {
      const fr = filtD[it.adcode]
      const color = fr && fr.hit ? cropScaleColor(fr.value) : '#2a322c'
      out.push({
        ...it,
        cropValue: fr ? fr.value : null,
        cropHit: fr ? fr.hit : false,
        fill: onTiles
          ? shadeA(color, 1, fr && fr.hit ? 0.5 : 0.22)
          : shadeA(color, 1, fr && fr.hit ? 0.62 : 0.42)
      })
      return
    }
    out.push({
      ...it,
      cropValue: null,
      cropHit: false,
      fill: onTiles
        ? shadeA(it.baseColor, it.shadeK, it.farm ? 0.42 : 0.26)
        : shadeA(it.baseColor, it.shadeK, it.farm ? 0.62 : 0.55)
    })
  }

  if (cityLayer) {
    // 已下钻到地级市（市层被钉住）→ 显示该市的区县，隐藏市面
    for (const it of cityLayer.items) push(it)
    return out
  }

  /**
   * 自动加载的区县层（2026-10-21）：镜头进入市级尺度后，
   * 视野内已加载区县层的市应该显示**区县面**而不是市面。
   *
   * <p>逐市判断：这个市有区县层就画区县面，没加载到的市继续画市面——
   * 两层混合是正常的过渡态，不用等所有市都加载完才切换。
   *
   * <p>作物分布图层开启时不走这条：色阶着色要用「市」这一级的聚合值，
   * 换成区县面会让色阶语义错位（见 {@link cropLevel}）。
   */
  const autoIds = useCrop ? null : new Set(autoCityIds.value)

  for (const id of ids) {
    const layer = layers[id]
    if (!layer) continue
    for (const it of layer.items) {
      // 这个市已自动加载了区县层 → 它的区县面取代市面
      if (autoIds && autoIds.has(it.adcode) && cityLayers.value[it.adcode]) {
        continue
      }
      // 省级视图里画的是**地级市面**，用该市下各区县加总后的值着色
      if (useCrop && it.type === 'city') {
        const fr = filtC[it.adcode]
        const color = fr && fr.hit ? cropScaleColor(fr.value) : '#2a322c'
        out.push({
          ...it,
          cropValue: fr ? fr.value : null,
          cropHit: fr ? fr.hit : false,
          fill: onTiles
            ? shadeA(color, 1, fr && fr.hit ? 0.5 : 0.22)
            : shadeA(color, 1, fr && fr.hit ? 0.62 : 0.42)
        })
        continue
      }
      push(it)
    }
  }

  // 把自动加载的区县面补进来（画在市面之后 = 更上层）
  if (autoIds) {
    for (const cid of autoIds) {
      const cl = cityLayers.value[cid]
      if (!cl) continue
      for (const it of cl.items) push(it)
    }
  }

  return out
})

const hasDistricts = computed(() => districtItems.value.length > 0)

/** 当前钉住的地级市（面包屑用） */
const pinnedCity = computed(() => {
  if (!pinnedCityId.value) return null
  return (
    districtItems.value.find((c) => c.adcode === pinnedCityId.value) ||
    provCityCache.value[pinnedCityId.value] ||
    null
  )
})

/**
 * 已加载过的地级市名称缓存。
 *
 * <p>下钻到市后市面被该市的区县替换掉，市名就从渲染列表里消失了，
 * 但面包屑还要显示它——所以单独记一份。
 */
const provCityCache = ref({})

/**
 * 地级市名标注。
 *
 * <p>只在省级视图出现（视野跨度 3.5°–26°）：再放大就是区县视图了，
 * 市名会和区县名挤在一起。
 */
const cityLabels = computed(() => {
  if (spanDeg.value > 26 || spanDeg.value <= 3.5) return []
  if (pinnedCityId.value) return []
  const b = vbBox.value
  const out = []
  for (const c of districtItems.value) {
    if (c.type !== 'city' || !c.bbox) continue
    const cx = (c.bbox.minLng + c.bbox.maxLng) / 2
    const cy = (c.bbox.minLat + c.bbox.maxLat) / 2
    const [x, y] = PROJ.project(cx, cy)
    if (x < b.x - 20 || x > b.x + b.w + 20 || y < b.y - 20 || y > b.y + b.h + 20) continue
    const w = c.bbox.maxLng - c.bbox.minLng
    // 太小的市（跨度 < 0.5°）标名字会糊成一片
    if (w < 0.5) continue
    out.push({ adcode: c.adcode, name: c.name, x, y, big: w > 1.2 })
  }
  return out
})

/**
 * 省名标注。
 *
 * <p>只在「看得到省、还看不清县」的中间尺度出现：全国视图（跨度 > 26°）不需要，
 * 区县视图（跨度 < 3.5°）有区县名就够了。
 */
const provinceLabels = computed(() => {
  if (spanDeg.value > 26 || spanDeg.value <= 3.5) return []
  const b = vbBox.value
  const out = []
  for (const p of provFeatures.value) {
    const cx = (p.bbox.minLng + p.bbox.maxLng) / 2
    const cy = (p.bbox.minLat + p.bbox.maxLat) / 2
    const [x, y] = PROJ.project(cx, cy)
    if (x < b.x || x > b.x + b.w || y < b.y || y > b.y + b.h) continue
    // 跨度太小的省（澳门、香港）标出来也没意义
    if (p.bbox.maxLng - p.bbox.minLng < 0.8) continue
    out.push({ adcode: p.adcode, name: p.name, x, y })
  }
  return out
})

/**
 * 区县级标注。
 *
 * <p>放大到一定程度才显示：全国视图下几百个名字会糊成一片，反而看不清地图。
 * 标注点用区县 bbox 中心近似——简化后的边界不保证有真正的质心，
 * 用 bbox 中心更稳定，也不会出现标注跑到省外的情况。
 *
 * <p>字号随缩放反向缩放（SVG 里的字号不随 viewBox 缩放），保证名字大小恒定。
 */
const districtLabels = computed(() => {
  // 视野跨度小于约 3.5° 才打标（大约到「一个县占屏幕十分之一」的尺度）
  if (spanDeg.value > 3.5) return []
  const out = []
  const b = vbBox.value
  for (const d of districtItems.value) {
    if (!d.bbox) continue
    const cx = (d.bbox.minLng + d.bbox.maxLng) / 2
    const cy = (d.bbox.minLat + d.bbox.maxLat) / 2
    const [x, y] = PROJ.project(cx, cy)
    if (x < b.x - 20 || x > b.x + b.w + 20 || y < b.y - 20 || y > b.y + b.h + 20) continue
    // 区县太小（跨度 < 0.5°）时名字会盖住相邻县，跳过
    const w = d.bbox.maxLng - d.bbox.minLng
    if (w < 0.5) continue
    out.push({ adcode: d.adcode, name: d.name, x, y, big: w > 1.2 })
  }
  return out
})

/**
 * 当前层级名称（给玩家一个明确的「现在看到的是哪一级」）。
 *
 * <p>按视野实际跨度判定，而不是按「数据有没有加载」——后者会在图层
 * 加载完成的瞬间跳变，玩家看着莫名其妙。
 */
const levelName = computed(() => {
  if (detail.value) return '区县级'
  if (pinnedCityId.value) return '地级市'
  // 作物分布图层画的是「市」这一级的聚合面（见 cropLevel），不算区县级
  if (cropLayerOn.value) {
    return cropLevel.value === 'province' ? '全国' : '地级市'
  }
  // 自动加载了区县面 → 画的就是区县级，跟钉住某个市无关
  if (autoCityIds.value.length) return '区县级'
  if (spanDeg.value <= 6) return '区县级'
  if (spanDeg.value <= 26) return '地级市'
  return '全国'
})

const levelHint = computed(() => {
  if (detail.value) return detail.value.fullName || detail.value.name
  if (pinnedCityId.value) {
    const city = districtItems.value.find((c) => c.adcode === pinnedCityId.value)
    return city ? `${city.name} · ${districtItems.value.length} 个区县` : ''
  }
  if (hasDistricts.value) {
    const n = districtItems.value.length
    return `${centerProvince.value ? centerProvince.value.name + ' · ' : ''}${n} 个地级市`
  }
  if (spanDeg.value > 26) return '滚轮放大进入地级市'
  return centerProvince.value ? centerProvince.value.name : '拖动到某个省'
})

// ------------------------------------------------------------------
//  区县图层加载
// ------------------------------------------------------------------

const geoCache = new Map()
/** 省份区县数据缓存（后端给的作物画像） */
const distApiCache = new Map()
/** 正在加载中的省份，避免并发重复请求 */
const loadingProvs = new Set()
/** 正在加载区划的地级市，避免并发重复请求 */
const loadingCities = new Set()

async function loadGeo(path) {
  if (geoCache.has(path)) return geoCache.get(path)
  const p = fetch(path)
    .then((r) => {
      if (!r.ok) throw new Error('地图数据加载失败：' + path)
      return r.json()
    })
    .catch((e) => {
      geoCache.delete(path)
      throw e
    })
  geoCache.set(path, p)
  return p
}

/**
 * 把 geo feature 转成可渲染的 item。
 *
 * <p>fill 不在这里定死：只存「底色 + 亮度系数」，最终 fill 在渲染层按
 * 当前底图现算（矢量底图=实色块，卫星/地形底图=半透明），
 * 否则底图一换就得重新拉数据。
 */
function toRegionItem(f, type, meta) {
  const p = f.properties
  const d = meta ? meta.get(p.adcode) : null
  return {
    type,
    adcode: p.adcode,
    name: p.name,
    d: featurePath(f.geometry, PROJ.project),
    bbox: geometryBbox(f.geometry),
    baseColor: d ? cropColor(d.visual) : '#33452f',
    shadeK: 0.87 + ((p.adcode % 11) / 11) * 0.26,
    crop: d ? d.mainCrop : null,
    farm: farmByAdcode.value.get(p.adcode) || null
  }
}

/**
 * 加载某省的地级市图层（边界）。
 *
 * <p>这是「全国 → 省 → 市 → 区县」四级链路的第二级：省级视图里显示的是
 * 地级市的面，点一下某个市才继续下钻到它的区县。
 *
 * <p>作物画像只在区县级才有意义（市没有「主栽作物」），所以这里不拉后端接口。
 */
async function ensureProvince(provAdcode) {
  if (districtLayers.value[provAdcode] || loadingProvs.has(provAdcode)) return
  loadingProvs.add(provAdcode)
  distLoading.value = loadingProvs.size
  try {
    const geo = await loadGeo(`/geo/p${provAdcode}.json`)
    const items = (geo.features || [])
      .map((f) => toRegionItem(f, 'city', null))
      .filter((s) => s.d)
    districtLayers.value = {
      ...districtLayers.value,
      [provAdcode]: { items, districts: [] }
    }
    // 市面数据刚到位：视野内的市现在可以判定是否该自动加载区县层了。
    // 必须在 await 之后补这一次——自动加载依赖市面数据，
    // 而市面是异步 fetch 回来的，watch 触发时往往还没到。
    syncAutoCityLayers()
  } catch (e) {
    // 某个省的数据缺失不应该让整张地图不可用
    console.warn('[farm-map] 加载省区划失败', provAdcode, e)
  } finally {
    loadingProvs.delete(provAdcode)
    distLoading.value = loadingProvs.size
  }
}

/**
 * 加载某市下辖区县图层（边界 + 作物画像）。
 *
 * <p>两路数据并行：边界来自静态 geo（离线、无鉴权），作物画像来自后端
 * （服务端权威的「这个县适合种什么」，前端不能自己算）。
 */
async function ensureCity(cityAdcode) {
  if (cityLayers.value[cityAdcode] || loadingCities.has(cityAdcode)) return
  loadingCities.add(cityAdcode)
  distLoading.value = loadingProvs.size + loadingCities.size
  try {
    const [geo, res] = await Promise.all([
      loadGeo(`/geo/c${cityAdcode}.json`),
      distApiCache.has(cityAdcode)
        ? Promise.resolve(distApiCache.get(cityAdcode))
        : getFarmMapDistricts(cityAdcode).then((r) => {
            const d = r?.data || []
            distApiCache.set(cityAdcode, d)
            return d
          })
    ])
    const meta = new Map((res || []).map((d) => [d.adcode, d]))
    const items = (geo.features || [])
      .map((f) => toRegionItem(f, 'district', meta))
      .filter((s) => s.d)
    cityLayers.value = {
      ...cityLayers.value,
      [cityAdcode]: { items, districts: res || [] }
    }
    // 记下市名：下钻后市面被区县替换，面包屑/定位还要用
    const cityName = (districtItems.value.find((c) => c.adcode === cityAdcode) || {}).name
    if (cityName && !provCityCache.value[cityAdcode]) {
      provCityCache.value = {
        ...provCityCache.value,
        [cityAdcode]: { adcode: cityAdcode, name: cityName }
      }
    }
  } catch (e) {
    console.warn('[farm-map] 加载市区划失败', cityAdcode, e)
  } finally {
    loadingCities.delete(cityAdcode)
    distLoading.value = loadingProvs.size + loadingCities.size
  }
}

/**
 * 找出视野内应该显示区县层的省份。
 *
 * <p>先用 bbox 粗筛（绝大多数省直接被排除），再对候选省做点在多边形判定，
 * 避免内蒙古这种大 bbox 把华北也算进来。
 *
 * <p>判定用的采样点取自视野中心 + 网格：中心点所在省必选，其余按网格命中。
 * 网格密度随视野自适应——视野大用粗网格（少加载几个省），视野小用细网格。
 */
function computeVisibleProvs() {
  const b = viewBounds.value
  const [cLng, cLat] = viewCenter.value
  const out = []
  for (const p of provFeatures.value) {
    if (!bboxHit(p.bbox, b)) continue
    if (pointInGeometry(cLng, cLat, p.geometry)) {
      out.push(p)
      continue
    }
    // 网格采样：视野内铺 5×5 个点，命中任一即算可见
    const N = 4
    let hit = false
    for (let i = 0; i <= N && !hit; i++) {
      for (let j = 0; j <= N && !hit; j++) {
        const lng = b.minLng + ((b.maxLng - b.minLng) * i) / N
        const lat = b.minLat + ((b.maxLat - b.minLat) * j) / N
        if (pointInGeometry(lng, lat, p.geometry)) hit = true
      }
    }
    if (hit) out.push(p)
  }
  return out
}

/** 视野跨度小于这个值（度）就值得加载区县层 */
const DISTRICT_SPAN = 26

/**
 * 视野跨度小于这个值（度）就自动加载视野内各市所辖的区县层。
 *
 * <p>四级链路原来只能「点市下钻」才看到区县，纯滚轮放大到市级尺度
 * 时画面上还是一堆市级面——玩家明明已经放大到能看清县了，
 * 却必须先点一下市才知道下一级长什么样。
 *
 * <p>阈值取 3.5°：与 {@link districtLabels} 的门槛一致，
 * 也正好是「一个县占屏幕十分之一」的尺度。
 */
const AUTO_CITY_SPAN = 3.5

/**
 * 找出视野内应该加载区县层的地级市。
 *
 * <p>先用 bbox 粗筛，再用 5×5 网格采样判定（与 {@link computeVisibleProvs} 同思路）。
 * 只对已经加载了市层的省做判定——市面本身来自省层数据。
 */
function computeVisibleCities() {
  const b = viewBounds.value
  const out = []
  const seen = new Set()
  for (const id of visibleProvIds.value) {
    const layer = districtLayers.value[id]
    if (!layer) continue
    for (const c of layer.items) {
      if (c.type !== 'city' || !c.bbox || seen.has(c.adcode)) continue
      if (!bboxHit(c.bbox, b)) continue
      const N = 4
      let hit = false
      for (let i = 0; i <= N && !hit; i++) {
        for (let j = 0; j <= N && !hit; j++) {
          const lng = b.minLng + ((b.maxLng - b.minLng) * i) / N
          const lat = b.minLat + ((b.maxLat - b.minLat) * j) / N
          if (lng >= c.bbox.minLng && lng <= c.bbox.maxLng &&
              lat >= c.bbox.minLat && lat <= c.bbox.maxLat) hit = true
        }
      }
      if (hit) {
        seen.add(c.adcode)
        out.push(c.adcode)
      }
    }
  }
  return out
}

/**
 * 同步「自动加载区县层」的目标市集合。
 *
 * <p>这是自动加载的唯一入口，被三处调用：
 * <ul>
 *   <li>镜头跨过 {@link AUTO_CITY_SPAN} 阈值（watch spanDeg）</li>
 *   <li>市面数据异步到位后（{@link ensureProvince} 的 await 之后）</li>
 *   <li>视野平移后（watch viewBounds 的防抖回调里）</li>
 * </ul>
 *
 * <p>写成函数而不是散在各个 watch 里，是因为「数据到位」和「镜头到位」
 * 是两条独立的时间线：先缩放到市级尺度时市面可能还没加载完，
 * 只靠 watch spanDeg 会漏掉这一批市；只靠 ensureProvince 又会在
 * 缩放跨阈值时慢半拍。两条线都汇到这一个函数，行为才一致。
 */
function syncAutoCityLayers() {
  // 拉远了：自动加载的区县层收起，回到市面
  if (spanDeg.value > AUTO_CITY_SPAN) {
    if (autoCityIds.value.length) autoCityIds.value = []
    return
  }
  // 钉住某个市时，它的区县层已经在显示了，不用再自动加载一堆别的市
  if (pinnedCityId.value) return
  // 作物分布图层下画的是「市」这一级的聚合面，换成区县面色阶会错位
  if (cropLayerOn.value) {
    if (autoCityIds.value.length) autoCityIds.value = []
    return
  }
  const ids = computeVisibleCities()
  if (!ids.length) {
    if (autoCityIds.value.length) autoCityIds.value = []
    return
  }
  // autoCityIds 只保留**当前视野内**的市：平移后远处的市要被摘掉，
  // 否则 districtItems 会一直渲染早已离开视野的区县面（面越堆越多）。
  // 已经 fetch 过的市留在 cityLayers 缓存里，再回来时不用重新请求。
  autoCityIds.value = ids
  ids.forEach((id) => ensureCity(id))
}

let visTimer = null
watch(
  () => {
    const b = viewBounds.value
    return [
      b.minLng.toFixed(2), b.maxLng.toFixed(2),
      b.minLat.toFixed(2), b.maxLat.toFixed(2)
    ].join(',')
  },
  () => {
    if (visTimer) clearTimeout(visTimer)
    visTimer = setTimeout(() => {
      visTimer = null
      if (spanDeg.value > DISTRICT_SPAN) {
        // 太远：只保留被钉住的省，其余收起来
        visibleProvIds.value = pinnedProvId.value ? [pinnedProvId.value] : []
        return
      }
      const hits = computeVisibleProvs()
      const ids = new Set(hits.map((p) => p.adcode))
      if (pinnedProvId.value) ids.add(pinnedProvId.value)
      visibleProvIds.value = [...ids]
      ids.forEach((id) => ensureProvince(id))
      // 视野平移后重新判定自动加载的市（ensureProvince 到位时也会补一次）
      syncAutoCityLayers()
    }, 180)
  }
)

/**
 * 视野进入市级尺度后，自动加载视野内各市所辖的区县层。
 *
 * <p>和「点市下钻」是并行的两条路：点市是**主动**选择某个市看它的区县，
 * 自动加载是**被动**跟随镜头——玩家滚轮放大到能看清县了，
 * 画面就该自动换成县的面，而不是还停在市级。
 *
 * <p>单独一个 watch（不塞进上面那个）：上面的 watch 盯着的是
 * 「视野跨度的粗粒度变化」（两位小数），而这里要的是「是否跨过
 * AUTO_CITY_SPAN 阈值」，用 spanDeg 单独盯着更准，也不会多跑。
 */
watch(spanDeg, () => {
  syncAutoCityLayers()
})

// 钉住某个市后，它的区县层必须一直显示，哪怕镜头又拉远了
watch(pinnedCityId, (cid, old) => {
  if (cid) {
    ensureCity(cid)
  } else if (old) {
    // 退出下钻：恢复「跟随镜头」的自动加载
    syncAutoCityLayers()
  }
})

/**
 * 作物分布数据的重拉时机：地图层级变了（全国 ↔ 省 ↔ 市），或钉住的市变了。
 *
 * <p>层级决定用哪一级的数值着色，所以层级一变必须重拉——
 * 否则会出现「全国视图用省值着色，但下钻到市后还拿着省值给区县面着色」。
 */
watch(
  [cropLevel, () => pinnedCityId.value, () => [...visibleProvIds.value].join(',')],
  () => {
    if (!cropLayerOn.value) return
    loadCropDistribution()
  }
)

/**
 * 视野中心所在省 → 面包屑、层级提示、右侧面板范围。
 *
 * <p>镜头移到哪个省，右侧就展示哪个省的概览——地图位置和右侧内容永远一致，
 * 玩家不需要「点一下才知道这里有什么」。
 */
watch(
  () => viewCenter.value.map((v) => v.toFixed(3)).join(','),
  () => {
    const [lng, lat] = viewCenter.value
    for (const p of provFeatures.value) {
      if (pointInGeometry(lng, lat, p.geometry)) {
        if (!centerProvince.value || centerProvince.value.adcode !== p.adcode) {
          centerProvince.value = { adcode: p.adcode, name: p.name }
        }
        return
      }
    }
    // 中心落在国境外（海里）：清空省级范围，右侧回到全国概览
    if (centerProvince.value) centerProvince.value = null
  }
)

// ==================================================================
//  数据加载
// ==================================================================

async function loadFarms() {
  const res = await getFarmBoard()
  allFarms.value = res?.data?.farms || []
  unassignedPlots.value = res?.data?.unassigned || []
  // 🔴 S25：/board 的 today 是**世界日期**（后端已改），不是 LocalDate.now()
  worldDate.value = res?.data?.today || ''
}

async function loadCountry() {
  loading.value = true
  loadingText.value = '正在加载全国行政区划…'
  try {
    const [geo] = await Promise.all([loadGeo('/geo/cn.json'), loadFarms()])
    provFeatures.value = geo.features.map((f) => ({
      adcode: f.properties.adcode,
      name: f.properties.name,
      geometry: f.geometry,
      bbox: geometryBbox(f.geometry)
    }))
    detail.value = null
    farmDetail.value = null
    activeFarmId.value = null
    hoverName.value = null
    ready.value = true
    resetView()
    await nextTick()
    refit()
  } catch (e) {
    ElMessage.error(e?.message || '地图加载失败')
  } finally {
    loading.value = false
  }
}

function backToCountry() {
  detail.value = null
  farmDetail.value = null
  pinnedProvId.value = null
  pinnedCityId.value = null
  centerProvince.value = null
  visibleProvIds.value = []
  resetView()
}

/**
 * 从市级退回省级视图。
 *
 * <p>只清市级下钻，保留省的面——玩家还能看到自己刚才点的市在哪。
 */
function backToProvince() {
  detail.value = null
  farmDetail.value = null
  pinnedCityId.value = null
}

async function openDistrict(adcode) {
  try {
    // 详情要展示在右侧，折叠态下必须先展开
    if (sideFolded.value) toggleSide()
    loading.value = true
    loadingText.value = '读取区县气候与作物…'
    const res = await getFarmMapDistrict(adcode)
    detail.value = res?.data || null
    farmDetail.value = null
    activeFarmId.value = null
  } catch (e) {
    ElMessage.error(e?.message || '区县详情加载失败')
  } finally {
    loading.value = false
  }
}

async function openFarm(farmId) {
  try {
    // 详情要展示在右侧，折叠态下必须先展开
    if (sideFolded.value) toggleSide()
    // 🔴 2026-10-22：先把「当前选中农场」和「它的档案」定下来，再发请求。
    // 否则在 await 期间，界面上还是上一个农场的标题 + 上一个农场的档案；
    // 玩家快速连点两个农场时，await 回来的旧响应还会把新农场的详情覆盖掉。
    // activeFarmId 先落定 → loadFarmSeasons 的响应校验才认得「谁才是当前农场」。
    activeFarmId.value = farmId
    farmSeasons.value = []
    const res = await getFarmStead(farmId)
    // 期间玩家又点了别的农场 → 这次响应已过期，丢弃
    if (activeFarmId.value !== farmId) return
    farmDetail.value = res?.data || null
    detail.value = null
    // 季节档案：与农场详情并行拉，失败不牵连主面板
    loadFarmSeasons(farmId)
    const f = allFarms.value.find((x) => x.farmId === farmId)
    if (f) {
      const prov = Math.floor(f.adcode / 10000) * 10000
      const city = Math.floor(f.adcode / 100) * 100
      pinnedProvId.value = prov
      await ensureProvince(prov)
      // 直辖市的区县直挂省级（city == prov），没有市级层可下钻
      if (city !== prov && city % 10000 !== 0) {
        pinnedCityId.value = city
        const cn = (provCityCache.value[city] || {}).name
        if (cn) {
          provCityCache.value = { ...provCityCache.value, [city]: { adcode: city, name: cn } }
        }
        await ensureCity(city)
      }
      await nextTick()
      // 镜头推到农场所在县：区县层已就位，画面里能看到县的边界
      zoom.value = clampZoom(18)
      flyTo(f.lng, f.lat, zoom.value)
    }
  } catch (e) {
    ElMessage.error(e?.message || '农场详情加载失败')
  }
}

/**
 * 拉取农场的种植与收获记录（2026-10-22）。
 *
 * <p>失败时静默清空——季节档案是**附加信息**，拉不到不该让整个农场详情打不开。
 *
 * <p>🔴 <b>竞态防护（2026-10-22）</b>：玩家快速连点两个农场时，
 * 两个请求并行发出、响应乱序返回。先发后到的那个会把后发先到的结果覆盖掉，
 * 导致「A 农场的标题 + B 农场的记录」这种串台。
 * 这里用**请求序号 + farmId 双重校验**：只有最新一次请求、且 farmId 仍是当前选中农场的
 * 响应才允许写入。
 */
let farmSeasonsSeq = 0

async function loadFarmSeasons(farmId) {
  const seq = ++farmSeasonsSeq
  if (farmId == null) {
    farmSeasons.value = []
    return
  }
  try {
    const res = await getFarmSeasons(farmId)
    // 过期响应：直接丢弃，别让它覆盖当前农场的记录
    if (seq !== farmSeasonsSeq) return
    if (activeFarmId.value !== farmId) return
    // 兜底：后端已按当前农场过滤，这里再按 farmId 过滤一次，
    // 防止任何「服务端没过滤干净」的情况把别的农场的记录漏进来。
    const rows = res?.data || []
    farmSeasons.value = rows.filter((r) => r.farmId == null || Number(r.farmId) === Number(farmId))
  } catch (e) {
    if (seq !== farmSeasonsSeq) return
    farmSeasons.value = []
  }
}

// ==================================================================
//  右侧概览范围
// ==================================================================

/**
 * 右侧面板的范围。
 *
 * <p>'auto' 模式下跟随镜头中心所在省；'all' 模式下恒为「全部农场」。
 */
const scope = computed(() => {
  if (scopeMode.value === 'all') {
    return { type: 'all', title: '全部农场', badge: '全国概览' }
  }
  if (centerProvince.value) {
    return {
      type: 'province',
      title: centerProvince.value.name,
      badge: '省级概览',
      adcode: centerProvince.value.adcode
    }
  }
  return { type: 'all', title: '全部农场', badge: '全国概览' }
})

/** 省视图：把该省 100 来个区县的主栽作物按类聚合，别让右侧只剩一张空卡片 */
const provinceCrops = computed(() => {
  // 「全部农场」模式下不显示「本省主栽」——那是省级的信息，
  // 挂在"全部农场"标题下语义对不上。
  if (scopeMode.value === 'all') return []
  const pid = centerProvince.value?.adcode
  if (!pid) return []
  const layer = districtLayers.value[pid]
  const list = layer ? layer.districts : (distApiCache.get(pid) || [])
  const m = new Map()
  for (const d of list) {
    const k = d.visual || 'none'
    m.set(k, (m.get(k) || 0) + 1)
  }
  return [...m.entries()]
    .sort((a, b) => b[1] - a[1])
    .map(([k, c]) => ({ key: k, name: cropName(k), color: cropColor(k), count: c }))
})

/**
 * 面板要显示的农场列表。
 *
 * <p>'all' 模式：全部农场，一个都不少。
 * 'auto' 模式：跟随镜头中心所在省（镜头在海里则显示全部）。
 */
const scopeFarms = computed(() => {
  if (scopeMode.value === 'all') return allFarms.value
  if (!centerProvince.value) return allFarms.value
  const p = centerProvince.value.adcode
  return allFarms.value.filter((f) => Math.floor(f.adcode / 10000) * 10000 === p)
})

/**
 * 概览卡片单击：定位到地图上的该农场。
 *
 * <p>只做「定位」这一件事——镜头推过去、标记高亮、卡片选中态同步。
 * 进农场详情是**双击**（{@link onOverviewEnter}）或卡片上的「详情」按钮，
 * 两条路都明确，不靠「再点一次」这种需要玩家记忆的隐式约定。
 */
async function onOverviewSelect(f) {
  await focusFarm(f)
}

/**
 * 概览卡片双击 / 点「进入」：直接进入 3D 农场场景。
 *
 * <p>与 {@link onOverviewSelect}（定位）是两条路：定位只动镜头，
 * 这里则一步落到田里，能看到作物长什么样、有图形的界面。
 *
 * <p>🔴 2026-10-22 需求 O：原来这里调的是 {@link openFarm}（只开右侧面板），
 * 玩家双击卡片看到的是「一块地一个列表」，而不是「我的农场长什么样」。
 * 现在改成走 {@link enterFarmScene} —— 与悬浮面板的「进入农场」按钮同一条路。
 */
async function onOverviewEnter(f) {
  await enterFarmScene(f.farmId)
}

/**
 * 定位到农场。
 *
 * <p>先把所在省的区县层加载出来，再把镜头推到农场所在的县——
 * 这样放大后画面里是有边界的区县，而不是一块糊掉的省域色。
 */
async function focusFarm(f) {
  const prov = Math.floor(f.adcode / 10000) * 10000
  pinnedProvId.value = prov
  await ensureProvince(prov)
  activeFarmId.value = f.farmId
  await nextTick()
  zoom.value = clampZoom(18)
  flyTo(f.lng, f.lat, zoom.value)
}

/** 概览里点未归属地块：直接切到那块地 */
function onOverviewEnterPlot(p) {
  emit('enter-plot', { farmId: null, slot: p.slot })
}

async function onMarkerClick(farmId) {
  if (activeFarmId.value === farmId) {
    openFarm(farmId)
    return
  }
  const f = allFarms.value.find((x) => x.farmId === farmId)
  if (f) await focusFarm(f)
  // 触屏没有 mouseenter：点完标记点直接亮出概览卡片，
  // 否则触屏玩家永远看不到「进入农场」按钮。
  // 放在 focusFarm 之后 —— 镜头稳了卡片才不会挂歪。
  const m = markers.value.find((x) => x.farmId === farmId)
  if (m) await showFarmCard(m)
}

// ==================================================================
//  聚焦
// ==================================================================

/**
 * 缩放到「目标地物占据视野的 ratio」。
 *
 * <p><b>必须乘上当前 zoom</b>：{@link spanDeg} 是视野跨度的**绝对值**，
 * 随缩放变化（`spanDeg = fit.w * DEG_PER_UNIT / zoom`），不是比例。
 * 直接用 `(spanDeg * ratio) / span` 算出来的是「绝对缩放倍数」，
 * 相当于把当前视野强行当成 1× 基准，缩放结果和当前状态无关——
 * 在省级视图（zoom≈4.3）点一个市，只会把镜头推到 8×，
 * 市区县还是缩成一小团，正确值应是 4.3 × 15.4/1.88 ≈ 35×。
 *
 * @param span 目标地物的经度跨度（度）
 * @param ratio 目标地物应占视野的比例（0~1）
 */
function zoomToSpan(span, ratio) {
  const targetSpan = Math.max(0.05, span / ratio)
  // spanDeg = fit.w * DEG_PER_UNIT / zoom ⇒ zoom_new = zoom_old * spanDeg_old / targetSpan
  return clampZoom(zoom.value * (spanDeg.value / targetSpan))
}

/**
 * 聚焦到某个省：镜头推到这个省的中心，缩放到「这个省占大部分屏幕」。
 *
 * <p>不再切换视图——省界图层本来就在那儿，镜头推过去就等于下钻，
 * 缩放级别还能继续往下走到区县级。
 */
async function focusProvince(adcode) {
  const p = provFeatures.value.find((x) => x.adcode === adcode)
  if (!p) return
  detail.value = null
  farmDetail.value = null
  // 点选即「钉住」这个省：它的市层一直显示，哪怕镜头还没推近
  pinnedProvId.value = adcode
  // 从市级退回省级：清掉市层，回到「省内看地级市」
  pinnedCityId.value = null
  await ensureProvince(p.adcode)
  const cx = (p.bbox.minLng + p.bbox.maxLng) / 2
  const cy = (p.bbox.minLat + p.bbox.maxLat) / 2
  // 目标：省的经度跨度约占视野的 60%
  const span = Math.max(0.6, p.bbox.maxLng - p.bbox.minLng)
  const target = zoomToSpan(span, 0.6)
  zoom.value = target
  flyTo(cx, cy, target)
}

/**
 * 下钻到某个地级市：加载它下辖的区县并把镜头推进去。
 *
 * <p>这是四级链路「省 → 市 → 区县」的中间一步：点了市以后，
 * 市面被该市的区县面替换，玩家继续点区县就能到区县详情。
 */
async function focusCity(adcode) {
  const item = districtItems.value.find((c) => c.adcode === adcode)
  if (!item) return
  detail.value = null
  farmDetail.value = null
  pinnedCityId.value = adcode
  // 市名记进缓存：下钻后市面被区县替换，面包屑还要显示它
  provCityCache.value = { ...provCityCache.value, [adcode]: { adcode, name: item.name } }
  await ensureCity(adcode)
  const cx = (item.bbox.minLng + item.bbox.maxLng) / 2
  const cy = (item.bbox.minLat + item.bbox.maxLat) / 2
  // 目标：市的经度跨度约占视野的 60%
  const span = Math.max(0.6, item.bbox.maxLng - item.bbox.minLng)
  const target = zoomToSpan(span, 0.6)
  zoom.value = target
  flyTo(cx, cy, target)
}

/**
 * 聚焦到某个区县：镜头推到这个县，缩放到能看清它的边界。
 *
 * <p>直接双击区县时调用——玩家的意图是「我要看这个县」，不是「我要看它所在的省」。
 */
async function focusDistrict(adcode) {
  const item = districtItems.value.find((d) => d.adcode === adcode)
  const provAdcode = Math.floor(adcode / 10000) * 10000
  // 作物画像可能挂在「市层」上（下钻后），也可能挂在「省层」上（老路径）
  const meta = (cityLayers.value[pinnedCityId.value]?.districts
    || districtLayers.value[provAdcode]?.districts
    || []).find((d) => d.adcode === adcode)
  if (!item || !meta) return
  const span = item.bbox
    ? Math.max(0.15, item.bbox.maxLng - item.bbox.minLng)
    : 0.6
  const target = zoomToSpan(span, 0.4)
  zoom.value = target
  flyTo(meta.lng, meta.lat, target)
}

/**
 * 农场标记悬浮：弹出概览卡片。
 *
 * <p>卡片跟随鼠标但会被容器边界夹住——标记经常贴着地图边缘，
 * 不夹的话卡片一半在屏幕外，看不全。
 */
/**
 * 农场标记悬浮：弹出概览卡片。
 *
 * <p>卡片**锚定在标记点**上（而不是跟着鼠标跑）：
 * 跟随鼠标的卡片在玩家伸手去点「进入农场」时会一直躲，
 * 而且会盖住标记本身。锚定后位置稳定、按钮好点。
 *
 * <p>卡片会被容器边界夹住——标记经常贴着地图边缘，
 * 不夹的话卡片一半在屏幕外，看不全。
 */
function onFarmHover(m, e) {
  if (drag.on && drag.moved) return
  // 鼠标又回到标记上了 → 解除钉住，恢复「移开就关」的常规行为
  farmHoverPinned.value = false
  const host = e.currentTarget.ownerSVGElement.parentElement
  placeFarmCard(m, e.clientX, e.clientY, host)
}

/**
 * 把卡片放到标记点旁边并显示。
 *
 * @param ax 屏幕 x 兜底值（没有 CTM 时用鼠标位置）
 * @param ay 屏幕 y 兜底值
 */
function placeFarmCard(m, ax, ay, host) {
  // 重新进入标记：取消待执行的关闭
  clearFarmHoverTimer()
  farmHover.value = m

  const el = svgEl.value
  const rect = host.getBoundingClientRect()
  // 卡片宽度随「有没有地块」而变（.farm-card / .farm-card.wide 两档），
  // 且窄屏下还有一条 media query 兜底 —— 所以这里**不能**再硬编码一个常量，
  // 否则翻转判断用的宽度会和真实渲染宽度对不上。
  // 🔴 历史 bug：这里写死 252，而卡片实际是 320px（.wide），
  //    差 68px → 卡片右边缘被 .wm-canvas 的 overflow:hidden 裁掉。
  //
  // 宽度来源用 CSS 变量（--farm-card-w / --farm-card-w-wide），
  // JS 和 CSS 共用同一份定义，不会再各自写死数字而对不上。
  // ⚠️ 不能靠 querySelector 量 DOM：farmHover.value = m 之后 DOM 还没重渲染，
  //    此时量到的是**上一张卡**的宽度（可能正好对、也可能不对）——不可靠。
  const rows = (m.plots || []).length
  const cssVar = rows ? '--farm-card-w-wide' : '--farm-card-w'
  const cs = getComputedStyle(host)
  const declared = parseFloat(cs.getPropertyValue(cssVar))
  const CARD_W = declared > 0 ? declared : (rows ? 380 : 268)
  // 卡片高度随农田行数增长，但**封顶**（列表内部滚动）。
  // 不封顶的话 20 块地的农场会把卡片撑到屏幕外，底部按钮点不到。
  const CARD_MIN_H = 176
  const CARD_MAX_H = 420
  const CARD_H = Math.min(CARD_MAX_H, Math.max(CARD_MIN_H, CARD_MIN_H + rows * 30 + 34))

  // 标记点的屏幕位置：用 SVG 的 CTM 把用户坐标换算成屏幕坐标
  if (el) {
    const ctm = el.getScreenCTM()
    if (ctm) {
      const pt = new DOMPoint(m.x, m.y).matrixTransform(ctm)
      ax = pt.x
      ay = pt.y
    }
  }
  if (ax == null || ay == null) return
  // 卡片落在标记点右下角，留 14px 间隙
  let x = ax - rect.left + 14
  let y = ay - rect.top + 14
  // 右边放不下就翻到标记点左侧
  if (x + CARD_W > rect.width - 8) {
    x = ax - rect.left - CARD_W - 14
    // 连左侧也放不下（地图区太窄）→ 贴左边距，别让它跑出容器被裁掉
    if (x < 8) x = 8
  }
  // 下边放不下就往上翻；连上方也放不下就贴顶（卡片内部会滚动）
  if (y + CARD_H > rect.height - 8) {
    y = ay - rect.top - CARD_H - 14
    if (y < 8) y = 8
  }
  farmHoverPos.x = Math.max(8, x)
  farmHoverPos.y = y
}

/**
 * 直接点亮某农场的卡片（不依赖鼠标）。
 *
 * <p>触屏设备没有 mouseenter，点标记点只会走 click 那一路——
 * 如果不给它补一张卡片，触屏玩家永远看不到「进入农场」按钮。
 *
 * <p><b>必须在镜头稳定之后再调用</b>：卡片是按屏幕坐标绝对定位的，
 * 镜头一动（focusFarm 会把镜头推到 18 倍）标记点就换了位置，
 * 早一步定位卡片就会挂歪。
 */
async function showFarmCard(m) {
  const host = svgEl.value?.parentElement
  if (!host) return
  // 钉住：镜头马上要动，标记点会离开鼠标，mouseleave 必然触发
  farmHoverPinned.value = true
  // 等两帧：第一帧让 viewBox 生效，第二帧让 getScreenCTM 返回新矩阵
  await nextTick()
  await new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r)))
  const el2 = svgEl.value
  const host2 = el2?.parentElement
  if (!host2) return
  placeFarmCard(m, null, null, host2)
}

/**
 * 离开标记点：延迟关闭卡片。
 *
 * <p>不能立刻关——鼠标要跨过标记与卡片之间的间隙才移得到卡片上，
 * 立刻关等于「进入农场」按钮永远点不到。
 *
 * <p>被点击「钉住」的卡片例外：点击后镜头会动，标记点从鼠标下移走，
 * mouseleave 必然触发——不钉住的话卡片刚亮出来就被自己关掉了。
 */
function onFarmLeave() {
  if (farmHoverPinned.value) return
  clearFarmHoverTimer()
  farmHoverCloseTimer = setTimeout(() => {
    farmHover.value = null
    farmHoverCloseTimer = null
  }, FARM_HOVER_CLOSE_DELAY)
}

/** 鼠标进入卡片本体：取消延迟关闭，让玩家能点到「进入农场」 */
function onFarmCardEnter() {
  clearFarmHoverTimer()
}

/** 鼠标离开卡片本体：延迟关闭 */
function onFarmCardLeave() {
  onFarmLeave()
}

// ==================================================================
//  悬浮面板里的农田基础操作
// ==================================================================

/**
 * 正在操作的地块槽位（-1 表示空闲）。
 *
 * <p>用来禁用行内按钮并显示 loading——玩家可能连点两下"浇水"，
 * 不禁用会重复提交。
 */
const busyPlot = ref(-1)

/**
 * 地块状态分档 → CSS 类名（带 `pc-` 前缀）。
 *
 * <p>🔴 2026-10-28：判定逻辑收进 {@code utils/farmCrops.js} 的
 * {@code plotDotState}（与 FarmOverviewPanel / FarmBoard 同一套口径），
 * 这里只负责加 CSS 前缀。
 */
function plotDotClass(p) {
  return 'pc-' + plotDotState(p)
}

/**
 * 作物名去掉括号注释：「水稻（中稻/一季稻）」→「水稻」
 *
 * <p>🔴 2026-10-28：实现收进 {@code utils/farmCrops.js} 的 {@code shortCrop}
 * （原来有 5 份拷贝，兜底值各不相同）。
 */
function plotCropShort(name) {
  return shortCrop(name)
}

/**
 * 悬浮面板里对某块地做一次农事。
 *
 * <p>做完必须重拉 /board：地块的 DVS、阶段、产量都会变，
 * 卡片上显示的还是旧数据的话，玩家会以为操作没生效。
 *
 * <p>操作完卡片不能关——玩家往往要连着操作好几块地。
 * 但重新定位一次是必须的（地块行数可能变了）。
 */
async function hoverPlotAction(slot, type) {
  if (busyPlot.value >= 0) return
  busyPlot.value = slot
  try {
    await performFarmAction(slot, type, null)
    await refreshFarms()
    ElMessage.success(HOVER_ACTION_TEXT[type] || '操作完成')
  } catch (e) {
    ElMessage.error(e?.message || '操作失败')
  } finally {
    busyPlot.value = -1
  }
}

/** 悬浮面板农事按钮的成功提示文案（与 i18n 无关，这里的按钮是硬编码中文）。 */
const HOVER_ACTION_TEXT = {
  IRRIGATE: '已浇水',
  WEED: '已除草',
  DRAIN: '已排水'
}

/**
 * 悬浮面板里打开某块地的农资抽屉（打药 / 施肥 / 除草剂）。
 *
 * <p>🔴 2026-10-22 需求 O：打药与施肥**不能**走 {@code /action} 这条路——
 * 后端明确拒绝笼统的 PESTICIDE / FERTILIZE（必须带具体药剂和用量，绕不过「对的物」这道闸门）。
 * 所以这里不是调农事接口，而是切到那块地的农资面板，让玩家选药剂。
 */
function openHoverInputs(slot) {
  const farmId = farmHover.value?.farmId
  if (farmId == null) return
  // 关闭悬浮卡片，把注意力交给农资抽屉（否则两层浮窗叠在一起）
  closeFarmCard()
  emit('open-inputs', { farmId, slot })
}

/** 天气图标：按降水与温度给一个直觉符号。 */
function weatherIcon(w) {
  if (!w) return '·'
  if (w.precip >= 10) return '☔'
  if (w.precip > 0) return '🌦'
  if (w.tmax >= 35) return '🔥'
  if (w.tmin <= 2) return '❄'
  return '☀'
}

/** 日期短格式：2026-04-21 → 04-21（悬浮面板空间小，全年份太占地方）。 */
function shortDate(d) {
  if (!d) return ''
  const s = String(d)
  return s.length >= 10 ? s.slice(5) : s
}

/** 悬浮面板里收割某块地 */
async function hoverPlotHarvest(slot) {
  if (busyPlot.value >= 0) return
  busyPlot.value = slot
  try {
    const r = await harvestFarm(slot)
    await refreshFarms()
    const y = r?.data?.yieldKgPerMu ?? r?.data?.yield
    ElMessage.success(y != null ? `已收割，产量 ${y} ${t('farm.unit.perMu')}` : '已收割')
  } catch (e) {
    ElMessage.error(e?.message || '收割失败')
  } finally {
    busyPlot.value = -1
  }
}

/**
 * 从悬浮面板直接切到某块地（进 3D 场景）。
 *
 * <p>与「进入农场」的区别：这个直接落到指定的那块地，
 * 不用玩家在 3D 场景里再找一遍。
 */
function enterHoverPlot(slot) {
  const farmId = farmHover.value?.farmId
  if (farmId == null) return
  onFarmLeave()
  farmHoverPinned.value = false
  emit('enter-plot', { farmId, slot })
}

/**
 * 重拉农场数据并刷新当前卡片。
 *
 * <p>卡片内容来自 markers computed，它依赖 allFarms——
 * 重拉 /board 后 allFarms 会变，markers 跟着变，
 * 但 farmHover 持有的是**旧对象引用**，不会自动更新，
 * 所以要显式把 farmHover 重新指向新的那个。
 */
async function refreshFarms() {
  await loadFarms()
  const fid = farmHover.value?.farmId
  if (fid == null) return
  const next = markers.value.find((m) => m.farmId === fid)
  if (next) {
    // 保持卡片钉住：玩家正在连着操作，不能因为刷新就关掉
    farmHoverPinned.value = true
    farmHover.value = next
  }
}

/**
 * 手动收起卡片（点空白处 / 点别的农场时用）。
 *
 * <p>同时解除钉住——钉住是给「点击亮出」用的，
 * 玩家主动关卡片就说明不想看它了。
 */
function closeFarmCard() {
  farmHoverPinned.value = false
  clearFarmHoverTimer()
  farmHover.value = null
}

function clearFarmHoverTimer() {
  if (farmHoverCloseTimer) {
    clearTimeout(farmHoverCloseTimer)
    farmHoverCloseTimer = null
  }
}

/** 从悬浮卡片进入农场详情（右侧面板展开，不离开地图） */
async function enterFarmFromHover(farmId) {
  onFarmLeave()
  // 折叠状态下进入农场必须先展开，否则玩家点了「进入」却什么都没发生
  if (sideFolded.value) toggleSide()
  await openFarm(farmId)
}

/**
 * 从悬浮卡片直接进入 3D 农场场景。
 *
 * <p>与 {@link enterFarmFromHover}（只开侧栏详情）是两条路：
 * 按钮写着「进入农场」就应该一步落到田里，而不是再让玩家点一次侧栏按钮。
 *
 * <p>落地哪块地：优先取该农场里<b>已种植</b>（chosen）且作物成熟度最高的地块，
 * 没有已种的地就退而取第一块——让玩家看到他自己的农场长什么样，
 * 而不是把他丢进建田流程。若该农场一块地都没有，就提示先建田。
 */
async function enterFarmScene(farmId) {
  const f = allFarms.value.find((x) => x.farmId === farmId)
  const plots = (f && f.plots) || []
  if (!plots.length) {
    // 🔴 2026-10-23：空农场没有地就没有场景可进 —— 直接进建田流程。
    // 原来这里调 enterFarmFromHover（只开右侧面板），玩家点了「进入农场」
    // 却什么都没发生；而另一条路（侧栏的 enterFarm）会发出 slot: 0 串到别的农场。
    // 两条路现在统一：slot = -1 → 建田。
    ElMessage.info('这个农场还没有地块，先开垦第一块地吧')
    emit('enter-plot', { farmId, slot: -1 })
    return
  }
  const chosen = plots.filter((p) => p.chosen)
  const pool = chosen.length ? chosen : plots
  const target = pool.reduce((a, b) =>
    (Number(b.dvs || 0) > Number(a.dvs || 0) ? b : a), pool[0])
  onFarmLeave()
  farmHoverPinned.value = false
  emit('enter-plot', { farmId, slot: target.slot })
}

/** 缩放级别夹取：至少 1（全国），至多 MAX_ZOOM */
function clampZoom(z) {
  return Math.min(MAX_ZOOM, Math.max(1, z))
}

/** 视口中心的 SVG 坐标（x） */
function baseCenterX() {
  return PROJ.base.w / 2 + pan.x
}

/** 视口中心的 SVG 坐标（y） */
function baseCenterY() {
  return PROJ.base.h / 2 + pan.y
}

// ==================================================================
//  交互
// ==================================================================

async function onRegionClick(f) {
  // 拖拽或捏合刚结束时，浏览器仍会补一个 click —— 必须吞掉，
  // 否则「捏一下放大」会顺带把手指底下的县点开
  if (drag.moved || touch.pinching) return
  // 测量模式下不点区县：这一下 click 是要落测量点的
  if (measureMode.value) return
  // 点到别处了：收起钉住的农场卡片，别让它挡住视野
  closeFarmCard()
  if (f.type === 'district') {
    await openDistrict(f.adcode)
  } else if (f.type === 'city') {
    await focusCity(f.adcode)
  } else {
    await focusProvince(f.adcode)
  }
}

/**
 * 作物分布图层下的悬浮信息。
 *
 * <p>显示该单元在**已选作物**上的面积/产量，以及是否命中当前筛选。
 * 命中与否要明说——否则玩家看到一堆灰掉的县分不清是「没数据」还是「被筛掉了」。
 *
 * <p>三种层级分别取数：
 * <ul>
 *   <li>省：{@link cropProvData}（省级实测值）；</li>
 *   <li>市：由区县派发值加总；</li>
 *   <li>区县：{@link cropDistData}（区县派发值）。</li>
 * </ul>
 */
function cropHoverInfo(f) {
  const metric = cropMetric.value
  const unit = CROP_METRICS.find((m) => m.id === metric)?.unit || ''
  const level = f.type === 'province' ? 'province' : f.type === 'city' ? 'city' : 'district'

  // 取该单元的原始 crop 记录
  let crops = null
  let areaKm2 = 0
  if (level === 'province') {
    const row = cropProvData.value[f.adcode]
    crops = row ? row.crops : null
  } else if (level === 'district') {
    const row = cropDistData.value[f.adcode]
    crops = row ? row.crops : null
    areaKm2 = Number(row?.areaKm2) || 0
  } else {
    // 市级：把该市下各区县加总
    const acc = new Map()
    for (const row of Object.values(cropDistData.value)) {
      if (row.cityAdcode !== f.adcode) continue
      const w = Number(row.areaKm2) || 0
      areaKm2 += w
      for (const [cid, c] of Object.entries(row.crops || {})) {
        const v = c[metric]
        if (typeof v !== 'number') continue
        if (!acc.has(cid)) acc.set(cid, { sum: 0, wsum: 0, w: 0 })
        const e = acc.get(cid)
        e.sum += v
        e.wsum += v * w
        e.w += w
      }
    }
    if (acc.size) {
      crops = {}
      for (const [cid, e] of acc) {
        crops[cid] = {
          area: metric === 'area' ? e.sum : undefined,
          output: metric === 'output' ? e.sum : undefined,
          yield: metric === 'yield' ? (e.w > 0 ? e.wsum / e.w : e.sum) : undefined
        }
      }
    }
  }

  if (!crops) {
    return { line1: '该单元暂无统计数据', line2: '' }
  }
  // 用去重后的作物集合（父子不重复列）
  const ids = cropAggIds.value.filter((cid) => crops[cid])
  const parts = []
  for (const cid of ids.slice(0, 3)) {
    const c = crops[cid]
    if (!c) continue
    const v = c[metric]
    if (typeof v !== 'number') continue
    const meta = cropMetas.value.find((m) => m.cropId === cid)
    parts.push(`${meta ? meta.name : cid} ${v >= 1000 ? (v / 1000).toFixed(1) + 'k' : Math.round(v * 10) / 10}`)
  }
  const hitPool = cropFilterResult.value[level] || {}
  const hit = hitPool[f.adcode]
  // 命中时也把合计值说清楚——玩家看到的色块是合计，不是单个作物
  const total = hit && hit.value != null
    ? `合计 ${hit.value >= 1000 ? (hit.value / 1000).toFixed(1) + 'k' : Math.round(hit.value * 10) / 10} ${unit}`
    : ''
  return {
    line1: parts.length ? parts.join(' · ') : '该单元无该作物数据',
    line2: hit && !hit.hit ? `未命中筛选（${cropRangeLabel.value}）` : total
  }
}

function onHover(f, e) {
  if (drag.on && drag.moved) return
  hoverName.value = f.name
  const host = e.currentTarget.ownerSVGElement.parentElement
  const rect = host.getBoundingClientRect()
  tip.x = Math.min(rect.width - 230, e.clientX - rect.left + 14)
  tip.y = Math.max(8, e.clientY - rect.top - 10)
  if (f.type === 'district') {
    // 作物分布图层开启时，悬浮信息优先显示该县的面积/产量
    if (cropLayerOn.value) {
      hoverInfo.value = cropHoverInfo(f)
      return
    }
    hoverInfo.value = {
      line1: f.crop ? `主栽：${f.crop}` : '—',
      line2: f.farm ? `我的农场：${f.farm.name}` : ''
    }
  } else if (f.type === 'city') {
    // 作物分布图层开启时，市悬浮显示该市加总后的面积/产量
    if (cropLayerOn.value) {
      hoverInfo.value = cropHoverInfo(f)
      return
    }
    // 地级市层：告诉玩家这个市下面有多少个区县、里面有没有我的农场
    let n = 0
    let mine = 0
    for (const x of farmByAdcode.value.values()) {
      n++
      if (Math.floor(x.adcode / 100) * 100 === f.adcode) mine++
    }
    hoverInfo.value = {
      line1: mine ? `我的农场 ${mine} 个` : '点击进入看区县'
    }
  } else {
    // 作物分布图层开启时，省悬浮显示该省实测统计值
    if (cropLayerOn.value) {
      hoverInfo.value = cropHoverInfo(f)
      return
    }
    const mine = farmByAdcode.value
    let n = 0
    for (const x of mine.values()) {
      if (Math.floor(x.adcode / 10000) * 10000 === f.adcode) n++
    }
    hoverInfo.value = {
      line1: n ? `我的农场 ${n} 个` : '未建农场'
    }
  }
}

/**
 * 建农场（S25）：打开「名称 + 开垦日期」表单弹窗。
 *
 * <p>🔴 为什么不用 {@code ElMessageBox.prompt}：它只能收一个纯文本输入框，
 * 塞不进日期选择器（而且 {@code ElMessageBox} 里放 {@code el-date-picker}
 * 需要自己挂 DOM，很脆）。需求要求「新建农场不能选择今天之前的日期」，
 * 所以这里用一个正经的 {@code el-dialog}。
 */
function buildFarm() {
  const d = detail.value
  if (!d) return
  buildForm.name = `${d.name}农场`
  buildForm.openedOn = worldDate.value || ''
  buildForm.district = d.fullName || d.name
  buildOpen.value = true
}

/** 禁用「今天之前」的日期（按世界日期算，不是 LocalDate.now()）。 */
function disabledOpenedOn(date) {
  if (!worldDate.value) return false
  const base = new Date(worldDate.value + 'T00:00:00')
  const d = new Date(date.getFullYear(), date.getMonth(), date.getDate())
  return d.getTime() < base.getTime()
}

async function submitBuildFarm() {
  const d = detail.value
  if (!d || buildBusy.value) return
  const name = (buildForm.name || '').trim()
  if (!name || name.length > 20) {
    ElMessage.error('农场名 1–20 字')
    return
  }
  buildBusy.value = true
  try {
    const res = await createFarmStead(d.adcode, name, buildForm.openedOn || undefined)
    ElMessage.success(res?.data?.created ? '农场已建立' : '你在这里已经有农场了')
    buildOpen.value = false
    await loadFarms()
    await ensureProvince(Math.floor(d.adcode / 10000) * 10000)
    await openDistrict(d.adcode)
    emit('state-changed')
  } catch (e) {
    ElMessage.error(e?.message || '建场失败')
  } finally {
    buildBusy.value = false
  }
}

/**
 * 选中一条搜索结果。
 *
 * <p>本地（本地区县库）和天地图（POI / 行政区划）两类结果的定位方式不同：
 * <ul>
 *   <li>本地区县：有 6 位 adcode，能直接定位到区县层并打开右侧详情</li>
 *   <li>天地图 POI / 行政区划：只有坐标，直接飞镜头过去</li>
 * </ul>
 */
async function onSuggestSelect(item) {
  if (!item) return
  keyword.value = ''
  // 本地区县：走「钉住省 → 加载区县层 → 打开详情」的完整链路
  if (item.source === 'local' && item.adcode) {
    const pa = Math.floor(item.adcode / 10000) * 10000
    pinnedProvId.value = pa
    await ensureProvince(pa)
    await openDistrict(item.adcode)
    zoom.value = clampZoom(20)
    flyTo(item.lng, item.lat, zoom.value)
    return
  }
  // 天地图结果：只飞到坐标
  if (Number.isFinite(item.lng) && Number.isFinite(item.lat)) {
    const target = clampZoom(item.source === 'tdt-area' ? 12 : 16)
    zoom.value = target
    flyTo(item.lng, item.lat, target)
  }
}

/**
 * 把天地图 v2/search 的原始 JSON 拆成可渲染的候选项。
 *
 * <p>天地图的返回结构按 {@code resultType} 分三种，前端必须分别处理：
 * <ul>
 *   <li>{@code 1} → POI 列表（{@code pois[]}），每项 {@code lonlat} 是 {@code "lng,lat"} 字符串</li>
 *   <li>{@code 2} → 当前范围无结果、按城市统计（{@code statistics.priorityCitys[]}），
 *       只有城市名没有坐标，只能当「提示」不能定位</li>
 *   <li>{@code 3} → 行政区划（{@code area} 对象），{@code lonlat} 同为 {@code "lng,lat"} 字符串</li>
 * </ul>
 *
 * @param {object} raw 天地图原始 JSON
 * @returns {Array<object>} 统一结构的候选项
 */
function parseTdtSearch(raw) {
  const out = []
  const rt = raw?.resultType
  if (rt === 1 && Array.isArray(raw.pois)) {
    for (const p of raw.pois) {
      const c = parseLonLat(p?.lonlat)
      if (!c) continue
      out.push({
        source: 'tdt-poi',
        name: p.name || '未命名地点',
        province: '',
        city: '',
        address: p.address || '',
        phone: p.phone || '',
        lng: c.lng,
        lat: c.lat,
        typeLabel: 'POI'
      })
    }
    return out
  }
  if (rt === 3 && raw.area) {
    const a = raw.area
    const c = parseLonLat(a.lonlat)
    if (c) {
      out.push({
        source: 'tdt-area',
        name: a.name || '行政区划',
        province: '',
        city: '',
        address: a.bound || '',
        // adminCode 不是标准 6 位 adcode（形如 156361128），
        // 只作展示，不参与本项目「按 adcode 定位区县」的逻辑
        adminCode: a.adminCode || '',
        lng: c.lng,
        lat: c.lat,
        typeLabel: '行政区划'
      })
    }
    return out
  }
  if (rt === 2 && raw.statistics?.priorityCitys) {
    // 无坐标，只能提示「换个范围再搜」
    for (const s of raw.statistics.priorityCitys) {
      out.push({
        source: 'tdt-city',
        name: s.name || '城市',
        province: '',
        city: s.name || '',
        address: '',
        lng: NaN,
        lat: NaN,
        typeLabel: '城市'
      })
    }
  }
  return out
}

/** 解析天地图的 {@code "lng,lat"} 字符串 */
function parseLonLat(s) {
  if (!s || typeof s !== 'string') return null
  const parts = s.split(',')
  if (parts.length < 2) return null
  const lng = Number(parts[0])
  const lat = Number(parts[1])
  if (!Number.isFinite(lng) || !Number.isFinite(lat)) return null
  return { lng, lat }
}

/**
 * 双源搜索：本地区县库 + 天地图。
 *
 * <p><b>合并策略</b>：本地结果排在前面，天地图结果追加在后。
 * <ul>
 *   <li>本地快（本地库 2875 个区县，毫秒级）而且带 adcode，能直接定位到区县层，
 *       是「我要找某个县」的主路径；</li>
 *   <li>天地图补的是本地库里没有的东西：POI（具体的地名/设施）和视野内的其他行政区划。</li>
 * </ul>
 *
 * <p><b>天地图不可用时静默降级</b>：只返回本地结果，不弹错误。
 * 搜索框是「锦上添花」功能，不能因为第三方服务挂了就让用户看到红字。
 *
 * <p><b>防抖 300ms</b>：天地图是按调用量计费的，打字过程中每个字符都查一次
 * 既浪费配额又会被限流。本地查询也一起防抖（共用一个定时器）。
 */
let sugTimer = null
function suggest(q, cb) {
  if (!q) return cb([])
  clearTimeout(sugTimer)
  sugTimer = setTimeout(async () => {
    const local = await fetchLocalSuggest(q)
    // 天地图：视野范围作为 mapBound，结果更相关
    const b = viewBounds.value
    const mapBound = [
      b.minLng.toFixed(4), b.minLat.toFixed(4),
      b.maxLng.toFixed(4), b.maxLat.toFixed(4)
    ].join(',')
    let remote = []
    try {
      const res = await searchMapPlaces({
        keyword: q,
        level: 12,
        mapBound,
        start: 0,
        count: 8
      })
      remote = parseTdtSearch(res?.data || {})
    } catch (e) {
      // 静默降级：不告诉用户天地图挂了
      remote = []
    }
    // 本地在前，天地图在后；同名去重（本地已经有就不重复列）
    const seen = new Set(local.map((x) => x.name))
    const merged = [...local]
    for (const r of remote) {
      if (r.source === 'tdt-city') continue
      if (seen.has(r.name)) continue
      seen.add(r.name)
      merged.push(r)
    }
    cb(merged.map((x) => ({ ...x, value: x.fullName || x.name })))
  }, 300)
}

/** 本地（后端 2875 县库）搜索 */
async function fetchLocalSuggest(q) {
  try {
    const res = await searchFarmDistricts(q)
    return (res?.data || []).map((x) => ({
      ...x,
      source: 'local',
      typeLabel: '区县'
    }))
  } catch (e) {
    return []
  }
}

/**
 * 从农场详情面板点「进入农场」。
 *
 * <p>🔴 2026-10-23 事故修复：原来空农场（0 地块）会回退到 {@code slot: 0}，
 * 而 slot 0 是**别的农场**的地 —— 玩家点了「五台县农场」却落进了别人的农场。
 * slot 是用户范围内全局唯一的，绝不能猜。
 *
 * <p>空农场没有地，就没有 3D 场景可进。这里直接进**建田流程**
 * （{@code slot: -1} 表示「在这个农场里新开一块地」），
 * 玩家建完第一块地才进场景 —— 这才是「进入农场」该有的结果。
 */
function enterFarm(f) {
  const plots = f.plots || []
  if (!plots.length) {
    ElMessage.info('这个农场还没有地块，先开垦第一块地吧')
    emit('enter-plot', { farmId: f.farmId, slot: -1 })
    return
  }
  const target = plots.find((p) => p.chosen) || plots[0]
  emit('enter-plot', { farmId: f.farmId, slot: target.slot })
}

function enterPlot(slot) {
  emit('enter-plot', { farmId: farmDetail.value?.farmId, slot })
}

// ==================================================================
//  生命周期
// ==================================================================

let ro = null

watch(ready, async (v) => {
  if (!v) return
  await nextTick()
  refit()
})

onMounted(async () => {
  // 恢复上次选择的底图 / 注记（只记图层 id，不记任何密钥）
  const savedBase = localStorage.getItem(BASE_STORE)
  if (BASE_LAYERS.some((l) => l.id === savedBase)) {
    baseLayer.value = savedBase
    labelLayer.value = DEFAULT_LABEL_FOR_BASE[savedBase] || 'none'
  }
  const savedLabel = localStorage.getItem(LABEL_STORE)
  if (LABEL_LAYERS.some((l) => l.id === savedLabel)) {
    labelLayer.value = savedLabel
  }
  // 恢复右侧看板的收起状态
  try {
    sideFolded.value = localStorage.getItem('farm.sideFolded') === '1'
  } catch (e) {
    // localStorage 不可用就用默认展开态
  }
  // 问后端天地图代理是否就绪（前端拿不到 key，只能问状态）
  await probeTdtStatus()
  await loadCountry()
  const el = svgEl.value
  if (el) el.addEventListener('wheel', onWheel, { passive: false })
  if (canvasEl.value && 'ResizeObserver' in window) {
    ro = new ResizeObserver(() => refit())
    ro.observe(canvasEl.value)
  }
  window.addEventListener('resize', refit)
})

onBeforeUnmount(() => {
  const el = svgEl.value
  if (el) el.removeEventListener('wheel', onWheel)
  if (ro) ro.disconnect()
  window.removeEventListener('resize', refit)
  if (visTimer) clearTimeout(visTimer)
})
</script>

<style scoped>
.wm-page {
  position: fixed;
  inset: 0;
  z-index: 60;
  display: flex;
  flex-direction: column;
  background: radial-gradient(120% 100% at 50% 0%, #162218 0%, #0b120d 68%, #070b08 100%);
  color: #e8f2e4;
}
.wm-page.tile-on {
  background: radial-gradient(120% 100% at 50% 0%, #101c22 0%, #080f12 68%, #05090a 100%);
}

/* ---- 顶部 ---- */
.wm-head {
  display: flex;
  align-items: center;
  flex-wrap: nowrap;
  gap: 12px;
  height: 54px;
  padding: 0 16px;
  border-bottom: 1px solid rgba(150, 190, 140, 0.16);
  background: rgba(10, 18, 12, 0.72);
  backdrop-filter: blur(10px);
  flex: 0 0 auto;
  position: relative;
  z-index: 6;
}
.wm-back {
  flex: 0 0 auto;
  white-space: nowrap;
  display: flex;
  align-items: center;
  gap: 6px;
  height: 32px;
  padding: 0 12px;
  border: 1px solid rgba(160, 200, 150, 0.28);
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.05);
  color: #dfeeda;
  font-size: 12.5px;
  cursor: pointer;
}
.wm-back:hover { background: rgba(255, 255, 255, 0.12); }
.wm-arrow { font-size: 14px; }
.wm-title { display: flex; align-items: baseline; gap: 9px; flex: 0 0 auto; white-space: nowrap; }
.wm-title b { font-size: 15px; letter-spacing: 0.4px; white-space: nowrap; }
.wm-sub { font-size: 11.5px; color: rgba(210, 232, 200, 0.5); }

.wm-crumb { display: flex; align-items: center; gap: 7px; margin-left: 4px; flex: 0 0 auto; white-space: nowrap; }
.crumb {
  border: 0;
  background: none;
  color: rgba(214, 236, 205, 0.62);
  font-size: 12.5px;
  cursor: pointer;
  padding: 8px 4px;
}
.crumb.on { color: #fff; font-weight: 600; }
.crumb-sep { font-style: normal; color: rgba(200, 225, 190, 0.3); }
.crumb-cur { font-size: 12.5px; color: #fff; }
.crumb-cur.clickable { cursor: pointer; }
.crumb-cur.clickable:hover { color: #b7dcff; text-decoration: underline; }

/* 搜索框：wrapper 控宽，内部 el-autocomplete 撑满 */
.wm-search {
  flex: 0 1 168px;
  min-width: 92px;
  margin-left: auto;
  display: flex;
}
.wm-search :deep(.el-autocomplete) { flex: 1 1 auto; min-width: 0; }
.wm-search :deep(.el-input__wrapper) {
  background: rgba(255, 255, 255, 0.06);
  box-shadow: 0 0 0 1px rgba(160, 200, 150, 0.22) inset;
  border-radius: 14px;
  padding: 1px 9px;
}
/* 搜索框整体压小：字号 12.5 → 11.5，行高也收一点 */
.wm-search :deep(.el-input__inner) {
  color: #e8f2e4;
  font-size: 11.5px;
  line-height: 22px;
  height: 24px;
}
.wm-search :deep(.el-input__inner::placeholder) { color: rgba(206, 228, 196, 0.42); font-size: 11.5px; }
.wm-search :deep(.el-input__prefix) { font-size: 12px; }
.wm-search :deep(.el-input__clear) { font-size: 12px; }

.wm-stat { flex: 0 0 auto; font-size: 12px; color: rgba(210, 232, 200, 0.62); white-space: nowrap; }
.wm-stat b { color: #ffd86b; font-variant-numeric: tabular-nums; }
.dot-sep { margin: 0 5px; opacity: 0.4; }

/* ---- 仓库入口（S25） ---- */
.wm-warehouse {
  position: relative;
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  gap: 5px;
  height: 28px;
  padding: 0 11px;
  border-radius: 14px;
  border: 1px solid rgba(160, 200, 150, 0.24);
  background: rgba(255, 255, 255, 0.05);
  color: rgba(220, 238, 212, 0.78);
  font-size: 11.5px;
  white-space: nowrap;
  cursor: pointer;
}
.wm-warehouse:hover { background: rgba(255, 255, 255, 0.12); }
.wh-ico { font-size: 12px; line-height: 1; }
/* 🔴 商店（需求 R）：紧挨仓库，但配色区分开——仓库是「存」，商店是「买」 */
.wm-shop {
  border-color: rgba(255, 196, 108, 0.28);
  background: rgba(255, 196, 108, 0.08);
  color: rgba(255, 226, 176, 0.85);
}
.wm-shop:hover { background: rgba(255, 196, 108, 0.18); }
/* S26 成就入口：与仓库/商店同一排，绿色系区分「成就」 */
.wm-ach {
  border-color: rgba(126, 200, 128, 0.28);
  background: rgba(126, 200, 128, 0.08);
  color: rgba(206, 238, 208, 0.85);
}
.wm-ach:hover { background: rgba(126, 200, 128, 0.18); }
.wm-shop.has-warn {
  border-color: rgba(224, 85, 47, 0.5);
  background: rgba(214, 106, 40, 0.28);
  color: #ffd9c8;
}
/* 有临期/变质批次时点亮，和顶部 chip 的告警色一致 */
.wm-warehouse.has-warn {
  border-color: rgba(224, 85, 47, 0.5);
  background: rgba(214, 106, 40, 0.28);
  color: #ffd9c8;
}
.wh-dot {
  position: absolute;
  top: -5px;
  right: -4px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 15px;
  height: 15px;
  padding: 0 4px;
  border-radius: 8px;
  background: #e0552f;
  color: #fff;
  font-size: 10px;
  font-style: normal;
  font-weight: 700;
  line-height: 1;
}

.wm-base {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  gap: 6px;
  height: 28px;
  padding: 0 11px;
  border-radius: 14px;
  border: 1px solid rgba(160, 200, 150, 0.24);
  background: rgba(255, 255, 255, 0.05);
  color: rgba(220, 238, 212, 0.78);
  font-size: 11.5px;
  white-space: nowrap;
  cursor: pointer;
}
.wm-base:hover { background: rgba(255, 255, 255, 0.12); }
.wm-base.on { border-color: rgba(120, 200, 230, 0.45); color: #bfe6f4; }
.bs-dot { width: 7px; height: 7px; border-radius: 50%; background: #7a8f79; flex: 0 0 auto; }
.wm-base.on .bs-dot { background: #66c2e0; box-shadow: 0 0 0 2px rgba(102, 194, 224, 0.25); }
.bs-caret { font-size: 9px; opacity: 0.6; }

/* ---- 图层切换器 ---- */
.wm-layer-ctl {
  position: relative;
  flex: 0 0 auto;
}
.wm-layer-panel {
  position: absolute;
  top: calc(100% + 8px);
  right: 0;
  width: 232px;
  padding: 10px;
  border-radius: 12px;
  background: rgba(12, 20, 15, 0.97);
  border: 1px solid rgba(150, 190, 140, 0.26);
  backdrop-filter: blur(12px);
  box-shadow: 0 12px 32px rgba(0, 0, 0, 0.5);
  z-index: 20;
}
.lp-sec + .lp-sec { margin-top: 10px; padding-top: 10px; border-top: 1px solid rgba(150, 190, 140, 0.16); }
.lp-t {
  font-size: 10.5px;
  letter-spacing: 1px;
  color: rgba(200, 224, 190, 0.5);
  margin-bottom: 6px;
}
.lp-item {
  width: 100%;
  display: flex;
  align-items: baseline;
  gap: 7px;
  padding: 6px 8px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: rgba(220, 238, 212, 0.78);
  font-size: 12px;
  text-align: left;
  cursor: pointer;
}
.lp-item:hover { background: rgba(255, 255, 255, 0.07); }
.lp-item b { font-weight: 500; flex: 0 0 auto; }
.lp-item span { font-size: 10px; color: rgba(200, 224, 190, 0.42); margin-left: auto; }
.lp-radio {
  width: 9px;
  height: 9px;
  border-radius: 50%;
  border: 1.5px solid rgba(160, 200, 150, 0.45);
  flex: 0 0 auto;
  align-self: center;
}
.lp-item.on { background: rgba(120, 190, 96, 0.16); color: #a8d78e; }
.lp-item.on .lp-radio { border-color: #8cc46e; background: #8cc46e; }
.lp-foot {
  margin-top: 9px;
  padding-top: 8px;
  border-top: 1px solid rgba(150, 190, 140, 0.16);
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 6px;
  font-size: 10px;
  color: rgba(200, 224, 190, 0.4);
}
.lp-warn { color: #ffb27a; }

/* ---- 测量工具条 ---- */
.wm-measure-ctl {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  gap: 4px;
}
.wm-mbtn {
  height: 28px;
  padding: 0 9px;
  border-radius: 14px;
  border: 1px solid rgba(160, 200, 150, 0.24);
  background: rgba(255, 255, 255, 0.05);
  color: rgba(220, 238, 212, 0.78);
  font-size: 11.5px;
  white-space: nowrap;
  cursor: pointer;
}
.wm-mbtn:hover { background: rgba(255, 255, 255, 0.12); }
.wm-mbtn.on { border-color: rgba(255, 180, 90, 0.6); color: #ffd08a; background: rgba(255, 170, 70, 0.14); }
.wm-mbtn.clear { border-color: rgba(255, 120, 100, 0.4); color: #ffb3a8; }

/* ---- 测量层 ---- */
.wm-measure-layer { pointer-events: none; }
.wm-measure-line {
  fill: none;
  stroke-width: 2;
  vector-effect: non-scaling-stroke;
  stroke-linejoin: round;
  stroke-linecap: round;
}
.wm-measure-line.distance { stroke: #ffa73d; }
.wm-measure-line.area { stroke: #45d6c4; }
.wm-measure-area {
  fill: rgba(69, 214, 196, 0.16);
  stroke: none;
}
.wm-measure-dot {
  vector-effect: non-scaling-stroke;
  stroke-width: 1.6;
}
.wm-measure-dot.distance { fill: #ffa73d; stroke: #fff2dc; }
.wm-measure-dot.area { fill: #45d6c4; stroke: #dcfffb; }
.wm-measure-idx {
  font-size: 10px;
  text-anchor: middle;
  paint-order: stroke;
  stroke: rgba(6, 16, 10, 0.9);
  stroke-width: 2.4px;
  stroke-linejoin: round;
  fill: #fff;
}
.wm-measure-text {
  font-size: 12px;
  font-weight: 600;
  text-anchor: middle;
  paint-order: stroke;
  stroke: rgba(6, 16, 10, 0.9);
  stroke-width: 3px;
  stroke-linejoin: round;
}
.wm-measure-text.distance { fill: #ffc47d; }
.wm-measure-text.area { fill: #7ff0e2; }

/* ---- 测量读数面板 ---- */
.wm-measure-panel {
  position: absolute;
  right: 16px;
  top: 56px;
  width: 196px;
  padding: 11px 13px;
  border-radius: 11px;
  background: rgba(12, 20, 15, 0.92);
  border: 1px solid rgba(150, 190, 140, 0.24);
  backdrop-filter: blur(10px);
  z-index: 6;
}
.mp-title { font-size: 12.5px; font-weight: 600; color: #e8f2e4; }
.mp-hint { margin-top: 5px; font-size: 10.5px; line-height: 1.6; color: rgba(206, 228, 196, 0.55); }
.mp-result {
  margin-top: 9px;
  padding: 8px 9px;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.06);
  border-left: 2px solid #ffa73d;
}
.mp-result b { display: block; font-size: 15px; color: #ffc47d; font-variant-numeric: tabular-nums; }
.mp-result span { display: block; margin-top: 3px; font-size: 10px; color: rgba(206, 228, 196, 0.5); }
.mp-acts { margin-top: 10px; display: flex; gap: 6px; }
.mp-btn {
  flex: 1;
  height: 28px;
  border-radius: 14px;
  border: 1px solid rgba(160, 200, 150, 0.28);
  background: rgba(255, 255, 255, 0.06);
  color: rgba(220, 238, 212, 0.85);
  font-size: 11.5px;
  cursor: pointer;
}
.mp-btn:hover { background: rgba(255, 255, 255, 0.13); }
.mp-btn.ghost { flex: 0 0 auto; padding: 0 10px; }

/* ---- 逆地理编码 ---- */
.wm-reverse-pin { pointer-events: none; }
.wm-reverse-pin .rp-dot { fill: #66c2e0; stroke: #e8f6fb; }
.wm-reverse-pin .rp-halo { fill: rgba(102, 194, 224, 0.22); }
.wm-reverse-tip {
  position: absolute;
  z-index: 7;
  max-width: 250px;
  padding: 9px 26px 9px 11px;
  border-radius: 9px;
  background: rgba(12, 22, 28, 0.96);
  border: 1px solid rgba(102, 194, 224, 0.4);
  box-shadow: 0 8px 22px rgba(0, 0, 0, 0.45);
  font-size: 11.5px;
  line-height: 1.65;
  color: rgba(226, 240, 220, 0.9);
}
.wm-reverse-tip b { display: block; font-size: 12.5px; color: #bfe6f4; margin-bottom: 3px; }
.wm-reverse-tip span { display: block; color: rgba(206, 228, 196, 0.68); }
.wm-reverse-tip .rt-poi { color: #ffd86b; }
.wm-reverse-tip .rt-coord { margin-top: 4px; font-size: 10px; color: rgba(206, 228, 196, 0.42); }
.rt-close {
  position: absolute;
  top: 5px;
  right: 5px;
  width: 18px;
  height: 18px;
  border: 0;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.09);
  color: rgba(220, 238, 212, 0.6);
  font-size: 13px;
  line-height: 1;
  cursor: pointer;
}
.rt-close:hover { background: rgba(255, 255, 255, 0.2); }

/* ---- 主体 ---- */
.wm-body { flex: 1; display: flex; min-height: 0; }
.wm-canvas {
  position: relative;
  flex: 1;
  min-width: 0;
  overflow: hidden;
  cursor: grab;
  touch-action: none;
  background:
    radial-gradient(100% 90% at 50% 40%, rgba(30, 46, 34, 0.55) 0%, rgba(8, 14, 10, 0) 70%);
  /* 悬浮卡片宽度：JS 侧 placeFarmCard 读同一份变量做翻转判断，
     保证「CSS 渲染宽度」和「JS 认为的宽度」永远一致。
     （历史 bug：JS 写死 252、CSS 实际 320 → 卡片被 overflow:hidden 裁掉） */
  --farm-card-w: 268px;
  --farm-card-w-wide: 380px;
}
.wm-page.grabbing .wm-canvas { cursor: grabbing; }

.wm-svg { width: 100%; height: 100%; display: block; overflow: visible; }
.wm-sea { pointer-events: none; }
.wm-grat line {
  stroke: rgba(255, 255, 255, 0.16);
  stroke-width: 1;
  stroke-dasharray: 3 6;
  vector-effect: non-scaling-stroke;
  pointer-events: none;
}
.wm-tile { pointer-events: none; }

.wm-region {
  stroke: rgba(226, 240, 220, 0.3);
  stroke-width: 0.55;
  cursor: pointer;
  transition: fill 0.12s ease-out;
}
.wm-region:hover {
  stroke: rgba(255, 255, 255, 0.9);
  stroke-width: 1.1;
}
/*
 * 省界描边：矢量底图下自己的 fill 已经透明了（不再遮挡天地图原生界线），
 * 边界就得靠自己画出来——否则全国视图里各省糊成一片，分不清哪儿是哪儿。
 * 深色描边配浅色底图（天地图矢量底图是米白色）最清楚。
 */
.wm-prov {
  stroke: rgba(24, 44, 28, 0.55);
  stroke-width: 0.9;
}
.wm-prov:hover {
  stroke: rgba(255, 255, 255, 0.92);
  stroke-width: 1.3;
}
/*
 * 区县层：边界必须比省界更明显，否则同为主栽的一百个县会糊成一整块。
 *
 * <p>描边用**浅色**而不是深色（2026-10-21 修复）：矢量底图下区县是半透明
 * 色块，深色描边压在深绿色块上几乎看不见，两个相邻县糊成一片。
 * 浅色描边（米白）在深绿块和浅色底图上都能分出界线。
 */
.wm-dist {
  stroke: rgba(248, 252, 240, 0.82);
  stroke-width: 1.1;
}
.wm-dist:hover {
  stroke: rgba(255, 255, 255, 0.98);
  stroke-width: 1.8;
}
/*
 * fill 的透明度完全由 JS 里算好的 rgba 决定，这里不要再叠 fill-opacity：
 * 省界/区县的实色与半透明是两套配色，同一个 CSS 规则没法区分，
 * 叠加之后卫星底图下的半透明会被再乘一次（透出来的影像更暗）。
 */
.tile-on .wm-region { stroke: rgba(255, 255, 255, 0.42); stroke-width: 0.7; }
.tile-on .wm-dist { stroke: rgba(255, 255, 255, 0.55); stroke-width: 0.9; }
.tile-on .wm-region:hover { stroke: #fff; stroke-width: 1.3; }
/* 卫星/地形底图是深色影像，省界描边必须换回浅色 */
.tile-on .wm-prov { stroke: rgba(255, 255, 255, 0.5); stroke-width: 1; }
.tile-on .wm-prov:hover { stroke: #fff; stroke-width: 1.4; }
.wm-region.mine { stroke: #ffe07a; stroke-width: 1.1; }
.tile-on .wm-region.mine { stroke: #ffd24a; stroke-width: 1.5; }

.wm-marker { cursor: pointer; }
.wm-marker .dot { fill: #ffd24a; stroke: #fff8d8; stroke-width: 1.2; }

/* 区县标注：白字 + 深色描边，压在任何底图上都能看清 */
.wm-label-layer { pointer-events: none; }
.wm-dlabel {
  fill: #fff;
  font-size: 11px;
  text-anchor: middle;
  dominant-baseline: middle;
  paint-order: stroke;
  stroke: rgba(6, 16, 10, 0.9);
  stroke-width: 2.6px;
  stroke-linejoin: round;
  letter-spacing: 0.4px;
}
.wm-dlabel.big { font-size: 13px; font-weight: 600; }
.wm-plabel {
  fill: rgba(255, 255, 255, 0.92);
  font-size: 12.5px;
  font-weight: 600;
  text-anchor: middle;
  dominant-baseline: middle;
  paint-order: stroke;
  stroke: rgba(6, 16, 10, 0.85);
  stroke-width: 3px;
  stroke-linejoin: round;
  letter-spacing: 1px;
}
.wm-marker .halo { fill: rgba(255, 210, 74, 0.22); }
.wm-marker:hover .dot { fill: #fff0a8; }
.wm-marker.active .dot { fill: #fff; stroke: #ffd24a; stroke-width: 2; }
.wm-marker.active .halo {
  fill: rgba(255, 210, 74, 0.1);
  stroke: rgba(255, 210, 74, 0.55);
  stroke-width: 1.5;
  vector-effect: non-scaling-stroke;
}

.wm-loading {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
  background: rgba(8, 14, 10, 0.42);
  font-size: 13px;
  color: rgba(220, 238, 212, 0.82);
  z-index: 5;
}
.spin {
  width: 22px;
  height: 22px;
  border: 2px solid rgba(160, 210, 140, 0.25);
  border-top-color: #8cc46e;
  border-radius: 50%;
  animation: sp 0.85s linear infinite;
}
@keyframes sp { to { transform: rotate(360deg); } }

.wm-tip {
  position: absolute;
  pointer-events: none;
  padding: 7px 10px;
  border-radius: 7px;
  background: rgba(14, 22, 16, 0.95);
  border: 1px solid rgba(160, 200, 140, 0.3);
  font-size: 11.5px;
  line-height: 1.6;
  color: rgba(226, 240, 218, 0.9);
  max-width: 230px;
  z-index: 4;
}
.wm-tip b { display: block; font-size: 12.5px; margin-bottom: 2px; }
.wm-tip span { display: block; color: rgba(206, 228, 196, 0.62); }

/* ---- 农场标记悬浮预览卡片 ----
   宽度用 CSS 变量定义（在 .wm-canvas 上），JS 侧 placeFarmCard 读同一份变量
   做翻转判断 —— 这样「CSS 改宽度」和「JS 判断放不放得下」永远一致，
   不会再出现「JS 以为 252、CSS 实际 320」导致卡片被 overflow:hidden 裁掉的 bug。 */
.farm-card {
  position: absolute;
  z-index: 6;
  width: var(--farm-card-w);
  /* ⚠️ max-width 是防裁切的最后一道闸门：
     .wm-canvas 是 overflow:hidden，卡片超出容器宽度会被**直接裁掉**（不是滚动）。
     窄屏 / 折叠侧栏后地图区可能只剩几百 px，靠 JS 翻转兜底不够稳，
     这里再按容器宽度夹一次，保证任何情况下都不会被裁。 */
  max-width: calc(100% - 16px);
  padding: 11px 13px 12px;
  border-radius: 10px;
  background: rgba(12, 20, 14, 0.96);
  border: 1px solid rgba(160, 200, 140, 0.34);
  box-shadow: 0 10px 28px rgba(0, 0, 0, 0.45);
  font-size: 12px;
  color: rgba(226, 240, 218, 0.9);
  cursor: default;
}
.fc-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 4px;
}
/* 有地块时卡片加宽：地块行要放得下「地块名 + 作物 + 阶段 + 播期 + 4 个操作按钮」。
   2026-10-22 再放宽到 380px —— 320px 时长地块名（如「南昌县水稻田」）和
   阶段描述（如「出苗不整齐（缺苗 85%）」）仍会被 ellipsis 截断，
   加宽后这些关键信息能完整显示。 */
.farm-card.wide {
  width: var(--farm-card-w-wide);
}
.fc-name {
  font-size: 13.5px;
  color: #eaf6e2;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.fc-zone {
  flex: 0 0 auto;
  padding: 1px 7px;
  border-radius: 8px;
  font-size: 10.5px;
  background: rgba(120, 180, 255, 0.16);
  color: #b8d8ff;
}
.fc-loc {
  font-size: 11px;
  color: rgba(206, 228, 196, 0.6);
  margin-bottom: 8px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  /* 2026-10-22：卡片加宽后让位置描述能用更多像素，
     原来 320px 下「江西省南昌市南昌县」这类完整地址会显示不全。 */
  max-width: 100%;
}
/* 天气区（需求 O）：一眼看清今天能不能浇水 / 打药。
   ⚠️ 数值是当日实测/推算值，不是预报，所以数据来源单独占一行如实标注。 */
.fc-weather {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 3px 6px;
  margin-bottom: 8px;
  padding: 5px 8px;
  border-radius: 7px;
  background: rgba(110, 165, 225, 0.12);
  border: 1px solid rgba(120, 175, 235, 0.16);
  font-size: 11px;
  line-height: 1.45;
}
.fw-icon {
  flex: 0 0 auto;
  font-size: 13px;
  line-height: 1;
}
.fw-temp {
  flex: 0 0 auto;
  font-weight: 600;
  color: #eaf6e2;
}
.fw-low {
  flex: 0 0 auto;
  color: rgba(206, 228, 196, 0.55);
}
.fw-rain {
  flex: 0 0 auto;
  color: rgba(186, 216, 255, 0.78);
}
/* 数据来源：单独一行。
   🔴 2026-10-22 移动端修正：原来用 nowrap + ellipsis 截断，
   375px 宽下只剩「演示天气：2019 年南昌实测，按日历日重放（...」，
   恰好把「非实时，非预报」这个最关键的免责信息截没了 ——
   玩家会以为那是实时预报。改成允许换行，来源标签完整可读。 */
.fw-src {
  flex: 0 1 100%;
  min-width: 0;
  white-space: normal;
  word-break: break-word;
  font-size: 9.5px;
  line-height: 1.4;
  color: rgba(206, 228, 196, 0.42);
}
.fc-stats {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin-bottom: 8px;
  font-size: 11px;
  color: rgba(206, 228, 196, 0.66);
}
.fc-stats i {
  font-style: normal;
  font-weight: 600;
  color: #eaf6e2;
}
.fc-stats i.ok { color: #ffd479; }
.fc-crops {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  margin-bottom: 9px;
}
.fc-crop {
  padding: 1px 7px;
  border-radius: 8px;
  font-size: 10.5px;
  background: rgba(255, 255, 255, 0.08);
  color: rgba(220, 238, 212, 0.78);
}
.fc-crop.more { background: transparent; color: rgba(206, 228, 196, 0.5); }

/* 空农场提示：0 块地时告诉玩家下一步该做什么 */
.fc-empty {
  margin-bottom: 9px;
  padding: 5px 8px;
  border-radius: 6px;
  background: rgba(255, 255, 255, 0.06);
  color: rgba(214, 232, 208, 0.72);
  font-size: 11px;
  line-height: 1.5;
}

/* ---- 农田列表（悬浮面板内的基础操作区） ---- */
.fc-plots {
  margin: 0 -13px 9px;
  padding: 7px 13px 8px;
  border-top: 1px solid rgba(160, 200, 140, 0.16);
  border-bottom: 1px solid rgba(160, 200, 140, 0.16);
  background: rgba(0, 0, 0, 0.22);
}
.fpl-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 6px;
  font-size: 10.5px;
  letter-spacing: 0.8px;
  color: rgba(200, 224, 190, 0.5);
}
.fpl-tip { letter-spacing: 0; font-size: 9.5px; color: rgba(200, 224, 190, 0.32); }
.fpl-list {
  display: flex;
  flex-direction: column;
  gap: 2px;
  /* 地块多时列表内部滚动，卡片整体不无限变高 */
  max-height: 168px;
  overflow-y: auto;
}
.fpl-list::-webkit-scrollbar { width: 4px; }
.fpl-list::-webkit-scrollbar-thumb {
  background: rgba(150, 190, 140, 0.26);
  border-radius: 2px;
}
.fpl-row {
  display: flex;
  align-items: center;
  gap: 5px;
  padding: 3px 4px;
  border-radius: 5px;
  font-size: 10.5px;
  transition: background 0.12s;
  /* 整行可点进地块（需求 O） */
  cursor: pointer;
}
.fpl-row:hover { background: rgba(255, 255, 255, 0.06); }
.fpl-row:focus-visible {
  outline: 1px solid rgba(150, 210, 130, 0.6);
  outline-offset: -1px;
}
.fpl-row.empty { opacity: 0.62; }
.fpl-row.ready { background: rgba(255, 210, 74, 0.08); }
.fpl-dot {
  width: 7px;
  height: 7px;
  border-radius: 2px;
  flex: 0 0 auto;
}
/* 地块状态四档：空 / 未出苗 / 生长 / 可收（与右侧面板同一套口径） */
.pc-idle { background: rgba(255, 255, 255, 0.16); }
.pc-sown { background: rgba(110, 160, 210, 0.8); }
.pc-grow { background: rgba(122, 190, 96, 0.92); }
.pc-ready { background: #ffd24a; }
/* 地块名 + 次行元信息（作物 / 阶段 / 播种日）——两行布局 */
.fpl-main {
  flex: 1 1 auto;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 1px;
}
.fpl-name {
  display: block;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: rgba(226, 240, 218, 0.88);
  line-height: 1.35;
}
.fpl-meta {
  display: flex;
  align-items: center;
  gap: 5px;
  min-width: 0;
  line-height: 1.3;
}
.fpl-crop {
  flex: 0 1 auto;
  min-width: 0;
  /* 2026-10-22：卡片加宽后放开 46px 上限 —— 原来「水稻」「郁金香」之类
     短名没问题，但「西瓜」「观赏菊」等一旦被截成「观赏…」就没法看了。 */
  max-width: 64px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: rgba(168, 214, 143, 0.86);
}
.fpl-stage {
  flex: 0 0 auto;
  /* 阶段描述是玩家判断「该不该浇水/打药」的关键信息，
     不能被压缩（原来 min-width:40px 会让「出苗不整齐（缺苗 85%）」挤成一团）。 */
  min-width: 0;
  color: rgba(200, 224, 190, 0.5);
  text-align: left;
}
/* 播种日期（需求 O）：灰一点，别跟作物名抢视觉 */
.fpl-sow {
  flex: 0 0 auto;
  color: rgba(190, 214, 180, 0.42);
  white-space: nowrap;
}
/* 异常徽标：枯死 / 缺苗（2026-10-22） */
.fpl-flag {
  flex: 0 0 auto;
  padding: 1px 4px;
  border-radius: 4px;
  font-size: 9px;
  line-height: 1.3;
  font-family: inherit;
  white-space: nowrap;
}
.fpl-flag.bad { background: rgba(214, 90, 70, 0.24); color: #f0a08e; }
.fpl-flag.warn { background: rgba(255, 200, 90, 0.2); color: #ffd47a; }
.fpl-ops {
  display: flex;
  gap: 3px;
  flex: 0 0 auto;
}
.fpl-btn {
  min-width: 20px;
  height: 19px;
  padding: 0 4px;
  border: 0;
  border-radius: 5px;
  background: rgba(255, 255, 255, 0.1);
  color: rgba(226, 240, 218, 0.85);
  font-size: 10px;
  font-family: inherit;
  line-height: 1;
  cursor: pointer;
  transition: background 0.13s, color 0.13s;
}
.fpl-btn:hover:not(:disabled) { background: rgba(255, 255, 255, 0.2); }
.fpl-btn.ok {
  background: rgba(255, 210, 74, 0.2);
  color: #ffd86b;
  font-weight: 600;
}
.fpl-btn.ok:hover:not(:disabled) { background: rgba(255, 210, 74, 0.34); }
.fpl-btn.ghost { background: rgba(122, 190, 96, 0.16); color: #a8d78e; }
.fpl-btn.ghost:hover:not(:disabled) { background: rgba(122, 190, 96, 0.3); }
.fpl-btn:disabled {
  opacity: 0.34;
  cursor: not-allowed;
}
.fc-enter {
  width: 100%;
  padding: 6px 0;
  border: none;
  border-radius: 7px;
  background: linear-gradient(180deg, #6aa845, #4f8a31);
  color: #f2fbee;
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.4px;
  cursor: pointer;
  transition: filter 0.15s;
}
.fc-enter:hover { filter: brightness(1.12); }

/* 窄屏（≤480px）：手机竖屏宽度有限，卡片不能照搬桌面的 380px。
   2026-10-22 原来固定 252px，在 375px 手机上只用了 71% 的宽度就截断，
   现在放宽到 min(340px, 100vw-20px) —— 375px 机型得到 340px，
   地块名/阶段不再被 ellipsis 砍掉，同时仍然留 10px 安全边距不贴边。 */
@media (max-width: 480px) {
  .wm-canvas {
    /* 窄屏也要覆盖变量本身，不能只覆盖 width ——
       否则 JS 读 --farm-card-w-wide 拿到 380，而实际渲染是 340，两者对不上。 */
    --farm-card-w: 268px;
    --farm-card-w-wide: 340px;
  }
  /* ⚠️ 两档必须分开写：.farm-card 只能用 --farm-card-w，
     如果合并成 `.farm-card, .farm-card.wide { width: var(--farm-card-w-wide) }`，
     无地块的窄卡片会变成 340px，而 JS 按 --farm-card-w(268) 判断 → 又对不上了。 */
  .farm-card {
    width: min(var(--farm-card-w), calc(100vw - 20px));
  }
  .farm-card.wide {
    width: min(var(--farm-card-w-wide), calc(100vw - 20px));
  }
  .farm-card,
  .farm-card.wide {
    padding: 9px 11px 10px;
  }
  .fc-plots { margin: 0 -11px 8px; padding: 6px 11px 7px; }
  .fpl-list { max-height: 150px; }
  /* 窄屏仍要让元信息换行，否则「作物+阶段+播期+4 个按钮」会挤成一条线 */
  .fpl-meta { flex-wrap: wrap; row-gap: 1px; }
  .fpl-stage { min-width: 0; }
  .fpl-crop { max-width: 44px; }
  .fpl-btn { min-width: 18px; height: 18px; padding: 0 3px; font-size: 9.5px; }
  .fpl-ops { gap: 2px; }
}

/* 底部动作区：需求 O 移除了「进入地块 / 查看详情」，
   只保留一个全宽的「进入农场 →」。 */
.fc-acts {
  display: flex;
  gap: 7px;
}

/* ---- 右侧看板折叠 ---- */
.side-fold-btn {
  position: absolute;
  left: -15px;
  top: 14px;
  z-index: 9;
  width: 22px;
  height: 44px;
  border: 1px solid rgba(150, 190, 140, 0.24);
  border-right: none;
  border-radius: 8px 0 0 8px;
  background: rgba(12, 20, 14, 0.94);
  color: rgba(206, 228, 196, 0.75);
  font-size: 14px;
  line-height: 1;
  cursor: pointer;
  transition: background 0.15s, color 0.15s;
}
.side-fold-btn:hover {
  background: rgba(30, 52, 30, 0.96);
  color: #eaf6e2;
}
/* 收起态：按钮跑到面板外侧（左侧），面板只剩一条窄边 */
.side-fold-btn.out {
  left: -22px;
  border-radius: 8px 0 0 8px;
}
.side-fold-strip {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  padding: 16px 6px;
  width: 100%;
}
.side-fold-strip .fs-title {
  writing-mode: vertical-rl;
  font-size: 12px;
  letter-spacing: 3px;
  color: rgba(206, 228, 196, 0.75);
}
.side-fold-strip .fs-line {
  writing-mode: vertical-rl;
  font-size: 10.5px;
  color: rgba(206, 228, 196, 0.45);
  max-height: 240px;
  overflow: hidden;
}
.wm-side.folded {
  width: 42px;
  flex: 0 0 42px;
  align-items: center;
}

/*
 * ⚠️ 2026-10-24：图例从 11 项扩到 **22 项**。
 *
 * 单列排下来 22 行 × 18px ≈ 400px，在 768px 高的窗口里会顶出屏幕，
 * 所以改成**两列网格**：宽 2 列（约 260px），窄屏 1 列。
 * 容器仍然 absolute 定位在左下角，但给一个 max-height + overflow 兜底。
 */
.wm-legend {
  position: absolute;
  left: 18px;
  bottom: 18px;
  padding: 10px 13px;
  border-radius: 10px;
  background: rgba(10, 18, 12, 0.74);
  border: 1px solid rgba(150, 190, 140, 0.16);
  backdrop-filter: blur(8px);
  font-size: 11.5px;
  color: rgba(216, 236, 208, 0.78);
  pointer-events: none;
  max-width: min(300px, calc(100% - 36px));
  max-height: calc(100% - 36px);
  overflow: hidden;
}
.lg-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 2px 12px;
}
.lg-row { display: flex; align-items: center; gap: 7px; margin-bottom: 5px; }
.lg-box { width: 11px; height: 11px; border-radius: 3px; display: inline-block; }
.lg-farm { background: rgba(96, 156, 72, 0.9); }
.lg-none { background: rgba(62, 82, 56, 0.9); }
.lg-dot {
  width: 9px;
  height: 9px;
  border-radius: 50%;
  background: #ffd24a;
  box-shadow: 0 0 0 2px rgba(255, 216, 107, 0.3);
  display: inline-block;
}
.lg-tip {
  margin-top: 7px;
  padding-top: 7px;
  border-top: 1px solid rgba(150, 190, 140, 0.14);
  color: rgba(200, 224, 190, 0.42);
  font-size: 10.5px;
}
.lg-gesture {
  margin-top: 5px;
  font-size: 10px;
  color: rgba(200, 224, 190, 0.32);
}

/* ---- 缩放控件 ---- */
.wm-zoomctl {
  position: absolute;
  right: 16px;
  bottom: 18px;
  display: flex;
  align-items: center;
  gap: 1px;
  padding: 4px;
  border-radius: 20px;
  background: rgba(10, 18, 12, 0.78);
  border: 1px solid rgba(150, 190, 140, 0.18);
  backdrop-filter: blur(8px);
}
.wm-zoomctl button {
  width: 28px;
  height: 28px;
  border: 0;
  border-radius: 50%;
  background: transparent;
  color: rgba(224, 240, 216, 0.82);
  font-size: 14px;
  line-height: 1;
  cursor: pointer;
}
.wm-zoomctl button:hover { background: rgba(255, 255, 255, 0.12); }
.wm-zoomctl button.zr { font-size: 13px; }
.zc-lv {
  padding: 0 8px 0 4px;
  font-size: 10.5px;
  color: rgba(200, 224, 190, 0.5);
}

/* ---- 层级指示 ---- */
.wm-level {
  position: absolute;
  left: 18px;
  top: 14px;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 5px 11px;
  border-radius: 15px;
  background: rgba(10, 18, 12, 0.78);
  border: 1px solid rgba(150, 190, 140, 0.2);
  backdrop-filter: blur(8px);
  pointer-events: none;
}
.wl-tag {
  font-size: 11.5px;
  color: #a8d78e;
  font-weight: 600;
  letter-spacing: 0.4px;
}
.wl-hint { font-size: 11px; color: rgba(206, 228, 196, 0.5); }
.wl-load {
  font-size: 10.5px;
  color: #ffd86b;
  padding-left: 8px;
  border-left: 1px solid rgba(150, 190, 140, 0.2);
}

/* ---- 右侧面板 ---- */
.wm-side {
  width: 372px;
  flex: 0 0 372px;
  display: flex;
  flex-direction: column;
  min-height: 0;
  border-left: 1px solid rgba(150, 190, 140, 0.16);
  background: rgba(10, 17, 12, 0.86);
  backdrop-filter: blur(12px);
}
.wm-side.empty { align-items: stretch; }

.side-head {
  position: relative;
  padding: 16px 18px 13px;
  border-bottom: 1px solid rgba(150, 190, 140, 0.14);
}
.side-head h3 { margin: 0 0 5px; font-size: 17px; letter-spacing: 0.3px; }
.side-loc { margin: 0 0 9px; font-size: 11.5px; color: rgba(206, 228, 196, 0.52); }
.side-tags { display: flex; gap: 7px; flex-wrap: wrap; }
.tag {
  padding: 2px 9px;
  border-radius: 9px;
  font-size: 11px;
  background: rgba(255, 255, 255, 0.07);
  color: rgba(220, 238, 212, 0.72);
}
.tag.zone { background: rgba(120, 190, 96, 0.18); color: #a8d78e; }
.side-close {
  position: absolute;
  top: 11px;
  right: 12px;
  width: 24px;
  height: 24px;
  border: 0;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.07);
  color: rgba(220, 238, 212, 0.6);
  font-size: 15px;
  line-height: 1;
  cursor: pointer;
}
.side-close:hover { background: rgba(255, 255, 255, 0.16); }

.side-scroll { flex: 1; overflow-y: auto; padding: 14px 16px 18px; }
.side-scroll::-webkit-scrollbar { width: 5px; }
.side-scroll::-webkit-scrollbar-thumb { background: rgba(150, 190, 140, 0.24); border-radius: 3px; }

.notice {
  margin-bottom: 14px;
  padding: 8px 10px;
  border-radius: 7px;
  border-left: 2px solid rgba(140, 196, 110, 0.5);
  background: rgba(120, 180, 96, 0.09);
  font-size: 10.5px;
  line-height: 1.7;
  color: rgba(206, 228, 196, 0.58);
}

.sec { margin-bottom: 16px; }
.sec-t {
  font-size: 11px;
  letter-spacing: 1px;
  color: rgba(200, 224, 190, 0.48);
  margin-bottom: 9px;
}
.sec-empty { font-size: 11.5px; color: rgba(200, 224, 190, 0.42); line-height: 1.7; }

.crop-list { display: flex; flex-direction: column; gap: 8px; }
.crop-card {
  padding: 10px 11px;
  border-radius: 9px;
  background: rgba(255, 255, 255, 0.045);
  border-left: 3px solid rgba(140, 196, 110, 0.6);
}
.crop-card.v-rice { border-left-color: #5aa654; }
.crop-card.v-wheat { border-left-color: #c9a441; }
.crop-card.v-corn { border-left-color: #cf8536; }
.crop-card.v-soybean { border-left-color: #87944a; }
/* ⚠️ 2026-10-24：22 种作物的边框色不再用 CSS 类，而是**内联**取 CROP_COLOR
   （:style="{ borderLeftColor: cropColor(c.visual) }"）。
   原因：作物专属色有 22 种，用类名要写 22 条 CSS 规则，且新增作物时
   很容易漏掉一条 —— 漏掉就会退回默认的绿色，和图例对不上。
   下面的旧族类名保留，作为存量 class 的兜底。 */
.crop-card.v-leafy { border-left-color: #7fc45c; }
.crop-card.v-vine { border-left-color: #3f9e72; }
.crop-card.v-bush { border-left-color: #c8563f; }
.crop-card.v-flower { border-left-color: #a06bbf; }
.crop-card.v-millet { border-left-color: #9aa84e; }
.crop-card.v-tuber { border-left-color: #b08d57; }
.crop-card.v-tree { border-left-color: #6f8f3f; }
.cc-top { display: flex; align-items: center; justify-content: space-between; margin-bottom: 5px; }
.cc-top b { font-size: 13px; }
.cc-meta {
  display: flex;
  justify-content: space-between;
  font-size: 11px;
  color: rgba(214, 236, 204, 0.62);
  margin-bottom: 7px;
}
.cc-bar { height: 3px; border-radius: 2px; background: rgba(255, 255, 255, 0.08); overflow: hidden; }
.cc-bar i { display: block; height: 100%; border-radius: 2px; background: linear-gradient(90deg, #6fae4f, #b6d97a); }
.cc-foot {
  display: flex;
  justify-content: space-between;
  margin-top: 6px;
  font-size: 10.5px;
  color: rgba(200, 224, 190, 0.45);
}

.lv {
  font-size: 10.5px;
  padding: 1px 8px;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.08);
}
.lv-0 { background: rgba(96, 190, 90, 0.22); color: #a8e08c; }
.lv-1 { background: rgba(150, 200, 90, 0.2); color: #c0dd8c; }
.lv-2 { background: rgba(210, 190, 80, 0.2); color: #ddd08a; }
.lv-3 { background: rgba(220, 150, 70, 0.2); color: #e3b184; }
.lv-4, .lv-5 { background: rgba(210, 90, 70, 0.2); color: #e6a094; }

.rate-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 7px; }
.rate-cell {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 7px 10px;
  border-radius: 7px;
  background: rgba(255, 255, 255, 0.04);
  font-size: 11.5px;
}
.rc-name { color: rgba(224, 240, 216, 0.82); }
.rc-lv { font-size: 10.5px; color: rgba(200, 224, 190, 0.7); }

.my-farm {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 12px;
  border-radius: 8px;
  background: rgba(120, 190, 96, 0.14);
  border: 1px solid rgba(140, 200, 110, 0.26);
  cursor: pointer;
  font-size: 12.5px;
}
.my-farm:hover { background: rgba(120, 190, 96, 0.22); }
.my-farm span { font-size: 11px; color: rgba(210, 232, 200, 0.6); }

.plot-row {
  display: grid;
  grid-template-columns: 26px 1fr 62px 74px 40px;
  align-items: center;
  gap: 6px;
  padding: 7px 8px;
  border-radius: 6px;
  font-size: 11.5px;
  cursor: pointer;
  color: rgba(220, 238, 212, 0.82);
}
.plot-row:hover { background: rgba(255, 255, 255, 0.06); }
/* 待播种：等玩家做「下一季种什么」的决策，用虚线框提示它不是坏了 */
.plot-row.awaiting { border: 1px dashed rgba(255, 210, 74, 0.28); }
.pr-idx { color: rgba(200, 224, 190, 0.45); font-variant-numeric: tabular-nums; }
.pr-name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.pr-crop { color: rgba(168, 214, 143, 0.9); }
.pr-stage { color: rgba(206, 228, 196, 0.6); font-size: 11px; }
.pr-dvs { text-align: right; color: rgba(206, 228, 196, 0.5); }
.mono { font-variant-numeric: tabular-nums; }

/* 地块行尾的异常徽标（2026-10-22） */
.pr-bad,
.pr-warn {
  font-size: 10px;
  line-height: 1;
  padding: 2px 4px;
  border-radius: 3px;
  text-align: center;
}
.pr-bad { background: rgba(214, 90, 70, 0.24); color: #f0a08e; }
.pr-warn { background: rgba(255, 200, 90, 0.2); color: #ffd47a; }

.sec-tip {
  float: right;
  font-size: 10px;
  letter-spacing: 0;
  color: rgba(200, 224, 190, 0.34);
}

/* 种植与收获记录（2026-10-22） */
.seasons-block {
  margin-bottom: 10px;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.035);
  padding: 7px 8px;
}
.sb-head {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 11px;
  color: rgba(200, 224, 190, 0.55);
  padding-bottom: 5px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.06);
}
.sb-idx {
  width: 16px;
  height: 16px;
  border-radius: 4px;
  background: rgba(140, 196, 110, 0.18);
  color: rgba(190, 226, 168, 0.9);
  font-size: 10px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-variant-numeric: tabular-nums;
}
.sb-name { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.sb-count { font-size: 10px; color: rgba(200, 224, 190, 0.35); }

.season-row {
  padding: 6px 0 5px;
  border-bottom: 1px dashed rgba(255, 255, 255, 0.05);
}
.season-row:last-child { border-bottom: none; }
.sr-line1 {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: rgba(226, 240, 218, 0.9);
}
.sr-line1 b { font-weight: 600; }
.sr-season { font-size: 10px; color: rgba(200, 224, 190, 0.42); }
.sr-status {
  margin-left: auto;
  font-size: 10px;
  padding: 1px 5px;
  border-radius: 3px;
  background: rgba(120, 190, 96, 0.18);
  color: rgba(180, 222, 150, 0.9);
}
.season-row.st-FAILED .sr-status { background: rgba(214, 90, 70, 0.24); color: #f0a08e; }
.season-row.st-ABANDONED .sr-status { background: rgba(255, 255, 255, 0.08); color: rgba(220, 238, 212, 0.5); }
.season-row.st-GROWING .sr-status { background: rgba(110, 174, 79, 0.22); color: #b6d97a; }

.sr-line2 {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 3px;
  font-size: 10.5px;
  color: rgba(200, 224, 190, 0.55);
}
.sr-line2 .bad { color: #f0a08e; }
.sr-line2 .warn { color: #ffd47a; }
.sr-note {
  margin: 4px 0 0;
  font-size: 10.5px;
  line-height: 1.55;
  color: rgba(240, 160, 142, 0.82);
}

.side-foot {
  display: flex;
  gap: 9px;
  padding: 12px 16px 16px;
  border-top: 1px solid rgba(150, 190, 140, 0.14);
}
.btn {
  flex: 1;
  height: 38px;
  border-radius: 19px;
  border: 1px solid transparent;
  font-size: 13px;
  cursor: pointer;
  transition: filter 0.15s, background 0.15s;
}
.btn.primary {
  background: linear-gradient(135deg, #6aa94b, #8bc466);
  color: #0d1a0f;
  font-weight: 600;
}
.btn.primary:hover { filter: brightness(1.08); }
.btn.ghost {
  flex: 0 0 92px;
  background: rgba(255, 255, 255, 0.06);
  border-color: rgba(160, 200, 150, 0.24);
  color: rgba(220, 238, 212, 0.8);
}
.btn.ghost:hover { background: rgba(255, 255, 255, 0.12); }

/* ---- 建农场弹窗（S25） ---- */
.wb-form { display: flex; flex-direction: column; gap: 6px; }
.wb-label {
  margin-top: 6px;
  font-size: 12px;
  font-weight: 600;
  color: #7d8b7e;
}
.wb-label:first-child { margin-top: 0; }
.wb-static {
  padding: 8px 11px;
  border-radius: 8px;
  background: #f4f8f1;
  border: 1px solid #e3eae0;
  font-size: 13px;
  color: #334436;
}
.wb-hint {
  margin: 2px 0 0;
  font-size: 11.5px;
  line-height: 1.6;
  color: #97a598;
}
.wb-btn {
  min-width: 84px;
  height: 34px;
  padding: 0 18px;
  border-radius: 17px;
  border: 1px solid transparent;
  font-size: 13px;
  cursor: pointer;
  transition: filter 0.15s, background 0.15s;
}
.wb-btn.primary {
  background: linear-gradient(135deg, #6aa94b, #8bc466);
  color: #0d1a0f;
  font-weight: 600;
}
.wb-btn.primary:hover:not(:disabled) { filter: brightness(1.08); }
.wb-btn.primary:disabled { opacity: 0.5; cursor: not-allowed; }
.wb-btn.ghost {
  background: #f4f8f1;
  border-color: #dbe4d6;
  color: #5a6a5b;
}
.wb-btn.ghost:hover { background: #eaf0e6; }

.sug { display: flex; align-items: baseline; gap: 8px; }
.sug b { font-size: 12.5px; }
.sug span { font-size: 10.5px; color: #9aa894; }
.sug-tag {
  margin-left: auto;
  padding: 1px 7px;
  border-radius: 8px;
  font-size: 9.5px;
  font-style: normal;
  background: rgba(150, 190, 140, 0.16);
  color: rgba(200, 224, 190, 0.7);
  white-space: nowrap;
}
.sug-tag.t-tdt-poi { background: rgba(102, 194, 224, 0.18); color: #bfe6f4; }
.sug-tag.t-tdt-area { background: rgba(150, 120, 220, 0.2); color: #d3c4f5; }

/* ---- 窄屏 ---- */
@media (max-width: 1100px) {
  .wm-side { width: 320px; flex: 0 0 320px; }
  .wm-title .wm-sub { display: none; }
  .wm-stat { display: none; }
}
@media (max-width: 860px) {
  .wm-head { gap: 8px; padding: 0 10px; height: 50px; }
  .wm-title { display: none; }
  .wm-search { flex: 1 1 auto; }
  .wm-body { flex-direction: column; }
  .wm-canvas { flex: 0 0 46vh; }
  .wm-side {
    width: auto;
    flex: 1 1 auto;
    border-left: 0;
    border-top: 1px solid rgba(150, 190, 140, 0.16);
  }
  .wm-legend { display: none; }
  .wm-zoomctl { right: 10px; bottom: 10px; }
  .wm-level { left: 10px; top: 10px; }
  /* 移动端：面包屑挤掉，测量按钮与图层按钮保留 */
  .wm-crumb { display: none; }
  .wm-layer-panel { right: -4px; width: 216px; }
  .wm-measure-panel { right: 10px; top: 58px; width: 178px; }
  .wm-reverse-tip { max-width: 210px; }
}

/* 768px：把「测距 / 测面 / 清除」三个按钮从工具条挪到画布内，
   否则 375px 宽的顶栏会被挤爆（面包屑 + 搜索 + 3 个按钮根本放不下） */
@media (max-width: 768px) {
  .wm-head { gap: 6px; padding: 0 8px; }
  .wm-measure-ctl {
    position: absolute;
    left: 10px;
    top: calc(100% + 8px);
    z-index: 8;
  }
  .wm-layer-panel { width: 200px; }
  .lp-item { padding: 7px 8px; }
  /* 仓库入口是主功能，窄屏也必须留图标（文字省掉） */
  .wm-warehouse { padding: 0 9px; }
  .wm-warehouse .wh-txt { display: none; }
}

/* 400px：极窄屏（iPhone SE 竖屏）—— 顶栏只留返回 + 搜索 + 仓库 + 图层 */
@media (max-width: 400px) {
  .wm-back { padding: 0 9px; font-size: 12px; }
  .wm-back .wm-arrow { display: none; }
  .wm-base { padding: 0 9px; font-size: 11px; }
  .bs-caret { display: none; }
  .wm-layer-panel { width: 186px; }
  .lp-item span { display: none; }
  .wm-measure-panel { width: 162px; padding: 9px 10px; }
  .wm-reverse-tip { max-width: 180px; font-size: 11px; }
}

/* ==================================================================
   作物分布筛选面板
   ================================================================== */

.wm-crop-panel {
  position: absolute;
  right: 18px;
  top: 58px;
  width: 268px;
  max-height: calc(100% - 140px);
  overflow-y: auto;
  padding: 12px 13px 10px;
  border-radius: 12px;
  background: rgba(10, 18, 12, 0.9);
  border: 1px solid rgba(150, 190, 140, 0.2);
  backdrop-filter: blur(10px);
  box-shadow: 0 10px 30px rgba(0, 0, 0, 0.36);
  color: rgba(216, 236, 208, 0.86);
  font-size: 12px;
  z-index: 9;
}

.cp-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
  padding-bottom: 8px;
  border-bottom: 1px solid rgba(150, 190, 140, 0.14);
}
.cp-head b { font-size: 12.5px; color: #d8ecd0; }
.cp-close {
  width: 20px;
  height: 20px;
  border: none;
  border-radius: 5px;
  background: rgba(150, 190, 140, 0.12);
  color: rgba(216, 236, 208, 0.7);
  cursor: pointer;
  font-size: 14px;
  line-height: 1;
}
.cp-close:hover { background: rgba(150, 190, 140, 0.24); }

.cp-sec { margin-bottom: 12px; }
.cp-t {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: 6px;
  font-size: 11.5px;
  color: rgba(200, 224, 190, 0.62);
}
.cp-hint { font-size: 10.5px; color: rgba(200, 224, 190, 0.4); }

.cp-metric { display: flex; gap: 5px; }
.cp-mbtn {
  flex: 1;
  padding: 6px 4px;
  border: 1px solid rgba(150, 190, 140, 0.2);
  border-radius: 7px;
  background: rgba(150, 190, 140, 0.06);
  color: rgba(216, 236, 208, 0.72);
  font-size: 11.5px;
  cursor: pointer;
  transition: all 0.14s;
}
.cp-mbtn:hover { background: rgba(150, 190, 140, 0.14); }
.cp-mbtn.on {
  background: rgba(140, 205, 96, 0.24);
  border-color: rgba(140, 205, 96, 0.5);
  color: #e6f7dc;
}

.cp-crop-groups { max-height: 168px; overflow-y: auto; padding-right: 2px; }
.cp-group { margin-bottom: 7px; }
.cp-gname {
  font-size: 10.5px;
  color: rgba(200, 224, 190, 0.4);
  margin-bottom: 4px;
}
.cp-chips { display: flex; flex-wrap: wrap; gap: 4px; }
.cp-chip {
  padding: 3px 8px;
  border: 1px solid rgba(150, 190, 140, 0.2);
  border-radius: 20px;
  background: rgba(150, 190, 140, 0.05);
  color: rgba(216, 236, 208, 0.66);
  font-size: 11px;
  cursor: pointer;
  transition: all 0.14s;
}
.cp-chip:hover { background: rgba(150, 190, 140, 0.16); }
.cp-chip.on {
  background: rgba(140, 205, 96, 0.28);
  border-color: rgba(140, 205, 96, 0.55);
  color: #eafadc;
}

.cp-acts { display: flex; gap: 10px; margin-top: 6px; }
.cp-link {
  border: none;
  background: none;
  padding: 0;
  color: rgba(140, 205, 96, 0.8);
  font-size: 11px;
  cursor: pointer;
}
.cp-link:hover { text-decoration: underline; }

.cp-range { display: flex; align-items: center; gap: 6px; margin-bottom: 6px; }
.cp-rl { font-size: 11px; color: rgba(200, 224, 190, 0.5); flex: 0 0 auto; }
.cp-range :deep(.el-input-number) { flex: 1; min-width: 0; }
.cp-range :deep(.el-input__inner) { text-align: left; padding: 0 6px; }

.cp-quick { display: flex; gap: 4px; margin-top: 4px; }
.cp-qbtn {
  flex: 1;
  padding: 4px 2px;
  border: 1px solid rgba(150, 190, 140, 0.18);
  border-radius: 6px;
  background: rgba(150, 190, 140, 0.05);
  color: rgba(216, 236, 208, 0.66);
  font-size: 10.5px;
  cursor: pointer;
}
.cp-qbtn:hover { background: rgba(150, 190, 140, 0.16); }

.cp-foot {
  padding-top: 8px;
  border-top: 1px solid rgba(150, 190, 140, 0.14);
  font-size: 10px;
  color: rgba(200, 224, 190, 0.4);
  line-height: 1.5;
}
.cp-src { display: block; }
.cp-note { display: block; margin-top: 2px; color: rgba(212, 160, 50, 0.62); }

/*
 * 面板内的色带图例。
 * 桌面端不显示（地图左下角已有 .wm-legend），窄屏才出现。
 */
.cp-legend { display: none; }
.cp-lgtitle {
  font-size: 11px;
  font-weight: 600;
  color: rgba(216, 236, 208, 0.86);
  margin-bottom: 5px;
}
.cp-lgrow {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 10.5px;
  color: rgba(200, 224, 190, 0.66);
  margin-bottom: 3px;
}
.cp-lgbox {
  width: 12px;
  height: 9px;
  border-radius: 2px;
  display: inline-block;
  flex: 0 0 auto;
}

/* 作物分布图例：数值分级 */
.lg-title {
  font-weight: 600;
  color: rgba(216, 236, 208, 0.9);
  margin-bottom: 6px;
  font-size: 11.5px;
}

/* 768px：筛选面板改成抽屉式，避开右侧看板 */
@media (max-width: 768px) {
  .wm-crop-panel {
    right: 8px;
    top: 52px;
    width: 232px;
    max-height: calc(100% - 110px);
  }
  .cp-crop-groups { max-height: 120px; }
}

/*
 * 400px 以下：筛选面板改成**底部抽屉**。
 *
 * <p>之前用的是「左右撑满 + 绝对定位在顶部」，在 390px 宽的屏上：
 * ① 面板横向占满后正好压在图层面板下面，两者重叠；
 * ② 竖向又只留 52% 高度，地图被遮掉一大半。
 *
 * <p>改成底部抽屉后：面板贴底、宽度自适应、最多占 62% 高度，
 * 上半屏的地图始终看得见；抽屉自带独立滚动，不影响地图手势。
 */
@media (max-width: 480px) {
  .wm-crop-panel {
    right: 0;
    left: 0;
    bottom: 0;
    top: auto;
    width: auto;
    max-height: 62%;
    border-radius: 14px 14px 0 0;
    border-bottom: none;
    padding: 10px 12px calc(12px + env(safe-area-inset-bottom, 0px));
  }
  .cp-crop-groups { max-height: 108px; }
  /* 窄屏没有地图图例，色带挪进面板 */
  .cp-legend { display: block; margin-bottom: 10px; }
  /* 抽屉把手：暗示可以从底部收起 */
  .cp-head::before {
    content: '';
    position: absolute;
    top: 5px;
    left: 50%;
    transform: translateX(-50%);
    width: 34px;
    height: 3px;
    border-radius: 2px;
    background: rgba(150, 190, 140, 0.3);
  }
  .cp-head { position: relative; padding-top: 8px; }
}
</style>
