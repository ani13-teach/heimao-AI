/*
 * runtimeprobe - native side of a standalone availability probe.
 *
 * Design rules (kept deliberately boring):
 *   - C only, GNU11, JNI + libc. No C++, no third-party libraries, no liblog.
 *   - Nothing is done in the app process that could change its security state.
 *     Every kernel-side experiment runs in a forked child process. If a child
 *     crashes, hangs or is killed, the app process keeps running untouched.
 *   - The parent never blocks forever: the child pipe is read with O_NONBLOCK +
 *     poll(), the child is reaped with waitpid(WNOHANG) and hard-killed after a
 *     3 second budget.
 *   - First version is an availability query only. It never calls
 *     landlock_create_ruleset with a real ruleset, never calls
 *     landlock_add_rule / landlock_restrict_self, so it cannot sandbox or
 *     un-sandbox anything.
 *   - Only self-readable, non-sensitive data is collected: getuid/geteuid,
 *     uname, /proc/self/status (Seccomp, NoNewPrivs) and
 *     /proc/self/attr/current. Nothing else from /proc, no other app's data.
 *
 * Everything a child writes is plain text on a pipe; the parent prefixes it
 * with exit status / terminating signal, so partial output survives a crash.
 */

#define _GNU_SOURCE 1

#include <jni.h>

#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <stddef.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <sys/utsname.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#include <linux/filter.h>
#include <linux/seccomp.h>

/* ------------------------------------------------------------------ */
/* syscall numbers                                                     */
/* ------------------------------------------------------------------ */

/*
 * Landlock (Linux 5.13+) syscall numbers are 444/445/446 in the *generic*
 * 64-bit syscall table. arm64 always uses that generic table. x86_64 has its
 * own table, but the landlock numbers were assigned 444/445/446 there as well,
 * so both supported ABIs agree. The numbers are therefore only valid for these
 * two ABIs -- they must not be reused for 32-bit arches.
 *
 * Recent NDK <asm/unistd.h> already defines __NR_landlock_create_ruleset; the
 * fallback below only exists so the probe still compiles against older NDKs
 * and it is reported at runtime as "hardcoded_fallback_444".
 */
#if defined(__aarch64__) || defined(__x86_64__)
#  ifndef __NR_landlock_create_ruleset
#    define __NR_landlock_create_ruleset 444
#    define PROBE_LANDLOCK_NR_FALLBACK 1
#  endif
#  define PROBE_LANDLOCK_NR_KNOWN 1
#endif

/* From <linux/landlock.h>; defined locally so no extra header is required. */
#ifndef LANDLOCK_CREATE_RULESET_VERSION
#  define LANDLOCK_CREATE_RULESET_VERSION (1U << 0)
#endif

#ifndef SECCOMP_RET_ERRNO
#  define SECCOMP_RET_ERRNO 0x00050000U
#endif
#ifndef SECCOMP_RET_ALLOW
#  define SECCOMP_RET_ALLOW 0x7fff0000U
#endif
#ifndef SECCOMP_RET_DATA
#  define SECCOMP_RET_DATA 0x0000ffffU
#endif

#define PROBE_CHILD_TIMEOUT_SEC 3.0
#define PROBE_OUT_CAP 8192
#define PROBE_READ_CHUNK 1024
#define PROBE_POLL_SLICE_MS 200
#define PROBE_POST_REAP_SLICES 2

/* ------------------------------------------------------------------ */
/* report buffer (parent process only, normal libc is fine here)       */
/* ------------------------------------------------------------------ */

struct rep {
    char *buf;
    size_t len;
    size_t cap;
    int failed;
};

static void rep_append(struct rep *r, const char *s, size_t n) {
    if (r->failed || n == 0) {
        return;
    }
    if (r->len + n + 1 > r->cap) {
        size_t ncap = r->cap ? r->cap : 4096;
        while (ncap < r->len + n + 1) {
            ncap *= 2;
        }
        char *nb = (char *)realloc(r->buf, ncap);
        if (nb == NULL) {
            r->failed = 1;
            return;
        }
        r->buf = nb;
        r->cap = ncap;
    }
    memcpy(r->buf + r->len, s, n);
    r->len += n;
    r->buf[r->len] = '\0';
}

static void rep_printf(struct rep *r, const char *fmt, ...) {
    char tmp[1024];
    va_list ap;
    int n;

    va_start(ap, fmt);
    n = vsnprintf(tmp, sizeof tmp, fmt, ap);
    va_end(ap);
    if (n < 0) {
        return;
    }
    if ((size_t)n < sizeof tmp) {
        rep_append(r, tmp, (size_t)n);
        return;
    }
    char *big = (char *)malloc((size_t)n + 1);
    if (big == NULL) {
        return;
    }
    va_start(ap, fmt);
    (void)vsnprintf(big, (size_t)n + 1, fmt, ap);
    va_end(ap);
    rep_append(r, big, (size_t)n);
    free(big);
}

static void rep_escaped(struct rep *r, const char *s, size_t n) {
    size_t i;
    for (i = 0; i < n; i++) {
        unsigned char c = (unsigned char)s[i];
        if (c >= 0x20 && c < 0x7f && c != '\\') {
            char ch = (char)c;
            rep_append(r, &ch, 1);
        } else if (c == '\n') {
            rep_append(r, "\\n", 2);
        } else if (c == '\\') {
            rep_append(r, "\\\\", 2);
        } else {
            rep_printf(r, "\\x%02x", c);
        }
    }
}

/* ------------------------------------------------------------------ */
/* child-side primitives                                               */
/* ------------------------------------------------------------------ */

/*
 * After fork() in a multi-threaded process only async-signal-safe work is
 * allowed, so the children below use raw write()/syscall() and hand-rolled
 * integer formatting instead of printf/malloc.
 */

static void cwrite_all(int fd, const char *buf, size_t len) {
    while (len > 0) {
        ssize_t n = write(fd, buf, len);
        if (n > 0) {
            buf += (size_t)n;
            len -= (size_t)n;
            continue;
        }
        if (n < 0 && errno == EINTR) {
            continue;
        }
        return; /* reader is gone; give up quietly */
    }
}

static void cstr(int fd, const char *s) {
    cwrite_all(fd, s, strlen(s));
}

static void cnum(int fd, long long v) {
    char tmp[24];
    char out[26];
    size_t i = 0;
    size_t j = 0;
    unsigned long long u;

    if (v < 0) {
        out[j++] = '-';
        u = (unsigned long long)(-(v + 1)) + 1ULL;
    } else {
        u = (unsigned long long)v;
    }
    if (u == 0) {
        tmp[i++] = '0';
    }
    while (u != 0) {
        tmp[i++] = (char)('0' + (int)(u % 10ULL));
        u /= 10ULL;
    }
    while (i > 0) {
        out[j++] = tmp[--i];
    }
    cwrite_all(fd, out, j);
}

static void ckey(int fd, const char *key, long long v) {
    cstr(fd, key);
    cnum(fd, v);
    cstr(fd, "\n");
}

/* ------------------------------------------------------------------ */
/* child bodies                                                        */
/* ------------------------------------------------------------------ */

static int child_landlock(int fd) {
    long r;
    int e;

    cstr(fd, "child_role=landlock_query\n");
    cstr(fd, "child_pid=");
    cnum(fd, (long long)getpid());
    cstr(fd, " child_uid=");
    cnum(fd, (long long)getuid());
    cstr(fd, "\n");

#ifdef PROBE_LANDLOCK_NR_KNOWN
    cstr(fd, "call=syscall(__NR_landlock_create_ruleset, NULL, 0, LANDLOCK_CREATE_RULESET_VERSION)\n");
    ckey(fd, "syscall_nr=", (long long)__NR_landlock_create_ruleset);
#  ifdef PROBE_LANDLOCK_NR_FALLBACK
    cstr(fd, "syscall_nr_source=hardcoded_fallback_444\n");
#  else
    cstr(fd, "syscall_nr_source=ndk_header\n");
#  endif

    errno = 0;
    r = (long)syscall(__NR_landlock_create_ruleset,
                      (void *)NULL,
                      (size_t)0,
                      (unsigned int)LANDLOCK_CREATE_RULESET_VERSION);
    e = errno;

    ckey(fd, "returned=", r);
    ckey(fd, "errno=", (long long)e);
    if (r >= 1) {
        cstr(fd, "verdict=syscall_available\n");
        ckey(fd, "landlock_abi_version=", r);
    } else {
        cstr(fd, "verdict=syscall_not_available\n");
    }
    cstr(fd, "note=query only: no ruleset was created and no policy was enforced\n");
    return (r >= 1) ? 0 : 3;
#else
    cstr(fd, "call=skipped\n");
    cstr(fd, "verdict=unknown_abi_no_landlock_syscall_number\n");
    cstr(fd, "note=this ABI is outside the supported arm64/x86_64 set\n");
    return 8;
#endif
}

static int child_seccomp(int fd) {
    struct sock_filter insns[] = {
        /* load syscall number */
        BPF_STMT(BPF_LD | BPF_W | BPF_ABS, (__u32)offsetof(struct seccomp_data, nr)),
        /* if nr == getppid, fall through; otherwise skip one instruction */
        BPF_JUMP(BPF_JMP | BPF_JEQ | BPF_K, (__u32)__NR_getppid, 0, 1),
        /* getppid -> -1 / EPERM */
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ERRNO | ((__u32)EPERM & SECCOMP_RET_DATA)),
        /* everything else -> allow */
        BPF_STMT(BPF_RET | BPF_K, SECCOMP_RET_ALLOW),
    };
    struct sock_fprog prog;
    long getppid_ret;
    long getpid_ret;
    int r_nnp, e_nnp, r_sc, e_sc, r_mode, e_mode, e_getppid, e_getpid;

    cstr(fd, "child_role=seccomp_selftest\n");
    cstr(fd, "child_pid=");
    cnum(fd, (long long)getpid());
    cstr(fd, " child_uid=");
    cnum(fd, (long long)getuid());
    cstr(fd, "\n");

    /* required before installing a filter without CAP_SYS_ADMIN */
    errno = 0;
    r_nnp = prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0);
    e_nnp = errno;
    cstr(fd, "call=prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0)\n");
    ckey(fd, "returned=", r_nnp);
    ckey(fd, "errno=", e_nnp);

    prog.len = (unsigned short)(sizeof insns / sizeof insns[0]);
    prog.filter = insns;
    errno = 0;
    r_sc = prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, &prog);
    e_sc = errno;
    cstr(fd, "call=prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, {getppid->EPERM, rest->ALLOW})\n");
    ckey(fd, "filter_insns=", (long long)prog.len);
    ckey(fd, "returned=", r_sc);
    ckey(fd, "errno=", e_sc);

    errno = 0;
    r_mode = prctl(PR_GET_SECCOMP);
    e_mode = errno;
    cstr(fd, "call=prctl(PR_GET_SECCOMP)\n");
    ckey(fd, "returned=", r_mode);
    ckey(fd, "errno=", e_mode);
    cstr(fd, "seccomp_mode_meaning=0:disabled 1:strict 2:filter\n");

    /* the actual verification: the filtered syscall must fail with EPERM */
    errno = 0;
    getppid_ret = (long)syscall(__NR_getppid);
    e_getppid = errno;
    cstr(fd, "call=syscall(__NR_getppid)\n");
    ckey(fd, "syscall_nr=", (long long)__NR_getppid);
    ckey(fd, "returned=", getppid_ret);
    ckey(fd, "errno=", e_getppid);

    /* control syscall: must still work, proving the filter is not a blanket deny */
    errno = 0;
    getpid_ret = (long)syscall(__NR_getpid);
    e_getpid = errno;
    cstr(fd, "call=syscall(__NR_getpid) (control)\n");
    ckey(fd, "syscall_nr=", (long long)__NR_getpid);
    ckey(fd, "returned=", getpid_ret);
    ckey(fd, "errno=", e_getpid);

    if (r_sc != 0) {
        cstr(fd, "verdict=filter_install_failed\n");
        return 4;
    }
    if (r_nnp == 0 && r_mode == SECCOMP_MODE_FILTER &&
        getppid_ret == -1 && e_getppid == EPERM && getpid_ret > 0 && e_getpid == 0) {
        cstr(fd, "verdict=getppid_blocked_with_EPERM_control_syscall_ok\n");
        return 0;
    }
    if (getpid_ret <= 0) {
        cstr(fd, "verdict=control_syscall_also_blocked_unexpected\n");
        return 7;
    }
    if (getppid_ret >= 0) {
        cstr(fd, "verdict=getppid_not_blocked_filter_ineffective\n");
        return 5;
    }
    cstr(fd, "verdict=getppid_failed_with_unexpected_errno\n");
    return 6;
}

/* ------------------------------------------------------------------ */
/* parent-side child runner                                            */
/* ------------------------------------------------------------------ */

typedef int (*child_body_fn)(int fd);

struct child_result {
    pid_t pid;
    int forked;
    int reaped;
    int timed_out;
    int killed;
    int spawn_errno;
    int read_errno;
    int status;
    int status_known;
    int wait_errno;
    int out_truncated;
    size_t out_len;
    char out[PROBE_OUT_CAP];
};

static double mono_now(void) {
    struct timespec ts;
    if (clock_gettime(CLOCK_MONOTONIC, &ts) != 0) {
        return 0.0;
    }
    return (double)ts.tv_sec + (double)ts.tv_nsec / 1e9;
}

/*
 * Fork, run the body in the child, stream the child's text output into
 * cr->out, and return once the child has been reaped and the pipe drained.
 * Reads and waitpid calls are nonblocking; after the budget expires we kill
 * the child and observe reaping for at most another second.
 */
static void run_child(child_body_fn body, struct child_result *cr) {
    int fds[2];
    pid_t pid;
    int fl;
    int eof = 0;
    int post_reap_slices = 0;
    double start;
    double kill_time = 0;
    int iterations = 0;

    memset(cr, 0, sizeof *cr);
    cr->pid = -1;

    if (pipe2(fds, O_CLOEXEC) != 0) {
        cr->spawn_errno = errno;
        return;
    }
    fl = fcntl(fds[0], F_GETFL, 0);
    if (fl < 0 || fcntl(fds[0], F_SETFL, fl | O_NONBLOCK) < 0) {
        cr->spawn_errno = errno;
        close(fds[0]);
        close(fds[1]);
        return;
    }

    pid = fork();
    if (pid < 0) {
        cr->spawn_errno = errno;
        close(fds[0]);
        close(fds[1]);
        return;
    }
    if (pid == 0) {
        int rc;
        close(fds[0]);
        rc = body(fds[1]);
        close(fds[1]);
        _exit(rc & 0x7f);
    }

    cr->forked = 1;
    cr->pid = pid;
    close(fds[1]); /* only the child keeps a writer open, so EOF == child gone */

    start = mono_now();

    for (;;) {
        /* 1. drain whatever the child produced so far */
        for (;;) {
            char buf[PROBE_READ_CHUNK];
            ssize_t n = read(fds[0], buf, sizeof buf);
            if (n > 0) {
                size_t room = (sizeof cr->out - 1) - cr->out_len;
                size_t take = ((size_t)n < room) ? (size_t)n : room;
                if (take > 0) {
                    memcpy(cr->out + cr->out_len, buf, take);
                    cr->out_len += take;
                }
                if (take < (size_t)n) {
                    cr->out_truncated = 1;
                }
                continue;
            }
            if (n == 0) {
                eof = 1;
                break;
            }
            if (errno == EINTR) {
                continue;
            }
            if (errno == EAGAIN || errno == EWOULDBLOCK) {
                break;
            }
            cr->read_errno = errno;
            eof = 1;
            break;
        }

        /* 2. reap without blocking */
        if (!cr->reaped) {
            int status = 0;
            pid_t w = waitpid(pid, &status, WNOHANG);
            if (w == pid) {
                cr->status = status;
                cr->status_known = 1;
                cr->reaped = 1;
            } else if (w < 0 && errno != EINTR) {
                cr->wait_errno = errno;
                cr->reaped = 1; /* status is unknown, never report exit=0 */
            }
        } else {
            post_reap_slices++;
            if (post_reap_slices > PROBE_POST_REAP_SLICES && !eof) {
                break; /* paranoia: never spin on a dead child's pipe */
            }
        }

        if (cr->reaped && eof) {
            break;
        }

        if (!cr->reaped) {
            double elapsed = mono_now() - start;
            if (!cr->timed_out && (elapsed >= PROBE_CHILD_TIMEOUT_SEC || ++iterations >= 100)) {
                cr->timed_out = 1;
                if (kill(pid, SIGKILL) == 0) {
                    cr->killed = 1;
                }
                kill_time = mono_now();
            }
            if (cr->timed_out) {
                /* Never use blocking waitpid: even SIGKILL can be delayed in the kernel. */
                if (mono_now() - kill_time >= 1.0 || ++iterations >= 200) {
                    cr->wait_errno = ETIMEDOUT;
                    break;
                }
                (void)poll(NULL, 0, 20);
                continue;
            }
            {
                struct pollfd pfd;
                double remaining = PROBE_CHILD_TIMEOUT_SEC - elapsed;
                int ms = (int)(remaining * 1000.0);
                if (ms > PROBE_POLL_SLICE_MS) {
                    ms = PROBE_POLL_SLICE_MS;
                }
                if (ms < 1) {
                    ms = 1;
                }
                pfd.fd = fds[0];
                pfd.events = POLLIN;
                pfd.revents = 0;
                if (eof) {
                    (void)poll(NULL, 0, ms);
                } else {
                    (void)poll(&pfd, 1, ms);
                }
            }
        } else {
            struct pollfd pfd;
            pfd.fd = fds[0];
            pfd.events = POLLIN;
            pfd.revents = 0;
            (void)poll(&pfd, 1, 20);
        }
    }

    close(fds[0]);
    cr->out[cr->out_len] = '\0';
}

static const char *sig_name(int sig) {
#ifdef SIGSYS
    if (sig == SIGSYS) return "SIGSYS";
#endif
    switch (sig) {
        case SIGKILL: return "SIGKILL";
        case SIGSEGV: return "SIGSEGV";
        case SIGBUS:  return "SIGBUS";
        case SIGILL:  return "SIGILL";
        case SIGABRT: return "SIGABRT";
        case SIGTRAP: return "SIGTRAP";
        case SIGFPE:  return "SIGFPE";
        case SIGPIPE: return "SIGPIPE";
        case SIGTERM: return "SIGTERM";
        case SIGINT:  return "SIGINT";
        default:      return "UNKNOWN";
    }
}

static void report_child(struct rep *r, const char *title, const struct child_result *cr) {
    rep_printf(r, "--- %s ---\n", title);
    if (!cr->forked) {
        rep_printf(r, "fork=failed errno=%d (child not started)\n", cr->spawn_errno);
        return;
    }
    rep_printf(r, "child_pid=%ld\n", (long)cr->pid);
    if (cr->timed_out) {
        rep_printf(r, "timeout=yes budget=%.0fs sigkill_sent=%s\n",
                   PROBE_CHILD_TIMEOUT_SEC, cr->killed ? "yes" : "no");
    } else {
        rep_printf(r, "timeout=no budget=%.0fs\n", PROBE_CHILD_TIMEOUT_SEC);
    }

    if (cr->wait_errno != 0) {
        rep_printf(r, "wait_errno=%d status_known=%d\n", cr->wait_errno, cr->status_known);
    }
    if (cr->status_known && WIFEXITED(cr->status)) {
        rep_printf(r, "exit=%d\n", WEXITSTATUS(cr->status));
    } else if (cr->status_known && WIFSIGNALED(cr->status)) {
        int s = WTERMSIG(cr->status);
        rep_printf(r, "exit=none\nsignal=%d name=%s\n", s, sig_name(s));
#ifdef SIGSYS
        if (s == SIGSYS) {
            rep_printf(r,
                       "signal_note=SIGSYS is consistent with a blocked syscall."
                       " Confirm SYS_SECCOMP in the child's crash log before attributing it to Android seccomp.\n");
        }
#endif
    } else {
        rep_printf(r, "exit=unknown status=0x%x reaped=%d\n", cr->status, cr->reaped);
    }

    if (cr->read_errno != 0) {
        rep_printf(r, "pipe_read_errno=%d\n", cr->read_errno);
    }

    rep_printf(r, "child_output_begin (captured=%u bytes)\n", (unsigned)cr->out_len);
    if (cr->out_len > 0) {
        rep_append(r, cr->out, cr->out_len);
    } else {
        rep_printf(r, "(no output captured)\n");
    }
    if (cr->out_truncated) {
        rep_printf(r, "(child output truncated at %d bytes)\n", PROBE_OUT_CAP);
    }
    rep_printf(r, "child_output_end\n");
}

/* ------------------------------------------------------------------ */
/* small /proc helpers (parent process only)                           */
/* ------------------------------------------------------------------ */

/*
 * Read a whole (small) text file. Returns byte count, or -1 with *err_out set.
 * Only used on files that describe *this* process and that the app may read.
 */
static int read_text_file(const char *path, char *out, size_t cap, int *err_out) {
    int fd;
    size_t total = 0;

    if (err_out) {
        *err_out = 0;
    }
    fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        if (err_out) {
            *err_out = errno;
        }
        return -1;
    }
    for (;;) {
        ssize_t n;
        if (total + 1 >= cap) {
            break;
        }
        n = read(fd, out + total, cap - 1 - total);
        if (n > 0) {
            total += (size_t)n;
            continue;
        }
        if (n == 0) {
            break;
        }
        if (errno == EINTR) {
            continue;
        }
        if (err_out) {
            *err_out = errno;
        }
        close(fd);
        return -1;
    }
    close(fd);
    out[total] = '\0';
    return (int)total;
}

/* Copy the value of a "Key: value" line out of /proc/self/status content. */
static void status_value(const char *buf, const char *key, char *out, size_t cap) {
    size_t klen = strlen(key);
    const char *p = buf;

    if (cap == 0) {
        return;
    }
    out[0] = '\0';
    while (p != NULL && *p != '\0') {
        const char *nl = strchr(p, '\n');
        size_t linelen = (nl != NULL) ? (size_t)(nl - p) : strlen(p);
        if (linelen > klen && strncmp(p, key, klen) == 0) {
            const char *v = p + klen;
            size_t vlen = linelen - klen;
            while (vlen > 0 && (*v == ' ' || *v == '\t')) {
                v++;
                vlen--;
            }
            while (vlen > 0 && (v[vlen - 1] == ' ' || v[vlen - 1] == '\t' || v[vlen - 1] == '\r')) {
                vlen--;
            }
            if (vlen >= cap) {
                vlen = cap - 1;
            }
            memcpy(out, v, vlen);
            out[vlen] = '\0';
            return;
        }
        if (nl == NULL) {
            break;
        }
        p = nl + 1;
    }
}

static const char *build_abi(void) {
#if defined(__aarch64__)
    return "arm64-v8a (aarch64)";
#elif defined(__x86_64__)
    return "x86_64";
#elif defined(__arm__)
    return "armeabi-v7a (arm)";
#elif defined(__i386__)
    return "x86";
#else
    return "unknown";
#endif
}

/* ------------------------------------------------------------------ */
/* JNI entry point                                                     */
/* ------------------------------------------------------------------ */

JNIEXPORT jstring JNICALL
Java_me_rerere_heimao_runtimeprobe_ProbeActivity_nativeProbe(JNIEnv *env, jclass clazz, jstring files_dir) {
    struct rep r;
    struct child_result cr;
    struct utsname uts;
    const char *dir = NULL;
    char status[65536];
    char attr[512];
    char value[64];
    int err = 0;
    int n;
    jstring result;

    (void)clazz;
    memset(&r, 0, sizeof r);

    if (files_dir != NULL) {
        dir = (*env)->GetStringUTFChars(env, files_dir, NULL);
    }

    rep_printf(&r, "=== runtimeprobe report ===\n");
    rep_printf(&r, "generated_in_app_process=yes (tests run in forked children)\n");
    rep_printf(&r, "build_abi=%s\n", build_abi());
    rep_printf(&r, "build_ndk_api=%d pointer_bits=%u\n",
               (int)__ANDROID_API__, (unsigned)(sizeof(void *) * 8));
    rep_printf(&r, "process_pid=%ld process_uid=%ld process_euid=%ld\n",
               (long)getpid(), (long)getuid(), (long)geteuid());
    if (dir != NULL) {
        rep_printf(&r, "java_files_dir=%s\n", dir);
    }

    if (uname(&uts) == 0) {
        rep_printf(&r, "uname_sysname=%s\n", uts.sysname);
        rep_printf(&r, "uname_release=%s\n", uts.release);
        rep_printf(&r, "uname_version=%s\n", uts.version);
        rep_printf(&r, "uname_machine=%s\n", uts.machine);
    } else {
        rep_printf(&r, "uname=failed errno=%d\n", errno);
    }

    /* /proc/self/status: only Seccomp and NoNewPrivs are extracted. */
    n = read_text_file("/proc/self/status", status, sizeof status, &err);
    if (n < 0) {
        rep_printf(&r, "proc_self_status=unreadable errno=%d\n", err);
    } else {
        status_value(status, "Seccomp:", value, sizeof value);
        rep_printf(&r, "proc_status_seccomp=%s\n", value[0] ? value : "(absent)");
        status_value(status, "NoNewPrivs:", value, sizeof value);
        rep_printf(&r, "proc_status_nonewprivs=%s\n", value[0] ? value : "(absent)");
    }

    /* Own SELinux context, if the app is allowed to read it. */
    err = 0;
    n = read_text_file("/proc/self/attr/current", attr, sizeof attr, &err);
    if (n < 0) {
        rep_printf(&r, "proc_self_attr_current=unreadable errno=%d\n", err);
    } else {
        while (n > 0 && (attr[n - 1] == '\n' || attr[n - 1] == '\0')) {
            n--;
        }
        rep_printf(&r, "proc_self_attr_current=");
        rep_escaped(&r, attr, (size_t)n);
        rep_printf(&r, "\n");
    }

    rep_printf(&r, "\n");

    run_child(child_landlock, &cr);
    report_child(&r, "test 1/2: landlock availability query (forked child)", &cr);
    rep_printf(&r,
               "landlock_note=availability query only. No ruleset object was created and"
               " landlock_add_rule / landlock_restrict_self were never called, so a successful"
               " query does NOT mean this process is sandboxed or protected.\n");

    rep_printf(&r, "\n");

    run_child(child_seccomp, &cr);
    report_child(&r, "test 2/2: seccomp filter self-test, getppid -> EPERM (forked child)", &cr);
    rep_printf(&r,
               "seccomp_note=the filter is installed only inside the forked child and only for"
               " that child; the app process, its other threads and this probe keep their"
               " original privilege state.\n");

    rep_printf(&r, "\n=== end of report ===\n");

    if (dir != NULL) {
        (*env)->ReleaseStringUTFChars(env, files_dir, dir);
    }

    result = (*env)->NewStringUTF(env, r.buf != NULL ? r.buf : "(report allocation failed)");
    free(r.buf);
    return result;
}
