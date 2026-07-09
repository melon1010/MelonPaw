---
name: public_api
description: "帮助用户查找、筛选和推荐可调用的公开 API / 免费 API / 开放数据接口。适用于用户询问天气、汇率、地理、测试数据、新闻 API、政府开放数据、设计素材、开发工具等接口来源；不用于直接总结最新新闻，最新新闻请求应使用 news 技能。"
metadata:
  builtin_skill_version: "1.0"
  qwenpaw:
    emoji: "🔌"
    requires: {}
---

# Public API Finder

当用户询问"有没有某类 API"、"找一个免费的接口"、"推荐开放数据源"、"哪里有 JSON API"、"给我一个测试 API"、"找天气/汇率/地理/新闻 API"时，使用本技能。

本技能用于**寻找 API 候选**，不是直接替代专业数据源。返回候选时必须说明认证方式、HTTPS、CORS、文档入口和使用风险。

## 与 news 技能的边界

- 用户要"今天新闻"、"最新新闻"、"帮我总结新闻"、"某类新闻要点"时，使用 `news` 技能。
- 用户要"新闻 API"、"有没有可调用的新闻接口"、"用接口获取新闻数据"时，使用本技能。
- 如果用户既要新闻内容又要 API，例如"找个新闻 API 并抓今天科技新闻"，先用本技能选择接口，再按接口文档获取数据；如接口不可用，回退到 `news` 技能的网页来源。

## 使用策略

1. 先确认用户需要的 API 类别、认证限制、是否必须免费、是否要求 CORS、是否用于生产。
2. 优先从下方精选候选中选择 3 到 6 个，不要一次列出过多。
3. 如果用户要求"最新/更多/指定国家或行业"，再访问公开 API 目录：
   - Public APIs 目录页面：https://github.com/public-apis/public-apis
   - Public APIs 查询服务：https://api.publicapis.org/entries
   - 分类查询：https://api.publicapis.org/categories
4. 输出候选时使用固定字段：名称、用途、认证、HTTPS、CORS、文档 URL、适合场景、注意事项。
5. 对金融、医疗、法律、安全、个人数据相关 API，要提醒用户核对服务条款、限流、数据授权和隐私要求。

## 精选候选

### API 目录

| 名称 | URL | Auth | 用途 |
|------|-----|------|------|
| Public APIs | https://api.publicapis.org/entries | No | 查询社区维护的公开 API 列表 |
| Public APIs Categories | https://api.publicapis.org/categories | No | 查看公开 API 分类 |

### 天气和环境

| 名称 | URL | Auth | 适合场景 |
|------|-----|------|----------|
| Open-Meteo | https://open-meteo.com/ | No | 天气预报、历史天气、地理编码 |
| wttr.in | https://wttr.in/ | No | 快速文本天气、命令行 demo |
| MET Norway | https://api.met.no/ | No | 欧洲和全球天气预报 |
| RainViewer | https://www.rainviewer.com/api.html | No | 雷达图、降雨图层 |
| OpenAQ | https://docs.openaq.org/ | No / apiKey | 空气质量数据 |

### 汇率和金融数据

| 名称 | URL | Auth | 适合场景 |
|------|-----|------|----------|
| Frankfurter | https://www.frankfurter.app/ | No | 汇率换算、历史汇率 |
| Currency API | https://github.com/fawazahmed0/currency-api | No | 静态汇率 JSON，适合 demo |
| VATComply | https://www.vatcomply.com/documentation | No | 汇率和 VAT 信息 |
| SEC EDGAR | https://www.sec.gov/edgar/sec-api-documentation | No | 美国上市公司披露文件 |
| OpenFIGI | https://www.openfigi.com/api | apiKey | 证券标识映射 |

### 地理、国家和地址

| 名称 | URL | Auth | 适合场景 |
|------|-----|------|----------|
| Nominatim | https://nominatim.org/release-docs/latest/api/Overview/ | No | OpenStreetMap 地理编码 |
| REST Countries | https://restcountries.com/ | No | 国家、货币、语言、地区信息 |
| Postcodes.io | https://postcodes.io/ | No | 英国邮编查询 |
| Open Topo Data | https://www.opentopodata.org/ | No | 海拔查询 |
| GeoNames | https://www.geonames.org/export/web-services.html | username | 地名、时区、行政区 |

### 开发、测试和工具

| 名称 | URL | Auth | 适合场景 |
|------|-----|------|----------|
| JSONPlaceholder | https://jsonplaceholder.typicode.com/ | No | 前端 demo、测试 CRUD |
| ReqRes | https://reqres.in/ | No / apiKey | 登录、分页、用户列表 mock |
| HTTPBin | https://httpbin.org/ | No | HTTP 请求调试 |
| DummyJSON | https://dummyjson.com/ | No | 商品、用户、帖子等假数据 |
| npm Registry | https://github.com/npm/registry/blob/master/docs/REGISTRY-API.md | No | 查询 npm 包信息 |
| Shields.io | https://shields.io/ | No | 生成徽章 |
| Kroki | https://kroki.io/ | No | Mermaid、PlantUML 等图表渲染 |

### 新闻 API

这些来源只用于"找新闻接口"。如果用户要新闻摘要或热点新闻，使用 `news` 技能。

| 名称 | URL | Auth | 适合场景 |
|------|-----|------|----------|
| Spaceflight News API | https://api.spaceflightnewsapi.net/ | No | 航天新闻 |
| The Guardian Open Platform | https://open-platform.theguardian.com/ | apiKey | 英文新闻检索 |
| New York Times APIs | https://developer.nytimes.com/apis | apiKey | NYT 新闻、档案、书评 |
| NewsAPI | https://newsapi.org/ | apiKey | 聚合新闻标题和来源 |
| NewsData.io | https://newsdata.io/ | apiKey | 多语言新闻聚合 |
| GNews | https://gnews.io/ | apiKey | 新闻搜索和头条 |

### 政府和开放数据

| 名称 | URL | Auth | 适合场景 |
|------|-----|------|----------|
| data.gov | https://www.data.gov/developers/apis | No / varies | 美国政府开放数据 |
| USAspending | https://api.usaspending.gov/ | No | 美国联邦支出 |
| Federal Register | https://www.federalregister.gov/developers/documentation/api/v1 | No | 美国联邦法规公告 |
| FBI Wanted | https://www.fbi.gov/wanted/api | No | FBI 通缉公开数据 |
| UK Police API | https://data.police.uk/docs/ | No | 英国警务公开数据 |

### 设计、图片和内容素材

| 名称 | URL | Auth | 适合场景 |
|------|-----|------|----------|
| Art Institute of Chicago | https://api.artic.edu/docs/ | No | 艺术品数据和图片 |
| Metropolitan Museum of Art | https://metmuseum.github.io/ | No | 博物馆藏品 |
| Lorem Picsum | https://picsum.photos/ | No | 占位图片 |
| DiceBear | https://www.dicebear.com/how-to-use/http-api/ | No | 头像生成 |
| Icon Horse | https://icon.horse/ | No | 网站 favicon 获取 |
| xColors | https://x-colors.yurace.pro/ | No | 随机颜色和调色 |

### 安全和信誉检查

| 名称 | URL | Auth | 适合场景 |
|------|-----|------|----------|
| URLhaus | https://urlhaus-api.abuse.ch/ | No | 恶意 URL 检查 |
| EmailRep | https://emailrep.io/ | No / apiKey | 邮箱信誉 |
| Have I Been Pwned | https://haveibeenpwned.com/API/v3 | apiKey | 泄露检查 |
| GreyNoise | https://docs.greynoise.io/ | apiKey | IP 噪声和扫描行为 |
| GitGuardian | https://api.gitguardian.com/doc | apiKey | 密钥泄露扫描 |

## 回复格式

优先用简短表格：

| API | Auth | HTTPS/CORS | 适合 | 注意 |
|-----|------|------------|------|------|
| 名称 + 链接 | No / apiKey / OAuth | Yes / Unknown | 一句话 | 限流、许可、生产风险 |

然后补充：

- 推荐首选：说明为什么最适合当前用户需求。
- 接入建议：给出最小可行请求方式或文档入口。
- 风险提醒：生产使用前核对条款、限流、稳定性和数据授权。

## 授权和来源

本技能参考 public-apis/public-apis 的公开 API 目录、davemachado/public-api 查询服务和相关 API 官方文档。public-apis/public-apis 使用 MIT License；davemachado/public-api 使用 Apache-2.0 License。如果复制其数据快照、批量分发列表或复用服务代码，需要保留对应版权和许可声明。
