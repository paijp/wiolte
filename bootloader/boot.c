/*
 * WioOTA bootloader for Wio LTE (STM32F439, sectors 0-2).
 *
 * On reset:
 *   1. If the update record is pending and the staging image's CRC
 *      matches, copy it to the application area and mark it done
 *      (or mark it failed).
 *   2. Jump to the application at WIOOTA_APP_ADDR if its vector
 *      table looks valid; otherwise stay here (LED-less idle loop).
 *
 * The clocks are left at their reset state (HSI 16MHz) so that the
 * application's own clock setup starts from a known state.  No
 * interrupts and no RAM variables are used.
 */
#include <stdint.h>
#include "wioota_layout.h"
#include "wioota_flash.h"

#define SCB_VTOR	(*(volatile uint32_t *)0xe000ed08UL)

extern uint32_t _estack;
void reset_handler(void);

__attribute__((section(".vectors"), used))
const void *const vectors[2] = {
	&_estack,
	(void *)reset_handler,
};

static void mark(const volatile uint32_t *field)
{
	uint32_t zero = 0;

	wf_program((uint32_t)field, &zero, 4);
}

static void apply_update(void)
{
	const volatile struct wioota_meta *m =
		(const volatile struct wioota_meta *)WIOOTA_META_ADDR;
	uint32_t len = m->len;

	if (m->magic != WIOOTA_MAGIC)
		return;
	if (m->done != 0xffffffffUL || m->fail != 0xffffffffUL)
		return;

	wf_unlock();
	if (len == 0 || len > WIOOTA_APP_SIZE ||
	    wf_crc32(WIOOTA_STAGE_ADDR, len) != m->crc) {
		mark(&m->fail);
		wf_lock();
		return;
	}
	/*
	 * Retry the copy a few times; a power loss in the middle simply
	 * repeats it on the next reset because the record stays pending.
	 */
	for (int i = 0; i < 3; i++) {
		if (wf_erase_range(WIOOTA_APP_ADDR, len))
			continue;
		if (wf_program(WIOOTA_APP_ADDR,
				(const void *)WIOOTA_STAGE_ADDR, len))
			continue;
		if (wf_crc32(WIOOTA_APP_ADDR, len) == m->crc) {
			mark(&m->done);
			break;
		}
	}
	wf_lock();
}

static int app_valid(void)
{
	const uint32_t *v = (const uint32_t *)WIOOTA_APP_ADDR;
	uint32_t sp = v[0], pc = v[1];

	if (sp < WIOOTA_RAM_START || sp > WIOOTA_RAM_END)
		return 0;
	if (pc < WIOOTA_APP_ADDR || pc >= WIOOTA_APP_ADDR + WIOOTA_APP_SIZE)
		return 0;
	return (pc & 1) != 0;		/* Thumb */
}

__attribute__((noreturn))
static void jump_app(void)
{
	const uint32_t *v = (const uint32_t *)WIOOTA_APP_ADDR;

	SCB_VTOR = WIOOTA_APP_ADDR;
	__asm volatile(
		"msr msp, %0\n"
		"dsb\n"
		"isb\n"
		"bx %1\n"
		: : "r"(v[0]), "r"(v[1]) : "memory");
	for (;;)
		;
}

__attribute__((noreturn))
void reset_handler(void)
{
	apply_update();
	if (app_valid())
		jump_app();
	/*
	 * No usable application: wait for DFU (BOOT0 + reset).
	 */
	for (;;)
		__asm volatile("wfi");
}
