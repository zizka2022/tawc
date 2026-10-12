/* GPL-3.0-or-later. Fixed fixture supervisor; no libc, shell or network. */
#include "raw_sys.h"

#if defined(__aarch64__)
#define NR_CLONE 220
#define NR_WAIT4 260
#define NR_SETSID 157
#define NR_KILL 129
#define NR_GETPPID 173
#define NR_PRLIMIT64 261
#define NR_GETPRIORITY 141
#define NR_SETPRIORITY 140
#define NR_SCHED_GETAFFINITY 123
#define NR_SCHED_SETAFFINITY 122
#define NR_CLOCK_GETTIME 113
#define NR_NANOSLEEP 101
#define NR_PIPE2 59
#elif defined(__x86_64__)
#define NR_CLONE 56
#define NR_WAIT4 61
#define NR_SETSID 112
#define NR_KILL 62
#define NR_GETPPID 110
#define NR_PRLIMIT64 302
#define NR_GETPRIORITY 140
#define NR_SETPRIORITY 141
#define NR_SCHED_GETAFFINITY 204
#define NR_SCHED_SETAFFINITY 203
#define NR_CLOCK_GETTIME 228
#define NR_NANOSLEEP 35
#define NR_PIPE2 293
#else
#error "Only reviewed 64-bit ABIs"
#endif

#define RAW(n,a,b,c,d,e,f) TAWC_RAW(n,a,b,c,d,e,f)
struct limit { unsigned long cur, max; };
struct clock_value { long seconds, nanos; };
struct action { void (*handler)(int); unsigned long flags; void (*restorer)(void); unsigned long mask; };
extern void tawcroot_sigreturn_trampoline(void);
static volatile int cancelled;
static int suffix(const char *text, const char *end)
{
    unsigned long length = 0, tail = 0;
    while (text[length] && length < 1024) length++;
    while (end[tail]) tail++;
    if (length == 1024 || length < tail) return 0;
    for (unsigned long i = 0; i < tail; i++) if (text[length-tail+i] != end[i]) return 0;
    return 1;
}

static void cancel(int signal) { (void)signal; cancelled = 1; }
static void say(const char *s, long n) { (void)RAW(TAWC_SYS_write, 1, (long)s, n, 0, 0, 0); }
static int limit_to(int resource, unsigned long cap)
{
    struct limit value;
    if (RAW(NR_PRLIMIT64, 0, resource, 0, (long)&value, 0, 0) < 0) return 0;
    if (value.cur < cap) cap = value.cur;
    if (value.max < cap) cap = value.max;
    value.cur = value.max = cap;
    return RAW(NR_PRLIMIT64, 0, resource, (long)&value, 0, 0, 0) == 0;
}
static long now(void)
{
    struct clock_value time;
    if (RAW(NR_CLOCK_GETTIME, 1, (long)&time, 0, 0, 0, 0) < 0) return -1;
    return time.seconds * 1000 + time.nanos / 1000000;
}
static void pause_short(void)
{
    struct clock_value pause = { 0, 20000000 };
    (void)RAW(NR_NANOSLEEP, (long)&pause, 0, 0, 0, 0, 0);
}
static int policy(void)
{
    unsigned long mask = 0;
    struct action action = { cancel, 0x04000000, tawcroot_sigreturn_trampoline, 0 };
    if (RAW(TAWC_SYS_rt_sigprocmask, 2, (long)&mask, 0, 8, 0, 0) < 0) return 0;
    if (RAW(TAWC_SYS_rt_sigaction, 15, (long)&action, 0, 8, 0, 0) < 0 ||
        RAW(TAWC_SYS_rt_sigaction, 2, (long)&action, 0, 8, 0, 0) < 0) return 0;
    struct action default_action = { 0, 0, 0, 0 };
    if (RAW(TAWC_SYS_rt_sigaction, 17, (long)&default_action, 0, 8, 0, 0) < 0 ||
        RAW(TAWC_SYS_prctl, 36, 1, 0, 0, 0, 0) < 0) return 0;
    long parent = RAW(NR_GETPPID, 0, 0, 0, 0, 0, 0);
    if (parent <= 1 || RAW(TAWC_SYS_prctl, 1, 15, 0, 0, 0, 0) < 0 ||
        RAW(NR_GETPPID, 0, 0, 0, 0, 0, 0) != parent) return 0;
    if (!limit_to(0, 2) || !limit_to(1, 1048576) || !limit_to(3, 32UL*1024*1024) ||
        !limit_to(4, 0) || !limit_to(9, 128UL*1024*1024)) return 0;
    long priority = RAW(NR_GETPRIORITY, 0, 0, 0, 0, 0, 0);
    if (priority < 0) return 0;
    long target = 20 - priority;
    if (target < 15) target = 15;
    if (RAW(NR_SETPRIORITY, 0, 0, target, 0, 0, 0) < 0) return 0;
    volatile unsigned long cpus[16];
    for (int i = 0; i < 16; i++) cpus[i] = 0;
    if (RAW(NR_SCHED_GETAFFINITY, 0, sizeof cpus, (long)cpus, 0, 0, 0) < 0) return 0;
    int first = -1;
    for (int word = 0; word < 16; word++) {
        if (first < 0 && cpus[word]) {
            for (int bit = 0; bit < 64; bit++) {
                if (cpus[word] & (1UL << bit)) { first = word*64 + bit; break; }
            }
        }
        cpus[word] = 0;
    }
    if (first < 0) return 0;
    cpus[first/64] = 1UL << (first%64);
    return RAW(NR_SCHED_SETAFFINITY, 0, sizeof cpus, (long)cpus, 0, 0, 0) == 0;
}
static void stop_owned(long pid)
{
    /* PID remains our unreaped child or the ID of its still-live group. */
    (void)RAW(NR_KILL, -pid, 9, 0, 0, 0, 0);
    (void)RAW(NR_KILL, pid, 9, 0, 0, 0, 0);
}

int probe_runner_main(long argc, char **argv)
{
    if (argc != 3 || !argv[1] || !argv[2] || argv[1][0] != '/' || argv[2][0] != '/' ||
        !(suffix(argv[1], "/libpocket_baseline.so") || suffix(argv[1], "/libpocket_trace.so")) ||
        !suffix(argv[2], "/rootfs")) {
        say("cleanup=ok\n", 11); return 88;
    }
    if (!policy()) { say("cleanup=ok\n", 11); return 88; }
    long start = now();
    if (start < 0) { say("cleanup=ok\n", 11); return 88; }
    long parent = RAW(TAWC_SYS_getpid, 0, 0, 0, 0, 0, 0);
    int pipe[2];
    if (RAW(NR_PIPE2, (long)pipe, 04000 | 02000000, 0, 0, 0, 0) < 0) {
        say("cleanup=ok\n", 11); return 88;
    }
    long child = RAW(NR_CLONE, 17, 0, 0, 0, 0, 0);
    if (child < 0) { say("cleanup=ok\n", 11); return 88; }
    if (child == 0) {
        (void)RAW(TAWC_SYS_close, pipe[0], 0, 0, 0, 0, 0);
        if (RAW(NR_SETSID, 0, 0, 0, 0, 0, 0) < 0 ||
            RAW(TAWC_SYS_prctl, 1, 9, 0, 0, 0, 0) < 0 ||
            RAW(NR_GETPPID, 0, 0, 0, 0, 0, 0) != parent ||
            RAW(TAWC_SYS_chdir, (long)argv[2], 0, 0, 0, 0, 0) < 0)
            return 88;
        long guardian = RAW(TAWC_SYS_getpid, 0, 0, 0, 0, 0, 0);
        long runtime = RAW(NR_CLONE, 17, 0, 0, 0, 0, 0);
        if (runtime < 0) return 88;
        if (runtime == 0) {
            (void)RAW(TAWC_SYS_close, pipe[1], 0, 0, 0, 0, 0);
            if (RAW(TAWC_SYS_prctl, 1, 9, 0, 0, 0, 0) < 0 ||
                RAW(NR_GETPPID, 0, 0, 0, 0, 0, 0) != guardian) return 88;
            char *command[] = { argv[1], "-r", argv[2], "--", "/probe", 0 };
            char *empty[] = { 0 };
            (void)RAW(TAWC_SYS_execve, (long)argv[1], (long)command, (long)empty, 0, 0, 0);
            return 88;
        }
        int result = 0;
        for (;;) {
            long waited = RAW(NR_WAIT4, runtime, (long)&result, 1, 0, 0, 0);
            if (waited == runtime) break;
            if (waited < 0 && waited != -4) return 88;
            pause_short();
        }
        (void)RAW(TAWC_SYS_write, pipe[1], (long)&result, sizeof result, 0, 0, 0);
        (void)RAW(TAWC_SYS_close, pipe[1], 0, 0, 0, 0, 0);
        /* Hold the group's ID until the owner kills it. No PID-reuse signalling. */
        for (;;) pause_short();
    }
    (void)RAW(TAWC_SYS_close, pipe[1], 0, 0, 0, 0, 0);
    int status = 0, reaped = 0, timed_out = 0;
    for (;;) {
        long read = RAW(TAWC_SYS_read, pipe[0], (long)&status, sizeof status, 0, 0, 0);
        if (read == (long)sizeof status) break;
        if (read >= 0 || (read != -4 && read != -11)) { status = 88 << 8; break; }
        long time = now();
        if (cancelled || time < 0 || time-start >= 10000) { timed_out = 1; break; }
        pause_short();
    }
    (void)RAW(TAWC_SYS_close, pipe[0], 0, 0, 0, 0, 0);
    stop_owned(child);
    long end = now();
    int group_gone = 0;
    for (;;) {
        int discarded;
        long waited;
        do {
            waited = RAW(NR_WAIT4, -1, (long)&discarded, 1, 0, 0, 0);
            if (waited == child) reaped = 1;
        } while (waited > 0);
        group_gone = RAW(NR_KILL, -child, 0, 0, 0, 0, 0) == -3;
        if (reaped && group_gone) break;
        long time = now();
        if (end < 0 || time < 0 || time-end >= 2000) break;
        pause_short();
    }
    if (!reaped || !group_gone) { say("cleanup=failed\n", 15); return 89; }
    say("cleanup=ok\n", 11);
    if (timed_out || cancelled) return 90;
    if (status & 127) return 128 + (status & 127);
    return (status >> 8) & 255;
}
