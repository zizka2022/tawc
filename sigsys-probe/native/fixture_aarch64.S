/* Signed fixed fixture: read one known marker, close it, print fixed success. */
.text
.global _start
_start:
    mov x0, #-100
    adr x1, marker_path
    mov x2, #0
    mov x3, #0
    mov x8, #56
    svc #0
    cmp x0, #0
    b.lt failed
    mov x19, x0
    adr x1, buffer
    mov x2, #13
    mov x8, #63
    svc #0
    cmp x0, #13
    b.ne failed
    adr x1, buffer
    adr x2, marker_data
    mov x3, #13
compare:
    ldrb w4, [x1], #1
    ldrb w5, [x2], #1
    cmp w4, w5
    b.ne failed
    subs x3, x3, #1
    b.ne compare
    mov x0, x19
    mov x8, #57
    svc #0
    cmp x0, #0
    b.ne failed
    mov x0, #1
    adr x1, success
    mov x2, #9
    mov x8, #64
    svc #0
    cmp x0, #9
    b.ne failed
    mov x0, #0
    b finish
failed:
    mov x0, #1
finish:
    mov x8, #94
    svc #0
    brk #0
.section .rodata
marker_path: .asciz "/marker"
marker_data: .ascii "probe marker\n"
success: .ascii "probe-ok\n"
.section .bss
.align 4
buffer: .skip 16
.section .note.GNU-stack,"",%progbits
