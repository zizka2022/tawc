.text
.global _start
_start:
    ldr x0, [sp]
    add x1, sp, #8
    bl probe_runner_main
    mov x8, #94
    svc #0
    brk #0
.section .note.GNU-stack,"",%progbits
