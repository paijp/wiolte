MEMORY
{
	rom (rx)  : ORIGIN = 0x08000000, LENGTH = 48K
	ram (rwx) : ORIGIN = 0x20000000, LENGTH = 112K
}

_estack = ORIGIN(ram) + LENGTH(ram);

SECTIONS
{
	.text : {
		KEEP(*(.vectors))
		*(.text*)
		*(.rodata*)
	} > rom

	/DISCARD/ : {
		*(.data*) *(.bss*) *(COMMON) *(.ARM.exidx*)
	}
}

ASSERT(SIZEOF(.text) <= 48K, "bootloader too large")
