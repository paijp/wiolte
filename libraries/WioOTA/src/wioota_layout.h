/*
 * Flash layout shared by the bootloader and the WioOTA library.
 *
 * STM32F439 1MB, single bank:
 *   sector 0-2  0x08000000  48KB  bootloader
 *   sector 3    0x0800C000  16KB  update record (wioota_meta)
 *   sector 4-7  0x08010000 448KB  application
 *   sector 8-11 0x08080000 512KB  staging (downloaded image)
 *
 * The application start 0x08010000 matches USER_ADDR_ROM of the
 * Seeed STM32F4 core (MCU_STM32F406VG), so a sketch linked with
 * ld/flash.ld and VECT_TAB_FLASH runs there unmodified.
 */
#ifndef WIOOTA_LAYOUT_H
#define WIOOTA_LAYOUT_H

#include <stdint.h>

#define WIOOTA_BOOT_ADDR	0x08000000UL
#define WIOOTA_META_ADDR	0x0800C000UL
#define WIOOTA_META_SECTOR	3
#define WIOOTA_APP_ADDR		0x08010000UL
#define WIOOTA_APP_SIZE		(448UL * 1024)
#define WIOOTA_STAGE_ADDR	0x08080000UL
#define WIOOTA_STAGE_SIZE	(512UL * 1024)

#define WIOOTA_RAM_START	0x20000000UL
#define WIOOTA_RAM_END		0x20030000UL	/* 192KB SRAM1+2+3 */

#define WIOOTA_MAGIC		0x41544f57UL	/* "WOTA" */

/*
 * Update record.  The application writes magic/len/crc after the
 * staging area verified; done/fail stay 0xffffffff (erased) and are
 * cleared to 0 by the bootloader, so no erase is needed to record
 * the outcome.
 *   done == 0            : image copied to the application area
 *   fail == 0            : staging image was rejected
 *   both 0xffffffff      : copy pending
 */
struct wioota_meta {
	uint32_t	magic;
	uint32_t	len;
	uint32_t	crc;
	uint32_t	done;
	uint32_t	fail;
};

#endif
