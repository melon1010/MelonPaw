# 开源发布检查清单

这份清单用于把仓库从“个人/内部项目”调整到“可以公开协作”的状态。

## 必须确认

- [ ] 许可证选择已确认。当前仓库使用 Apache-2.0；如改用 MIT/GPL/私有许可，需要同步修改 `LICENSE`、`NOTICE`、`pom.xml` 和 README。
- [ ] `pom.xml` 中的项目 URL、SCM、issue 地址已指向最终公开仓库。
- [ ] `NOTICE` 中的版权主体已确认。
- [ ] README 中不存在夸大能力；兼容桩、未实现功能、实验能力要明确说明。
- [ ] 仓库中没有真实 API Key、token、账号密码、备份包、日志或用户数据。
- [ ] 内置第三方素材、脚本、schema、图标、前端资源的许可证允许再分发。
- [ ] `melon-app/src/main/resources/builtin-skills/**` 的来源、许可证和改动记录已梳理。
- [ ] 安全风险已评估，尤其是文件工具、Shell 工具、浏览器自动化、插件/技能导入、备份恢复。
- [ ] 默认配置不应在公网环境下无认证暴露管理接口。

## Java 规范建议

参考标准：

- Google Java Style Guide：命名、导入、格式和结构规则。
- Spring Framework Code Style：Spring 项目常见组织方式和可读性习惯。
- Alibaba Java Coding Guidelines：中文团队常用的工程规约、安全和异常处理建议。

当前仓库更适合采用“增量收敛”策略：

- 新代码严格按 `CONTRIBUTING.md` 和 `.editorconfig` 提交。
- 老代码不要为了格式化产生超大 diff，结合功能改动逐步整理。
- 大规模格式化应单独提交，避免和行为变更混在一起。
- 对安全边界、公共 API、插件 SPI 的改动优先补测试。

## 推荐发布流程

1. 修复 `doc/CODE_ANALYSIS.md` 中的 P0 安全问题。
2. 开启 GitHub branch protection，要求 CI 通过后才能合并。
3. 启用 GitHub Security Advisory 和 Dependabot。
4. 跑一次依赖许可证扫描和 secret 扫描。
5. 发布第一个非 SNAPSHOT 版本，例如 `0.1.0`。
