# 贡献指南

感谢你愿意改进 melonPaw。提交前请先确认改动范围清晰、能本地构建，并且没有提交本机密钥、日志、备份包或用户数据。

## 开发环境

- JDK 17+
- Maven 3.8+
- Node.js 18+（仅开发 `console/` 前端时需要）

## 本地检查

后端基础构建：

```bash
mvn test
```

可选质量检查：

```bash
mvn -Pquality checkstyle:check
```

前端检查：

```bash
cd console
npm install
npm run lint
npm run test:run
```

## Java 代码规范

本仓库采用“以 Google Java Style 为主、兼容 Spring/Alibaba 常见实践”的规范：

- Java 使用 4 空格缩进，避免 tab。
- 类名使用 `UpperCamelCase`，方法、字段、局部变量使用 `lowerCamelCase`，常量使用 `UPPER_SNAKE_CASE`。
- 优先使用构造器注入，避免新增字段注入。
- Controller 保持薄层，业务逻辑放到 Service/Core 层。
- 文件、Shell、网络等敏感能力必须走现有安全/审批抽象，不能绕过。
- 公共工具方法放入合适的 `util` 或领域服务，避免复制私有方法。
- 新增用户可见行为时，补充 self-check 或 JUnit 测试；新增修复要尽量覆盖回归场景。

## Pull Request 要求

- 一个 PR 只解决一个明确问题。
- PR 描述中说明动机、主要变更、测试结果和兼容性影响。
- 行为变更需要更新 README 或相关文档。
- 不要提交 `target/`、`node_modules/`、本地配置、密钥、备份包或运行时数据。

## 开源前确认

请维护者在首次公开前完成 [开源发布检查清单](docs/OPEN_SOURCE_CHECKLIST.md)。
