.text
.global _start
_start:
    mov (%rsp), %rdi
    lea 8(%rsp), %rsi
    call probe_runner_main
    mov %eax, %edi
    mov $231, %eax
    syscall
    ud2
.section .note.GNU-stack,"",@progbits
