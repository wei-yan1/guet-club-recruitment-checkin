# 桂电社团招新签到系统 · GUET Club Recruitment Check-in System

> 面向桂林电子科技大学（GUET）社团招新的现场签到与机试成绩管理系统，按 **上百人规模**（单场 100～300 名新生）的现场场景设计：扫码签到、自动分配 OJ 账号、机试成绩抓取与二维码查询，一条流程跑完。

**关键词**：桂林电子科技大学 · 社团招新 · 签到系统 · 新生报名 · OJ 机试成绩 · 二维码查询

---

## 1. 这个系统解决什么问题

每年社团招新现场都有几百名新生排队：登记信息、领取机试账号、等成绩、查成绩，全靠纸笔和表格非常容易乱。本项目把这段流程做成一个可直接在局域网部署的 Web 系统：

- 新生到场后由工作人员核验学号，系统自动从候选人名单匹配信息；
- 自动分配一个未被占用的 OJ 账号和密码，不需要人工登记；
- 机试结束后一键抓取 OJ 比赛成绩，按上午/下午两场分别入库；
- 新生扫二维码即可查询自己的账号、密码与成绩，不用工作人员逐个回复。

设计目标是 **现场能扛住上百人同时扫码查询**，而不是一个课程演示项目。

## 2. 功能特性

| 模块 | 说明 |
| --- | --- |
| 管理员登录 | 口令 + 账号密码双重入口，登录后签发 12 小时有效的 Bearer Token |
| 候选人匹配 | 按 10 位学号（25/26 开头）自动匹配报名表，也支持人工核验录入 |
| OJ 账号分配 | 优先使用候选人自带账号，否则从预留账号池原子分配，避免并发重复分配 |
| 现场签到 | 记录签到时间，生成 16 位随机 `publicId` 作为二维码查询凭据 |
| 成绩同步 | 按场次（AM/PM）抓取 OJ 比赛榜单，按 OJ 账号匹配、学号兜底，幂等写入 |
| 扫码查询 | `GET /api/public/scores/{publicId}` 免登录查询，60 秒缓存，支持现场高并发 |
| 管理后台 | 单页后台：名单、签到、成绩、爬虫配置、二维码生成 |
| 运行时可配置 | OJ 比赛 ID、比赛密码、Cookie 存于数据库 `app_config`，后台改完即生效，无需重启 |

## 3. 技术栈

- **后端**：Spring Boot 4.1 / Java 17 / MyBatis-Plus 3.5 / Spring Security Crypto（BCrypt）
- **数据库**：MySQL 8（`student_login_crud`）
- **前端**：原生 HTML + CSS + JS 单页后台，无前端构建步骤
- **文档**：springdoc-openapi（启动后可访问 Swagger UI）
- **部署**：单机 `mvn spring-boot:run` 或 `start.cmd`，适合校内笔记本/社团服务器直接跑

## 4. 快速开始

### 4.1 准备环境

- JDK 17+（推荐 21）
- MySQL 8，创建库 `student_login_crud`（字符集 `utf8mb4`）
- Maven 3.9+，或直接使用仓库内的 `mvnw`

### 4.2 配置（重要）

仓库里 **不包含任何密码**。数据库口令、管理员入口口令都通过环境变量或本地文件注入：

```bash
cp config/local.properties.example config/local.properties
# 编辑 config/local.properties，填入数据库口令与管理员入口口令
```

`config/local.properties` 已被 `.gitignore` 排除；也可以用环境变量覆盖：

| 变量 | 用途 |
| --- | --- |
| `SPRING_DATASOURCE_URL` / `SPRING_DATASOURCE_USERNAME` / `SPRING_DATASOURCE_PASSWORD` | 数据库连接 |
| `ADMIN_ACCESS_PASSPHRASE` | 管理后台入口口令（必填，留空则任何口令都无法进入） |
| `APP_PUBLIC_BASE_URL` | 二维码里写入的对外地址，如 `http://192.168.10.102:8080` |
| `CANDIDATE_PROFILE_SEED` / `OJ_RESERVED_ACCOUNT_SEED` | 候选人名单、预留 OJ 账号的本地 SQL 种子文件路径 |
| `OJ_BASE_URL` / `OJ_CONTEST_ID_AM` / `OJ_CONTEST_ID_PM` / `OJ_CONTEST_PASSWORD_AM` / `OJ_CONTEST_PASSWORD_PM` / `OJ_SESSION_COOKIE` | OJ 爬虫首次启动默认值（运行时以后台配置为准） |

### 4.3 启动

```bash
# Windows
start.cmd            # 默认 8080，端口占用会自动退到 8081-8099
# 或
mvnw.cmd spring-boot:run
```

启动后浏览器打开 `http://localhost:8080/`，先在后台创建管理员账号，再进行签到。

## 5. 数据初始化

候选人名单与预留 OJ 账号属于 **含个人信息的敏感数据**，因此 **不随仓库分发**：

1. 把名单整理成 `candidate_profile` 的 `INSERT` 语句，放到本机任意路径；
2. 通过 `CANDIDATE_PROFILE_SEED` 指向该文件，启动时自动导入（`INSERT IGNORE`，重复启动不会重复插入）；
3. 预留账号同理，用 `OJ_RESERVED_ACCOUNT_SEED` 指向。

表结构见 `src/main/resources/schema.sql`。

## 6. API 摘要

管理端（需 `Authorization: Bearer <token>`）：

- `POST /api/admin/login` / `POST /api/admin/logout`
- `POST /api/students`、`PUT /api/students/{id}`、`GET /api/students/{id}`、`DELETE /api/students/{id}`
- `POST /api/students/sync-scores` 一次性抓取两场比赛成绩
- `GET /api/students/check?studentNo=`、`GET /api/students/match?studentNo=`
- `GET|PUT /api/admin/oj-config` 爬虫配置

公开端（免登录）：

- `GET /api/public/scores/{publicId}` 扫码查成绩

> `publicId` 为 16 位安全随机串，只有拿到二维码的人才能查询对应记录。

## 7. 现场部署建议（上百人规模）

- **网络**：把笔记本/服务器接到现场同一台路由器，固定 IP，二维码里写这个局域网地址；不要用 `localhost`。
- **连接池**：`spring.datasource.hikari.maximum-pool-size` 建议 20～30，够 200 人扫码并发。
- **Tomcat**：默认 200 线程足够；若同时签到 + 查分，可把 `server.tomcat.threads.max` 调到 300。
- **静态资源**：前端是纯静态文件，建议由 Nginx 前置托管并开启 gzip，后端只处理 API。
- **数据库**：现场机建议 MySQL 本地部署并关闭 `useSSL`（局域网内），避免握手开销。
- **回滚**：正式使用前先 `mysqldump` 备份一次，签到当天再导一次。

## 8. 安全与隐私

本仓库按“可以公开”的标准整理过，请保持这一点：

- **不含任何真实学生信息**：候选人名单、OJ 预留账号、导出的比赛代码与日志全部在 `.gitignore` 中，只在本地提供；
- **不含口令**：数据库口令、管理员入口口令通过 `config/local.properties` 或环境变量注入，配置文件示例只有 `change-me`；
- **历史提交干净**：发布时使用全新的提交历史，旧仓库中曾出现的运行日志（含学号与姓名）不会被带到公开仓库；
- **令牌**：登录 Token 由 `SecureRandom` 生成，32 字节，12 小时过期，仅存内存；
- **建议**：正式环境把管理员密码改为 BCrypt 存储、入口口令用 20 位以上随机串、对外只暴露 80/443 并前置 HTTPS；公网部署时给 `/api/public/scores/**` 加限流。

发现安全问题或需要脱敏的数据，请勿直接在 Issue 里贴个人信息。

## 9. 目录结构

```text
src/main/java/org/example/studentlogincrud
├── config/       # Token 校验切面、字段迁移、Web 配置
├── controller/   # 管理端、公开查询、OJ 配置
├── service/      # 签到、成绩同步、OJ 爬虫、口令校验
├── mapper/       # MyBatis-Plus Mapper
├── entity/ dto/  # 数据模型
src/main/resources
├── static/       # 后台与扫码页面（原生 HTML/JS）
├── db/           # schema.sql；种子文件仅本地存在
└── application.properties
config/           # local.properties（已忽略）与示例
tools/            # OJ 榜单导出脚本
```

## 10. 说明

本项目由社团技术组在招新现场迭代而成，用于校内社团招新场景。如果你也要在校内办活动，欢迎参考；涉及真实学生数据时，请自行做好脱敏与访问控制。
