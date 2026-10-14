/*
 * Minimal STM32F4 flash driver (register level), shared by the
 * bootloader and the WioOTA library.  Header-only so that both
 * builds pick it up without extra source files.
 */
#ifndef WIOOTA_FLASH_H
#define WIOOTA_FLASH_H

#include <stdint.h>
#include "wioota_layout.h"

#define WF_REG(off)	(*(volatile uint32_t *)(0x40023c00UL + (off)))
#define WF_ACR		WF_REG(0x00)
#define WF_KEYR		WF_REG(0x04)
#define WF_SR		WF_REG(0x0c)
#define WF_CR		WF_REG(0x10)

#define WF_SR_BSY	(1UL << 16)
#define WF_SR_ERRS	0xf2UL		/* OPERR WRPERR PGAERR PGPERR PGSERR */
#define WF_CR_PG	(1UL << 0)
#define WF_CR_SER	(1UL << 1)
#define WF_CR_PSIZE32	(2UL << 8)
#define WF_CR_STRT	(1UL << 16)
#define WF_CR_LOCK	(1UL << 31)
#define WF_ACR_DCEN	(1UL << 10)
#define WF_ACR_DCRST	(1UL << 12)

static inline int wf_wait(void)
{
	uint32_t sr;

	while (WF_SR & WF_SR_BSY)
		;
	sr = WF_SR;
	WF_SR = sr;			/* clear EOP and error flags */
	return (sr & WF_SR_ERRS) ? -1 : 0;
}

static inline void wf_unlock(void)
{
	if (WF_CR & WF_CR_LOCK) {
		WF_KEYR = 0x45670123UL;
		WF_KEYR = 0xcdef89abUL;
	}
	WF_SR = WF_SR_ERRS | 1;
}

static inline void wf_lock(void)
{
	WF_CR |= WF_CR_LOCK;
}

/* Drop stale data-cache lines after erase/program. */
static inline void wf_cache_flush(void)
{
	uint32_t acr = WF_ACR;

	if (acr & WF_ACR_DCEN) {
		WF_ACR = acr & ~WF_ACR_DCEN;
		WF_ACR = (acr & ~WF_ACR_DCEN) | WF_ACR_DCRST;
		WF_ACR = acr & ~WF_ACR_DCEN;
		WF_ACR = acr;
	}
}

/* Sector number for an address (single-bank 1MB layout), -1 if none. */
static inline int wf_sector(uint32_t addr)
{
	if (addr < 0x08000000UL || addr >= 0x08100000UL)
		return -1;
	addr -= 0x08000000UL;
	if (addr < 0x10000UL)
		return addr / 0x4000UL;
	if (addr < 0x20000UL)
		return 4;
	return 4 + addr / 0x20000UL;
}

static inline uint32_t wf_sector_addr(int sector)
{
	if (sector < 4)
		return 0x08000000UL + sector * 0x4000UL;
	if (sector == 4)
		return 0x08010000UL;
	return 0x08000000UL + (sector - 4) * 0x20000UL;
}

/* Flash must be unlocked.  Returns 0 on success. */
static inline int wf_erase_sector(int sector)
{
	int r;

	wf_wait();
	WF_CR = WF_CR_PSIZE32 | WF_CR_SER | ((uint32_t)sector << 3);
	WF_CR |= WF_CR_STRT;
	r = wf_wait();
	WF_CR = 0;
	wf_cache_flush();
	return r;
}

/* Erase every sector overlapping [addr, addr+len). */
static inline int wf_erase_range(uint32_t addr, uint32_t len)
{
	int s, last;

	if (len == 0)
		return 0;
	s = wf_sector(addr);
	last = wf_sector(addr + len - 1);
	if (s < 0 || last < 0)
		return -1;
	for (; s <= last; s++)
		if (wf_erase_sector(s))
			return -1;
	return 0;
}

/* Program 32-bit words; addr must be 4-byte aligned. */
static inline int wf_program(uint32_t addr, const void *src, uint32_t len)
{
	const uint8_t *p = (const uint8_t *)src;
	uint32_t w;
	int r = 0;

	wf_wait();
	WF_CR = WF_CR_PSIZE32 | WF_CR_PG;
	while (len > 0) {
		uint32_t n = len < 4 ? len : 4;
		uint32_t i;

		w = 0xffffffffUL;
		for (i = 0; i < n; i++)
			((uint8_t *)&w)[i] = p[i];
		*(volatile uint32_t *)addr = w;
		if (wf_wait()) {
			r = -1;
			break;
		}
		addr += 4;
		p += n;
		len -= n;
	}
	WF_CR = 0;
	wf_cache_flush();
	return r;
}

/* CRC-32 (IEEE 802.3, same as PHP crc32() / zlib). */
static inline uint32_t wf_crc32_update(uint32_t crc, const uint8_t *p, uint32_t len)
{
	static const uint32_t t[16] = {
		0x00000000UL, 0x1db71064UL, 0x3b6e20c8UL, 0x26d930acUL,
		0x76dc4190UL, 0x6b6b51f4UL, 0x4db26158UL, 0x5005713cUL,
		0xedb88320UL, 0xf00f9344UL, 0xd6d6a3e8UL, 0xcb61b38cUL,
		0x9b64c2b0UL, 0x86d3d2d4UL, 0xa00ae278UL, 0xbdbdf21cUL,
	};

	crc = ~crc;
	while (len--) {
		crc ^= *p++;
		crc = (crc >> 4) ^ t[crc & 15];
		crc = (crc >> 4) ^ t[crc & 15];
	}
	return ~crc;
}

static inline uint32_t wf_crc32(uint32_t addr, uint32_t len)
{
	return wf_crc32_update(0, (const uint8_t *)addr, len);
}

#endif
