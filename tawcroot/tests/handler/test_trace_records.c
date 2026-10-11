/* Exercise real SIGSYS records with a regular-file sink, independently of
 * Cleat's wait-before-drain subprocess pipes. Never print captured records. */
#include <cleat/test.h>
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

#ifndef TAWCROOT_TESTHOST_BIN
# error "TAWCROOT_TESTHOST_BIN must be defined by the build"
#endif
#ifndef TAWCROOT_TEST_TMPDIR
# error "TAWCROOT_TEST_TMPDIR must be defined by the build"
#endif

static int collect_trace_records(unsigned *enters, unsigned *returns)
{
	char path[] = TAWCROOT_TEST_TMPDIR "/tawc-trace-XXXXXX";
	int fd = mkstemp(path);
	if (fd < 0) return 0;
	unlink(path);
	pid_t child = fork();
	if (child == 0) {
		if (dup2(fd, STDERR_FILENO) < 0) _exit(126);
		if (fd != STDERR_FILENO) close(fd);
		execl(TAWCROOT_TESTHOST_BIN, TAWCROOT_TESTHOST_BIN, (char *)NULL);
		_exit(127);
	}
	int status = 0, ok = 0;
	if (child < 0) goto done;
	struct timespec start, now, pause = { .tv_nsec = 10000000 };
	if (clock_gettime(CLOCK_MONOTONIC, &start) < 0) goto reap;
	for (;;) {
		pid_t waited = waitpid(child, &status, WNOHANG);
		if (waited == child) {
			child = -1;
			break;
		}
		if (waited < 0 && errno != EINTR) goto reap;
		if (clock_gettime(CLOCK_MONOTONIC, &now) < 0 ||
		    now.tv_sec - start.tv_sec >= 10) goto reap;
		nanosleep(&pause, NULL);
	}
	if (!WIFEXITED(status) || WEXITSTATUS(status) != 0) goto done;
	if (lseek(fd, 0, SEEK_SET) < 0) goto done;
	FILE *stream = fdopen(fd, "r");
	if (!stream) goto done;
	fd = -1;
	char line[512];
	ok = 1;
	while (fgets(line, sizeof line, stream)) {
		if (strncmp(line, "[te] ", 5)) continue;
		char phase[7];
		long pid, tid, nr, code, arch;
		int used = 0;
		if (strlen(line) >= 192 ||
		    sscanf(line, "[te] %6s pid=%ld tid=%ld nr=%ld code=%ld arch=%ld%n",
			   phase, &pid, &tid, &nr, &code, &arch, &used) != 6 ||
		    pid <= 0 || tid <= 0 || nr < 0 || code != 1 || arch <= 0) {
			ok = 0; break;
		}
		const char *suffix = line + used;
		if (!strcmp(phase, "enter") && !strcmp(suffix, "\n")) {
			(*enters)++;
		} else if (!strcmp(phase, "return")) {
			long error;
			int end = 0;
			if (strcmp(suffix, " result=ok\n") &&
			    !(sscanf(suffix, " errno=%ld%n", &error, &end) == 1 &&
			      error >= 1 && error <= 4095 && !strcmp(suffix + end, "\n"))) {
				ok = 0; break;
			}
			(*returns)++;
		} else { ok = 0; break; }
	}
	if (ferror(stream)) ok = 0;
	fclose(stream);
	goto done;
reap:
	/* Only this fixture's still-owned child is stopped on failure. */
	kill(child, SIGKILL);
	while (waitpid(child, &status, 0) < 0 && errno == EINTR) {}
done:
	if (fd >= 0) close(fd);
	return ok;
}

test(trace_records_real_handler_regular_file)
{
	unsigned enters = 0, returns = 0;
	int ok = collect_trace_records(&enters, &returns);
	test_int_eq(ok, 1);
#ifdef TAWCROOT_TRACE_ENTER
	test_true(enters > 0);
	test_int_eq(enters, returns);
#else
	test_int_eq(enters, 0);
	test_int_eq(returns, 0);
#endif
}
