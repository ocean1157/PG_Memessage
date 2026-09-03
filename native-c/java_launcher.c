#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <wchar.h>
#include "resource.h"

#define APP_TITLE L"PG Bug Mail Tracker"

static void show_message(const wchar_t *title, const wchar_t *message, UINT icon) {
    MessageBoxW(NULL, message, title, MB_OK | icon);
}

static void strip_file_name(wchar_t *path) {
    size_t len = wcslen(path);
    while (len > 0) {
        wchar_t ch = path[len - 1];
        if (ch == L'\\' || ch == L'/') {
            path[len - 1] = L'\0';
            return;
        }
        len--;
    }
}

static BOOL path_exists(const wchar_t *path) {
    DWORD attr = GetFileAttributesW(path);
    return attr != INVALID_FILE_ATTRIBUTES;
}

static BOOL make_path(wchar_t *out, size_t out_len, const wchar_t *root, const wchar_t *relative) {
    return swprintf_s(out, out_len, L"%s\\%s", root, relative) > 0;
}

static BOOL has_java_source(const wchar_t *root) {
    wchar_t source[MAX_PATH];
    return make_path(source, ARRAYSIZE(source), root, L"legacy-java\\src\\PgBugMailTracker.java") && path_exists(source);
}

static BOOL has_app_payload(const wchar_t *root) {
    wchar_t jar[MAX_PATH];
    return (make_path(jar, ARRAYSIZE(jar), root, L"PGBugMailTracker.jar") && path_exists(jar))
            || has_java_source(root);
}

static BOOL find_project_root(wchar_t *root, size_t root_len) {
    DWORD length = GetModuleFileNameW(NULL, root, (DWORD)root_len);
    if (length == 0 || length >= root_len) return FALSE;
    strip_file_name(root);

    for (int i = 0; i < 8; i++) {
        if (has_app_payload(root)) return TRUE;
        size_t before = wcslen(root);
        strip_file_name(root);
        if (wcslen(root) == before || wcslen(root) < 3) break;
    }
    return FALSE;
}

static BOOL command_exists(const wchar_t *command) {
    wchar_t found[MAX_PATH];
    return SearchPathW(NULL, command, NULL, ARRAYSIZE(found), found, NULL) > 0;
}

static BOOL get_modified_time(const wchar_t *path, FILETIME *time) {
    WIN32_FILE_ATTRIBUTE_DATA data;
    if (!GetFileAttributesExW(path, GetFileExInfoStandard, &data)) return FALSE;
    *time = data.ftLastWriteTime;
    return TRUE;
}

static BOOL needs_compile(const wchar_t *root) {
    wchar_t source[MAX_PATH];
    wchar_t output[MAX_PATH];
    FILETIME source_time;
    FILETIME output_time;

    if (!make_path(source, ARRAYSIZE(source), root, L"legacy-java\\src\\PgBugMailTracker.java")) return TRUE;
    if (!make_path(output, ARRAYSIZE(output), root, L"out\\PgBugMailTracker.class")) return TRUE;
    if (!get_modified_time(output, &output_time)) return TRUE;
    if (!get_modified_time(source, &source_time)) return TRUE;
    return CompareFileTime(&source_time, &output_time) > 0;
}

static BOOL run_process(const wchar_t *root, const wchar_t *command, BOOL wait, DWORD *exit_code) {
    wchar_t cmd_line[2048];
    STARTUPINFOW startup;
    PROCESS_INFORMATION process;
    BOOL ok;

    wcscpy_s(cmd_line, ARRAYSIZE(cmd_line), command);
    ZeroMemory(&startup, sizeof(startup));
    ZeroMemory(&process, sizeof(process));
    startup.cb = sizeof(startup);
    startup.dwFlags = STARTF_USESHOWWINDOW;
    startup.wShowWindow = SW_HIDE;

    ok = CreateProcessW(NULL, cmd_line, NULL, NULL, FALSE, CREATE_NO_WINDOW, NULL, root, &startup, &process);
    if (!ok) return FALSE;

    if (wait) {
        WaitForSingleObject(process.hProcess, INFINITE);
        if (exit_code != NULL) GetExitCodeProcess(process.hProcess, exit_code);
    }

    CloseHandle(process.hThread);
    CloseHandle(process.hProcess);
    return TRUE;
}

int APIENTRY wWinMain(HINSTANCE instance, HINSTANCE previous, PWSTR command_line, int show) {
    (void)instance;
    (void)previous;
    (void)command_line;
    (void)show;

    wchar_t root[MAX_PATH];
    wchar_t jar[MAX_PATH];
    DWORD exit_code = 0;

    if (!find_project_root(root, ARRAYSIZE(root))) {
        show_message(APP_TITLE,
            L"没有找到 PGBugMailTracker.jar 或 legacy-java\\src\\PgBugMailTracker.java。\n\n"
            L"请把 PGBugMailTracker.exe 放在 GitHub 下载解压后的项目根目录运行。",
            MB_ICONERROR);
        return 1;
    }

    SetCurrentDirectoryW(root);

    if (!command_exists(L"javaw.exe")) {
        show_message(APP_TITLE,
            L"没有找到 javaw.exe。\n\n"
            L"请先安装 Java 8 或更高版本运行环境，然后重新双击 PGBugMailTracker.exe。",
            MB_ICONERROR);
        return 2;
    }

    if (make_path(jar, ARRAYSIZE(jar), root, L"PGBugMailTracker.jar") && path_exists(jar)) {
        if (!run_process(root, L"javaw.exe -jar PGBugMailTracker.jar", FALSE, NULL)) {
            show_message(APP_TITLE,
                L"启动 PGBugMailTracker.jar 失败。\n\n请确认 Java 运行环境正常。",
                MB_ICONERROR);
            return 5;
        }
        return 0;
    }

    CreateDirectoryW(L"out", NULL);

    if (needs_compile(root)) {
        if (!command_exists(L"javac.exe")) {
            show_message(APP_TITLE,
                L"没有找到 PGBugMailTracker.jar，且没有找到 javac.exe 编译源码。\n\n"
                L"普通使用请从 GitHub 下载完整项目；开发调试请安装 JDK。",
                MB_ICONERROR);
            return 3;
        }
        if (!run_process(root, L"javac.exe -encoding UTF-8 -d out legacy-java\\src\\PgBugMailTracker.java", TRUE, &exit_code)
                || exit_code != 0) {
            show_message(APP_TITLE,
                L"Java 源码编译失败。\n\n"
                L"请用 run-console.bat 查看详细编译错误。",
                MB_ICONERROR);
            return 4;
        }
    }

    if (!run_process(root, L"javaw.exe -cp out PgBugMailTracker", FALSE, NULL)) {
        show_message(APP_TITLE,
            L"启动 Java 图形界面失败。\n\n请确认 Java 运行环境正常。",
            MB_ICONERROR);
        return 5;
    }
    return 0;
}
