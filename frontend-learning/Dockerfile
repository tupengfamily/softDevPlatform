# ===========================================
# 前端 Dockerfile(多阶段构建)
# 构建: node:22-alpine (vite build)
# 运行: nginx:1.27-alpine (静态资源 + /api 反向代理到后端)
# ===========================================

# ---- 构建阶段 ----
FROM node:22-alpine AS build
WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci

COPY . .
RUN npm run build

# ---- 运行阶段 ----
FROM nginx:1.27-alpine

COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html

EXPOSE 80
