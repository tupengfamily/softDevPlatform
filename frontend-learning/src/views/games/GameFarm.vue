<template>
  <FieldSetup
    v-if="setupVisible"
    :initial-zone="targetFarmZone || state?.plot?.zoneId || ''"
    :initial-crop="state?.plot?.cropId || 'rice_mid'"
    :has-field="setupReplacesField"
    :slot="effectiveSetupSlot"
    :farm-id="pendingFarmId || currentFarmId"
    :farm-name="targetFarmName"
    :farm-zone="targetFarmZone"
    :today="userWorldDate || state?.worldDate || ''"
    @created="onFieldCreated"
    @cancel="onSetupCancel"
    @need-shop="onNeedShop"
  />

  <div v-else class="fm-page" :class="`season-${seasonBg.season}`">
    <!-- ================= 左：3D 农田（铺满剩余宽度） ================= -->
    <section class="fm-stage" :class="{ frosted: state && state.crop.frostStop }">
      <!-- Three.js 场景 -->
      <FarmScene3D
        v-if="state && !sceneFailed"
        :key="sceneKey"
        :stage="dvsStage"
        :crop="cropVisual"
        :season="seasonBg.season"
        :weather="state.weather"
        :water="cropVisual === 'rice' && state.soil ? state.soil.waterMm : 0"
        :cells="cells"
        :plots="plots"
        :slot="currentSlot"
        :view="sceneView"
        @fail="sceneFailed = true"
        @select-plot="selectPlot"
        @create-plot="onSceneCreatePlot"
        @fps="onSceneFps"
        @auto-downgrade="onSceneAutoDowngrade"
        @quality-changed="onSceneQualityChanged"
        @rebuild-scene="onSceneRebuild"
      />

      <!-- WebGL 不可用时的 SVG 退化田地（结构与旧版一致） -->
      <div v-if="sceneFailed" class="fm-fallback">
        <div class="season-bg" :style="{ background: seasonBg.sky }"></div>
        <div class="horizon">
          <svg class="mountains" viewBox="0 0 800 200" preserveAspectRatio="none">
            <path
              d="M 0 200 L 0 110 Q 80 80 130 95 Q 180 60 230 80 Q 280 50 330 70 Q 380 30 430 60 Q 480 40 530 65 Q 580 35 630 55 Q 680 45 730 70 Q 780 60 800 80 L 800 200 Z"
              :fill="mountainColor"
            />
          </svg>
          <div class="trees">
            <svg v-for="(t, i) in trees" :key="'t' + i" class="tree" :style="t.style" viewBox="0 0 30 60">
              <ellipse cx="15" cy="22" rx="14" ry="20" :fill="t.color" />
              <rect x="13" y="40" width="4" height="18" fill="#5a3a1a" />
            </svg>
          </div>
          <div class="skyline"></div>
        </div>
        <div class="field-surface">
          <div class="irrigation-channel left"></div>
          <div class="irrigation-channel right"></div>
          <div class="ridge"></div>
        </div>
        <div class="plotgrid">
          <PlantCell
            v-for="c in cells"
            :key="c.key"
            :stage="c.stage"
            :heat-wave="c.heatWave"
            :frost="c.frost"
            :rain="c.rain"
            :waterlogged="c.waterlogged"
            :dry="c.dry"
            :style="cellStyle(c)"
          />
        </div>
      </div>

      <!-- 顶部浮层：标题 + 实时指标 -->
      <header class="fm-head">
        <a class="back-link" @click="gotoCenter">
          <span class="back-icon">←</span><span class="back-txt">{{ $t('farm.back') }}</span>
        </a>
        <h2 class="title">{{ $t('farm.title') }}</h2>
        <span class="head-sep"></span>
        <!-- 🔴 2026-10-23：农场标识放在**前面**。
             原来它排在最后，头部空间不够时被 flex 挤成 20px
             （文字全被 ellipsis 吃掉，看上去像没有农场名）。
             「当前在哪个农场」是身份信息，优先级高于 DVS / 季数这类数值，
             必须排在前面才不会被挤没。 -->
        <span v-if="farmLabel" class="hchip farm-chip" :title="farmName">{{ farmLabel }}</span>
        <span v-if="state" class="hchip">
          <b>DVS</b> {{ state.crop.dvs.toFixed(2) }}
        </span>
        <!-- 🔴 2026-10-27：显示成熟阈值 + 还差多少 —— 讲清「为什么还不能收」 -->
        <span
          v-if="state && typeof state.crop.harvestDvs === 'number'"
          class="hchip ripe-chip"
          :class="{ ready: state.crop.harvestable }"
          :title="state.crop.harvestable
            ? $t('farm.ripeAt', { dvs: state.crop.harvestDvs.toFixed(2) })
            : $t('farm.notRipeYet', { gap: ripeGap.toFixed(2) })"
        >
          {{ $t('farm.ripeAt', { dvs: state.crop.harvestDvs.toFixed(2) }) }}
        </span>
        <span v-if="state" class="hchip">{{ state.crop.stage }}</span>
        <span v-if="state" class="hchip">第 {{ state.seasons }} 季</span>
        <span v-if="state && state.plot" class="hchip plot-chip" @click="rebuildPlot">
          <b>{{ state.plot.name }}</b>
          <em class="plot-crop">{{ state.plot.cropName }}</em>
          <i class="plot-lv" :class="'l-'+state.plot.level">{{ state.plot.levelName }}</i>
        </span>
        <span
          class="hchip board-chip"
          :class="{ on: sceneView === 'map' }"
          @click="toggleMap"
        >{{ sceneView === 'map' ? '回到田地' : '俯瞰' }}</span>
        <span class="hchip board-chip" @click="worldMapOpen = true">中国地图</span>
        <span class="hchip board-chip" @click="boardOpen = true">
          看板{{ plots.length ? ' ' + plots.length : '' }}
        </span>
        <!-- S24 仓库入口：临期时红点提醒 -->
        <span
          class="hchip board-chip wh-chip"
          :class="{ 'has-warn': warehouseWarn > 0 }"
          @click="openWarehouse"
        >
          仓库{{ warehouseCount ? ' ' + warehouseCount : '' }}
          <i v-if="warehouseWarn > 0" class="wh-dot">{{ warehouseWarn }}</i>
        </span>
        <!-- S26 成就 / 图鉴入口 -->
        <span class="hchip board-chip" @click="openAchievement">
          {{ $t('farm.ach.title') }}
        </span>
        <!-- 🔴 画质设置入口（2026-10-26 画质分级）：
             用户可自设画质档位，并看到实测帧率 -->
        <span class="hchip board-chip" @click="qualityOpen = true">
          {{ $t('farm.quality.short') }}
          <i v-if="sceneFps > 0" class="wq-fps">{{ sceneFps }}</i>
        </span>
      </header>

      <!-- 左下角：天气条 + 地块切换条（同一列 flex 堆叠，见 .fm-bottom-stack）
           🔴 2026-10-22：原先两者各自 position:absolute + bottom 定位，
           窄屏下**完全重叠**（天气数值压在 chip 上），桌面端也压掉 4px。
           改成同一容器内上下排列，高度由内容决定，永远不会打架。 -->
      <div v-if="state" class="fm-bottom-stack">
        <div class="fm-weatherbar">
          <span class="wx-icon" :class="'wx-' + weatherFx.kind">{{ weatherIcon }}</span>
          <span class="wb-strong">{{ state.weather.tmax.toFixed(0) }}° / {{ state.weather.tmin.toFixed(0) }}°C</span>
          <span class="wb-item">💧 {{ state.weather.precip.toFixed(1) }} mm</span>
          <span class="wb-item" :class="{ hot: state.weather.heatTier >= 2 }">
            {{ $t('farm.heatTier.' + state.weather.heatTier) }}
          </span>
          <span class="wb-sep"></span>
          <span class="wb-item muted">{{ state.weatherSource }}</span>
        </div>

        <!-- 地块切换条：只列**当前农场**的地块，横向滚动
             （2026-10-22：此前列的是该用户全部地块，进入一个农场后
               切换条上仍挤着其它农场与 v1 遗留老田的地） -->
        <div v-if="farmPlots.length" class="fm-plotrail-wrap">
          <button
            class="pr-nav pr-nav-l"
            :class="{ off: railScroll.left <= 1 }"
            aria-label="向左滚动"
            @click="railScrollBy(-1)"
          >‹</button>
          <div ref="plotRailEl" class="fm-plotrail" @scroll="onRailScroll">
            <button
              v-for="p in farmPlots"
              :key="p.slot"
              :class="['pr-chip', 'pr-chip-' + p.slot, { on: p.slot === currentSlot, empty: !p.chosen }]"
              :title="p.fieldName || ('第 ' + (p.slot + 1) + ' 号田')"
              @click="selectPlot(p.slot)"
            >
              <b>{{ p.slot + 1 }}</b>
              <span class="pr-crop">{{ shortCrop(p.cropName) }}</span>
              <i class="pr-stage">{{ p.stage }}</i>
            </button>
            <button
              class="pr-chip pr-add"
              :class="{ full: farmPlots.length >= MAX_PLOTS_PER_FARM }"
              @click="newPlot"
            >＋</button>
          </div>
          <button
            class="pr-nav pr-nav-r"
            :class="{ off: railScroll.right <= 1 }"
            aria-label="向右滚动"
            @click="railScrollBy(1)"
          >›</button>
          <!-- 溢出提示：右侧渐隐 + "当前是第几块/共几块"计数 + 所属农场 -->
          <span class="pr-count mono">{{ plotIndexLabel }}</span>
          <span class="pr-farm mono" :title="farmName">{{ farmLabel }}</span>
        </div>
      </div>

      <!-- 🔴 4 个告警浮层统一加 `.fm-scene-alert`（统一定位 + pointer-events:none），
           避免遮住顶栏 chips（画质 / 成就 / 仓库）。定位规则见样式区注释。 -->
      <!-- 霜冻终止：本季已经结束，只能重开 -->
      <div v-if="state && state.crop.frostStop && !state.crop.harvestable" class="fm-scene-alert fm-frost-alert">
        <span class="fa-icon">❄️</span>
        <span>{{ $t('farm.frostStop', { dvs: state.crop.dvs.toFixed(2) }) }}</span>
        <el-button size="small" type="primary" link @click="restart">{{ $t('farm.restart') }}</el-button>
      </div>

      <!-- 苗期枯死：本季绝收，且永远到不了黄熟期，必须能「记为绝收」把这一季结掉 -->
      <div v-if="state && state.crop.withered" class="fm-scene-alert fm-wither-alert">
        <span class="wa-icon">🥀</span>
        <span class="wa-text">
          苗期枯死：{{ state.crop.failureNote || '本季已绝收' }}
        </span>
        <el-button size="small" type="warning" link @click="closeFailed">记为绝收</el-button>
      </div>

      <!-- 出苗不全：群体偏小，产量会相应打折 -->
      <div
        v-else-if="state && state.crop.germination != null && state.crop.germination < 0.85 && state.plot.chosen"
        class="fm-scene-alert fm-germ-alert"
      >
        <span class="ga-icon">🌱</span>
        <span class="ga-text">
          出苗率 {{ Math.round(state.crop.germination * 100) }}%（缺苗
          {{ Math.round((1 - state.crop.germination) * 100) }}%），群体偏小，产量会相应打折
        </span>
      </div>

      <!-- 病虫害告警（只在已显症且达到偏重时出现） -->
      <div v-if="pestAlert" class="fm-scene-alert fm-pest-alert">
        <span class="pa-icon">🐛</span>{{ pestAlert }}
      </div>

      <!-- 右下角交互提示 -->
      <div v-if="state && !sceneFailed" class="fm-hint">
        <span>拖拽旋转</span><i>·</i><span>滚轮缩放</span>
      </div>

      <div v-if="!state" class="fm-loading">
        <el-skeleton :rows="6" animated />
      </div>
    </section>

    <!-- ================= 右：数据与操作面板 ================= -->
    <aside class="fm-right">
      <div v-if="!state" class="fm-loading-r">
        <el-skeleton :rows="8" animated />
      </div>
      <template v-else>
        <!-- 固定操作头：随时可推进/收割，不随内容滚走 -->
        <header class="fb-head">
          <div class="fb-row1">
            <span class="fb-date mono">{{ state.worldDate }}</span>
            <span class="fb-stage">{{ state.crop.stage }}</span>
            <span class="fb-dvs mono">DVS {{ state.crop.dvs.toFixed(2) }}</span>
            <span class="fb-season">第 {{ state.seasons }} 季</span>
            <span v-if="state.demoMode" class="fb-demo" @click="demoInfoOpen = !demoInfoOpen">
              演示模式 {{ demoInfoOpen ? '▴' : '▾' }}
            </span>
          </div>
          <div class="fb-row2">
            <el-button-group v-if="state.demoMode">
              <el-button :disabled="busy" @click="advance(1)">+1 天</el-button>
              <el-button :disabled="busy" @click="advance(7)">+7 天</el-button>
              <el-button :disabled="busy" @click="advance(30)">+30 天</el-button>
            </el-button-group>
            <el-button
              type="primary"
              class="fb-harvest"
              :disabled="busy || !state.crop.harvestable"
              @click="harvest"
            >
              {{ $t('farm.harvest') }}
            </el-button>
            <span class="grow"></span>
            <el-dropdown trigger="click" @command="onMenu">
              <el-button text class="fb-more">更多 ▾</el-button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="restart">{{ $t('farm.restart') }}</el-dropdown-item>
                  <el-dropdown-item command="rules">{{ $t('farm.rules') }}</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </div>
          <p v-if="demoInfoOpen" class="fb-demo-note">
            <b>{{ $t('farm.demoTitle') }}</b>{{ $t('farm.demoDesc') }}
          </p>
        </header>

        <div class="fb-scroll">
          <!-- 四项关键指标 -->
          <section class="fb-metrics">
            <div class="metric">
              <span class="m-lab">{{ $t('farm.weather') }}</span>
              <span class="m-val mono">{{ state.weather.tmax.toFixed(1) }}°<i>/</i>{{ state.weather.tmin.toFixed(1) }}°</span>
              <span class="m-sub">💧 {{ state.weather.precip.toFixed(1) }} mm</span>
            </div>
            <div class="metric" :class="{ danger: state.weather.heatTier >= 2 }">
              <span class="m-lab">{{ $t('farm.heat') }}</span>
              <span class="m-val">{{ heatLabel }}</span>
              <span class="m-sub">{{ $t('farm.weatherSourceShort') }}</span>
            </div>
            <div class="metric">
              <span class="m-lab">{{ $t('farm.aat') }}</span>
              <span class="m-val mono">{{ Math.round(state.crop.aatCum) }}</span>
              <span class="m-sub">/ {{ Math.round(state.crop.aatReq) }} ℃·d</span>
            </div>
            <div class="metric">
              <span class="m-lab">{{ $t('farm.estimate') }}</span>
              <span class="m-val mono strong">{{ state.yield.estimatedYieldKgPerMu.toFixed(0) }}</span>
              <span class="m-sub">{{ $t('farm.unit.perMu') }}</span>
            </div>
          </section>

          <!-- 本季投入（S12） -->
          <section v-if="state.inputSummary" class="fb-input-strip">
            <div class="is-cell">
              <span class="is-lab">{{ $t('farm.input.cost') }}</span>
              <span class="is-val mono">{{ state.inputSummary.cost.toFixed(0) }}</span>
              <span class="is-unit">{{ $t('farm.yuan') }}/{{ $t('farm.mu') }}</span>
            </div>
            <div class="is-cell">
              <span class="is-lab">{{ $t('farm.input.n') }}</span>
              <span class="is-val mono">
                {{ state.inputSummary.nApplied.toFixed(1) }}<i>/{{ state.inputSummary.nNeed }}</i>
              </span>
              <span class="is-unit">kg/{{ $t('farm.mu') }}</span>
            </div>
            <div class="is-cell">
              <span class="is-lab">{{ $t('farm.input.net') }}</span>
              <span class="is-val mono" :class="{ bad: state.inputSummary.net < 0 }">
                {{ state.inputSummary.net.toFixed(0) }}
              </span>
              <span class="is-unit">{{ $t('farm.yuan') }}</span>
            </div>
            <el-button size="small" class="is-btn" @click="inputOpen = true">
              {{ $t('farm.input.open') }}
            </el-button>
          </section>
          <p v-if="phiWarn" class="fb-phi-warn">⚠️ {{ phiWarn }}</p>

          <!-- 生育进程（时间轴） -->
          <section class="fb-card fb-crop">
            <div class="fc-head">
              <h3>{{ state.crop.cropName }}</h3>
              <span class="muted">{{ $t('farm.sowDate') }} {{ state.crop.sowDate }}</span>
            </div>

            <div class="tl">
              <div class="tl-track">
                <span
                  v-for="(t, i) in timeline"
                  :key="i"
                  class="tl-seg"
                  :class="{ done: t.done, active: t.active }"
                  :style="{ left: t.from * 100 + '%', width: (t.to - t.from) * 100 + '%' }"
                ></span>
                <span class="tl-dot" :style="{ left: Math.min(100, state.crop.dvs * 100) + '%' }"></span>
              </div>
              <div class="tl-labels">
                <span
                  v-for="(t, i) in timeline"
                  :key="i"
                  :class="{ on: t.active, low: i % 2 === 1 }"
                  :style="{ left: ((t.from + t.to) / 2) * 100 + '%' }"
                >{{ t.label }}</span>
              </div>
            </div>

            <div class="tl-meta">
              <span class="mono">DVS {{ state.crop.dvs.toFixed(3) }}</span>
              <span class="muted">
                {{ $t('farm.toNext', { name: state.crop.nextMilestone, days: state.crop.daysToNextMilestone }) }}
              </span>
            </div>

            <div class="fc-kv">
              <div>
                <dt>{{ $t('farm.heading') }}</dt>
                <dd class="mono">{{ state.crop.headingDate || '—' }}</dd>
              </div>
              <div>
                <dt>{{ $t('farm.mature') }}</dt>
                <dd class="mono">{{ state.crop.matureDate || '—' }}</dd>
              </div>
              <div>
                <dt>{{ $t('farm.seasons') }}</dt>
                <dd class="mono">{{ state.seasons }}</dd>
              </div>
            </div>

            <details class="fb-details">
              <summary>{{ $t('farm.yieldBreakdown') }}</summary>
              <table class="ytable">
                <thead>
                  <tr>
                    <th>{{ $t('farm.col.item') }}</th>
                    <th>{{ $t('farm.col.value') }}</th>
                    <th>{{ $t('farm.col.loss') }}</th>
                    <th>{{ $t('farm.col.from') }}</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="r in yieldRows" :key="r.name">
                    <td>{{ r.name }}</td>
                    <td class="mono">{{ r.value }}</td>
                    <td class="mono" :class="{ bad: r.dmg > 0.005 }">
                      {{ r.dmg > 0.001 ? '−' + (r.dmg * 100).toFixed(1) + '%' : '—' }}
                    </td>
                    <td class="muted">{{ r.from }}</td>
                  </tr>
                </tbody>
              </table>
              <p class="note">{{ $t('farm.note') }}</p>
            </details>
          </section>

          <!-- 土壤水分与地力（S6） -->
          <section v-if="state.soil" class="fb-card fb-soil">
            <div class="panel-head">
              <h3>🌾 {{ $t('farm.soilTitle') }}</h3>
              <span class="soil-status" :class="'s-' + state.soil.status">
                {{ $t('farm.soilStatus.' + state.soil.status) }}
              </span>
            </div>
            <div class="water-gauge">
              <span v-for="n in 5" :key="n" :class="{ on: n <= waterLevel }"></span>
            </div>
            <div class="soil-kv">
              <div>
                <dt>{{ $t('farm.soilLayer') }}</dt>
                <dd class="mono">{{ state.soil.waterMm.toFixed(0) }} mm</dd>
              </div>
              <div>
                <dt>{{ $t('farm.soilSfi') }}</dt>
                <dd class="mono">{{ Math.round(state.soil.sfi) }}<i>/100</i></dd>
              </div>
              <div>
                <dt>{{ $t('farm.soilEt') }}</dt>
                <dd class="mono">{{ state.soil.etToday.toFixed(1) }} mm</dd>
              </div>
            </div>
            <p class="soil-tip">{{ soilTip }}</p>
          </section>

          <!-- 科学解释层（2026-10-27 P1）：有效积温 / 氮素 / 产量四要素 / 水分平衡 -->
          <section v-if="state.insights && state.insights.length" class="fb-card fb-insight">
            <div class="panel-head">
              <h3>🔬 {{ $t('farm.insight.title') }}</h3>
              <span class="insight-count mono muted">{{ state.insights.length }}</span>
            </div>
            <FarmInsightPanel :insights="state.insights" />
          </section>

          <!-- 水稻工序链（S10） -->
          <section class="fb-card fm-chain">
            <div class="chain-head">
              <h3>🌱 {{ $t('farm.chainTitle') }}</h3>
              <span class="chain-progress">{{ chainDone }}/{{ chainTotal }}</span>
            </div>
            <div v-for="g in chainGroups" :key="g.key" class="chain-group">
              <div class="chain-group-title">{{ $t('farm.opGroup.' + g.key) }}</div>
              <ul class="chain-list">
                <li v-for="s in g.steps" :key="s.id" :class="'op-' + s.status">
                  <span class="op-dot"></span>
                  <div class="op-main">
                    <div class="op-row">
                      <span class="op-name">{{ $t('farm.op.' + s.id) }}</span>
                      <span v-if="s.status === 'DONE' && !s.repeatable && s.quality > 0" class="op-stars">{{ stars(s.quality) }}</span>
                      <span v-if="s.repeatable && s.count > 0" class="op-count">×{{ s.count }}</span>
                      <span v-if="s.date" class="op-date mono">{{ s.date.slice(5) }}</span>
                    </div>
                    <div class="op-hint">{{ opHint(s) }}</div>
                  </div>
                  <el-button
                    v-if="s.status === 'AVAILABLE'"
                    size="small"
                    :type="s.id === 'HARVEST' ? 'primary' : 'default'"
                    :disabled="busy"
                    @click="onOp(s)"
                  >
                    {{ s.id === 'HARVEST' ? $t('farm.harvest') : $t('farm.doOp') }}
                  </el-button>
                </li>
              </ul>
            </div>
          </section>

          <!-- 病虫害 -->
          <section class="fb-card fm-pests">
            <div class="pest-head">
              <h3>🐛 {{ $t('farm.pestTitle') }}</h3>
              <span v-if="state.pestLossPct > 0" class="pest-loss">
                {{ $t('farm.pestLoss', { p: state.pestLossPct.toFixed(1) }) }}
              </span>
              <span class="grow"></span>
              <el-button size="small" type="warning" plain :disabled="busy" @click="inputOpen = true">
                {{ $t('farm.input.open') }}
              </el-button>
            </div>
            <ul v-if="visiblePests.length" class="pest-list">
              <li v-for="p in visiblePests" :key="p.id" :class="'st-' + p.status">
                <div class="pest-row">
                  <span class="pest-name">{{ pestName(p) }}</span>
                  <span class="pest-type">{{ $t('farm.pestType.' + p.type) }}</span>
                  <span class="pest-status">{{ $t('farm.pestStatus.' + p.status) }}</span>
                  <span class="pest-di">DI {{ Math.round(p.severity * p.incidence * 100) }}</span>
                </div>
                <div class="pest-bar"><i :style="{ width: Math.min(100, p.severity * 100) + '%' }"></i></div>
                <p class="pest-advice">{{ $t('farm.pestAdvice.' + p.advice) }}</p>
              </li>
            </ul>
            <p v-else class="muted pest-empty">{{ $t('farm.pestEmpty') }}</p>
            <p class="pest-tip muted">{{ $t('farm.pestTip') }}</p>
          </section>

          <!-- 灾害记录 -->
          <section class="fb-card fm-events">
            <div class="ev-head">
              <h3>🌩 {{ $t('farm.eventTitle') }}</h3>
              <span v-if="state.disasterLossPct > 0" class="ev-loss">
                {{ $t('farm.eventLoss', { p: state.disasterLossPct.toFixed(1) }) }}
              </span>
            </div>
            <ul v-if="state.events && state.events.length" class="ev-list">
              <li v-for="(e, i) in state.events" :key="i" :class="'ev-' + e.type">
                <div class="ev-row">
                  <span class="ev-date mono">{{ e.date }}</span>
                  <span class="ev-name">{{ $t('farm.eventType.' + e.type) }}</span>
                  <span class="ev-pct" :class="{ muted: e.loss <= 0 }">
                    {{ e.loss > 0 ? '−' + (e.loss * 100).toFixed(0) + '%' : '—' }}
                  </span>
                </div>
                <p class="ev-note">{{ e.note }}</p>
              </li>
            </ul>
            <p v-else class="muted ev-empty">{{ $t('farm.eventEmpty') }}</p>
          </section>

          <!-- 今日变化 -->
          <section class="fb-card">
            <div class="panel-head">
              <h3>{{ $t('farm.today') }}</h3>
            </div>
            <ul class="changes">
              <li v-for="(c, i) in state.todayChanges" :key="i">{{ c }}</li>
            </ul>
          </section>

          <!-- 历史成绩 + 农事记录 -->
          <div class="fb-grid2">
            <section class="fb-card">
              <div class="panel-head">
                <h3>{{ $t('farm.recent') }}</h3>
                <span class="muted">{{ $t('farm.best') }} {{ state.bestYield }}</span>
              </div>
              <!-- S26：最近几局 / 全服排行榜 两个标签页（分数口径 = 单产 kg/亩） -->
              <el-tabs v-model="scoreTab" class="score-tabs" @tab-change="(t) => t === 'board' && loadLeaderboard()">
                <el-tab-pane :label="$t('farm.scoreMine')" name="recent">
                  <ul v-if="state.recentScores && state.recentScores.length" class="recent">
                    <li v-for="(s, i) in state.recentScores" :key="i">
                      <span class="mono score">{{ s.score }}</span>
                      <span class="muted">{{ fmtTime(s.createTime) }}</span>
                    </li>
                  </ul>
                  <p v-else class="muted empty">{{ $t('farm.noRecord') }}</p>
                </el-tab-pane>

                <el-tab-pane :label="$t('game.leaderboard')" name="board">
                  <div v-if="lbLoading" class="muted empty">{{ $t('common.loading') }}</div>
                  <div v-else-if="!leaderboard.length" class="muted empty">{{ $t('game.noRecord') }}</div>
                  <ul v-else class="lb-list">
                    <li v-for="r in leaderboard" :key="r.userId" class="lb" :class="{ me: r.isMe }">
                      <span class="rank" :class="`r${r.rank <= 3 ? r.rank : 'n'}`">{{ r.rank }}</span>
                      <span class="who" :title="r.username">{{ r.displayName }}</span>
                      <span class="sc mono">{{ r.bestScore }}</span>
                    </li>
                  </ul>
                  <p class="lb-foot muted">{{ $t('farm.scoreUnitNote') }}</p>
                </el-tab-pane>
              </el-tabs>
            </section>

            <section v-if="actions.length" class="fb-card">
              <div class="panel-head">
                <h3>{{ $t('farm.actionHistory') }}</h3>
                <span class="muted">{{ actions.length }}</span>
              </div>
              <ul class="history">
                <li v-for="a in actions.slice(0, 6)" :key="a.id">
                  <span class="mono hd">{{ a.date.slice(5) }}</span>
                  <span class="action-tag">{{ $t('farm.action.' + a.type) }}</span>
                  <span v-if="a.durationDays > 1" class="muted">{{ a.durationDays }}d</span>
                </li>
              </ul>
            </section>
          </div>

          <!-- 测试期天气注入 -->
          <details v-if="state.demoMode" class="fb-card fb-mock">
            <summary>🧪 {{ $t('farm.mockTitle') }}</summary>
            <p class="muted mock-desc">{{ $t('farm.mockDesc') }}</p>
            <div class="mock-presets">
              <el-button size="small" :disabled="busy" @click="applyPreset('heat')">
                {{ $t('farm.mockPreset.heat') }}
              </el-button>
              <el-button size="small" :disabled="busy" @click="applyPreset('storm')">
                {{ $t('farm.mockPreset.storm') }}
              </el-button>
              <el-button size="small" :disabled="busy" @click="applyPreset('cold')">
                {{ $t('farm.mockPreset.cold') }}
              </el-button>
              <el-button size="small" type="danger" plain :disabled="busy || !mocks.length" @click="resetMocks">
                {{ $t('farm.mockReset') }}
              </el-button>
            </div>
            <ul v-if="mocks.length" class="mock-list">
              <li v-for="m in mocks" :key="m.id">
                <span class="mono">{{ m.fromDate }} → {{ m.toDate }}</span>
                <span class="mono">{{ m.tmax }}°/{{ m.tmin }}°</span>
                <span class="mono">💧{{ m.precip }}mm</span>
              </li>
            </ul>
            <p v-else class="muted mock-empty">{{ $t('farm.mockEmpty') }}</p>
          </details>
        </div>
      </template>
    </aside>

    <el-dialog v-model="rulesVisible" :title="$t('farm.rules')" width="620px" append-to-body>
      <div class="rules">
        <p v-for="(r, i) in rules" :key="i" v-html="sanitizeHtml(r)"></p>
      </div>
    </el-dialog>
  </div>
  <FarmWorldMap
    v-if="worldMapOpen && !setupVisible"
    :warehouse-warn="warehouseWarn"
    :shop-short="shopShort"
    @close="worldMapOpen = false"
    @state-changed="load"
    @enter-plot="onWorldEnterPlot"
    @open-inputs="onWorldOpenInputs"
    @open-warehouse="openWarehouse"
    @open-shop="openShop"
    @open-achievement="openAchievement"
  />

  <FarmBoard
    v-if="boardOpen"
    :busy="busy"
    @close="boardOpen = false"
    @select="onBoardSelect"
    @action="onBoardAction"
    @harvest="onBoardHarvest"
  />

  <!-- S24 仓库：收获物入库 → 变卖 -->
  <FarmWarehouse
    v-if="warehouseOpen"
    ref="warehouseRef"
    :busy="busy"
    @close="closeWarehouse"
    @sold="onWarehouseSold"
  />

  <!-- 🔴 需求 R 商店：买种子 / 农具 / 农资 / 水资源 -->
  <FarmShop
    v-if="shopOpen"
    :busy="busy"
    @close="closeShop"
    @bought="onShopBought"
  />

  <!-- S26 成就 / 目标 / 作物图鉴 -->
  <FarmAchievement v-if="achievementOpen" @close="achievementOpen = false" />

  <!-- 🔴 画质设置面板（2026-10-26 画质分级） -->
  <FarmQuality
    v-if="qualityOpen"
    :fps="sceneFps"
    @close="qualityOpen = false"
    @changed="onQualityChanged"
  />

  <InputDrawer
    v-if="state"
    v-model="inputOpen"
    :inputs="state.inputs || []"
    :busy="busy"
    :slot="currentSlot"
    @applied="load"
  />
</template>

<script setup>
import { computed, nextTick, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import PlantCell from '../../components/farm/PlantCell.vue'
import FarmScene3D from '../../components/farm/FarmScene3D.vue'
import FieldSetup from '../../components/farm/FieldSetup.vue'
import InputDrawer from '../../components/farm/InputDrawer.vue'
import FarmBoard from '../../components/farm/FarmBoard.vue'
import FarmWarehouse from '../../components/farm/FarmWarehouse.vue'
import FarmShop from '../../components/farm/FarmShop.vue'
import FarmAchievement from '../../components/farm/FarmAchievement.vue'
import FarmQuality from '../../components/farm/FarmQuality.vue'
import FarmWorldMap from '../../components/farm/FarmWorldMap.vue'
import FarmInsightPanel from '../../components/farm/FarmInsightPanel.vue'
import { phenoStageKeyOf } from '../../components/farm/scene/config.js'
import { sanitizeHtml } from '../../utils/markdown'
import { getLeaderboard } from '../../api/game'
import { readFarmContext, writeFarmContext, clearFarmContext } from '../../utils/farmContext'
import { useNotificationStore } from '../../store/notification'
import {
  getFarmState, listFarmPlots, advanceFarm, harvestFarm, restartFarm,
  mockFarmWeather, listFarmMockWeather, resetFarmMockWeather,
  performFarmAction, listFarmActions, closeFailedFarmSeason,
  getFarmWarehouse, getFarmBoard, getFarmShop
} from '../../api/farm'

const router = useRouter()
const { t } = useI18n()
/** 消息中心 store（S24：仓库临期通知要同步刷新铃铛未读数） */
const notifStore = useNotificationStore()

const state = ref(null)
/**
 * 农场排行榜（S26）：复用游戏中心的 {@code t_game_score}。
 *
 * <p>后端早就把每季收割的成绩写进 {@code t_game_score}（{@code game_code='farm'}），
 * {@code GameService.leaderboard()} 也是全 gameCode 参数化的 —— 缺的只是前端一块 UI。
 *
 * <p>🔴 <b>成绩口径</b>：{@code score = 单产(kg/亩) × 农残折价}（不是收入）。
 * 这是有意为之：跨作物「元/亩」不可比（草莓 ¥30240/亩 vs 谷子 ¥424/亩），
 * 而「kg/亩」能直接反映管理水平。所以排行榜展示时<b>不能标「元」</b>。
 */
const leaderboard = ref([])
const lbLoading = ref(false)
/** 「历史成绩」卡片里的标签页：recent=我的最近几局 / board=全服排行 */
const scoreTab = ref('recent')

async function loadLeaderboard() {
  if (lbLoading.value) return
  lbLoading.value = true
  try {
    const res = await getLeaderboard('farm', 20)
    leaderboard.value = (res && res.data) || []
  } catch (e) {
    // 排行榜是锦上添花，取不到不能影响主流程
    leaderboard.value = []
  } finally {
    lbLoading.value = false
  }
}
/**
 * 🔴 S25：用户级世界日期（YYYY-MM-DD）——「今天」的统一定义。
 *
 * <p>「不能选今天之前」这个约束必须是全局一致的，否则会出现：
 * <ul>
 *   <li>玩家在「落后」的地块上打开选地面板 → 能选到对全局来说已经过去的日子</li>
 *   <li>新建地块时后端按 `userWorldDate` 校验，前端却按单地块日期放行 → 两端口径打架</li>
 * </ul>
 *
 * <p>🔴 2026-10-26：后端已把用户级时钟提到 {@code t_farm_user_clock} 表，
 * 世界日期 = 现实今天 + 用户推进量，<b>不再受任何单块地偏移影响</b>
 * （原来取「所有地块里最靠后的那个」，一个无关地块的残留偏移就能污染全用户）。
 * 后端 `/board` 的 `today` 就是这个用户级世界日期。
 */
const userWorldDate = ref('')
const busy = ref(false)
const rulesVisible = ref(false)
const mocks = ref([])
const actions = ref([])
const sceneFailed = ref(false)
const demoInfoOpen = ref(false)
const showSetup = ref(false)
const inputOpen = ref(false)
/** 当前选中的地块槽位（0..99） */
const currentSlot = ref(0)
/** 全部地块摘要（多田切换用） */
const plots = ref([])
/** 建田目标槽位：-1 = 新建一块地，≥0 = 在指定地块上重建 */
const setupSlot = ref(-1)
/** 用户级地块总数上限（后端 FarmPlayService.MAX_PLOTS） */
const MAX_PLOTS = 100
/** 单个农场的地块数上限（后端 FarmPlayService.MAX_PLOTS_PER_FARM，2026-10-22） */
const MAX_PLOTS_PER_FARM = 12
const boardOpen = ref(false)
/** S24 仓库面板开关 */
const warehouseOpen = ref(false)
/** 仓库组件实例（变卖后要刷新顶部角标） */
const warehouseRef = ref(null)
/** 仓库里的批次数（顶部 chip 显示） */
const warehouseCount = ref(0)
/** 仓库里临期的批次数（顶部红点提醒） */
const warehouseWarn = ref(0)

/** S26 成就 / 图鉴面板开关 */
const achievementOpen = ref(false)

/**
 * 🔴 **画质设置面板开关**（2026-10-26 画质分级）
 */
const qualityOpen = ref(false)
/**
 * 3D 场景上报的**实测帧率**（0 = 还没测到）。
 *
 * ⚠️ 必须是实测值，不能拿「理论帧率」冒充 ——
 *   用户要靠这个数判断当前档位是否合适。
 */
const sceneFps = ref(0)
/**
 * 3D 场景的重建令牌（画质切换时 +1 → 触发 :key 变化 → 重挂载组件）。
 *
 * ⚠️ 与 cropVisual 合并成 sceneKey，见 sceneKey 的定义处说明。
 */
const sceneRebuildToken = ref(0)

/** 🔴 需求 R：商店面板是否打开 */
const shopOpen = ref(false)
/** 商店缺货提醒条数（地图「商店」按钮上的红点） */
const shopShort = ref(0)
/** 钱包余额（顶部显示） */
const shopBalance = ref(0)
/**
 * 中国地图（区县选点建农场）——进入游戏先看地图概览，符合「先看全局再下地」的操作顺序。
 *
 * <p>🔴 2026-10-23 需求「刷新保持当前画面」：
 * 首次进入游戏 → 打开地图（默认行为不变）；
 * 刷新页面 → 恢复上次的位置（地块 + 农场），<b>地图不自动弹出</b>，
 * 否则玩家刚刷新就被地图糊一脸，等于没保持住。
 */
const worldMapOpen = ref(true)
/** 从地图「进入农场」时指定的归属农场：新开地块要挂在它下面 */
const pendingFarmId = ref(null)
/**
 * 进选地面板**之前**的农场上下文（2026-10-23）。
 *
 * <p>用途：玩家从地图进了一个空农场的建田页，又点了「返回」——
 * 必须把农场上下文还原成他进来之前的样子，
 * 否则顶部标识写着「格尔木市农场」而 3D 场景里是别家的地。
 *
 * <p>{@code undefined} = 没有快照（正常流程不需要还原）。
 * {@code null} 是**有效值**，表示「未归属的老田」——两者必须区分开。
 */
const prevFarmId = ref(undefined)
/** field = 近景；map = 俯瞰选点 */
const sceneView = ref('field')

/**
 * 当前所在的农场（null = 未归属 / v1 遗留的老田）。
 *
 * <p>🔴 2026-10-22：这里从「按 currentSlot 反查 plots」改成**独立状态源**。
 * 原因：地块切换条要按「当前农场」过滤（只显示本农场的地块），
 * 而过滤后的列表又会参与 currentFarmId 的推导 —— 形成自依赖。
 * 独立 ref 之后：切换条怎么过滤都不会反过来影响「当前在哪个农场」。
 *
 * <p>赋值时机：
 * <ul>
 *   <li>从地图「进入农场/地块」→ 由 {@code onWorldEnterPlot} 写入；</li>
 *   <li>看板里点某块地 → 由 {@code onBoardSelect} 写入（该地的 farmId）；</li>
 *   <li>3D 场景里点到别农场的邻田 → 由 {@code selectPlot} 跟随切换；</li>
 *   <li>首次进入 / 兜底回退 → {@code load()} 里按当前地块的 farmId 兜底。</li>
 * </ul>
 */
const activeFarmId = ref(null)

/**
 * 该用户的**全部**农场（来自 {@code /board} 的 {@code farms} 数组）。
 *
 * <p>🔴 2026-10-23 需求：农场详情要显示「当前是哪个农场」。
 * 原先农场名只能从地块摘要（{@code plots}）里找，而**没有地块的农场
 * 根本不在 plots 里** —— 顶部标识退化成「#2」，玩家不知道自己在哪。
 *
 * <p>{@code /board} 的 {@code farms} 数组包含全部农场（含 0 地块的），
 * 每项带 {@code name} / {@code zoneId} / {@code districtName}，
 * 是「农场名 + 农业区」的唯一权威来源。
 */
const boardFarms = ref([])

/** 当前地块所属农场（地图建场后，新开的地块默认挂到这里） */
const currentFarmId = computed(() => {
  const p = plots.value.find((x) => x.slot === currentSlot.value)
  return p && p.farmId ? p.farmId : null
})

/**
 * 当前农场的地块（切换条只显示这些）。
 *
 * <p>需求（2026-10-22）：「农场详情内的作物切换功能只显示当前农场内的地块，
 * 不显示其他农场的」。此前切换条直接渲染 {@code plots}（该用户**全部**地块），
 * 于是进入南昌县的农场后，切换条上仍挤着 10 块其它农场与历史老田的地。
 *
 * <p>判定口径：{@code (p.farmId ?? null) === (activeFarmId ?? null)}。
 * <ul>
 *   <li>在某个农场里（{@code activeFarmId != null}）：
 *       只列该农场的地，{@code farmId=null} 的 v1 遗留老田属于「其他农场」，排除；</li>
 *   <li>没进任何农场（{@code activeFarmId == null}）：
 *       列 {@code farmId=null} 的老田 —— 它们自成一组，
 *       否则玩家关掉地图站在老田上时切换条会一条都不显示。</li>
 *   <li>玩家在 3D 场景里点到别农场的邻田时，{@code selectPlot} 会把
 *       {@code activeFarmId} 一起切过去，切换条随之换成那个农场 —— 上下文始终自洽。</li>
 * </ul>
 */
const farmPlots = computed(() => {
  const fid = activeFarmId.value ?? null
  return plots.value.filter((p) => (p.farmId ?? null) === fid)
})

/**
 * 农场名（切换条与顶部标识用）。
 *
 * <p>🔴 2026-10-23：原来只从 {@code plots}（地块摘要）里找 farmName，
 * 于是**没有地块的农场拿不到名字** —— 顶部标识退化成「#2」这种 ID，
 * 玩家根本不知道自己在哪个农场。
 *
 * <p>正确来源是 {@code /board} 的 {@code farms} 数组：它包含该用户的
 * **全部**农场（含 0 地块的），每项都带 {@code name}。
 * 两级兜底：地块摘要（最新，可能带别名）→ board.farms（权威，覆盖无地块农场）。
 */
const farmName = computed(() => {
  const fid = activeFarmId.value
  if (fid == null) return ''
  const hit = plots.value.find((p) => p.farmId === fid && p.farmName)
  if (hit?.farmName) return hit.farmName
  // 🔴 无地块的农场根本不在 plots 里 —— 必须从 board.farms 兜底
  return boardFarms.value.find((f) => f.farmId === fid)?.name || ''
})

/**
 * 这次要操作的目标农场（无地块农场也要能显示出来）。
 *
 * <p>口径：{@code pendingFarmId}（从地图点「进入农场」带过来的）
 * 优先于 {@code activeFarmId}（当前上下文）。
 */
const targetFarmId = computed(() => pendingFarmId.value ?? activeFarmId.value ?? null)

/**
 * 目标农场的名字（地图侧栏与选地面板顶部都用它）。
 */
const targetFarmName = computed(() => {
  const fid = targetFarmId.value
  if (fid == null) return ''
  const hit = plots.value.find((p) => p.farmId === fid && p.farmName)
  if (hit?.farmName) return hit.farmName
  return boardFarms.value.find((f) => f.farmId === fid)?.name || ''
})

/**
 * 目标农场的农业区（区县决定气候，不能手改）。
 *
 * <p>🔴 2026-10-23：无地块农场的 {@code state.plot.zoneId} 是空的
 * （那块地还没建），但农场的 zone 是确定的 —— 建田时必须用它，
 * 否则选地面板会退回默认的「江南」区，在东北的农场里种江南作物，
 * 风险评估全都算错。
 */
const targetFarmZone = computed(() => {
  const fid = targetFarmId.value
  if (fid == null) return ''
  return boardFarms.value.find((f) => f.farmId === fid)?.zoneId || ''
})

/** 未建田或主动换地时，整页展示选地流程 */
const setupVisible = computed(() => showSetup.value || !!(state.value?.plot && !state.value.plot.chosen))

/**
 * 真正提交给 FieldSetup 的槽位（2026-10-22）。
 *
 * <p><b>为什么不能直接用 {@link setupSlot}</b>：收割后地块进入「待播种」，
 * {@code state.plot.chosen} 变 false → {@link setupVisible} 变 true → 选地页自动弹出。
 * 但 {@code setupSlot} 只在玩家主动点「新建 / 换地」时才会被赋值，
 * 收割后它仍然是 -1（= 新建一块地）——
 * 于是玩家在「选下一季」的界面上确认，落地的是<b>另一块新地</b>，
 * 而刚收完的那块一直挂在「待播种」状态永远出不来。
 *
 * <p>规则：玩家主动打开（{@code showSetup=true}）时听 {@code setupSlot}；
 * 系统自动打开（收割后 / 首次进入）时锁定当前地块。
 */
const effectiveSetupSlot = computed(() => {
  if (showSetup.value) return setupSlot.value
  return state.value?.plot && !state.value.plot.chosen ? currentSlot.value : -1
})

/**
 * 这次提交是「替换已有地块」还是「开一块新地」。
 *
 * <p>🔴 2026-10-22 修复：原先 {@code has-field} 直接绑 {@code state.plot.chosen}
 * （= **当前**地块有没有种），于是点了「＋ 开垦新地」也会弹出
 * 「换一块地会放弃当前这一季」的确认框 —— 新地根本没种过，
 * 放弃的是**别的**地的那一季，提示词张冠李戴，玩家会以为要毁掉当前地。
 *
 * <p>正确口径：看<b>这次要提交的那个槽位</b>（{@link effectiveSetupSlot}）上
 * 是否已经种过东西：
 * <ul>
 *   <li>槽位 ≥ 0 且该槽位已种 → 替换，要警告；</li>
 *   <li>槽位 = -1（新建）→ 不警告；</li>
 *   <li>槽位 ≥ 0 但还没开垦（空地 / 待播种）→ 不警告
 *       （待播种是「选下一季」，不是「放弃这一季」）。</li>
 * </ul>
 */
const setupReplacesField = computed(() => {
  const slot = effectiveSetupSlot.value
  if (slot < 0) return false
  const p = plots.value.find((x) => x.slot === slot)
  if (!p) return false
  // 已经开垦过（种过或收过）才算「替换」；纯空地 / 待播种不算
  return !!p.chosen || !!p.awaitingSowing || (p.seasons || 0) > 0
})

/**
 * 这块地是否处于「苗期枯死」——本季绝收，但玩家还没结掉它。
 *
 * <p>枯死的地块 DVS 永远停在低位，收割会被「没到黄熟期」拒绝，
 * 必须用「记为绝收」把这一季结掉（状态记 FAILED，写明枯死原因），
 * 否则它既收不了也结不掉，玩家被卡死。
 */
const isWithered = computed(() => !!state.value?.crop?.withered)

const ACTION_TYPES = ['IRRIGATE', 'DRAIN', 'FERTILIZE', 'PESTICIDE', 'UNCOVER']

/** 作物形态：水稻/小麦/玉米/大豆，决定 3D 几何与配色 */
const cropVisual = computed(() => state.value?.plot?.visual || 'rice')

/**
 * 🔴 **3D 场景的 :key**（2026-10-26 画质分级）
 *
 *   **两个触发源必须合并**：
 *   ① 换作物 —— 作物贴图/密度全变，必须重建（改造前是 `:key="cropVisual"`）
 *   ② 画质切换到「必须重建」的档位 —— WebGL 上下文的 `antialias`
 *      是**创建参数**，运行时改不了；同一个 canvas 不能创建第二个上下文
 *      → 只能让 Vue 卸载旧组件、挂载新组件（全新 canvas + 全新上下文）。
 *
 * ⚠️ 改造时若直接把 `:key` 从 cropVisual 换成别的单一来源，
 *   **换作物就不再重建场景** —— 作物贴图不会更新（一屏旧稻子）。
 *   这是「改 key 时漏了另一处依赖」的典型翻车。
 */
const sceneKey = computed(() => `${cropVisual.value}#${sceneRebuildToken.value}`)

/** 作物全名太长，切换条上只留主名（去掉括号里的品种说明） */
function shortCrop(name) {
  if (!name) return '空地'
  return String(name).split(/[（(]/)[0]
}

// ---------------- 地块切换条：滚动导航 ----------------

const plotRailEl = ref(null)
/** 两侧可滚动余量（px）：>1 说明还有内容藏在边上 */
const railScroll = reactive({ left: 0, right: 0 })

/**
 * 「当前是第几块 / 共几块」。
 *
 * 不能用 currentSlot+1：槽位号是后端分配的，可以跳跃（11 号槽位之后可能是 15 号），
 * 那样会显示成 12/11 这种反常数字。要用当前地块在列表中的实际序号。
 *
 * 🔴 2026-10-22：分母改用 {@link farmPlots}（当前农场的地块数），
 * 与切换条上实际显示的条目数保持一致。
 */
const plotIndexLabel = computed(() => {
  const total = farmPlots.value.length
  if (!total) return '0/0'
  const idx = farmPlots.value.findIndex((p) => p.slot === currentSlot.value)
  return `${idx >= 0 ? idx + 1 : 0}/${total}`
})

/**
 * 切换条与顶部的农场标识（例：「南昌县农场 · 3 块地」）。
 *
 * <p>没有归属农场（v1 遗留老田）时显示「未归属」——
 * 那些地自成一群，不该伪装成某个农场，也不能什么都不显示
 * （否则玩家不知道切换条上那一排是什么）。
 */
const farmLabel = computed(() => {
  const fid = activeFarmId.value
  const n = farmPlots.value.length
  if (fid == null) {
    return n ? `未归属 · ${n} 块地` : ''
  }
  const name = farmName.value || `#${fid}`
  return n ? `${name} · ${n} 块地` : name
})

function onRailScroll() {
  const el = plotRailEl.value
  if (!el) return
  railScroll.left = el.scrollLeft
  railScroll.right = el.scrollWidth - el.clientWidth - el.scrollLeft
}

/** 导航按钮：按一屏的 80% 翻页 */
function railScrollBy(dir) {
  const el = plotRailEl.value
  if (!el) return
  const step = Math.max(120, el.clientWidth * 0.8)
  el.scrollTo({ left: el.scrollLeft + dir * step, behavior: 'smooth' })
}

/** 切换地块后把对应 chip 滚进可视区，否则在第 80 块时选中第 1 块会看不到高亮 */
function scrollRailTo(slot) {
  const el = plotRailEl.value
  if (!el) return
  const target = el.querySelector('.pr-chip-' + slot)
  if (!target) return
  const left = target.offsetLeft
  const right = left + target.offsetWidth
  const viewL = el.scrollLeft
  const viewR = viewL + el.clientWidth
  if (left < viewL + 8) {
    el.scrollTo({ left: Math.max(0, left - 24), behavior: 'smooth' })
  } else if (right > viewR - 8) {
    el.scrollTo({ left: right - el.clientWidth + 24, behavior: 'smooth' })
  }
}

/**
 * 打开选地/建田面板的统一入口。
 *
 * <p>🔴 2026-10-22：必须先关掉中国地图浮层。
 * {@code FarmWorldMap} 的根节点是 {@code .wm-page}（position:fixed; z-index:60），
 * 而选地面板 {@code .fs-page} 是普通文档流（z-index:auto）——
 * 两者同时存在时地图会**整个盖住**选地面板，表现为「点了＋却什么都没发生」。
 * 这个坑在 1600 视口下尤其隐蔽：DOM 里 .fs-page 明明在、风险评估也照常发请求，
 * 但玩家视线里只有地图。
 */
function openSetup() {
  worldMapOpen.value = false
  // 🔴 2026-10-23：记下进来之前的农场上下文，取消时要还原。
  // ⚠️ 这里只在**还没有快照**时记（`undefined`）——
  // 从地图进空农场时 `onWorldEnterPlot` 已经先记过了，
  // 这时再记一遍记到的就是目标农场，取消时还原不回去。
  if (prevFarmId.value === undefined) {
    prevFarmId.value = activeFarmId.value
  }
  showSetup.value = true
}

/**
 * 取消选地（2026-10-23）。
 *
 * <p>🔴 空农场点「进入农场」会直接进选地面板（那里没有地可进），
 * 必须给一条退路 —— 否则玩家被关在选地页里，连地图都回不去。
 *
 * <p>🔴 取消时必须**还原农场上下文**：玩家进来时我们把 {@code activeFarmId}
 * 切成了目标农场（空农场），但 3D 场景里的地还是原来那块（别的农场）。
 * 不还原的话顶部写着「格尔木市农场」、场景里却是别家的地 ——
 * 正是用户报的「进入了其他的农场」。
 *
 * <p>退到哪里：
 * <ul>
 *   <li>还原到进来前的农场 + 重新拉数据 → 回 3D 场景（回到他刚才待的地方）；</li>
 *   <li>一个地块都没有 → 打开中国地图，让他去别处建农场。</li>
 * </ul>
 */
async function onSetupCancel() {
  showSetup.value = false
  setupSlot.value = -1
  pendingFarmId.value = null
  // 🔴 还原农场上下文（undefined = 没有快照，别动）
  if (prevFarmId.value !== undefined) {
    activeFarmId.value = prevFarmId.value
  }
  prevFarmId.value = undefined
  if (!plots.value.length) {
    worldMapOpen.value = true
    return
  }
  await load()
}

/**
 * 建田完成：关掉选地页，新建时自动跳到新地块。
 *
 * <p>🔴 2026-10-22：不再用「取最大 slot」猜新地块落在哪 ——
 * 槽位是后端按「全局第一个空位」分配的，农场内的 slot 并不一定连续或递增，
 * 猜最大 slot 会跳到别的农场的地上。改为用后端回传的 slot（{@code info.slot}）。
 */
async function onFieldCreated(info = {}) {
  showSetup.value = false
  const wasNew = setupSlot.value < 0
  setupSlot.value = -1
  pendingFarmId.value = null
  prevFarmId.value = undefined
  await load()
  const newSlot = Number.isInteger(info?.slot) ? info.slot : null
  if (wasNew && newSlot != null) {
    currentSlot.value = newSlot
    await load()
  } else if (wasNew && plots.value.length) {
    // 兜底：后端没回 slot（老接口）时退回「取最大 slot」
    const last = plots.value.reduce((a, b) => (a.slot >= b.slot ? a : b))
    currentSlot.value = last.slot
    await load()
  }
}

/**
 * 地图里点「进入农场/地块」：切到那块地并关掉地图。
 *
 * <p>🔴 2026-10-22：同时写入 {@link activeFarmId} —— 切换条按它过滤，
 * 不写的话进了农场仍会显示其它农场的地块。
 *
 * <p>🔴 2026-10-23 事故修复：**地块必须属于目标农场**，否则会串场。
 *
 * <p>原实现只校验「slot 存在」：
 * <pre>{@code const exists = plots.value.some((p) => p.slot === slot)}</pre>
 * 而 slot 是**用户范围内全局唯一**的，空农场（0 地块）进来时
 * 前端传了个 {@code slot: 0}（slot 0 属于别的农场），
 * 于是玩家点了「五台县农场」（0 地块）却落进了 slot 0 那个农场。
 *
 * <p>正确口径：{@code (p.farmId ?? null) === (farmId ?? null)} ——
 * 既要 slot 对上，也要归属农场对上。
 *
 * <p>{@code slot = -1} 是「这个农场还没有地，去开垦第一块」的信号
 * （空农场点「进入农场」时地图侧就是这么发的）。
 */
async function onWorldEnterPlot({ farmId, slot }) {
  worldMapOpen.value = false
  // 🔴 2026-10-23：先记快照，再切上下文 ——
  // 顺序反了的话，快照记下来的已经是目标农场，取消时就还原不回去了。
  prevFarmId.value = activeFarmId.value
  if (farmId != null) {
    activeFarmId.value = farmId
  } else {
    // 未归属的老田：清掉农场上下文
    activeFarmId.value = null
  }
  await load()
  // 🔴 空农场：直接进建田流程，slot = -1 表示「在这个农场里新开一块地」
  if (slot < 0) {
    pendingFarmId.value = farmId || null
    setupSlot.value = -1
    openSetup()
    return
  }
  // 🔴 归属校验：slot 存在 **且** 属于目标农场
  const exists = plots.value.some(
    (p) => p.slot === slot && (p.farmId ?? null) === (farmId ?? null)
  )
  if (exists) {
    currentSlot.value = slot
    await load()
    return
  }
  // 这个农场下还没有对应地块：直接进建田流程，新地挂在目标农场下
  //
  // 🔴 2026-10-23：**不要**把 currentSlot 设成这个不存在的 slot。
  // slot 是用户范围内全局唯一的，前端传的 slot 往往属于**别的农场**；
  // 一旦设上去，state 就变成了别家的地 —— 顶部显示「五台县农场」，
  // 3D 场景里却是另一块田，正是用户报的「进入了其他的农场」。
  // 保持 currentSlot 不动，FieldSetup 靠 pendingFarmId 拿到归属农场。
  pendingFarmId.value = farmId || null
  setupSlot.value = -1
  openSetup()
}

/** 看板里点某块地：切过去并关掉看板 */
async function onBoardSelect(slot) {
  boardOpen.value = false
  // 看板跨农场：切换到那块地时把农场上下文也切过去
  const p = plots.value.find((x) => x.slot === slot)
  if (p) {
    activeFarmId.value = p.farmId ?? null
  }
  await selectPlot(slot)
}

/**
 * 地图悬浮面板点「药」：切到那块地并打开农资抽屉（2026-10-22 需求 O）。
 *
 * <p>打药/施肥不能走笼统的农事接口（后端要求必须带具体药剂与用量），
 * 所以这里是把玩家送到农资面板去选药，而不是直接代他做一次打药。
 */
async function onWorldOpenInputs({ slot }) {
  worldMapOpen.value = false
  await load()
  const exists = plots.value.some((p) => p.slot === slot)
  if (!exists) {
    ElMessage.warning('这块地不在当前用户的田里')
    return
  }
  if (currentSlot.value !== slot) {
    currentSlot.value = slot
    await load()
  }
  inputOpen.value = true
}

/** 看板里做一次农事 */
async function onBoardAction(slot, type) {
  try {
    await performFarmAction(slot, type, null)
    await load()
    ElMessage.success(t('farm.actionOk', { name: t('farm.actionName.' + type) }))
  } catch (e) {
    ElMessage.error(e?.message || t('farm.opFail'))
  }
}

/** 看板里收割 */
async function onBoardHarvest(slot) {
  try {
    const r = await harvestFarm(slot)
    await load()
    const y = r?.data?.yield || r?.data?.y
    ElMessage.success(t('farm.harvestOk', { y: y ?? '—', best: '' }))
    // S24：看板收割同样入仓
    if (r?.data?.warehouseStored) {
      refreshWarehouseBadge()
      ElMessage.info(t('farm.warehouse.stored', { kg: r.data.storedKg }))
    }
  } catch (e) {
    ElMessage.error(e?.message || t('farm.opFail'))
  }
}

/**
 * 切换到另一块地。
 *
 * <p>🔴 2026-10-22：同步切换 {@link activeFarmId}。
 * 3D 场景里相邻的田可能属于**别的农场**（玩家在地图上建的多个农场
 * 各自有地，槽位在全局网格上相邻）。点过去时若不切农场上下文，
 * 切换条会停留在原农场、当前高亮的地却不在列表里（计数变成 0/N）。
 */
async function selectPlot(slot) {
  if (slot === currentSlot.value || busy.value) return
  const p = plots.value.find((x) => x.slot === slot)
  if (p) {
    const nextFarm = p.farmId ?? null
    if (nextFarm !== (activeFarmId.value ?? null)) {
      activeFarmId.value = nextFarm
    }
  }
  currentSlot.value = slot
  await load()
  // 高亮的 chip 必须留在视野里，否则第 80 块被选中时用户看不到自己在哪
  await nextTick()
  scrollRailTo(slot)
}

/** 近景 ↔ 俯瞰地图 */
function toggleMap() {
  sceneView.value = sceneView.value === 'map' ? 'field' : 'map'
}

/**
 * 打开仓库（S24）。
 *
 * <p>🔴 必须先关掉中国地图浮层 —— {@code .wm-page} 是 fixed/z-index:60，
 * 会整个盖住仓库面板（与选地面板同一个坑）。
 */
function openWarehouse() {
  worldMapOpen.value = false
  boardOpen.value = false
  achievementOpen.value = false
  warehouseOpen.value = true
}

function closeWarehouse() {
  warehouseOpen.value = false
}

/**
 * 打开成就 / 图鉴面板（S26）。
 *
 * <p>🔴 必须先关掉中国地图浮层 —— {@code .wm-page} 是 fixed/z-index:60，
 * 会整个盖住面板（与仓库面板同一个坑，见 {@code openWarehouse}）。
 */
function openAchievement() {
  worldMapOpen.value = false
  boardOpen.value = false
  warehouseOpen.value = false
  shopOpen.value = false
  achievementOpen.value = true
}

/**
 * 🔴 **3D 场景上报实测帧率**（2026-10-26 画质分级）
 */
function onSceneFps(payload) {
  sceneFps.value = payload?.fps || 0
}

/**
 * 🔴 **场景请求重建**（2026-10-26 画质分级）
 *
 *   画质切换改了「必须重建几何/贴图/着色器」的参数时，
 *   WebGL 上下文无法原地改（antialias 是创建参数），
 *   只能换掉 :key 让 Vue 挂载一个全新的组件实例。
 */
function onSceneRebuild(payload) {
  sceneRebuildToken.value += 1
}

/**
 * 🔴 **画质切换完成**（2026-10-26 画质分级）
 *
 *   场景组件自己会处理「热更新」还是「重建」，
 *   这里只负责提示用户 + 刷新实测帧率（重建后旧帧率失效）。
 */
function onSceneQualityChanged() {
  sceneFps.value = 0
}

/**
 * 🔴 **场景自动降档**（2026-10-26 画质分级 · #131）
 *
 *   只有在用户**没有手动选过**画质时才会触发（场景组件里有判定）。
 *   这里给一个提示，让用户知道系统为什么把画质降了。
 */
function onSceneAutoDowngrade(payload) {
  ElMessage.info(
    `${t('farm.quality.autoDowngraded', {
      fps: payload?.fps || 0,
      to: t('farm.quality.level.' + (payload?.to || 'standard'))
    })}`
  )
}

/**
 * 用户在画质面板里切换档位。
 */
function onQualityChanged(payload) {
  ElMessage.success(
    t('farm.quality.changed', { level: t('farm.quality.level.' + (payload?.id || 'standard')) })
  )
}

/**
 * 建田后提示「缺种子」，玩家点了「去商店」→ 直接开商店面板。
 *
 * <p>⚠️ 必须先关掉选地面板（{@code setupVisible}）——
 * 它是全屏的，不关的话商店面板会开在选地面板下面，玩家看不到。
 */
function onNeedShop() {
  showSetup.value = false
  worldMapOpen.value = false
  boardOpen.value = false
  warehouseOpen.value = false
  achievementOpen.value = false
  shopOpen.value = true
}

/** 变卖完成：刷新顶部角标 */
function onWarehouseSold() {
  refreshWarehouseBadge()
}

// ==================== 商店（需求 R） ====================

/** 打开商店（地图顶栏「商店」按钮）。 */
function openShop() {
  worldMapOpen.value = false
  boardOpen.value = false
  warehouseOpen.value = false
  achievementOpen.value = false
  shopOpen.value = true
}

function closeShop() {
  shopOpen.value = false
}

/**
 * 买完东西：刷新顶部余额 + 商店角标 + 仓库（种子/水进了库存）。
 *
 * <p>⚠️ 种子和水的库存变化会影响「仓库」的农资区显示，
 * 所以这里除了刷商店角标，还要刷一次仓库角标。
 */
function onShopBought() {
  refreshShopBadge()
  refreshWarehouseBadge()
}

/**
 * 刷新商店角标：缺货提醒条数。
 *
 * <p>语义：「有该补的东西」——比如没有种子、没有灌溉水。
 * 玩家看到红点就知道该去商店了。
 *
 * <p>⚠️ 静默失败：角标只是提示，拉不到就保持原样。
 */
async function refreshShopBadge() {
  try {
    const res = await getFarmShop()
    const d = res?.data || {}
    shopBalance.value = d.balance || 0
    const items = d.items || []
    let short = 0
    for (const it of items) {
      // 种子：库存为 0 → 缺货（但耐用品「已拥有」不算缺）
      if (it.kind === 'SEED' && !it.owned) short++
      // 水：灌溉额度为 0 → 缺水
      if (it.kind === 'WATER' && !it.owned) short++
    }
    shopShort.value = short
  } catch (e) {
    // 静默
  }
}

/**
 * 刷新顶部仓库角标（批次数 + 待处理数）。
 *
 * <p>静默失败：角标只是提示，拉不到就保持原样，不能因此弹错误提示打断玩家。
 *
 * <p>⚠️ {@code getFarmWarehouse()} 后端会顺带跑一次临期/变质检查并生成站内通知，
 * 所以拿到数据后要同步刷新消息中心未读数，否则玩家收不到「该卖了」的铃铛提醒。
 */
async function refreshWarehouseBadge() {
  try {
    const res = await getFarmWarehouse()
    const d = res?.data || {}
    warehouseCount.value = (d.items || []).length
    // 临期 + 已变质都要玩家处理，一起算进红点
    warehouseWarn.value = (d.expiringCount || 0) + (d.rottenCount || 0)
    // 顺带刷新铃铛（后端刚生成过临期/变质通知）
    notifStore.fetchUnread()
  } catch (e) {
    // 静默
  }
}

/** 在 3D 场景里点空地：直接在那一格建田 */
function onSceneCreatePlot(slot) {
  setupSlot.value = slot
  openSetup()
}

/**
 * 新建一块地（后端自动分配槽位）。
 *
 * <p>🔴 2026-10-22：上限判断从「用户总量 100」改成「**当前农场 12 块**」
 * —— 后端 {@code MAX_PLOTS_PER_FARM} 才是真正的闸门（超了后端直接拒绝），
 * 前端还用总量判断会让玩家在还有余量时就被拦住。
 * 没有归属农场（v1 老田）时退回用户级总量。
 */
function newPlot() {
  const fid = activeFarmId.value
  if (fid != null) {
    if (farmPlots.value.length >= MAX_PLOTS_PER_FARM) {
      ElMessage.warning(t('farm.plotLimitPerFarm', { n: MAX_PLOTS_PER_FARM }))
      return
    }
  } else if (plots.value.length >= MAX_PLOTS) {
    ElMessage.warning(t('farm.plotLimit', { n: MAX_PLOTS }))
    return
  }
  setupSlot.value = -1
  openSetup()
}

/** 在当前地块上换作物/换播期 */
function rebuildPlot() {
  setupSlot.value = currentSlot.value
  openSetup()
}

/**
 * 生育进程时间轴：按**当前作物自己的**物候段切分。
 *
 * 🔴 **2026-10-26 重构**：原来是一套写死的 7 段（seedling/tiller/dry/boot/head/fill/ripe），
 *   那是**水稻**的口径 —— 番茄的时间轴会显示出「晒田」「孕穗」这种番茄根本没有的阶段。
 *   现在段数、段名、段边界全部来自后端下发的 `stageBounds` / `stageNames`，
 *   与 3D 画面用的是同一份数据 —— 文案与画面必然一致。
 */
const timeline = computed(() => {
  const d = state.value?.crop?.dvs ?? 0
  const bounds = state.value?.crop?.stageBounds
  const names = state.value?.crop?.stageNames
  if (!Array.isArray(bounds) || !bounds.length) {
    return []
  }
  return bounds.map((b, i) => {
    const from = Array.isArray(b) ? b[0] : 0
    // 末段右边界封到 1.0（后端末段 hi 常是 1.3，但进度条只画到 1）
    const rawTo = Array.isArray(b) ? b[1] : 1
    const to = i === bounds.length - 1 ? 1 : rawTo
    const cn = Array.isArray(names) ? names[i] : ''
    const key = phenoStageKeyOf(cn)
    return {
      key: key || cn || String(i),
      from,
      to,
      // 未登记 i18n key 时回退到后端下发的中文名（不显示空白）
      label: key ? t('farm.phenoStage.' + key) : (cn || '—'),
      done: d >= to,
      active: d >= from && d < to
    }
  })
})

function onMenu(cmd) {
  if (cmd === 'restart') restart()
  else if (cmd === 'rules') rulesVisible.value = true
}

const rules = computed(() => [
  t('farm.rule1'), t('farm.rule2'), t('farm.rule3'), t('farm.rule4'), t('farm.rule5')
].filter(Boolean))

function gotoCenter() {
  // 🔴 2026-10-23：主动离开游戏 → 清除画面上下文。
  // 否则下次进「稼穑」会直接落到上次那块地（虽然也能刷新保持，
  // 但玩家明明是从游戏中心重新进来的，落到旧位置反而突兀）。
  clearFarmContext()
  router.push('/games')
}

async function load() {
  try {
    const res = await getFarmState(currentSlot.value)
    state.value = res?.data || null
    const pl = await listFarmPlots()
    plots.value = pl?.data || []
    // 选中的地块被删/不存在时回到 0
    if (!plots.value.some(p => p.slot === currentSlot.value) && plots.value.length) {
      currentSlot.value = plots.value[0].slot
      const again = await getFarmState(currentSlot.value)
      state.value = again?.data || null
    }
    // 🔴 S25：用户级世界日期。/board 的 today 是「今天」唯一的统一定义。
    // 🔴 2026-10-26：后端已改为只读用户级时钟表（t_farm_user_clock），
    // 不再是「所有地块里最靠后的那个」—— 一个无关地块的偏移曾把整个用户推到 2028 年。
    const board = await getFarmBoard()
    userWorldDate.value = board?.data?.today || ''
    // 🔴 2026-10-23：把 farms 存下来 —— 农场名与农业区要靠它，
    // 无地块的农场不在 plots 里（地块摘要），只能从这里拿。
    boardFarms.value = board?.data?.farms || []
    // 🔴 2026-10-22：农场上下文兜底。
    // 首次进入（直接打开「稼穑」路由）时 activeFarmId 还是 null，
    // 这时按当前地块的 farmId 推断 —— 否则切换条会一条都不显示。
    const cur = plots.value.find(p => p.slot === currentSlot.value)
    const curFarm = cur?.farmId ?? null
    if (activeFarmId.value == null && curFarm != null) {
      activeFarmId.value = curFarm
    }
    if (state.value?.demoMode) {
      const m = await listFarmMockWeather()
      mocks.value = m?.data || []
    }
    const a = await listFarmActions(currentSlot.value)
    actions.value = a?.data || []
    // S24：仓库角标（批次数 + 临期数）——静默刷新，不打断主流程
    refreshWarehouseBadge()
    // 🔴 需求 R：商店角标（缺种子 / 缺水）+ 余额——同样静默
    refreshShopBadge()
    // 切换条：等 DOM 出来再同步滚动位置与余量
    await nextTick()
    onRailScroll()
    scrollRailTo(currentSlot.value)
  } catch (e) {
    ElMessage.error(e?.message || t('farm.loadFail'))
  }
}

async function run(fn, okMsg) {
  busy.value = true
  try {
    const r = await fn()
    await load()
    if (okMsg) ElMessage.success(okMsg(r))
    return r
  } catch (e) {
    ElMessage.error(e?.message || t('farm.opFail'))
    return null
  } finally {
    busy.value = false
  }
}

async function advance(d) {
  // 大跨度推进会跳过正在窗口内的工序：先提示，避免"一按就错过"
  if (d >= 30) {
    const pending = (state.value?.chain || []).filter(
      s => s.status === 'AVAILABLE' && !s.repeatable && s.id !== 'HARVEST'
    )
    if (pending.length) {
      try {
        await ElMessageBox.confirm(
          t('farm.jumpWarn', { days: d, names: pending.map(s => s.name).join('、') }),
          t('farm.jumpWarnTitle'),
          { type: 'warning', confirmButtonText: t('farm.jumpConfirm'), cancelButtonText: t('common.cancel') }
        )
      } catch (e) {
        return
      }
    }
  }
  return run(() => advanceFarm(currentSlot.value, d))
}

async function harvest() {
  const r = await run(
    () => harvestFarm(currentSlot.value),
    (res) => t('farm.harvestOk', {
      y: res?.data?.yieldKgPerMu ?? 0,
      best: res?.data?.isNewBest ? t('farm.newBest') : ''
    })
  )
  const d = r?.data
  if (d && d.phiViolated) {
    ElMessageBox.alert(
      t('farm.input.phiViolation', {
        item: d.phiItem || '',
        y: d.yieldKgPerMu,
        cost: d.inputCost,
        net: d.net
      }),
      t('farm.input.phiViolationTitle'),
      { type: 'warning', confirmButtonText: t('farm.input.phiAck') }
    )
  } else if (d && d.yieldCapped) {
    // 产量触顶时给一句说明，别让玩家对着"到顶了"猜原因
    ElMessageBox.alert(
      d.yieldCapNote || '',
      t('farm.harvestCappedTitle'),
      { type: 'info', confirmButtonText: t('farm.input.phiAck') }
    )
  }
  // S24：收割后进仓库了 —— 提示玩家货入仓，什么时候卖得自己算
  if (d && d.warehouseStored) {
    refreshWarehouseBadge()
    ElMessage.info(t('farm.warehouse.stored', { kg: d.storedKg }))
  }
}

const restart = () => run(() => restartFarm(currentSlot.value), () => t('farm.restartOk'))

/**
 * 把本季记为绝收（2026-10-22）。
 *
 * <p>苗期枯死的地块 DVS 永远停在低位，收割会被「没到黄熟期」拒绝——
 * 玩家既收不了也结不掉，这一季就卡死了。这一季恰恰是最该进档案的一季
 * （枯死原因、出苗率、沉没成本），所以单开一个入口：产量记 0、状态 FAILED、
 * <b>不记游戏成绩</b>（失败不占榜）。
 */
async function closeFailed() {
  try {
    await ElMessageBox.confirm(
      t('farm.failedConfirm'),
      t('farm.failedTitle'),
      { type: 'warning', confirmButtonText: t('farm.failedAck'), cancelButtonText: t('common.cancel') }
    )
  } catch (e) {
    return
  }
  const r = await run(() => closeFailedFarmSeason(currentSlot.value), null)
  const d = r?.data
  if (d) {
    ElMessageBox.alert(
      t('farm.failedDone', {
        note: d.failureNote || '',
        cost: d.inputCost ?? 0,
        net: d.net ?? 0
      }),
      t('farm.failedTitle'),
      { type: 'warning', confirmButtonText: t('farm.failedAck') }
    )
  }
}

// ==================== 测试用：模拟天气 ====================

const PRESETS = {
  heat: { days: 5, tmax: 36, tmin: 28, precip: 0, labelKey: 'farm.mockPreset.heat' },
  storm: { days: 1, tmax: 24, tmin: 22, precip: 120, labelKey: 'farm.mockPreset.storm' },
  cold: { days: 3, tmax: 18, tmin: 12, precip: 2, labelKey: 'farm.mockPreset.cold' }
}

function todayStr() {
  const d = new Date()
  return d.toISOString().slice(0, 10)
}

function applyPreset(key) {
  const p = PRESETS[key]
  if (!p) return
  const from = todayStr()
  const to = new Date(from)
  to.setDate(to.getDate() + p.days - 1)
  const toStr = to.toISOString().slice(0, 10)
  run(
    () => mockFarmWeather({ fromDate: from, toDate: toStr, tmax: p.tmax, tmin: p.tmin, precip: p.precip, label: t(p.labelKey) }),
    (r) => t('farm.mockInjectOk', { label: r?.data?.label || t(p.labelKey), from, to: toStr })
  )
}

function resetMocks() {
  run(
    () => resetFarmMockWeather(),
    (r) => t('farm.mockResetOk', { n: r?.data?.removed ?? 0 })
  )
}

// ==================== 农事操作 ====================

function doAction(type) {
  run(
    () => performFarmAction(currentSlot.value, type, null),
    (r) => t('farm.actionOk', { type: t('farm.action.' + type), dvs: r?.data?.dvsAtAction?.toFixed(3) ?? '?' })
  )
}

onMounted(async () => {
  // 🔴 2026-10-23 需求：「在游戏内刷新的时候应该还保持在当前游戏画面」。
  //
  // 做法：把「当前地块 + 当前农场 + 地图开合」写进 sessionStorage，
  // 刷新时读回来。有记录 → 回到上次那块地、地图不弹；无记录 → 保持默认（打开地图）。
  //
  // ⚠️ 恢复必须在 load() **之前**设好 currentSlot / activeFarmId，
  // 否则 load() 会先按默认 slot 拉一次数据，再被覆盖（多余请求 + 闪一下别的地）。
  const ctx = readFarmContext()
  if (ctx) {
    currentSlot.value = ctx.slot
    activeFarmId.value = ctx.farmId
    worldMapOpen.value = ctx.mapOpen
  }
  await load()
  // load() 之后 plots 才有内容 —— 此时校正农场上下文并落盘
  //
  // ⚠️ 无论有没有 ctx 都要落盘：首次进入时 ctx 为 null（sessionStorage 还是空的），
  // 但**这一次的画面就是玩家下次刷新要回来的地方**，必须记下来。
  if (ctx) {
    // 🔴 2026-10-23：地块要按 **(slot, farmId) 组合**定位，不能只按 slot 反查 farmId。
    //
    // 为什么：slot 在用户范围内唯一，但**不同农场的地可以有各自的 slot 0**
    // （slot 是「农场内」的槽位，v1 遗留的老田还占着用户级的 slot 0）。
    // 只按 slot 找会把「农场 3 的第 0 块地」错认成「未归属的老田」→ farmId 被抹成 null，
    // 刷新后顶部农场名就没了 —— 正是本需求要修的东西自己出了 bug。
    const fid = ctx.farmId ?? null
    const hit = plots.value.find(
      (p) => p.slot === currentSlot.value && (p.farmId ?? null) === fid
    )
    if (hit) {
      // 组合命中 → 上下文正确，保持原样
      activeFarmId.value = hit.farmId ?? null
    } else {
      // 组合没命中（地块被删了 / 归属变了）→ 退回该 farmId 下的第一块地；
      // 连一块都没有就回退到用户级第一块（load() 已做过）
      const sameFarm = plots.value.filter((p) => (p.farmId ?? null) === fid)
      if (sameFarm.length) {
        currentSlot.value = sameFarm[0].slot
        await load()
      }
    }
  }
  persistContext()
})

/**
 * 把当前画面上下文落盘（刷新后据此恢复）。
 *
 * <p>只在「玩家已经进入某个具体画面」时保存 —— 地图概览态也保存，
 * 这样刷新后仍然停在地图上（保持一致），而不是莫名跳到某块地。
 */
function persistContext() {
  writeFarmContext({
    slot: currentSlot.value,
    farmId: activeFarmId.value ?? null,
    mapOpen: worldMapOpen.value
  })
}

/**
 * 画面上下文变化 → 落盘。
 *
 * <p>🔴 2026-10-23：覆盖三条路径 —— 切换地块、切换农场、开关中国地图。
 * 用 watch 而不是手动在每个函数里调，是因为这些状态散落在
 * {@code selectPlot} / {@code onWorldEnterPlot} / 模板里的直接赋值，
 * 手动补容易漏（漏一处 = 刷新后跳错地方）。
 */
watch([currentSlot, activeFarmId, worldMapOpen], () => {
  persistContext()
})

const dvs = computed(() => state.value?.crop?.dvs ?? 0)

const phiWarn = computed(() => {
  const s = state.value?.inputSummary
  if (!s || s.phiLeft < 0 || !s.lastSprayDate) return ''
  const minPhi = 21
  if (s.phiLeft >= minPhi) return ''
  return t('farm.input.phiCountdown', { d: s.phiLeft })
})

const visiblePests = computed(() =>
  (state.value?.pests || []).filter(p => p.detected && p.severity >= 0.04)
)

/**
 * 🔴 2026-10-27：距离成熟阈值还差多少 DVS。
 *
 * <p>有了这个数，「为什么还不能收」就不再是「进度条自己看着办」，
 * 而是「差 0.07 到成熟期」—— 这正是农业科学模块该给出的解释。
 */
const ripeGap = computed(() => {
  const c = state.value?.crop
  if (!c || typeof c.harvestDvs !== 'number') return 0
  return Math.max(0, c.harvestDvs - (c.dvs || 0))
})

const pestAlert = computed(() => {
  const list = visiblePests.value.filter(p => p.severity >= 0.15)
  if (!list.length) return ''
  return list.map(p => pestName(p) + ' ' + t('farm.pestStatus.' + p.status)).join(' · ')
})

function pestName(p) {
  const key = 'farm.pestName.' + p.id
  const v = t(key)
  return v === key ? p.name : v
}

const SOIL_LEVEL = { FLOODED: 5, WET: 4, MOIST: 3, DRY: 2, DROUGHT: 1 }

const OP_GROUPS = [
  { key: 'setup', ids: ['PUDDLE', 'BASE_FERT', 'TRANSPLANT'] },
  { key: 'manage', ids: ['TILLER_FERT', 'DRY_FIELD', 'PANICLE_FERT', 'IRRIGATE', 'PESTICIDE'] },
  { key: 'harvest', ids: ['DRAIN_FINAL', 'HARVEST'] }
]

const chainGroups = computed(() => {
  const list = state.value?.chain || []
  const byId = Object.fromEntries(list.map(s => [s.id, s]))
  return OP_GROUPS
    .map(g => ({ key: g.key, steps: g.ids.map(id => byId[id]).filter(Boolean) }))
    .filter(g => g.steps.length)
})

const chainVisible = computed(() => {
  const ids = new Set(OP_GROUPS.flatMap(g => g.ids))
  return (state.value?.chain || []).filter(s => ids.has(s.id) && !s.repeatable)
})

const chainDone = computed(() => chainVisible.value
  .filter(s => s.status === 'DONE' || s.status === 'AUTO').length)

const chainTotal = computed(() => chainVisible.value.length)

function stars(q) {
  if (q >= 0.95) return '★★'
  if (q >= 0.7) return '★☆'
  return '☆☆'
}

function opHint(s) {
  if (s.id === 'IRRIGATE' || s.id === 'PESTICIDE') {
    return t('farm.opHint.' + s.id)
  }
  if (s.status === 'AUTO') return t('farm.opHint.AUTO')
  if (s.status === 'DONE' || s.status === 'MISSED' || s.status === 'LOCKED') {
    return t('farm.opHint.' + s.status, { from: s.from.toFixed(2), to: s.to.toFixed(2) })
  }
  return t('farm.opHint.AVAILABLE', { from: s.from.toFixed(2), to: s.to.toFixed(2) })
}

function onOp(s) {
  if (s.id === 'HARVEST') harvest()
  else if (s.id === 'PESTICIDE' || s.id.endsWith('_FERT')) {
    // 施肥/打药必须走农资：选对药剂/肥料、对的量，才叫完成了这道工序
    inputOpen.value = true
  } else doAction(s.id)
}

const waterLevel = computed(() => SOIL_LEVEL[state.value?.soil?.status] || 3)

const soilTip = computed(() => {
  const s = state.value?.soil
  if (!s) return ''
  if (s.status === 'DROUGHT') return t('farm.soilTip.DROUGHT')
  if (s.status === 'DRY') return t('farm.soilTip.DRY')
  if (s.status === 'FLOODED') return t('farm.soilTip.FLOODED')
  return t('farm.soilTip.OK')
})

const heatLabel = computed(() => {
  const tier = state.value?.weather?.heatTier ?? 0
  return t('farm.heatTier.' + tier)
})

// ==================== 物候段（2026-10-26 口径统一） ====================
//
// 🔴 **这里原来是一套写死的 DVS 切点**（0.16 / 0.36 / 0.63 / 0.72 / 0.92），
//   那是**水稻**的口径 —— 恰好水稻是 7 段且切点一致，所以「水稻看着对」，
//   但其余 21 种作物全错位：
//   番茄 DVS 0.45 的真实阶段是「开花坐果」，这里却算成「拔节」。
//
// 现在改为**读后端下发的物候段索引**（`state.crop.stageIndex`），
// 后端用 `SimEngine.phenologyIndex` 与阶段文案同源计算 ——
// 文案与画面保证一致（这才是「阶段与真实情况相符」的真正含义）。
//
// 兜底：后端未下发索引时，用 `stageBounds` 自己切一次（仍比写死切点正确）。
const dvsStage = computed(() => {
  const idx = state.value?.crop?.stageIndex
  if (typeof idx === 'number' && idx >= 0) return idx
  // 兜底：按后端下发的段边界切（首段从 0 开始）
  const bounds = state.value?.crop?.stageBounds
  if (Array.isArray(bounds) && bounds.length) {
    const d = dvs.value
    for (let i = 0; i < bounds.length; i++) {
      const b = bounds[i]
      if (Array.isArray(b) && d >= b[0] && d < b[1]) return i
    }
    return bounds.length - 1
  }
  // 后端完全没下发（旧接口）→ 退回第一段，不要瞎猜
  return 0
})

// ==================== 季节背景（3D 未加载时的退化层） ====================
const seasonBg = computed(() => {
  const date = state.value?.worldDate
  if (!date) return { sky: 'linear-gradient(180deg,#cfe3b4 0%,#6da23f 100%)', season: 'summer', overlay: null }
  const month = parseInt(date.slice(5, 7), 10)
  if (month >= 11 || month <= 2) {
    return { sky: 'linear-gradient(180deg,#dde7f0 0%,#b8c4d0 100%)', season: 'winter', overlay: 'snow' }
  } else if (month >= 3 && month <= 5) {
    return { sky: 'linear-gradient(180deg,#f6e8d0 0%,#d8c8a0 60%,#a9d4a0 100%)', season: 'spring', overlay: 'petals' }
  } else if (month >= 6 && month <= 8) {
    return { sky: 'linear-gradient(180deg,#a9d4a0 0%,#6da23f 60%,#3a6a20 100%)', season: 'summer', overlay: null }
  }
  return { sky: 'linear-gradient(180deg,#f4d090 0%,#d8a060 60%,#a07830 100%)', season: 'autumn', overlay: 'leaves' }
})

const weatherFx = computed(() => {
  const w = state.value?.weather
  if (!w) return { kind: 'clear', intensity: 0 }
  if (w.precip > 30) return { kind: 'storm', intensity: 1 }
  if (w.precip > 5) return { kind: 'rain', intensity: 0.6 }
  if (w.tmin < 0) return { kind: 'snow', intensity: 0.8 }
  if (w.tmax > 33) return { kind: 'heatwave', intensity: 0.7 }
  if (w.tmax > 28) return { kind: 'sunny', intensity: 0.5 }
  return { kind: 'clear', intensity: 0.2 }
})

const weatherIcon = computed(() => ({
  storm: '⛈', rain: '🌧', snow: '🌨', heatwave: '🌡', sunny: '☀', clear: '☁'
}[weatherFx.value.kind] || '☀'))

// 退化田地的远树/远山配色
const trees = computed(() => {
  const palette = {
    spring: ['#7ec850', '#8ed460', '#9fd870', '#a9d870', '#7ec850', '#6da23f', '#8ed460', '#7ec850', '#9fd870'],
    summer: ['#4a7a2a', '#5a8a30', '#3a6a20', '#4a7a2a', '#6da23f', '#5a8a30', '#4a7a2a', '#3a6a20', '#5a8a30'],
    autumn: ['#c9a040', '#b89030', '#a8801f', '#c99030', '#b89030', '#a8801f', '#c9a040', '#b89030', '#a8801f'],
    winter: ['#8a8478', '#9a9488', '#7a7468', '#8a8478', '#9a9488', '#7a7468', '#8a8478', '#9a9488', '#7a7468']
  }
  const colors = palette[seasonBg.value.season] || palette.summer
  return Array.from({ length: 9 }, (_, i) => ({
    color: colors[i],
    style: {
      left: (5 + i * 11) + '%',
      bottom: (i % 3 === 0 ? '12%' : (i % 3 === 1 ? '14%' : '11%')),
      width: (28 + (i % 4) * 4) + 'px',
      height: (50 + (i % 3) * 8) + 'px',
      opacity: 0.85
    }
  }))
})

const mountainColor = computed(() => {
  const map = { spring: '#a8c890', summer: '#4a7a5a', autumn: '#9a8858', winter: '#a8b0c0' }
  return map[seasonBg.value.season] || map.summer
})

/**
 * 退化田地: 5×3=15 株
 */
function cellStyle(c) {
  const col = c.col
  const row = c.row
  const cx = 10 + col * 20 + ((c.key * 3.7) % 4) - 2
  const cy = 3 + row * 29.5 + ((c.key * 5.3) % 2)
  const cs = 0.72 + row * 0.22
  const cr = ((c.key * 23) % 15 - 7) + 'deg'
  return { '--cx': cx + '%', '--cy': cy + '%', '--cs': cs, '--cr': cr }
}

/**
 * 按后端下发的物候段边界查表，得到某 DVS 落在第几段。
 *
 * 🔴 2026-10-27：**原来这里是一套写死的 6 段切点**
 * （0.16 / 0.36 / 0.63 / 0.72 / 0.92）—— 那是**水稻**的口径。
 * 番茄 DVS 0.45 的真实阶段是「开花坐果」，这里却算成第 3 段（拔节）。
 *
 * 现在改为查后端下发的 `stageBounds`（与 3D 场景、时间轴同一份数据），
 * 口径与 `dvsStage`、`timeline` 完全一致 —— 三处不再各切各的。
 *
 * @param {number} dvs
 * @param {Array<Array<number>>} bounds 后端下发的 [[lo, hi], ...]
 * @returns {number} 段索引（0-based）；无边界时返回 0
 */
function stageIndexOfDvs(dvs, bounds) {
  if (!Array.isArray(bounds) || !bounds.length) return 0
  for (let i = 0; i < bounds.length; i++) {
    const b = bounds[i]
    if (Array.isArray(b) && dvs >= b[0] && dvs < b[1]) return i
  }
  // 超过最后一段的 hi（后端末段 hi 常是 1.30）→ 归入最后一段
  return bounds.length - 1
}

const cells = computed(() => {
  const d = dvs.value
  const w = state.value?.weather
  const tier = w?.heatTier ?? 0
  const tmax = w?.tmax ?? 25
  const tmin = w?.tmin ?? 18
  const precip = w?.precip ?? 0

  // 🔴 后端下发的物候段边界（与 3D / 时间轴同源）
  const bounds = state.value?.crop?.stageBounds
  const segCount = Array.isArray(bounds) && bounds.length ? bounds.length : 0

  const COLS = 5
  const ROWS = 3
  return Array.from({ length: COLS * ROWS }, (_, i) => {
    const col = i % COLS
    const row = Math.floor(i / COLS)
    const microOffset = ((i * 7) % 4) * 0.005
    const cellDvs = Math.min(1.0, d + microOffset)

    // 段索引 → PlantCell 的 6 段形态模板（0..5）。
    // 各作物段数不同（5/6/7），按比例映射到统一的 6 段画法。
    const segIdx = stageIndexOfDvs(cellDvs, bounds)
    const cellStage = segCount > 1
      ? Math.min(5, Math.round(segIdx / (segCount - 1) * 5))
      : 0

    return {
      key: i,
      col,
      row,
      stage: cellStage,
      heatWave: tier >= 2 && cellStage >= 2,
      frost: tmin < 0,
      rain: precip > 5,
      waterlogged: precip > 50,
      dry: tmax > 33 && precip < 1
    }
  })
})

/**
 * 产量四要素。
 *
 * <p>引擎用的是同一套乘性模型，但四要素的名字是禾谷类口径（穗数/实粒/千粒重）。
 * 园艺作物的对应关系是：株数 / 单株果数 / 单果重 —— 数值口径一致，
 * 只是标签必须换，否则"番茄的每穗粒数"会让玩家一头雾水。
 */
/**
 * 是否禾谷口径（决定产量文案用「穗/粒/千粒重」还是「株/果/单果重」）。
 *
 * ⚠️ 2026-10-24：不能用 visual 判断 —— visual 现在是作物 id（谷子是 `millet`），
 * 而且 visual 是「3D 形态标识」，本就不该拿来区分作物类别。用 cropId。
 */
const isGrainCrop = computed(() =>
  ['rice_mid', 'wheat_winter', 'corn_spring', 'soybean', 'millet'].includes(state.value?.plot?.cropId)
)

const yieldRows = computed(() => {
  const y = state.value?.yield
  if (!y) return []
  const grain = isGrainCrop.value
  return [
    {
      name: t(grain ? 'farm.col.panicles' : 'farm.col.plants'),
      value: grain
        ? `${Math.round(y.panicles).toLocaleString()} ${t('farm.unit.paniclePerMu')}`
        : `${Math.round(y.panicles).toLocaleString()} ${t('farm.unit.plantPerMu')}`,
      dmg: y.dmgPanicle,
      from: t(grain ? 'farm.from.tiller' : 'farm.from.veg1')
    },
    {
      name: t(grain ? 'farm.col.grains' : 'farm.col.fruits'),
      value: y.grainsPerPanicle.toFixed(1),
      dmg: Math.max(y.dmgFloret, y.dmgFertility),
      from: t(grain ? 'farm.from.head' : 'farm.from.veg2')
    },
    {
      name: t('farm.col.soil'),
      value: '×' + (state.value?.soil?.soilCoef ?? 1).toFixed(2),
      dmg: 0,
      from: 'SFI ' + Math.round(state.value?.soil?.sfi ?? 0)
    },
    {
      name: t(grain ? 'farm.col.tgw' : 'farm.col.unitWeight'),
      value: `${y.thousandGrainWeight.toFixed(grain ? 2 : 1)} g`,
      dmg: y.dmgWeight,
      from: t(grain ? 'farm.from.fill' : 'farm.from.veg3')
    }
  ]
})

function fmtTime(v) {
  if (!v) return ''
  const s = String(v).replace('T', ' ')
  return s.length > 16 ? s.slice(0, 16) : s
}
</script>

<style scoped>
/* ===== 地块切换条 ===== */
/* ---- 地块切换条 ----
   容器：flex 布局 = [左翻页] [可横向滚动的 chip 列表] [右翻页] + [计数]
   滚动列表两侧用渐隐遮罩提示"还有内容在边上"，否则用户根本不知道能横滚。
   翻页按钮在两端溢出时自动禁用（.off）。 */
.fm-plotrail-wrap {
  position: relative;
  width: 100%;
  display: flex;
  align-items: center;
  gap: 6px;
  pointer-events: none;
}
.fm-plotrail-wrap > * { pointer-events: auto; }

.fm-plotrail {
  position: relative;
  flex: 1 1 auto;
  min-width: 0;
  display: flex;
  gap: 8px;
  overflow-x: auto;
  overflow-y: hidden;
  padding: 6px 2px;
  scrollbar-width: thin;
  scroll-behavior: smooth;
  /* 左右渐隐：提示两侧还有地块 */
  -webkit-mask-image: linear-gradient(90deg, transparent 0, #000 22px, #000 calc(100% - 22px), transparent 100%);
  mask-image: linear-gradient(90deg, transparent 0, #000 22px, #000 calc(100% - 22px), transparent 100%);
}
.fm-plotrail::-webkit-scrollbar { height: 5px; }
.fm-plotrail::-webkit-scrollbar-thumb { background: rgba(255,255,255,.32); border-radius: 3px; }

/* 翻页按钮 */
.pr-nav {
  flex: 0 0 auto;
  width: 28px;
  height: 34px;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 0;
  border-radius: 17px;
  border: 1px solid rgba(255, 255, 255, 0.28);
  background: rgba(18, 30, 20, 0.62);
  backdrop-filter: blur(8px);
  color: #eef5ea;
  font-size: 17px;
  line-height: 1;
  cursor: pointer;
  transition: background 0.16s, opacity 0.16s;
}
.pr-nav:hover { background: rgba(40, 64, 42, 0.86); }
.pr-nav.off { opacity: 0.22; cursor: default; pointer-events: none; }

/* 当前地块 / 总数 */
.pr-count {
  flex: 0 0 auto;
  font-size: 11.5px;
  color: rgba(238, 245, 234, 0.85);
  background: rgba(18, 30, 20, 0.62);
  backdrop-filter: blur(8px);
  border: 1px solid rgba(255, 255, 255, 0.18);
  border-radius: 12px;
  padding: 0 9px;
  height: 26px;
  line-height: 24px;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

.pr-chip {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  gap: 6px;
  height: 34px;
  padding: 0 12px;
  border-radius: 17px;
  border: 1px solid rgba(255, 255, 255, 0.28);
  background: rgba(18, 30, 20, 0.52);
  backdrop-filter: blur(8px);
  color: #eef5ea;
  font-size: 12px;
  cursor: pointer;
  transition: background 0.16s, border-color 0.16s, transform 0.16s;
  white-space: nowrap;
}
.pr-chip:hover { background: rgba(28, 46, 30, 0.72); }
.pr-chip.on {
  background: rgba(96, 156, 72, 0.92);
  border-color: rgba(180, 232, 150, 0.9);
  box-shadow: 0 0 0 2px rgba(150, 220, 120, 0.28);
}
.pr-chip.empty { opacity: 0.62; border-style: dashed; }
.pr-chip b {
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  opacity: 0.9;
}
.pr-crop { font-weight: 600; }
.pr-stage { font-style: normal; opacity: 0.72; font-size: 11px; }
.pr-add {
  width: 34px;
  padding: 0;
  justify-content: center;
  font-size: 17px;
  line-height: 1;
}
.pr-add.full { opacity: 0.4; cursor: not-allowed; }

/* 当前农场标识（切换条右端）：告诉玩家「这条切换条只列这个农场的地」 */
.pr-farm {
  flex: 0 0 auto;
  max-width: 168px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 11.5px;
  color: rgba(238, 245, 234, 0.92);
  background: rgba(52, 92, 44, 0.72);
  backdrop-filter: blur(8px);
  border: 1px solid rgba(180, 232, 150, 0.45);
  border-radius: 12px;
  padding: 0 10px;
  height: 26px;
  line-height: 24px;
}

/* 顶部：农场名 chip（顶部是白色面板，用浅底深字，与其它 .hchip 一致） */
.farm-chip {
  cursor: default;
  color: #2f6b34;
  background: rgba(96, 156, 72, 0.16);
  border: 1px solid rgba(96, 156, 72, 0.32);
  max-width: 220px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  /* 🔴 2026-10-23：农场名是**身份信息**，不许被 flex 挤扁。
     原来它排在头部最后，空间不足时被压成 20px，文字全被 ellipsis 吃掉
     —— 玩家看到的就是「没有农场名」。 */
  flex: none;
  font-weight: 600;
}
.board-chip {
  cursor: pointer;
  background: rgba(255, 255, 255, 0.16);
}
.board-chip:hover { background: rgba(255, 255, 255, 0.3); }
.board-chip.on { background: rgba(96, 156, 72, 0.9); }

/* S24 仓库 chip：临期红点 */
.wh-chip { position: relative; }
.wh-chip.has-warn { background: rgba(214, 106, 40, 0.55); }
.wh-dot {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 15px;
  height: 15px;
  margin-left: 4px;
  padding: 0 4px;
  border-radius: 8px;
  background: #e0552f;
  color: #fff;
  font-size: 10px;
  font-style: normal;
  font-weight: 700;
  line-height: 1;
}

/* ============================================================
   PC 端布局：左侧 3D 农田自适应铺满 + 右侧 400–480 固定操作栏
   右栏分两段：fb-head（固定操作头）+ fb-scroll（内容滚动）
   ============================================================ */
.fm-page {
  display: grid;
  grid-template-columns: minmax(0, 1fr) clamp(400px, 30vw, 480px);
  width: 100%;
  max-width: none;
  height: 100%;
  max-height: 100%;
  margin: 0;
  padding: 0;
  overflow: hidden;
  background: #0e1726;
  position: relative;
}

/* ---------------- 左：农田舞台 ---------------- */
.fm-stage.frosted :deep(canvas) {
  filter: saturate(0.42) brightness(0.9) sepia(0.16);
  transition: filter 0.4s;
}

.fm-stage {
  position: relative;
  min-width: 0;
  min-height: 0;
  overflow: hidden;
  background: linear-gradient(180deg, #6fa7c9 0%, #a8cfa0 55%, #4a7a2a 100%);
}

/* 顶部浮层 */
.fm-head {
  position: absolute;
  z-index: 6;
  top: 14px;
  left: 14px;
  display: flex;
  align-items: center;
  /* 🔴 2026-10-23：允许换行。头部 chip 越来越多（仓库/商店/看板/地图/农场名），
     1600 视口下内容宽度 1044px 已经超出可用空间，
     不换行就必然把某个 chip 挤扁 —— 而挤扁的那个往往正是玩家要看的信息。 */
  flex-wrap: wrap;
  row-gap: 6px;
  gap: 10px;
  padding: 8px 14px;
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.84);
  backdrop-filter: blur(12px) saturate(1.2);
  box-shadow: 0 6px 20px rgba(15, 23, 42, 0.18);
  max-width: calc(100% - 28px);
}
.fm-head .back-link {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 13px;
  color: #2563eb;
  cursor: pointer;
  user-select: none;
  white-space: nowrap;
}
.fm-head .back-link:hover { text-decoration: underline; }
.fm-head .back-icon { font-size: 15px; line-height: 1; }
.fm-head .title {
  margin: 0;
  font-size: 16px;
  font-weight: 650;
  color: #0f172a;
  white-space: nowrap;
}
.head-sep { width: 1px; height: 16px; background: rgba(15, 23, 42, 0.14); }
.hchip {
  font-size: 12px;
  color: #334155;
  background: rgba(15, 23, 42, 0.06);
  border-radius: 999px;
  padding: 3px 9px;
  white-space: nowrap;
  font-variant-numeric: tabular-nums;
}
.hchip b { font-weight: 600; color: #64748b; margin-right: 2px; }
/* 🔴 2026-10-27：成熟阈值 chip —— 未熟时低调，熟了才变绿（视觉上与「可收」呼应） */
.ripe-chip {
  background: rgba(148, 163, 184, 0.18);
  color: #475569;
}
.ripe-chip.ready {
  background: rgba(34, 197, 94, 0.16);
  color: #15803d;
  font-weight: 600;
}
.plot-chip {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  cursor: pointer;
  background: rgba(47, 127, 208, 0.10);
  color: #1d5f96;
  /* 🔴 2026-10-23：作物名可能很长（「水稻·中稻/一季稻（籼）」），
     不限量的话它一个人就把头部撑到 282px，把后面的 chip 全挤没。 */
  max-width: 210px;
  overflow: hidden;
}
.plot-chip b, .plot-chip em { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.plot-chip i { flex: none; }
.plot-chip:hover { background: rgba(47, 127, 208, 0.18); }
.plot-chip em { font-style: normal; font-size: 11px; color: #64748b; }
.plot-chip i { font-style: normal; font-size: 10px; padding: 0 4px; border-radius: 4px; background: #eef7e7; color: #2f7a2a; }
.plot-chip i.l-2 { background: #fff7e6; color: #b45309; }
.plot-chip i.l-3, .plot-chip i.l-4 { background: #fdeeea; color: #b91c1c; }
.plot-chip i.l-5 { background: #f3eefe; color: #7c3aed; }

/* 左下天气条 */
/* 左下角堆叠容器：天气条 + 地块切换条
   🔴 2026-10-22：两者原先各自 position:absolute + bottom 定位，
   窄屏下**完全重叠**（天气数值压在 chip 上），桌面端也压掉 4px。
   改成同一容器内 flex 纵向排列：高度由内容决定，两者永远不打架。 */
.fm-bottom-stack {
  position: absolute;
  z-index: 6;
  left: 16px;
  bottom: 14px;
  right: 16px;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 8px;
  pointer-events: none;
}
.fm-bottom-stack > * { pointer-events: auto; }

.fm-weatherbar {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 14px;
  border-radius: 999px;
  background: rgba(9, 20, 33, 0.55);
  backdrop-filter: blur(10px);
  color: #eaf3fb;
  font-size: 12.5px;
  box-shadow: 0 6px 18px rgba(0, 0, 0, 0.22);
}
.fm-weatherbar .wx-icon { font-size: 16px; }
.fm-weatherbar .wb-strong { font-weight: 600; font-variant-numeric: tabular-nums; }
.fm-weatherbar .wb-item { opacity: 0.92; font-variant-numeric: tabular-nums; }
.fm-weatherbar .wb-item.hot { color: #ffc46b; }
.fm-weatherbar .wb-sep { width: 1px; height: 12px; background: rgba(255, 255, 255, 0.28); }
.fm-weatherbar .muted {
  color: rgba(234, 243, 251, 0.7);
  max-width: 240px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* 右下交互提示 */
.fm-hint {
  position: absolute;
  z-index: 6;
  right: 14px;
  bottom: 16px;
  display: flex;
  gap: 6px;
  align-items: center;
  padding: 6px 12px;
  border-radius: 999px;
  background: rgba(9, 20, 33, 0.42);
  color: rgba(255, 255, 255, 0.82);
  font-size: 11.5px;
  backdrop-filter: blur(8px);
  pointer-events: none;
}
.fm-hint i { opacity: 0.5; font-style: normal; }

/* 🔴🔴 **场景告警浮层的统一定位**（2026-10-26 修复「告警盖住顶栏按钮」）。
 *
 *   问题：4 个告警浮层（pest / frost / wither / germ）各自写死 `top: 64~66px`
 *   且水平居中 → 落在**顶栏 chips 行**（y≈185~208）上，
 *   把「画质 / 成就 / 仓库」等按钮**整个盖住**，
 *   表现是「按钮在 DOM 里、但点不动」（elementFromPoint 返回告警层）。
 *   —— 这正是本项目铁律「DOM 里有 ≠ 玩家看得见」的现实案例。
 *
 *   修法：统一用 `.fm-scene-alert` 定位类，浮层一律落在**顶栏下方**，
 *   且 `pointer-events: none`（纯提示不该拦点击），仅按钮恢复 pointer-events。
 *
 *   ⚠️ 为什么不用 `top: 66px` 再加高 z-index：加高 z-index 只是把「盖住按钮」
 *     换成「盖住更多东西」；真正的错位是**浮层与交互条抢同一条横向带**。
 */
.fm-scene-alert {
  position: absolute;
  z-index: 6;
  /* 顶栏 header 高 74px（含 padding）→ 浮层从 header 之下开始 */
  top: 86px;
  left: 50%;
  transform: translateX(-50%);
  display: flex;
  align-items: center;
  gap: 8px;
  border-radius: 999px;
  box-shadow: 0 6px 20px rgba(120, 30, 10, 0.32);
  backdrop-filter: blur(8px);
  white-space: nowrap;
  max-width: calc(100% - 40px);
  /* 🔴 纯提示不该拦点击（否则会盖住下层任何可点元素） */
  pointer-events: none;
}
/* 浮层里的操作按钮要能点 → 恢复 pointer-events */
.fm-scene-alert :deep(.el-button),
.fm-scene-alert button {
  pointer-events: auto;
}

/* 病虫害告警 */
.fm-pest-alert {
  gap: 6px;
  padding: 7px 16px;
  background: rgba(160, 42, 24, 0.82);
  color: #fff;
  font-size: 12.5px;
  font-weight: 500;
}
.fm-pest-alert .pa-icon { font-size: 14px; }

.fm-frost-alert {
  padding: 8px 10px 8px 16px;
  background: rgba(28, 58, 96, 0.86);
  color: #eaf4ff;
  font-size: 12.5px;
  box-shadow: 0 8px 24px rgba(10, 30, 60, 0.35);
}
.fm-frost-alert .fa-icon { font-size: 15px; }
.fm-frost-alert :deep(.el-button) { color: #9ed0ff; font-size: 12.5px; }

/* 苗期枯死告警（2026-10-22）：本季绝收，必须能结掉 */
.fm-wither-alert {
  padding: 8px 10px 8px 16px;
  background: rgba(112, 38, 30, 0.88);
  color: #ffe8e2;
  font-size: 12.5px;
  box-shadow: 0 8px 24px rgba(60, 16, 10, 0.35);
}
.fm-wither-alert .wa-icon { font-size: 15px; }
.fm-wither-alert .wa-text { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.fm-wither-alert :deep(.el-button) { color: #ffb3a3; font-size: 12.5px; }

/* 出苗不全告警（2026-10-22） */
.fm-germ-alert {
  gap: 7px;
  padding: 6px 14px;
  background: rgba(120, 96, 20, 0.86);
  color: #fff3cd;
  font-size: 12px;
  overflow: hidden;
}
.fm-germ-alert .ga-icon { font-size: 14px; }
.fm-germ-alert .ga-text { overflow: hidden; text-overflow: ellipsis; }

.fm-loading,
.fm-loading-r {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 40px;
}
.fm-loading-r { position: static; padding: 20px; }

/* ---------------- 右：操作栏 ---------------- */
.fm-right {
  display: flex;
  flex-direction: column;
  min-width: 0;
  height: 100%;
  background: #f4f6fa;
  border-left: 1px solid rgba(15, 23, 42, 0.07);
  overflow: hidden;
}

/* 固定操作头 */
.fb-head {
  flex: none;
  padding: 12px 14px 11px;
  background: #fff;
  border-bottom: 1px solid rgba(15, 23, 42, 0.07);
  box-shadow: 0 2px 10px rgba(15, 23, 42, 0.04);
  z-index: 2;
}
.fb-row1 { display: flex; align-items: center; gap: 8px; }
.fb-date {
  font-size: 15px;
  font-weight: 650;
  color: #0f172a;
  letter-spacing: 0.2px;
}
.fb-stage {
  font-size: 11.5px;
  font-weight: 600;
  color: #2f7a2a;
  background: #eaf6e4;
  border-radius: 6px;
  padding: 2px 8px;
}
.fb-dvs { font-size: 11.5px; color: #7b8798; }
.fb-season { margin-left: auto; font-size: 11.5px; color: #94a3b8; }
.fb-demo {
  font-size: 11px;
  color: #b45309;
  background: #fff5e8;
  border: 1px solid #f6dfc0;
  border-radius: 999px;
  padding: 2px 8px;
  cursor: pointer;
  white-space: nowrap;
}

.fb-row2 {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 10px;
}
.fb-row2 .el-button { margin: 0; }
.fb-row2 :deep(.el-button-group .el-button) { padding: 7px 10px; font-size: 12.5px; }
.fb-harvest { min-width: 74px; font-weight: 600; }
.fb-more { color: #64748b; padding: 7px 2px; font-size: 12.5px; }
.grow { flex: 1; }

/* 内容滚动区 */
.fb-scroll {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  overflow-x: hidden;
  overscroll-behavior: contain;
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 12px 12px 26px;
  scrollbar-width: thin;
}
.fb-scroll::-webkit-scrollbar { width: 8px; }
.fb-scroll::-webkit-scrollbar-thumb {
  background: rgba(15, 23, 42, 0.14);
  border-radius: 4px;
  border: 2px solid transparent;
  background-clip: content-box;
}
.fb-scroll::-webkit-scrollbar-thumb:hover { background: rgba(15, 23, 42, 0.26); background-clip: content-box; }

/* 指标条 */
.fb-metrics {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 1px;
  background: rgba(15, 23, 42, 0.07);
  border: 1px solid rgba(15, 23, 42, 0.07);
  border-radius: 12px;
  overflow: hidden;
  flex: none;
}
.metric {
  background: #fff;
  padding: 10px 10px 9px;
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}
.m-lab { font-size: 10.5px; color: #8a94a6; white-space: nowrap; }
.m-val {
  font-size: 15px;
  font-weight: 650;
  color: #0f172a;
  white-space: nowrap;
  line-height: 1.25;
}
.m-val i { font-style: normal; color: #c7ced8; margin: 0 1px; font-weight: 400; }
.m-val.strong { color: #2f7a2a; }
.m-sub {
  font-size: 10.5px;
  color: #a3abb9;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.metric.danger { background: #fff6f6; }
.metric.danger .m-val { color: #d14343; }

/* 卡片 */
.fb-card {
  flex: none;
  background: #fff;
  border: 1px solid rgba(15, 23, 42, 0.07);
  border-radius: 14px;
  padding: 12px 14px;
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.04);
}
.fb-card h3 { margin: 0; font-size: 14px; font-weight: 650; color: #0f172a; }
.panel-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 8px;
}
.muted { color: #8a94a6; font-size: 11.5px; }
.mono { font-variant-numeric: tabular-nums; }

/* 生育时间轴 */
.fc-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 10px;
}
.tl { padding: 2px 0 0; }
.tl-track {
  position: relative;
  height: 10px;
  border-radius: 999px;
  background: #eef1f5;
}
.tl-seg {
  position: absolute;
  top: 0;
  height: 100%;
  background: #e6ebf1;
  border-right: 2px solid #fff;
  box-sizing: border-box;
}
.tl-seg:first-child { border-radius: 999px 0 0 999px; }
.tl-seg:last-child { border-radius: 0 999px 999px 0; border-right: none; }
.tl-seg.done { background: linear-gradient(180deg, #8cc861, #5ea23a); }
.tl-seg.active { background: linear-gradient(180deg, #a8da78, #6cb043); }
.tl-dot {
  position: absolute;
  top: 50%;
  width: 14px;
  height: 14px;
  border-radius: 50%;
  background: #fff;
  border: 3px solid #3f8f2e;
  transform: translate(-50%, -50%);
  box-shadow: 0 1px 5px rgba(20, 60, 10, 0.28);
  transition: left 0.4s ease;
}
.tl-labels {
  position: relative;
  height: 30px;
  margin-top: 5px;
  font-size: 10px;
  color: #a3abb9;
}
.tl-labels span {
  position: absolute;
  top: 0;
  transform: translateX(-50%);
  white-space: nowrap;
  transition: color 0.2s;
}
.tl-labels span.low { top: 14px; }
.tl-labels span.on { color: #2f7a2a; font-weight: 650; }
.tl-meta {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 10px;
  margin-top: 6px;
  font-size: 12px;
}

.fc-kv {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
  margin-top: 10px;
  padding-top: 10px;
  border-top: 1px dashed #eef1f6;
}
.fc-kv dt { font-size: 10.5px; color: #8a94a6; margin-bottom: 2px; }
.fc-kv dd { margin: 0; font-size: 12.5px; color: #1f2937; }

/* 折叠区（产量归因 / 测试天气） */
.fb-details {
  margin-top: 10px;
  border-top: 1px solid #f1f4f8;
  padding-top: 9px;
}
.fb-details > summary {
  cursor: pointer;
  font-size: 12px;
  font-weight: 500;
  color: #4b5c74;
  list-style: none;
  display: flex;
  align-items: center;
  gap: 6px;
  user-select: none;
}
.fb-details > summary::-webkit-details-marker { display: none; }
.fb-details > summary::before {
  content: '';
  width: 0;
  height: 0;
  border-left: 5px solid #94a3b8;
  border-top: 4px solid transparent;
  border-bottom: 4px solid transparent;
  transition: transform 0.16s;
}
.fb-details[open] > summary::before { transform: rotate(90deg); }

.ytable { width: 100%; border-collapse: collapse; font-size: 11.5px; margin-top: 8px; }
.ytable th {
  text-align: left;
  font-weight: 500;
  color: #8a94a6;
  font-size: 10.5px;
  padding-bottom: 5px;
  border-bottom: 1px solid #eef1f6;
}
.ytable td { padding: 5px 0; border-bottom: 1px solid #f5f7fa; color: #334155; }
.ytable td.bad { color: #d14343; }
.note { margin: 8px 0 0; font-size: 10.5px; line-height: 1.6; color: #98a1b0; }

/* 本季投入条 */
.fb-input-strip {
  flex: none;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 10px;
  border-radius: 11px;
  background: #fff;
  border: 1px solid rgba(15, 23, 42, 0.07);
}
.is-cell { display: flex; flex-direction: column; gap: 0; min-width: 0; }
.is-lab { font-size: 9.5px; color: #98a1b0; white-space: nowrap; }
.is-val { font-size: 13px; font-weight: 650; color: #0f172a; white-space: nowrap; }
.is-val i { font-style: normal; font-size: 10px; color: #a3abb9; font-weight: 400; }
.is-val.bad { color: #b91c1c; }
.is-unit { font-size: 9.5px; color: #b0b8c4; }
.is-btn { margin-left: auto; flex: none; }
.fb-phi-warn {
  flex: none;
  margin: -4px 0 0;
  font-size: 10.5px;
  color: #b45309;
  background: #fff8ed;
  border: 1px solid #f7e3c6;
  border-radius: 8px;
  padding: 5px 9px;
  line-height: 1.5;
}

/* 土壤水分 */
.soil-status {
  font-size: 11.5px;
  font-weight: 600;
  border-radius: 6px;
  padding: 2px 8px;
  background: #eff6ff;
  color: #2563eb;
}
.soil-status.s-DRY { background: #fff7e6; color: #b45309; }
.soil-status.s-DROUGHT { background: #fff1ee; color: #c2410c; }
.soil-status.s-FLOODED { background: #e8f4fb; color: #0369a1; }

.water-gauge { display: flex; gap: 4px; margin: 4px 0 10px; }
.water-gauge span {
  flex: 1;
  height: 8px;
  border-radius: 3px;
  background: #eef1f5;
}
.water-gauge span.on { background: linear-gradient(180deg, #7cc4e8, #3d93c4); }
.soil-kv {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 8px;
}
.soil-kv dt { font-size: 10.5px; color: #8a94a6; margin-bottom: 2px; }
.soil-kv dd { margin: 0; font-size: 12.5px; color: #1f2937; }
.soil-kv dd i { font-style: normal; color: #a3abb9; font-size: 10.5px; margin-left: 2px; }
.soil-tip {
  margin: 9px 0 0;
  font-size: 10.5px;
  line-height: 1.55;
  color: #8a94a6;
}

/* 工序链 */
.chain-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: 8px;
}
.chain-progress {
  font-size: 11.5px;
  color: #2f7a2a;
  background: #eef7e7;
  border-radius: 999px;
  padding: 2px 9px;
  font-variant-numeric: tabular-nums;
}
.chain-group { margin-top: 8px; }
.chain-group-title {
  font-size: 10.5px;
  color: #98a1b0;
  letter-spacing: 0.4px;
  margin-bottom: 4px;
}
.chain-list { list-style: none; margin: 0; padding: 0; }
.chain-list li {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 0;
  border-bottom: 1px dashed #f0f3f7;
}
.chain-list li:last-child { border-bottom: none; }
.op-dot {
  flex: none;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #d5dbe3;
}
.op-DONE .op-dot { background: #5ea23a; }
.op-AUTO .op-dot { background: #9bbf86; }
.op-AVAILABLE .op-dot { background: #e8a13c; box-shadow: 0 0 0 3px rgba(232, 161, 60, 0.18); }
.op-MISSED .op-dot { background: #d9534f; }
.op-main { flex: 1; min-width: 0; }
.op-row { display: flex; align-items: center; gap: 6px; }
.op-name { font-size: 12.5px; font-weight: 600; color: #1f2937; }
.op-MISSED .op-name { color: #b23b38; }
.op-LOCKED .op-name { color: #8a94a6; font-weight: 500; }
.op-stars { font-size: 10px; color: #d9a13c; letter-spacing: -1px; }
.op-count { font-size: 10.5px; color: #7b8798; background: #f1f4f8; border-radius: 8px; padding: 0 5px; }
.op-date { margin-left: auto; font-size: 10.5px; color: #a3abb9; }
.op-hint {
  font-size: 10.5px;
  color: #98a1b0;
  margin-top: 1px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.op-AVAILABLE .op-hint { color: #b07824; }
.chain-list .el-button { margin: 0; flex: none; }

/* 病虫害 */
.pest-head,
.ev-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}
.pest-loss {
  font-size: 11px;
  color: #c2410c;
  background: #fff1e6;
  border-radius: 999px;
  padding: 2px 8px;
  font-variant-numeric: tabular-nums;
}
.pest-list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 8px; }
.pest-list li { padding: 8px 10px; border-radius: 10px; background: #faf8f5; border: 1px solid #f0eae3; }
.pest-list li.st-HEAVY { background: #fff7ef; border-color: #f7dcc4; }
.pest-list li.st-SEVERE { background: #fff2ef; border-color: #f6cdc4; }
.pest-row { display: flex; align-items: center; gap: 6px; font-size: 12.5px; }
.pest-name { font-weight: 650; color: #0f172a; }
.pest-type {
  font-size: 10.5px;
  color: #7c5a3a;
  background: #f4ead9;
  border-radius: 4px;
  padding: 1px 5px;
}
.pest-status { font-size: 11px; color: #b45309; }
.pest-list li.st-HEAVY .pest-status,
.pest-list li.st-SEVERE .pest-status { color: #c2410c; font-weight: 600; }
.pest-di { margin-left: auto; font-size: 11px; color: #98a1b0; font-variant-numeric: tabular-nums; }
.pest-bar { height: 5px; border-radius: 999px; background: #efe7df; margin: 6px 0 4px; overflow: hidden; }
.pest-bar i { display: block; height: 100%; border-radius: 999px; background: linear-gradient(90deg, #f0b45a, #d9663a); transition: width 0.4s; }
.pest-advice { margin: 0; font-size: 11px; color: #8a7a68; line-height: 1.5; }
.pest-empty { margin: 2px 0 4px; }
.pest-tip { margin: 8px 0 0; font-size: 10.5px; line-height: 1.55; }

/* 灾害 */
.ev-loss {
  font-size: 11px;
  color: #7c3aed;
  background: #f3eefe;
  border-radius: 999px;
  padding: 2px 8px;
  font-variant-numeric: tabular-nums;
}
.ev-list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 7px; }
.ev-list li { padding: 7px 10px; border-radius: 9px; background: #f8fafc; border: 1px solid #eef2f7; }
.ev-list li.ev-FLASH_FLOOD { background: #f2f8ff; border-color: #d9e8fa; }
.ev-list li.ev-FREEZE { background: #f4f9ff; border-color: #dbe7f5; }
.ev-row { display: flex; align-items: center; gap: 8px; font-size: 12.5px; }
.ev-date { color: #98a1b0; font-size: 11px; }
.ev-name { font-weight: 600; color: #1f2937; }
.ev-pct { margin-left: auto; font-size: 11.5px; color: #b91c1c; font-variant-numeric: tabular-nums; }
.ev-note { margin: 4px 0 0; font-size: 10.5px; color: #8a94a6; line-height: 1.5; }
.ev-empty { margin: 2px 0; }

/* 今日变化 / 历史 */
.changes { margin: 0; padding-left: 15px; font-size: 12px; line-height: 1.85; color: #334155; }
.changes li::marker { color: #b6c3d4; }
.fb-grid2 { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
.recent { margin: 0; padding: 0; list-style: none; }
.recent li {
  display: flex;
  justify-content: space-between;
  padding: 5px 0;
  border-bottom: 1px solid #f5f7fa;
  font-size: 12px;
}
.recent .score { font-weight: 650; color: #0f172a; }
.empty { margin: 4px 0; }

/* ============ S26：历史成绩标签页 + 排行榜 ============ */
.score-tabs { margin-top: -4px; }
.score-tabs :deep(.el-tabs__header) { margin-bottom: 6px; }

/* 🔴 画质入口上的实测帧率徽标（2026-10-26 画质分级） */
.wq-fps {
  margin-left: 4px;
  padding: 1px 5px;
  border-radius: 8px;
  background: #e2e8df;
  color: #4d5f50;
  font-size: 10.5px;
  font-style: normal;
  font-weight: 600;
}

.score-tabs :deep(.el-tabs__item) { font-size: 12px; height: 28px; line-height: 28px; }
.lb-list { margin: 0; padding: 0; list-style: none; }
.lb {
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 4px 0;
  border-bottom: 1px solid #f5f7fa;
  font-size: 12px;
}
.lb:last-child { border-bottom: none; }
/* 前三名用金/银/铜色，其余用中性灰 —— 与 2048 排行榜同一套视觉语言 */
.lb .rank {
  flex: 0 0 18px;
  text-align: center;
  font-weight: 700;
  font-size: 11px;
  color: #94a3b8;
  font-variant-numeric: tabular-nums;
}
.lb .rank.r1 { color: #d4a017; }
.lb .rank.r2 { color: #94a3b8; }
.lb .rank.r3 { color: #b45309; }
.lb .who {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: #334155;
}
.lb .sc { font-weight: 650; color: #0f172a; font-variant-numeric: tabular-nums; }
/* 自己的成绩高亮 —— 让玩家一眼找到「我排第几」 */
.lb.me { background: #f0f9eb; }
.lb.me .who { color: #4d7c2a; font-weight: 650; }
.lb-foot { margin: 6px 0 0; font-size: 10.5px; line-height: 1.5; }
.history { list-style: none; margin: 0; padding: 0; }
.history li {
  display: flex;
  gap: 7px;
  align-items: center;
  padding: 4px 0;
  border-bottom: 1px dashed #f0f3f7;
  font-size: 11.5px;
  color: #475569;
}
.history li:last-child { border-bottom: none; }
.history .hd { color: #94a3b8; font-size: 11px; }
.action-tag {
  display: inline-block;
  padding: 1px 6px;
  background: #ecf5ff;
  color: #409eff;
  border-radius: 10px;
  font-size: 10.5px;
}

/* 测试天气 */
.fb-mock > summary {
  cursor: pointer;
  font-size: 12.5px;
  font-weight: 600;
  color: #0f172a;
  list-style: none;
}
.fb-mock > summary::-webkit-details-marker { display: none; }
.mock-desc { margin: 6px 0 8px; line-height: 1.5; }
.mock-presets { display: flex; flex-wrap: wrap; gap: 6px; }
.mock-presets .el-button { margin: 0; }
.mock-list { list-style: none; margin: 8px 0 0; padding: 0; }
.mock-list li { display: flex; gap: 8px; font-size: 11px; color: #64748b; padding: 3px 0; }
.mock-empty { margin: 6px 0 0; }

/* 演示说明气泡 */
.fb-demo-note {
  margin: 9px 0 0;
  padding: 8px 10px;
  border-radius: 8px;
  background: #fffaF2;
  border: 1px solid #f6e6cd;
  font-size: 11.5px;
  line-height: 1.7;
  color: #6b5a44;
}
.fb-demo-note b { color: #8a5a12; margin-right: 4px; }

/* 玩法说明弹窗 */
.rules p { margin: 0 0 8px; font-size: 13px; line-height: 1.75; }

/* ============ WebGL 退化田地（结构沿用旧版 SVG） ============ */
.fm-fallback { position: absolute; inset: 0; overflow: hidden; }
.season-bg { position: absolute; inset: 0; z-index: 0; opacity: 0.35; }
.horizon { position: absolute; top: 0; left: 0; right: 0; height: 28%; z-index: 1; pointer-events: none; }
.mountains { position: absolute; bottom: 0; left: 0; width: 100%; height: 80%; opacity: 0.55; }
.skyline {
  position: absolute;
  bottom: 0;
  left: 0;
  right: 0;
  height: 1px;
  background: linear-gradient(90deg, transparent, rgba(255, 255, 255, 0.5), transparent);
}
.trees { position: absolute; inset: 0; z-index: 2; }
.tree { position: absolute; bottom: 0; }
.field-surface {
  position: absolute;
  top: 22%;
  bottom: 0;
  left: 0;
  right: 0;
  z-index: 3;
  background:
    linear-gradient(180deg, rgba(200, 225, 240, 0.35) 0%, rgba(170, 205, 190, 0.15) 18%, transparent 35%),
    repeating-linear-gradient(180deg, transparent 0, transparent 16px, rgba(220, 235, 245, 0.08) 16px, rgba(220, 235, 245, 0.08) 17px),
    linear-gradient(180deg, #7a6a4a 0%, #6b5230 25%, #5a3e1a 60%, #46300f 100%);
}
.irrigation-channel {
  position: absolute;
  top: 30%;
  bottom: 0;
  width: 4px;
  background: linear-gradient(180deg, rgba(120, 180, 220, 0.7), rgba(80, 140, 180, 0.9));
}
.irrigation-channel.left { left: 18%; }
.irrigation-channel.right { right: 18%; }
.ridge {
  position: absolute;
  top: 30%;
  height: 2px;
  left: 22%;
  right: 22%;
  background: linear-gradient(90deg, transparent, rgba(101, 67, 33, 0.5) 20%, rgba(101, 67, 33, 0.5) 80%, transparent);
}
.plotgrid { position: absolute; top: 24%; bottom: 4%; left: 0; right: 0; z-index: 4; pointer-events: none; }
.plotgrid > :deep(.plant-cell) {
  position: absolute;
  width: 18%;
  aspect-ratio: 6 / 10;
  height: auto !important;
  left: var(--cx, 0);
  top: var(--cy, 0);
  transform: scale(var(--cs, 1)) rotate(var(--cr, 0deg));
  transform-origin: 50% 100%;
}
.plotgrid :deep(.ground) { display: none !important; }
.plotgrid :deep(.perspective-wrap) { transform: none !important; }

/* ============ 响应式（PC 优先；<1180 收窄右栏，<900 才堆叠） ============ */
@media (max-width: 1440px) {
  .fm-page { grid-template-columns: minmax(0, 1fr) clamp(380px, 32vw, 440px); }
  .fb-metrics { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}

@media (max-width: 1180px) {
  .fm-page { grid-template-columns: minmax(0, 1fr) 380px; }
  .fm-head .title { font-size: 15px; }
  .hchip:nth-of-type(2) { display: none; }
}

@media (max-width: 900px) {
  .fm-page {
    grid-template-columns: 1fr;
    grid-template-rows: 56vh auto;
    height: auto;
    max-height: none;
    min-height: calc(100dvh - 52px);
    overflow: visible;
  }
  .fm-stage { height: 56vh; }
  .fm-right { height: auto; border-left: none; border-top: 1px solid rgba(15, 23, 42, 0.06); }
  .fb-scroll { overflow: visible; }
  .fm-hint { display: none; }
  .fm-weatherbar { font-size: 11.5px; gap: 8px; padding: 6px 10px; }
  /* 堆叠容器跟着场景一起上移（场景只有 56vh），别压在场景外 */
  .fm-bottom-stack { bottom: 8px; left: 8px; right: 8px; gap: 6px; }
}

@media (max-width: 480px) {
  .fm-head { left: 8px; right: 8px; top: 8px; padding: 6px 10px; gap: 8px; }
  .fm-head .back-txt { display: none; }
  .hchip { display: none; }
  /* 🔴 2026-10-23：农场名 chip 必须留着 ——
     「当前在哪个农场」是身份信息，窄屏上恰恰更需要它
     （玩家分不清哪个农场）。上面的 `.hchip{display:none}` 是为了省空间，
     但把身份信息一起藏了就本末倒置了。 */
  .fm-head .farm-chip {
    display: inline-flex;
    align-items: center;
    max-width: 46vw;
    font-size: 11.5px;
    padding: 3px 8px;
  }
  .fm-weatherbar .wb-sep, .fm-weatherbar .wb-item.muted { display: none; }
  .fm-weatherbar { gap: 6px; }
  .fb-metrics { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .fb-grid2 { grid-template-columns: 1fr; }

  /* ---- 地块条紧凑模式 ----
     窄屏一屏放不下几个 chip，作物名+阶段全显示的话更看不到几块。
     改成只显示编号，选中块才展开显示作物名。 */
  .fm-plotrail-wrap { gap: 4px; }
  .pr-nav { width: 24px; height: 30px; font-size: 15px; }
  .pr-count { height: 24px; line-height: 22px; padding: 0 7px; font-size: 11px; }
  .fm-plotrail { gap: 6px; padding: 5px 2px; }
  .pr-chip {
    height: 30px;
    padding: 0 9px;
    font-size: 11.5px;
    gap: 0;
  }
  .pr-chip .pr-crop,
  .pr-chip .pr-stage { display: none; }
  .pr-chip.on { padding: 0 11px; gap: 5px; }
  .pr-chip.on .pr-crop {
    display: inline;
    max-width: 74px;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .pr-add { width: 30px; }
}
</style>
