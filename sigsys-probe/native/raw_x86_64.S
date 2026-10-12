/* GPL-3.0-or-later: raw ABI/trampoline contract matches tawcroot's stub. */
.text
.global tawcroot_raw_syscall
tawcroot_raw_syscall:
    mov %rdi, %rax
    mov %rsi, %rdi
    mov %rdx, %rsi
    mov %rcx, %rdx
    mov %r8, %r10
    mov %r9, %r8
    mov 8(%rsp), %r9
    syscall
    ret
.global tawcroot_sigreturn_trampoline
tawcroot_sigreturn_trampoline:
    mov $15, %eax
    syscall
    ud2
.section .note.GNU-stack,"",@progbits
