# ============ 小说智能体平台 - 前端 Nginx Dockerfile ============
# 多阶段: Node 20 构建 → Nginx 运行
FROM node:20-alpine AS builder
WORKDIR /app
COPY package.json package-lock.json* ./
RUN npm install --no-audit --no-fund --prefer-offline
COPY . .
RUN npm run build && ls dist/

FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=builder /app/dist /usr/share/nginx/html

EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
