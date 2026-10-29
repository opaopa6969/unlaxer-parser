/* Test-only Linux interposition: never consult the real passwd database. */
#define _GNU_SOURCE
#include <pwd.h>
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#include <unistd.h>
int getpwuid_r(uid_t uid, struct passwd *pw, char *buf, size_t len, struct passwd **out) {
    const char *home = getenv("UBNF_TEST_PASSWD_HOME");
    *out = NULL;
    if (!home) return EIO; /* fail closed: never use real home */
    if (!strcmp(home, "!missing")) return 0;
    if (!strcmp(home, "!error")) return EIO;
    if (getenv("UBNF_TEST_PASSWD_RETRY") && len < 8192) return ERANGE;
    size_t size = strlen(home) + 1;
    if (len < size) return ERANGE;
    memcpy(buf, home, size);
    memset(pw, 0, sizeof(*pw));
    pw->pw_uid = uid; pw->pw_gid = getgid();
    pw->pw_dir = buf; pw->pw_name = "fixture"; pw->pw_passwd = "x";
    pw->pw_gecos = ""; pw->pw_shell = "/bin/sh";
    *out = pw;
    return 0;
}
struct passwd *getpwuid(uid_t uid) {
    static _Thread_local struct passwd pw;
    static _Thread_local char buf[65536];
    struct passwd *out;
    int code = getpwuid_r(uid, &pw, buf, sizeof(buf), &out);
    if (code) errno = code;
    return out;
}
