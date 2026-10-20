# 教学商城后端

Java 21、Spring Boot 3.5.6、MySQL 8、Flyway、Spring Security、JPA 的模块化单体，包含内容、交易、权益、分销和平台适配。

## 本地启动

1. 准备专用 MySQL 数据库和用户。
2. 复制 application-local.properties.example 为 application-local.properties，填写本机数据库和微信配置。该文件不入库；已有管理员时无需再次配置初始化密码。
3. Windows 运行 start-local.cmd，启用 dev,local；IDEA 可使用 .run/Jxsc Local.run.xml。

properties 值不加引号，反斜杠需转义。真实微信登录须前后端 AppID 一致、身份提供者 REAL、关闭前端开发登录。支付、转账、存储、视频和物流各有独立 DEV/REAL 模式。

## 构建与验证

需要 JDK 21、Python 3、Maven；隔离数据库测试另需 MySQL 8 的 mysql/mysqld。脚本自动查找 Maven/JDK；也可在 -- 前提供 --maven、--java-home、--mysql-bin。

~~~text
python -X utf8 scripts/maven.py -- --version
python -X utf8 scripts/maven.py -- -DskipTests package
python -X utf8 scripts/with_local_mysql.py -- -Dtest=FoundationApiTest,RuntimeSafetyGuardTest,MigrationTest test
python -X utf8 -m unittest discover -s scripts -p test_runners.py -v
python -X utf8 scripts/check_delivery.py
~~~

with_local_mysql.py 创建自己拥有的临时回环 MySQL，验证数据目录再测试，结束后只清理该实例。不要把测试连接指向业务库。按改动选择相关测试。

产物在 target，不提交 Git。Flyway 位于 src/main/resources/db/migration，已应用迁移不可改写，只追加版本；Hibernate 仅校验结构。持久化时间为 UTC，业务日历按 Asia/Shanghai。

## 配置与接口

| 入口 | 用途 |
| --- | --- |
| application-local.properties.example | 本机模板 |
| .env.example、compose.example.yml、Dockerfile | 生产部署示例 |
| docs/wechat-configuration.example.yml | 微信模板 |
| [部署维护](docs/deployment.md) | 更新、密钥和恢复 |
| [迁移兼容](docs/migration-checksum-recovery-2026-10-17.md) | V22/V23 checksum 与 V25 前向修正 |

GET /api/v1/health 为存活，GET /api/v1/health/ready 为就绪。开发 OpenAPI 在 /v3/api-docs、交互文档在 /swagger-ui/index.html；生产不公开。

接口使用统一 envelope、请求 ID、十进制字符串 ID、整数分金额和基点费率。会话保存在数据库，顾客归属和后台角色独立鉴权，未明确放行的路径默认拒绝。

现有服务器已运行后台、后端及真实微信身份登录；支付商户配置与真实支付验收尚未完成。其 compose.dev.yml 是独立运行配置，不得直接用生产示例覆盖。
