/* GPL-3.0-or-later: raw ABI/trampoline contract matches tawcroot's stub. */
.text
.global tawcroot_raw_syscall
tawcroot_raw_syscall:
    mov x8, x0
    mov x0, x1
    mov x1, x2
    mov x2, x3
    mov x3, x4
    mov x4, x5
    mov x5, x6
    svc #0
    ret
.global tawcroot_sigreturn_trampoline
tawcroot_sigreturn_trampoline:
    mov x8, #139
    svc #0
    brk #0
.section .note.GNU-stack,"",%progbits
