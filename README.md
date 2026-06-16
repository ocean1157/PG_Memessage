# PG Bug Mail Tracker

一个用于抓取 PostgreSQL `pgsql-bugs` 官方邮件归档、提取问题线索并在本地维护分析记录的 Java 桌面程序。

## 功能

- 按起始年月和月份数量抓取 `pgsql-bugs` 邮件归档。
- 自动解析邮件主题、作者、日期、Message-ID、归档 URL。
- 自动提取 PostgreSQL 版本、常见报错线索和疑似复现 SQL/代码片段。
- 本地保存分析字段：状态、严重级别、标签、复现代码、报错信息、排查步骤、备注。
- 支持按关键字过滤、打开原始邮件 URL、导出 CSV。

## 运行

双击 `run.bat` 即可启动。

如果需要手动运行：

```powershell
javac -encoding UTF-8 -d out src\PgBugMailTracker.java
java -cp out PgBugMailTracker
```

## 本地数据

程序会自动创建 `data/records.tsv`，保存抓取到的邮件和你补充的分析内容。该文件是本工具自己的本地数据文件，可以随项目一起备份。

## 数据来源

归档入口为 PostgreSQL 官方邮件列表页面：

https://www.postgresql.org/list/pgsql-bugs/
