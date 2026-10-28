.DEFAULT_GOAL := help
SHELL := /bin/bash
MVN ?= mvn
export MVN

help: ## 列出所有命令
	@grep -E '^[a-z-]+:.*?## ' $(MAKEFILE_LIST) | awk 'BEGIN{FS=":.*?## "}{printf "  \033[36m%-15s\033[0m %s\n",$$1,$$2}'

setup: hooks ## 首次克隆：启用 git hooks、下载依赖并安装共享模块
	$(MVN) -q -B install -DskipTests

hooks: ## 启用 git pre-commit（Codex/人类提交时同样走 verify）
	git config core.hooksPath .githooks

verify: ## 增量质量门：只测改动影响的模块 + 契约检查
	@scripts/harness/verify.sh

ci: ## 全量质量门（CI 同款）
	@scripts/harness/verify.sh --all

test: ## 全量测试
	$(MVN) -q -B verify

lint-spec: ## 契约规范检查
	@scripts/harness/contract-cli.sh lint

contract-diff: ## 契约相对 main 的变更（含破坏性检测）
	@scripts/harness/contract-diff.sh

sdk: ## 由契约生成前端 TS SDK -> web-sdk/src/gen
	@scripts/dev/sdk.sh

dev: ## 本地启动全部真实服务 + gateway(:8080)
	@scripts/dev/run.sh dev

mock: ## 按契约启动 Prism mock + gateway(:8080)，前端可先行
	@scripts/dev/run.sh mock

up: ## docker compose 启动
	docker compose up --build

new-service: ## 新服务骨架：make new-service NAME=payment
	@scripts/dev/new-service.sh $(NAME)

.PHONY: help setup hooks verify ci test lint-spec contract-diff sdk dev mock up new-service
