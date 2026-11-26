# 前端静态站点镜像
FROM node:26-alpine AS build
WORKDIR /build
# Node 25 起不再随发行版分发 corepack，pnpm 只能自己装。
# 固定在 10.x：11/12 默认的 minimumReleaseAge 供应链策略会把刚发布的依赖判为不可用。
RUN npm install -g pnpm@10.34.9
# pnpm-workspace.yaml 要一起进来，allowBuilds（esbuild 安装脚本）只在这里生效。
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
RUN pnpm install --frozen-lockfile
COPY . .
RUN pnpm build

FROM nginx:alpine
LABEL org.opencontainers.image.licenses="Apache-2.0"
COPY --from=build /build/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
