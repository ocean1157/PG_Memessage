# PG Bug Mail Tracker

用于抓取 PostgreSQL `pgsql-bugs` 官方邮件归档，并在本地按 Bug 编号维护版本、报错、复现案例和排查过程的 Java 桌面工具。

## 功能

- 按起始年月抓取 PostgreSQL 官方 `pgsql-bugs` 邮件归档，支持连续多个月份。
- 提取邮件主题、作者、日期、Message-ID、归档 URL、PG 版本与常见错误线索。
- 自动从 `Test case`、`Steps to reproduce`、`Reproduction`、SQL 等段落提取复现代码，并过滤邮件签名和网页导航内容。
- 使用 `BUG #xxxxx` 作为优先唯一键；同一个 Bug 的首封邮件和后续回复会合并为一条记录。
- 本地维护状态、严重级别、标签、报错信息、复现案例代码、排查步骤和备注。
- 支持关键词过滤、打开官方原文、导出 CSV。

## 环境要求

- Windows
- Java 8 或更高版本（需要 `java` 和 `javac` 可在命令行中使用）
- 可访问 https://www.postgresql.org 的网络连接

## 启动

双击项目根目录的 `run.bat`。

脚本会自动编译 `src/PgBugMailTracker.java` 到 `out/`，然后打开桌面程序。

也可以在 PowerShell 中手动启动：

```powershell
javac -encoding UTF-8 -d out src\PgBugMailTracker.java
java -cp out PgBugMailTracker
```

## 使用步骤

1. 在顶部填写“起始年”“月”“连续月份”和“每月最多”。
2. 第一次使用建议设置为一个月份、20 封邮件，确认网络和抓取结果正常后再增加范围。
3. 点击“抓取邮件”。左侧列表会边抓边显示，不必等待全部月份完成。
4. 点击任意记录，在右侧补充或修订 PG 版本、报错信息、复现案例、排查步骤、严重级别、状态、标签和备注。
5. 点击“保存”写入本地记录；通过“文件 -> 导出 CSV”生成可分享的分析清单。
6. 点击“打开原文”可在浏览器中查看 PostgreSQL 官方归档邮件。

## Bug 编号归并

程序优先从邮件主题和正文识别 `BUG #xxxxx`。具有相同编号的首封报告和 `Re:` 回复会合并为同一条记录，以便围绕一个问题查看版本、错误、复现和后续讨论。

没有 Bug 编号的邮件会回退使用 Message-ID 或归档 URL 作为唯一标识，因此不会和其他无编号邮件错误合并。

## 本地数据

抓取记录保存在 `data/records.tsv`。这是程序的本地工作数据，已被 Git 忽略，不会提交到 GitHub。

导出的 CSV 位于 `data/pg-bug-records.csv`。如需从零开始抓取，可先关闭程序，然后删除 `data/records.tsv`。

编译产物位于 `out/`，同样不会提交到 GitHub。

## 项目结构

```text
assets/                     应用和桌面快捷方式图标
src/PgBugMailTracker.java   Java 桌面程序及抓取、解析、存储逻辑
run.bat                     编译并启动程序
data/                       本地数据目录（运行时生成，Git 忽略）
out/                        编译输出目录（运行时生成，Git 忽略）
```

## GitHub Desktop 推送

1. 在 GitHub Desktop 中添加本地仓库：`C:\Users\55055\Documents\PG_Memessage`。
2. 确认左侧当前分支为 `master`，并确认 Changes 页面没有未提交的意外文件。
3. 点击顶部或工具栏的 `Push origin`。
4. 推送完成后，GitHub Desktop 会显示已同步状态；也可以刷新仓库网页确认提交出现。

## 数据来源

PostgreSQL 官方 `pgsql-bugs` 邮件归档：

https://www.postgresql.org/list/pgsql-bugs/
