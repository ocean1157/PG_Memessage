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

static BOOL find_project_root(wchar_t *root, size_t root_len) {
    DWORD length = GetModuleFileNameW(NULL, root, (DWORD)root_len);
    if (length == 0 || length >= root_len) return FALSE;
    strip_file_name(root);

    for (int i = 0; i < 8; i++) {
        if (has_java_source(root)) return TRUE;
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
    DWORD exit_code = 0;

    if (!find_project_root(root, ARRAYSIZE(root))) {
        show_message(APP_TITLE,
            L"没有找到 legacy-java\\src\\PgBugMailTracker.java。\n\n"
            L"请把 PGBugMailTracker.exe 放在项目根目录，或从 CMake 输出目录直接运行。",
            MB_ICONERROR);
        return 1;
    }

    SetCurrentDirectoryW(root);
    CreateDirectoryW(L"out", NULL);

    if (!command_exists(L"javaw.exe")) {
        show_message(APP_TITLE,
            L"没有找到 javaw.exe。\n\n请确认 Java 已安装，并且 javaw.exe 可以从 PATH 找到。",
            MB_ICONERROR);
        return 2;
    }

    if (needs_compile(root)) {
        if (!command_exists(L"javac.exe")) {
            show_message(APP_TITLE,
                L"源码已有更新，但没有找到 javac.exe。\n\n"
                L"请安装 JDK，或把 javac.exe 加入 PATH 后再启动。",
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
