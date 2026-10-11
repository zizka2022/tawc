/* Diagnostic sink policy. A blocking pipe/socket can deadlock a supervisor
 * which waits before draining it. Never change the guest's descriptor flags:
 * log only to a regular file or an already-nonblocking descriptor. */
#pragma once

#include <fcntl.h>
#include <sys/stat.h>

#include "raw_sys.h"

static inline int tawcroot_trace_sink_ready(int fd)
{
	long flags = TAWC_RAW(TAWC_SYS_fcntl, fd, F_GETFL, 0, 0, 0, 0);
	if (flags < 0) return 0;
	if (flags & O_NONBLOCK) return 1;
	struct stat st;
	if (TAWC_RAW(TAWC_SYS_fstat, fd, (long)&st, 0, 0, 0, 0) < 0)
		return 0;
	return (st.st_mode & S_IFMT) == S_IFREG;
}
