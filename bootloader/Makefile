CROSS	?= arm-none-eabi-
CC	= $(CROSS)gcc
OBJCOPY	= $(CROSS)objcopy
SIZE	= $(CROSS)size

CFLAGS	= -mcpu=cortex-m4 -mthumb -Os -Wall -Wextra -std=gnu99 \
	  -ffreestanding -ffunction-sections -fdata-sections \
	  -I../libraries/WioOTA/src
LDFLAGS	= -nostdlib -nostartfiles -Wl,--gc-sections -T boot.ld

all: bootloader.bin

bootloader.elf: boot.c boot.ld ../libraries/WioOTA/src/wioota_layout.h ../libraries/WioOTA/src/wioota_flash.h
	$(CC) $(CFLAGS) $(LDFLAGS) -o $@ boot.c
	$(SIZE) $@

bootloader.bin: bootloader.elf
	$(OBJCOPY) -O binary $< $@

clean:
	rm -f bootloader.elf bootloader.bin

.PHONY: all clean
