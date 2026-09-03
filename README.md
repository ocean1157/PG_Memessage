# PG Bug Mail Tracker

用于抓取 PostgreSQL `pgsql-bugs` 官方邮件归档，并在本地按 Bug 编号维护版本、报错、复现案例和排查过程的 Java 桌面工具。

## 功能

- 按起始年月抓取 PostgreSQL 官方 `pgsql-bugs` 邮件归档，支持连续多个月份。
- 提取邮件主题、作者、日期、Message-ID、归档 URL、PG 版本与常见错误线索。
- 自动从 `Test case`、`Steps to reproduce`、`Reproduction`、SQL 等段落提取复现代码，并过滤邮件签名和网页导航内容。
- 使用 `BUG #xxxxx` 作为优先唯一键；同一个 Bug 的首封邮件和后续回复会合并为一条记录。
- 本地维护状态、严重级别、标签、报错信息、复现案例代码、排查步骤和备注。
- “对话原文”会把同一线程中的邮件按时间线显示成类似聊天的左右气泡，清晰区分发件人、收件人、抄送、主题和附件，并自动去掉上一封邮件的引用内容。
- 支持删除本地邮件记录、关键词过滤、打开官方原文、导出 CSV。
- 支持多目标语言翻译，源语言自动识别，只翻译自然语言描述，尽量保留 SQL、代码、路径、日志、错误码、函数名和 PostgreSQL 专业术语。

## 环境要求

- Windows
- Java 8 或更高版本（需要 `java` 和 `javac` 可在命令行中使用）
- 可访问 https://www.postgresql.org 的网络连接

## 启动

首选双击项目根目录的 `PGBugMailTracker.exe`，它会以正常 Windows 图形程序方式启动，不显示 cmd 窗口。

如果还没有生成 exe，可以使用 CMake 构建，生成文件会在 `cmake-build-debug/bin/PGBugMailTracker.exe`，也可以复制到项目根目录使用。

双击项目根目录的 `run-gui.vbs` 也可以以 GUI 方式启动，不显示 cmd 窗口，适合作为备用入口。

也可以双击 `run.bat`，它会在编译后用 `javaw` 启动程序，cmd 窗口只会短暂闪一下。

如果需要查看 Java 控制台输出或编译错误，双击 `run-console.bat`。

启动脚本会自动编译 `legacy-java/src/PgBugMailTracker.java` 到 `out/`，然后打开桌面程序。

也可以在 PowerShell 中手动启动：

```powershell
javac -encoding UTF-8 -d out legacy-java\src\PgBugMailTracker.java
java -cp out PgBugMailTracker
```

## 使用步骤

1. 在顶部填写“起始年”“月”“连续月份”和“每月最多”。
2. 第一次使用建议设置为一个月份、20 封邮件，确认网络和抓取结果正常后再增加范围。
3. 点击“抓取邮件”。左侧列表会边抓边显示，不必等待全部月份完成。
4. 点击任意记录，在右侧补充或修订 PG 版本、报错信息、复现案例、排查步骤、严重级别、状态、标签和备注。
5. 切到“对话原文”查看邮件往返；对话页只显示每封邮件新增内容，会过滤上一封邮件引用。
6. 选择“翻译为”目标语言并点击“翻译原文”，在“翻译结果”页查看翻译；如需排查网页清洗结果，可以切到“纯文本原文”。
7. 点击“删除记录”可删除当前选中的本地记录；这不会删除 PostgreSQL 官方邮件归档。
8. 点击“保存”写入本地记录；通过“文件 -> 导出 CSV”生成可分享的分析清单。
9. 点击“打开原文”可在浏览器中查看 PostgreSQL 官方归档邮件。

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
PGBugMailTracker.exe        正常 Windows 图形启动入口
legacy-java/src/PgBugMailTracker.java   Java 桌面程序及抓取、解析、存储逻辑
native-c/java_launcher.c    正常 Windows exe 启动器，隐藏编译并用 javaw 打开完整 Java 界面
run.bat                     编译并启动程序
run-gui.vbs                 隐藏 cmd 的 GUI 启动入口
run-console.bat             保留控制台窗口的调试启动入口
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
