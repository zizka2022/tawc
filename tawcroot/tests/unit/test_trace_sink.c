/* Sink checks do not mutate guest flags or exercise real kernel policy. */
#include <cleat/test.h>
#include <fcntl.h>
#include <sys/stat.h>

#include "raw_sys.h"

static long fake_flags;
static long fake_stat_result;
static mode_t fake_mode;
static unsigned raw_calls;
static unsigned mutating_calls;

static long sink_raw(long nr, long a, long b, long c,
		     long d, long e, long f)
{
	(void)c; (void)d; (void)e; (void)f;
	raw_calls++;
	if (nr == TAWC_SYS_fcntl && a == 2 && b == F_GETFL)
		return fake_flags;
	if (nr == TAWC_SYS_fstat && a == 2) {
		((struct stat *)b)->st_mode = fake_mode;
		return fake_stat_result;
	}
	mutating_calls++;
	return -22;
}

#undef TAWC_RAW
#define TAWC_RAW(nr, ...) sink_raw((nr), __VA_ARGS__)
#include "trace_sink.h"

static void reset_sink(long flags, mode_t mode, long stat_result)
{
	fake_flags = flags; fake_mode = mode; fake_stat_result = stat_result;
	raw_calls = mutating_calls = 0;
}

test(trace_sink_rejects_blocking_pipe_and_socket)
{
	reset_sink(O_WRONLY, S_IFIFO, 0);
	test_int_eq(tawcroot_trace_sink_ready(2), 0);
	test_int_eq(mutating_calls, 0);
	reset_sink(O_WRONLY, S_IFSOCK, 0);
	test_int_eq(tawcroot_trace_sink_ready(2), 0);
	test_int_eq(mutating_calls, 0);
}

test(trace_sink_accepts_regular_file_and_existing_nonblock)
{
	reset_sink(O_WRONLY, S_IFREG, 0);
	test_int_eq(tawcroot_trace_sink_ready(2), 1);
	test_int_eq(mutating_calls, 0);
	reset_sink(O_WRONLY | O_NONBLOCK, S_IFIFO, -9);
	test_int_eq(tawcroot_trace_sink_ready(2), 1);
	test_int_eq(raw_calls, 1);
	test_int_eq(mutating_calls, 0);
}

test(trace_sink_errors_drop_records_without_mutation)
{
	reset_sink(-9, S_IFREG, 0);
	test_int_eq(tawcroot_trace_sink_ready(2), 0);
	test_int_eq(raw_calls, 1);
	reset_sink(O_WRONLY, S_IFREG, -9);
	test_int_eq(tawcroot_trace_sink_ready(2), 0);
	test_int_eq(mutating_calls, 0);
}
